package com.parrot.hellodrone.altitude
import com.parrot.hellodrone.bio.Zone
import com.parrot.hellodrone.bio.ZoneInfo

import android.util.Log
/**
 * AltitudeController VERSION 2 (Position + Trend - Optimal)
 *
 * POSITION+TREND-Controller mit kontinuierlicher Abdeckung der Zonen.
 *
 * ZONE-MAPPING (kontinuierliche Grenzen):
 * - GREEN Zone:  1.0m - 1.4m -> A2 nutzt POSITION (0.0-1.0) linear
 * - YELLOW Zone: 1.4m - 1.8m -> A2 nutzt POSITION (0.0-1.0) linear
 * - RED Zone:    1.8m - 1.8m -> A2 nutzt POSITION (0.0-1.0) linear
 *
 * Kombiniert:
 * - Relative Position innerhalb der Zone (0.0-1.0)
 * - HR-Trend (steigend/fallend)
 * - Kontinuierliche lineare Skalierung
 *
 * Beste physiologische Regelung mit präventiver Anpassung.
 *
 * MAPPING: Höhere physiologische Belastung = höhere Flughöhe
 * - GREEN: 1.0m -> 1.4m (kontinuierlich mit Position)
 * - YELLOW: 1.4m -> 1.8m (kontinuierlich mit Position)
 * - RED: 1.8m -> 1.8m (kontinuierlich mit Position)
 *
 * VORTEIL vs A0/A1: Feinste Granularität durch Position INNERHALB Zone
 */

class AltitudeControllerA2 (
    private val altMin: Float = 0.8f,           // Sicherheits-Minimum
    private val altMax: Float = 1.9f,           // Sicherheits-Maximum (Raumhöhe-Limit)
    private val altRedBase: Float = 1.80f,
    private val maxAltChangeRate: Float = 0.3f, // m/s Änderungsrate
    private val redPauseThresholdMs: Long = 45_000L
) : IAltitudeController {

    private var lastAlt: Float = 1.0f  // Startet auf GREEN-Start
    private var lastTsMs: Long = -1L
    private var lastHr: Int = 0
    private var redZoneDurationMs: Long = 0L
    private val window: ArrayDeque<Float> = ArrayDeque()
    private var lastSmoothedOutput: Float = 1.0f

    /**
     * A2 Fallback: Wenn nur Zone übergeben wird, verwende 0.5 als Position
     * (Mittelwert der Zone für robustes Fallback-Verhalten)
     */
    override fun update(zone: Zone, hrSmooth: Int, nowMs: Long): Float {
        // Silent fallback - keine Warning, da dies ein legitimer Fallback ist
        return updateInternal(zone, 0.5f, hrSmooth, nowMs)
    }

    /**
     * A2 Hauptmethode: Mit ZoneInfo (Zone + Position)
     */
    override fun update(zoneInfo: ZoneInfo, hrSmooth: Int, nowMs: Long): Float {
        return updateInternal(zoneInfo.zone, zoneInfo.relativePosition, hrSmooth, nowMs)
    }

    /**
     * Interne Update-Logik mit Position + Trend
     */
    private fun updateInternal(
        zone: Zone,
        relativePosition: Float,
        hrSmooth: Int,
        nowMs: Long
    ): Float {
        val dtMs = if (lastTsMs < 0L) 0L else (nowMs - lastTsMs).coerceAtLeast(0L)
        lastTsMs = nowMs
        val dtSec = dtMs / 1000f

        val hrTrend = if (lastHr == 0) 0 else hrSmooth - lastHr
        lastHr = hrSmooth

        // A2 LOGIK: Position + Trend kombiniert
        val altTargetBase = when (zone) {
            Zone.GREEN -> {
                // GREEN: 1.0m -> 1.4m (linear mit Position)
                // Position 0.0 (unten in GREEN) -> 1.0m
                // Position 1.0 (oben in GREEN) -> 1.4m
                val baseAlt = 1.0f + (relativePosition * 0.4f)  // Range: 0.4m

                // Trend-Modifikation: Bei steigender HR präventiv höher
                val trendMod = when {
                    hrTrend > 3  -> +0.05f  // Stark steigend -> präventiv höher
                    hrTrend < -2 -> -0.05f  // Sinkend -> kann tiefer bleiben
                    else         -> 0f
                }

                (baseAlt + trendMod).coerceIn(1.0f, 1.4f)
            }

            Zone.YELLOW -> {
                // YELLOW: 1.4m -> 1.8m (linear mit Position)
                // Position 0.0 (unten in YELLOW) -> 1.4m
                // Position 1.0 (oben in YELLOW) -> 1.8m
                val baseAlt = 1.4f + (relativePosition * 0.4f)  // Range: 0.4m

                // Stärkere Trend-Reaktion in YELLOW
                val trendMod = when {
                    hrTrend > 2  -> +0.1f   // Steigend -> deutlich höher
                    hrTrend > 0  -> +0.05f  // Leicht steigend -> etwas höher
                    hrTrend < -2 -> -0.1f   // Sinkend -> kann tiefer
                    hrTrend < 0  -> -0.05f  // Leicht sinkend -> etwas tiefer
                    else         -> 0f
                }

                (baseAlt + trendMod).coerceIn(1.4f, 1.8f)
            }

            Zone.RED -> {
                // RED: Fixe Höhe bei 1.8m (Threshold-Signal)
                val altRed = altRedBase  // = 1.8m

                // Optional: Kleiner Trend für Feinabstimmung (+/-2cm)
                val trendMod = when {
                    hrTrend > 5  -> +0.02f  // Sehr stark steigend
                    hrTrend < -5 -> -0.02f  // Stark sinkend
                    else         -> 0f
                }

                val altRedDynamic = (altRed + trendMod).coerceIn(1.78f, 1.82f)
                // ↑ Bleibt praktisch bei 1.8m (+/-2cm für Sensor-Noise)

                redZoneDurationMs += dtMs

                // Pause nach 45s
                if (redZoneDurationMs >= redPauseThresholdMs) {
                    1.8f
                } else {
                    altRedDynamic
                }
            }
        }

        if (zone != Zone.RED) {
            redZoneDurationMs = 0L
        }

        val altLimited = applyChangeRateLimit(altTargetBase, dtSec)
        val smoothed = smooth(altLimited)
        lastSmoothedOutput = smoothed

        Log.d("ALTITUDE_A2", "Zone=$zone, pos=${"%.2f".format(relativePosition)}, trend=$hrTrend, target=${"%.2f".format(altTargetBase)}m, out=${"%.2f".format(smoothed)}m")

        return smoothed
    }

    private fun applyChangeRateLimit(altTarget: Float, dtSec: Float): Float {
        val dAltMax = maxAltChangeRate * dtSec
        val altClamped = when {
            altTarget > lastAlt + dAltMax -> lastAlt + dAltMax
            altTarget < lastAlt - dAltMax -> lastAlt - dAltMax
            else -> altTarget
        }
        val altFinal = altClamped.coerceIn(altMin, altMax)
        lastAlt = altFinal
        return altFinal
    }

    private fun smooth(alt: Float): Float {
        if (window.size == 3) window.removeFirst()
        window.addLast(alt)
        return window.average().toFloat()
    }

    override fun reset() {
        lastAlt = 1.0f
        lastTsMs = -1L
        lastHr = 0
        redZoneDurationMs = 0L
        window.clear()
        repeat(3) { window.addLast(1.0f) }
        lastSmoothedOutput = 1.0f
        Log.i("ALTITUDE_A2", "AltitudeController A2 reset")
    }

    override fun getLastOutput(): Float = lastSmoothedOutput

    override fun setOutput(value: Float) {
        val clamped = value.coerceIn(altMin, altMax)
        lastAlt = clamped
        lastSmoothedOutput = clamped
        window.clear()
        repeat(3) { window.addLast(clamped) }
        Log.d("ALTITUDE_A2", "Output gesetzt auf ${"%.2f".format(clamped)}m")
    }

    override fun getRedZoneDurationMs(): Long = redZoneDurationMs
    override fun isPauseActive(): Boolean = redZoneDurationMs >= redPauseThresholdMs
    override fun getLastHr(): Int = lastHr
}