package com.parrot.hellodrone.altitude

import android.util.Log
import com.parrot.drone.groundsdk.device.pilotingitf.ManualCopterPilotingItf
import kotlin.math.roundToInt

/**
 * AltitudeDriver fuer Prototyp 2 (Indoor Altitude Control).
 *
 * Implementiert Hoehenregelung durch:
 * 1. Auslesen der aktuellen Drohnen-Hoehe
 * 2. Berechnung der vertikalen Geschwindigkeit (PD-Regler)
 * 3. Anwendung ueber setVerticalSpeed()
 *
 * SICHERHEIT:
 * - Emergency Landing wenn Hoehe nicht verfuegbar (null)
 * - Limits: 0.8m - 2.0m (angepasst an Raumhoehe/Ergometer-Sitzhoehe)
 *
 * WICHTIG: Parrot ANAFI hat KEINE direkte Hoehensteuerung (setAltitude),
 * sondern nur Vertikalgeschwindigkeit (setVerticalSpeed).
 */

class AltitudeDriver (
    private val itf: ManualCopterPilotingItf,
    private val altMin: Float = 0.8f,           // Sicherheits-Minimum (m)
    private val altMax: Float = 1.9f,           // Sicherheits-Maximum (m) - Raumhoehen-Limit
    private val kP: Float = 30.0f,              // Proportionalverstaerkung (% pro Meter Fehler)
    private val kD: Float = 25.0f,              // Daempfungsverstaerkung
    private val maxVerticalSpeed: Int = 40,     // Maximale Vertikalgeschwindigkeit (%)
    private val deadzone: Float = 0.05f,        // Totzone in Metern (keine Regelung)
    private val maxConsecutiveNulls: Int = 5,   // Anzahl null-Samples vor Emergency (bei 1Hz = 5 Sek)
    private val onEmergencyLanding: (() -> Unit)? = null  // Callback fuer Emergency Landing
) {

    private var lastError: Float = 0f
    private var lastTimestampMs: Long = -1L
    private var consecutiveNullCount: Int = 0   // Zaehler fuer aufeinanderfolgende null-Werte

    /**
     * Aktualisiert die Hoehensteuerung basierend auf Ziel-Hoehe.
     *
     * SICHERHEIT: Wenn currentAltitude null oder ungueltig -> Emergency Landing!
     *
     * @param targetAltitude Ziel-Flughoehe in Metern (vom AltitudeController)
     * @param currentAltitude Aktuelle Drohnen-Hoehe in Metern (aus Telemetrie) - KANN NULL SEIN!
     * @param nowMs Aktueller Zeitstempel (System.currentTimeMillis())
     */
    fun updateAltitude(targetAltitude: Float, currentAltitude: Float?, nowMs: Long) {


        // Hoehe verfuegbar?
        // ROBUSTHEIT: Nur nach mehreren aufeinanderfolgenden Nulls -> Emergency
        if (currentAltitude == null) {
            consecutiveNullCount++

            Log.w("ALTITUDE_DRIVER", "WARNING: Hoehe nicht verfuegbar ($consecutiveNullCount/$maxConsecutiveNulls)")

            if (consecutiveNullCount >= maxConsecutiveNulls) {
                // Nach N aufeinanderfolgenden Nulls -> jetzt Emergency
                Log.e("ALTITUDE_DRIVER", "KRITISCH: Hoehe $maxConsecutiveNulls Mal null! Notlandung.")

                // Nur Callback aufrufen - KEIN direktes itf.land() hier!
                // MainActivity.emergencyLand() kuemmert sich um State/Logging
                onEmergencyLanding?.invoke()
            } else {
                // Noch im Toleranzbereich -> Hover halten
                itf.setVerticalSpeed(0)
                itf.setPitch(0)
                itf.setRoll(0)
                itf.setYawRotationSpeed(0)
                Log.d("ALTITUDE_DRIVER", "Hover wegen fehlendem Altitude-Signal")
            }
            return
        }

        // Hoehe ist verfuegbar -> Reset des Null-Counters
        if (consecutiveNullCount > 0) {
            Log.i("ALTITUDE_DRIVER", "OK: Hoeheninformation wiederhergestellt nach $consecutiveNullCount Nulls")
            consecutiveNullCount = 0
        }

        //  Pruefe aktuelle Hoehe
        if (currentAltitude >= altMax - 0.1f) {
            // Innerhalb 10cm vom Limit -> NUR NOCH SINKEN!
            if (targetAltitude >= currentAltitude) {
                itf.setVerticalSpeed(0)  // Zwangs-Hover
                itf.setPitch(0)
                itf.setRoll(0)
                itf.setYawRotationSpeed(0)
                return
            }
        }

        //  Unteres Limit - innerhalb 10cm vom Minimum ->  HOVER
        if (currentAltitude <= altMin + 0.1f) {
            if (targetAltitude <= currentAltitude) {
                itf.setVerticalSpeed(0)
                itf.setPitch(0)
                itf.setRoll(0)
                itf.setYawRotationSpeed(0)
                return
            }
        }

        // ======================================
        // REGELUNG:
        // 1. Target clampen
        // 2. Error berechnen
        // 3. Zeitdifferenz bestimmen
        // 4. PD-Regler anwenden
        // ====================================

        // 1) Sicherheitscheck: Ziel-Hoehe begrenzen
        val targetClamped = targetAltitude.coerceIn(altMin, altMax)

        // 2) Regelabweichung berechnen
        val error = targetClamped - currentAltitude

        // 3) Zeitdifferenz fuer D-Anteil bestimmen
        val dtMs = if (lastTimestampMs < 0L) 0L else (nowMs - lastTimestampMs).coerceAtLeast(0L)
        val dtSec = dtMs / 1000f
        if (lastTimestampMs < 0L) {
            lastError = error
        }
        lastTimestampMs = nowMs

        // 4) Totzone: Bei kleinen Abweichungen nicht regeln (verhindert Oszillation)
        if (kotlin.math.abs(error) < deadzone) {
            itf.setVerticalSpeed(0)
            itf.setPitch(0)
            itf.setRoll(0)
            itf.setYawRotationSpeed(0)

            // Zustand fortschreiben, nicht auf 0 setzen
            lastError = error
            lastTimestampMs = nowMs

            Log.d("ALTITUDE_DRIVER", "In Totzone (error=${"%.3f".format(error)}m) - Hover")
            return
        }

        // PD-Regler: Proportional + Daempfung
        // P-Anteil: proportional zum Fehler
        val pTerm = kP * error

        // D-Anteil: daempft schnelle Aenderungen (verhindert Ueberschwingen)
        val errorRate = if (dtSec > 0f) (error - lastError) / dtSec else 0f
        val dTerm = kD * errorRate

        // Gesamt-Stellgroesse: P minus D fuer Daempfung
        val verticalSpeedRaw = pTerm - dTerm

        // Begrenzung auf maximale Vertikalgeschwindigkeit
        val verticalSpeed = verticalSpeedRaw.roundToInt().coerceIn(-maxVerticalSpeed, maxVerticalSpeed)

        // An Drohne senden
        itf.setVerticalSpeed(verticalSpeed)

        // Weitere Achsen auf Null (stationaer in X/Y)

        // WICHTIG: Pitch/Roll/Yaw werden NICHT auf 0 gesetzt!
        // Grund: Bei Manual Override sollen Joystick-Korrekturen moeglich sein.

        // State update
        lastError = error
        lastTimestampMs = nowMs

        Log.d("ALTITUDE_DRIVER", "target=${"%.2f".format(targetClamped)}m, current=${"%.2f".format(currentAltitude)}m, error=${"%.3f".format(error)}m, vSpeed=$verticalSpeed%")
    }

    /**
     * Stoppt alle Bewegungen (Hover).
     */
    fun stop() {
        itf.setPitch(0)
        itf.setRoll(0)
        itf.setYawRotationSpeed(0)
        itf.setVerticalSpeed(0)

        lastError = 0f
        lastTimestampMs = -1L
        consecutiveNullCount = 0

        Log.d("ALTITUDE_DRIVER", "STOP - Hover-Modus")
    }

    /**
     * Reset des Reglers (z.B. zwischen Sessions).
     */
    fun reset() {
        lastError = 0f
        lastTimestampMs = -1L
        consecutiveNullCount = 0
        Log.i("ALTITUDE_DRIVER", "AltitudeDriver reset")
    }
}