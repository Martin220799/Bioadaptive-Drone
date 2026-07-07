package com.parrot.hellodrone.altitude
import com.parrot.hellodrone.bio.Zone
import com.parrot.hellodrone.bio.ZoneInfo

import android.util.Log

/**
 * AltitudeControllerA0 für bioadaptive Höhenanpassung (Prototyp 2 - Indoor).
 *
 * Berechnet die Drohnen-Flughöhe basierend auf:
 * - Aktueller HR-Zone (GREEN/YELLOW/RED)
 * - HR-Trend (steigend/fallend)
 * - Glättung und Änderungsratenbegrenzung
 *
 * MAPPING: Höhere physiologische Belastung = höhere Flughöhe
 * - GREEN (entspannt): 1.0m (komfortable Greifhöhe)
 * - YELLOW (moderat): 1.4m (zwischen Sitz und Augenhöhe)
 * - RED (belastet): 1.8m (Augen-/Stirnhöhe beim Sitzen = Stress-Signalisierung)
 */
class AltitudeControllerA0(
    private val altGreen: Float = 1.0f,     // GREEN: Sitzhöhe Ergometer
    private val altYellow: Float = 1.4f,    // YELLOW: Zwischen Sitz und Augenhöhe
    private val altRedBase: Float = 1.8f,   // RED: Augen-/Stirnhöhe beim Sitzen
    private val altMin: Float = 0.8f,       // Sicherheits-Minimum
    private val altMax: Float = 1.9f,       // Sicherheits-Maximum (Raumhöhe-Limit)
    private val maxAltChangeRate: Float = 0.3f, // m/s Änderungsrate
    private val redPauseThresholdMs: Long = 45_000L // Pausenmodus-Schwelle
) : IAltitudeController {

    private var lastAlt: Float = altGreen  // Startet auf GREEN-Höhe
    private var lastTsMs: Long = -1L

    // Trend-Logik
    private var lastHr: Int = 0

    // Pausen-Logik
    private var redZoneDurationMs: Long = 0L

    // Einfaches 3er-Mittel für Glättung
    private val window: ArrayDeque<Float> = ArrayDeque()

    // Letzter geglätteter Output
    private var lastSmoothedOutput: Float = altGreen

    /**
     * zone     = aktuelle Zone (GREEN/YELLOW/RED)
     * hrSmooth = geglättete HR (z.B. aus HrProcessor)
     * nowMs    = System.currentTimeMillis()
     */
    override fun update(zone: Zone, hrSmooth: Int, nowMs: Long): Float {
        // Zeitdifferenz (ms -> s)
        // Wichtig: Beim ersten Sample wird dt=0 verwendet und der zuletzt gesetzte Output NICHT überschrieben.
        val dtMs: Long = if (lastTsMs < 0L) 0L else (nowMs - lastTsMs).coerceAtLeast(0L)
        lastTsMs = nowMs
        val dtSec = dtMs / 1000f

        // HR-Trend berechnen
        val hrTrend = if (lastHr == 0) {
            // Erstes Sample: keinen künstlichen Sprung erzeugen
            0
        } else {
            hrSmooth - lastHr
        }
        lastHr = hrSmooth

        // Basis-Höhe abhängig von Zone
        // Basis-Höhe abhängig von Zone (A0 = FIXED HEIGHTS; RED bleibt stabil bei altRedBase)
        val altTargetBase = when (zone) {
            Zone.GREEN -> altGreen
            Zone.YELLOW -> altYellow
            Zone.RED -> {
                // RED-Zone stabil: Drohne hält die Höhe als klares Stress-Signal (keine Dynamik wie bei SpeedController)
                // Pausenlogik: nach Threshold bleibt sie weiterhin bei altRedBase (zusätzliche Signale z.B. Audio/Vibration macht MainActivity)
                redZoneDurationMs += dtMs
                altRedBase
            }
        }

        // Reset der Red-Zonen-Dauer beim Verlassen von RED beim Verlassen von RED
        if (zone != Zone.RED) {
            redZoneDurationMs = 0L
        }

        // Änderungsratenbegrenzung (sanfte Höhenänderungen)
        val altLimited = applyChangeRateLimit(altTargetBase, dtSec)

        // Glätten
        val smoothed = smooth(altLimited)

        // Letzten Output speichern
        lastSmoothedOutput = smoothed

        Log.d("ALTITUDE_A0", "Zone=$zone, hrTrend=$hrTrend, target=${"%.2f".format(altTargetBase)}m, output=${"%.2f".format(smoothed)}m")

        return smoothed
    }

    /**
     * A0 unterstützt kein ZoneInfo - wirft Warnung und nutzt nur zone
     */
    override fun update(zoneInfo: ZoneInfo, hrSmooth: Int, nowMs: Long): Float {
        // Fallback: verwende nur die Zone, ignoriere Position
        Log.w("ALTITUDE_A0", "A0 unterstützt keine Position - verwende nur Zone")
        return update(zoneInfo.zone, hrSmooth, nowMs)
    }

    /**
     * Begrenzt die Änderungsrate der Höhe für sanfte Bewegungen
     */
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

    /**
     * 3er-Mittel Glättung
     */
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
        // Start-Window füllen, damit 3er-Mittel nicht künstlich 'hochläuft'
        repeat(3) { window.addLast(altGreen) }
        lastSmoothedOutput = altGreen
        Log.i("ALTITUDE_A0", "AltitudeControllerA0 reset")
    }

    /**
     * Gibt die letzte geglättete Ausgabe zurück
     */
    override fun getLastOutput(): Float = lastSmoothedOutput

    /**
     * Setzt den internen Zustand auf einen bestimmten Wert
     * Nützlich für sanften Übergang oder manuellen Start
     */
    override fun setOutput(value: Float) {
        val clamped = value.coerceIn(altMin, altMax)
        lastAlt = clamped
        lastSmoothedOutput = clamped

        // Window mit dem neuen Wert füllen für konsistente Glättung
        window.clear()
        // Window mit Startwert füllen, damit 3er-Mittel sofort stabil ist
        repeat(3) { window.addLast(clamped) }

        Log.d("ALTITUDE_A0", "Output manuell gesetzt auf ${"%.2f".format(clamped)}m")
    }

    /**
     * Gibt die aktuelle Dauer in der Red-Zone zurück (in ms)
     */
    override fun getRedZoneDurationMs(): Long = redZoneDurationMs

    /**
     * True, wenn der Pausenmodus aktiv ist (Red-Zone länger als Threshold)
     */
    override fun isPauseActive(): Boolean = redZoneDurationMs >= redPauseThresholdMs

    /**
     * Gibt den letzten verarbeiteten HR-Wert zurück
     */
    override fun getLastHr(): Int = lastHr
}
