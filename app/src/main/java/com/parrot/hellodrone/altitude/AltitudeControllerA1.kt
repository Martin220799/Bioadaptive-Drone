package com.parrot.hellodrone.altitude
import com.parrot.hellodrone.bio.Zone
import com.parrot.hellodrone.bio.ZoneInfo

import android.util.Log
/**
 * AltitudeControllerA1 für TREND-basierte bioadaptive Höhenanpassung.
 *
 * TREND-Controller mit dynamischer Anpassung basierend auf HR-Trend.
 *
 * ZONE-MAPPING (kontinuierliche Grenzen):
 * - GREEN Zone:  1.0m - 1.4m -> A1 nutzt MITTE (1.2m) +/- Trend
 * - YELLOW Zone: 1.4m - 1.8m -> A1 nutzt MITTE (1.6m) +/- Trend
 * - RED Zone:    1.8m - 1.9m -> A1 nutzt MITTE (1.80m) +/- Trend
 *
 * MAPPING: Höhere physiologische Belastung = höhere Flughöhe
 * - GREEN (entspannt): ~1.2m +/- 0.1m (Trend-abhängig)
 * - YELLOW (moderat): ~1.6m +/- 0.2m (Trend-abhängig)
 * - RED (belastet): ~1.80m +/- 0.05m (Trend-abhängig)
 *
 * VORTEIL vs A0: Feinere Trend-Reaktion innerhalb der Zonen
 */
class AltitudeControllerA1 (
    private val altGreen: Float = 1.2f,         // GREEN: Zonen-Mitte
    private val altYellow: Float = 1.6f,        // YELLOW: Zonen-Mitte
    private val altRedBase: Float = 1.8f,      // RED: Zonen-Mitte
    private val altMin: Float = 0.8f,           // Sicherheits-Minimum
    private val altMax: Float = 1.9f,           // Sicherheits-Maximum
    private val maxAltChangeRate: Float = 0.3f, // m/s Änderungsrate
    private val redPauseThresholdMs: Long = 45_000L
) : IAltitudeController {

    private var lastAlt: Float = altGreen  // Startet auf GREEN-Höhe
    private var lastTsMs: Long = -1L
    private var lastHr: Int = 0
    private var redZoneDurationMs: Long = 0L
    private val window: ArrayDeque<Float> = ArrayDeque()
    private var lastSmoothedOutput: Float = altGreen

    /**
     * A1 verwendet nur Zone (nicht Position), reagiert aber auf Trend
     */
    override fun update(zone: Zone, hrSmooth: Int, nowMs: Long): Float {
        val dtMs = if (lastTsMs < 0L) 0L else (nowMs - lastTsMs).coerceAtLeast(0L)
        lastTsMs = nowMs
        val dtSec = dtMs / 1000f

        val hrTrend = if (lastHr == 0) 0 else hrSmooth - lastHr
        lastHr = hrSmooth

        // A1 LOGIK: Zonen-Mitte + Trend-Modifikation
        val altTargetBase = when (zone) {
            Zone.GREEN -> {
                // GREEN: 1.2m +/- 0.1m (Trend) -> Range 1.1-1.3m (innerhalb 1.0-1.4)
                val trendMod = when {
                    hrTrend > 3  -> +0.1f  // Stark steigend -> höher
                    hrTrend > 0  -> +0.05f // Leicht steigend -> etwas höher
                    hrTrend < -2 -> -0.1f  // Sinkend -> tiefer
                    else         -> 0f     // Stabil -> Mitte
                }
                (altGreen + trendMod).coerceIn(1.0f, 1.4f)
            }

            Zone.YELLOW -> {
                // YELLOW: 1.6m +/- 0.2m (Trend) -> Range 1.4-1.8m (voller Range!)
                val trendMod = when {
                    hrTrend > 2  -> +0.2f  // Stark steigend -> deutlich höher
                    hrTrend > 0  -> +0.1f  // Leicht steigend -> höher
                    hrTrend < -2 -> -0.2f  // Sinkend -> deutlich tiefer
                    hrTrend < 0  -> -0.1f  // Leicht sinkend -> tiefer
                    else         -> 0f     // Stabil -> Mitte
                }
                (altYellow + trendMod).coerceIn(1.4f, 1.8f)
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
                // Bleibt praktisch bei 1.8m (+/-2cm für Sensor-Noise)

                redZoneDurationMs += dtMs

                // Pause nach 45s?
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

        Log.d("ALTITUDE_A1", "Zone=$zone, hrTrend=$hrTrend, target=${"%.2f".format(altTargetBase)}m, out=${"%.2f".format(smoothed)}m")

        return smoothed
    }

    /**
     * A1 unterstützt kein ZoneInfo - Fallback auf Zone
     */
    override fun update(zoneInfo: ZoneInfo, hrSmooth: Int, nowMs: Long): Float {
        Log.w("ALTITUDE_A1", "A1 unterstützt keine Position - verwende nur Zone")
        return update(zoneInfo.zone, hrSmooth, nowMs)
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
        lastAlt = altGreen
        lastTsMs = -1L
        lastHr = 0
        redZoneDurationMs = 0L
        window.clear()
        repeat(3) { window.addLast(altGreen) }
        lastSmoothedOutput = altGreen
        Log.i("ALTITUDE_A1", "AltitudeController A1 reset")
    }

    override fun getLastOutput(): Float = lastSmoothedOutput

    override fun setOutput(value: Float) {
        val clamped = value.coerceIn(altMin, altMax)
        lastAlt = clamped
        lastSmoothedOutput = clamped
        window.clear()
        repeat(3) { window.addLast(clamped) }
        Log.d("ALTITUDE_A1", "Output gesetzt auf ${"%.2f".format(clamped)}m")
    }

    override fun getRedZoneDurationMs(): Long = redZoneDurationMs
    override fun isPauseActive(): Boolean = redZoneDurationMs >= redPauseThresholdMs
    override fun getLastHr(): Int = lastHr
}