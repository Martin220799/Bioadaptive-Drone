package com.parrot.hellodrone.main

import android.util.Log
import android.view.View
import com.parrot.hellodrone.config.PrototypeConfig
import com.parrot.hellodrone.MainActivity
import com.parrot.hellodrone.R
import android.widget.*

    internal fun MainActivity.onToggleLogging() {
        //if (!noDroneMode) return
        if (!isLogging) {
            // FIX: Prüfung auf BEIDE Kalibrierungsschritte
            if (baselineHr == null || walkCalibrationMeanHr == null) {
                Toast.makeText(
                    this,
                    " Please do PreFlight-HRV AND Calibration first!",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            // FIX: Warnung wenn kein Profil geladen (Daten werden "ToFill" sein)
            if (currentProfile == null) {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("No Profile loaded")
                    .setMessage("Without profile 'ToFill' will be logged as User-ID.\n\n Continue?")
                    .setPositiveButton("Yes, continue") { _, _ ->
                        startLoggingSession()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                return
            }

            startLoggingSession()
        } else {
            // Logging stoppen
            isLogging = false
            stopWatchdog()
            flightLogging = false
            lastHrLoggedAtMs = 0L      // <<< HINZUFÜGEN
            lastSimSentAtMs = 0L       // <<< HINZUFÜGEN
            //polar.stopScan()
            btnLogToggle.text = getString(R.string.btn_start_logging)
            csvLogger.logEvent("STOP_CONTROL_WALK segment=$segmentId")
        }
    }

    /**
     * Startet die Logging-Session (ausgelagert für AlertDialog-Callback)
     */
    internal fun MainActivity.startLoggingSession() {

        ensureSessionExistsWithTimebase()
        // neuen CONTROL_WALK-Abschnitt beginnen
        segmentId++
        csvLogger.logEvent("START_CONTROL_WALK segment=$segmentId")

        isLogging = true
        flightLogging = true
        startWatchdog()
        lastHrLoggedAtMs = 0L
        lastSimSentAtMs = 0L  // Reset für sofortige erste Simulation-Sendung
        lastHrSmooth = 0  // Reset

        // Jetzt: Session nur anlegen, wenn noch keine existiert.
        /** if (csvLogger.getCurrentSessionId().isEmpty()) {
        csvLogger.startNewSession()
        }*/

        // Meta loggen (mit Profil-Daten und Prototype-Type)
        csvLogger.logSessionMeta(
            condition = "control",  // Walk-Test ohne Drohne
            streckeMeters = null,
            prototypeType = PrototypeConfig.currentPrototype.csvIdentifier,
            participantId = currentProfile?.participantId ?: "ToFill",
            participantAge = currentProfile?.age,
            speedControlVersion = if (PrototypeConfig.usesHorizontalControl())
                getCurrentVersionString() else null,
            altitudeControlVersion = if (PrototypeConfig.usesVerticalControl())
                getCurrentAltitudeVersionString() else null
        )

        hrProcessor.resetQc()
        resetAllSpeedControllers()

        polar.startScan()
        btnLogToggle.text = getString(R.string.btn_stop_logging)

        // Speed Recommendation sichtbar machen
        speedRecommendationTxt.visibility = View.VISIBLE

        Toast.makeText(this, "Logging started.", Toast.LENGTH_SHORT).show()
        Log.i("LOG", "Manual logging started (no-drone mode).")
    }
