package com.parrot.hellodrone.speed
import com.parrot.hellodrone.bio.Zone
import com.parrot.hellodrone.bio.ZoneInfo

import android.util.Log

/**
 * SpeedControllerV0 für bioadaptive Geschwindigkeitsanpassung.
 *
 * Berechnet die Drohnen-Vorwärtsgeschwindigkeit basierend auf:
 * - Aktueller HR-Zone (GREEN/YELLOW/RED)
 * - HR-Trend (steigend/fallend)
 * - Glättung und Beschleunigungsbegrenzung
 */
class SpeedControllerV0(
    private val vGreen: Float = 0.8f,   // zügiges Gehen
    private val vYellow: Float = 1.0f,  // schnelles Gehen
    private val vRedBase: Float = 0.6f, // langsames Gehen
    private val vMax: Float = 2.0f,
    private val maxAccel: Float = 0.4f, // m/s pro Sekunde
    private val redPauseThresholdMs: Long = 45_000L // Pausenmodus-Schwelle
) : ISpeedController{
    private var lastV: Float = 0f
    private var lastTsMs: Long = 0L

    // Trend-Logik (Option C)
    private var lastHr: Int = 0

    // Pausen-Logik ( kann auch einfach auf 0 gesetzt werden, wenn nicht gewünscht)
    private var redZoneDurationMs: Long = 0L

    // Einfaches 3er-Mittel für v_set
    private val window: ArrayDeque<Float> = ArrayDeque()

    // ======================================
    // Tracking des letzten geglätteten Outputs für Joystick-Override
    // Wird benötigt um beim Override-Start den aktuellen Wert einzufrieren
    // und nach dem Override sanft wieder einzusteigen
    // =========================================
    private var lastSmoothedOutput: Float = 0f

    /**
     * zone     = aktuelle Zone (GREEN/YELLOW/RED)
     * hrSmooth = geglättete HR (z. B. aus HrProcessor)
     * nowMs    = System.currentTimeMillis()
     */
    override fun update(zone: Zone, hrSmooth: Int, nowMs: Long): Float {
        // Zeitdifferenz
        if (lastTsMs == 0L) {
            lastTsMs = nowMs
        }
        val dtSec = ((nowMs - lastTsMs).coerceAtLeast(1L)) / 1000f
        lastTsMs = nowMs

        // HR-Trend für Option C
        val hrTrend = if (lastHr == 0) {
            // Erstes Sample nach Reset: keinen künstlichen Sprung erzeugen
            0
        } else {
            hrSmooth - lastHr
        }
        lastHr = hrSmooth

        // Basisgeschwindigkeit abhängig von Zone
        val vTargetBase = when (zone) {
            Zone.GREEN  -> vGreen
            Zone.YELLOW -> vYellow
            Zone.RED    -> {
                // Option A + C: Red-Zone abhängig vom Trend
                val vRedDynamic = when {
                    hrTrend > 2  -> vRedBase * 0.7f   // HR steigt stark -> stärker bremsen
                    hrTrend > 0  -> vRedBase * 0.9f   // HR steigt leicht -> moderat bremsen
                    else         -> vRedBase          // HR stabil/sinkend -> langsam, aber nicht frustrierend
                }.coerceIn(0.4f, vGreen)             // Sicherheitskorridor

                // Pausenlogik (Option B als Notbremse)
                redZoneDurationMs += (dtSec * 1000f).toLong()
                if (redZoneDurationMs >= redPauseThresholdMs) {
                    0.0f // echte Pause
                } else {
                    vRedDynamic
                }
            }
        }

        // Reset der Red-Zonen-Dauer beim Verlassen von RED
        if (zone != Zone.RED) {
            redZoneDurationMs = 0L
        }

        // Beschleunigungsbegrenzung
        val vLimited = applyAccelLimit(vTargetBase, dtSec)

        // Glätten
        val smoothed = smooth(vLimited)

        // =========================================================================
        // NEU: Letzten Output speichern für getLastOutput()
        // =========================================================================
        lastSmoothedOutput = smoothed

        Log.d("SPEED", "Zone=$zone, hrTrend=$hrTrend, target=${"%.2f".format(vTargetBase)}, output=${"%.2f".format(smoothed)}")

        return smoothed
    }

    /**
     * V0 unterstützt kein ZoneInfo - wirft Fehler oder nutzt nur zone
     */
    override fun update(zoneInfo: ZoneInfo, hrSmooth: Int, nowMs: Long): Float {
        // Fallback: verwende nur die Zone, ignoriere Position
        Log.w("SPEED_V0", "V0 unterstützt keine Position - verwende nur Zone")
        return update(zoneInfo.zone, hrSmooth, nowMs)
    }

    private fun applyAccelLimit(vTarget: Float, dtSec: Float): Float {
        val dvMax = maxAccel * dtSec
        val vClamped = when {
            vTarget > lastV + dvMax -> lastV + dvMax
            vTarget < lastV - dvMax -> lastV - dvMax
            else -> vTarget
        }
        val vFinal = vClamped.coerceIn(0f, vMax)
        lastV = vFinal
        return vFinal
    }

    private fun smooth(v: Float): Float {
        if (window.size == 3) window.removeFirst()
        window.addLast(v)
        return window.average().toFloat()
    }

    override fun reset() {
        lastV = 0f
        lastTsMs = 0L
        lastHr = 0
        redZoneDurationMs = 0L
        window.clear()
        lastSmoothedOutput = 0f
        Log.i("SPEED", "SpeedControllerV0 reset")
    }

    // ==========================================
    // Getter für letzten Output
    // Wird in MainActivity beim Joystick-Override-Start aufgerufen,
    // um den aktuellen vSet-Wert einzufrieren
    // =============================================

    /**
     * Gibt den letzten geglätteten Output zurück.
     * Nützlich für:
     * - Einfrieren bei Joystick-Override-Start
     * - Sanfter Übergang nach Override-Ende
     * - Logging/Debugging
     */
    override fun getLastOutput(): Float = lastSmoothedOutput

    // =====================================
    // Setter für Output
    // Ermöglicht sanften Wiedereinstieg nach Override
    // ====================================

    /**
     * Setzt den internen Zustand auf einen bestimmten Wert.
     * Nützlich für sanften Übergang nach Joystick-Override.
     *
     * @param value Neuer Ausgangswert für die nächste Berechnung
     */
    override fun setOutput(value: Float) {
        val clamped = value.coerceIn(0f, vMax)
        lastV = clamped
        lastSmoothedOutput = clamped

        // Window mit dem neuen Wert füllen für konsistente Glättung
        window.clear()
        window.addLast(clamped)

        Log.d("SPEED", "Output manuell gesetzt auf ${"%.2f".format(clamped)} m/s")
    }


    // =========================================================================
    //  Getter
    // =========================================================================

    /**
     * Gibt die aktuelle Dauer in der Red-Zone zurück (in ms).
     */
    override fun getRedZoneDurationMs(): Long = redZoneDurationMs

    /**
     * True, wenn der Pausenmodus aktiv ist (Red-Zone länger als Threshold).
     */
    override fun isPauseActive(): Boolean = redZoneDurationMs >= redPauseThresholdMs


    /**
     * Gibt den letzten verarbeiteten HR-Wert zurück.
     */
    override fun getLastHr(): Int = lastHr


}