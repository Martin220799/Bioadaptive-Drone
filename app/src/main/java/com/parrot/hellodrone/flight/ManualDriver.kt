package com.parrot.hellodrone.flight

import android.util.Log
import com.parrot.drone.groundsdk.device.pilotingitf.ManualCopterPilotingItf
import kotlin.math.roundToInt

/**
 * ManualDriver für bioadaptive Drohnensteuerung mit Joystick-Override.
 *
 * Unterstützt:
 * - HR-basierte automatische Vorwärtsgeschwindigkeit (setForwardSpeed)
 * - Manuelle Joystick-Kontrolle (applyManualControls)
 */
class ManualDriver(
    private val itf: ManualCopterPilotingItf,
    private val vMax: Float = 2.0f,     // erwartete v_set Obergrenze (m/s)
    private val pitchMax: Int = 40      // max Pitch %, erfahrungsgemäß ~0..40 = 0..~2 m/s
) : FlightDriver {

    // ===========================================================
    // Maximalwerte für manuelle Joystick-Steuerung
    // =========================================================
    private val maxManualPitch = 20     // Grad (vorwärts/rückwärts) - konservativer als pitchMax
    private val maxManualRoll = 20      // Grad (seitwärts)
    private val maxManualYawRate = 190  // deg/s (drehen)
    private val maxManualVertical = 40  // Prozent (-100 bis +100 im SDK)

    // =================================
    // Tracking für Debugging/Logging
    // ================================
    private var lastPitch = 0
    private var lastRoll = 0
    private var isManualMode = false

    override fun arm()    { if (itf.canTakeOff()) itf.takeOff() }
    override fun disarm() { if (itf.canLand()) itf.land() }

    /**
     * Setzt die Vorwärtsgeschwindigkeit basierend auf HR-Daten.
     * Verwendet negative Pitch-Werte für Vorwärtsbewegung (Parrot SDK Konvention).
     *
     * @param vSet Geschwindigkeit in m/s [0 ... vMax]
     */
    override fun setForwardSpeed(vSet: Float) {
        // Parrot-Doku: pitch < 0 = nach vorne, pitch > 0 = nach hinten
        // Mappen v_set [0..vMax] -> pitch [0..-pitchMax] (0 = stehen, -pitchMax = max. vorwärts)
        val v = vSet.coerceIn(0f, vMax)
        val f = if (vMax > 0f) (v / vMax).coerceIn(0f, 1f) else 0f

        // Lineares Mapping
        val pitch = (-f * pitchMax).roundToInt()
        itf.setPitch(pitch)

        // ======================================================================
        // Roll, Yaw, Vertical auf Null setzen für reine Vorwärtsbewegung
        // Dies verhindert Drift wenn von Manual auf HR-Steuerung gewechselt wird
        // =========================================================================
        itf.setRoll(0)
        itf.setYawRotationSpeed(0)
        itf.setVerticalSpeed(0)

        // Tracking
        lastPitch = pitch
        lastRoll = 0
        isManualMode = false

        Log.d("DRIVER", "HR-Steuerung: vSet=${"%.2f".format(v)} m/s -> pitch=$pitch%")
    }

    // =======================================
    // Manuelle Joystick-Kontrolle
    // =======================================

    /**
     * Wendet manuelle Joystick-Kontrolle an.
     * Alle Werte sind normalisiert auf [-1, 1].
     *
     * @param pitch Vorwärts/Rückwärts [-1 = rückwärts, +1 = vorwärts]
     * @param roll Seitwärts [-1 = links, +1 = rechts]
     * @param yaw Drehen [-1 = links, +1 = rechts]
     * @param vertical Steigen/Sinken [-1 = sinken, +1 = steigen]
     */
    fun applyManualControls(pitch: Float, roll: Float, yaw: Float, vertical: Float) {
        // ================================================================
        // Pitch: Parrot SDK erwartet negativ = vorwärts
        // Da pitch-Parameter bereits -1 (rückwärts) bis +1 (vorwärts) ist,
        // und Parrot negativ = vorwärts erwartet, invertieren
        // =================================================================
        val pitchValue = (-pitch * maxManualPitch).roundToInt().coerceIn(-maxManualPitch, maxManualPitch)
        val rollValue = (roll * maxManualRoll).roundToInt().coerceIn(-maxManualRoll, maxManualRoll)
        val yawValue = (yaw * maxManualYawRate).roundToInt().coerceIn(-maxManualYawRate, maxManualYawRate)
        val vertValue = (vertical * maxManualVertical).roundToInt().coerceIn(-maxManualVertical, maxManualVertical)

        itf.setPitch(pitchValue)
        itf.setRoll(rollValue)
        itf.setYawRotationSpeed(yawValue)
        itf.setVerticalSpeed(vertValue)

        // Tracking
        lastPitch = pitchValue
        lastRoll = rollValue
        isManualMode = true

        Log.d("DRIVER", "Manual: pitch=$pitchValue%, roll=$rollValue%, yaw=$yawValue°/s, vert=$vertValue%")
    }

    // =====================
    //  Hilfsfunktionen
    // ======================

    /**
     * Stoppt alle Bewegungen sofort (Hover).
     * Nützlich für Emergency-Situationen.
     */
    fun stop() {
        itf.setPitch(0)
        itf.setRoll(0)
        itf.setYawRotationSpeed(0)
        itf.setVerticalSpeed(0)

        lastPitch = 0
        lastRoll = 0
        isManualMode = false

        Log.d("DRIVER", "STOP - alle Achsen auf 0")
    }

    /**
     * Gibt den letzten Pitch-Wert zurück (für Debugging/Logging).
     */
    fun getLastPitch(): Int = lastPitch

    /**
     * Gibt den letzten Roll-Wert zurück (für Debugging/Logging).
     */
    fun getLastRoll(): Int = lastRoll

    /**
     * Prüft ob aktuell im manuellen Modus.
     */
    fun isInManualMode(): Boolean = isManualMode
}