package com.parrot.hellodrone.main

import android.util.Log
import android.os.Build
import android.view.View
import android.os.VibrationEffect
import android.view.MotionEvent
import com.parrot.drone.groundsdk.device.pilotingitf.ManualCopterPilotingItf
import com.parrot.hellodrone.altitude.AltitudeDriver
import com.parrot.hellodrone.flight.ManualDriver
import com.parrot.hellodrone.config.PrototypeType
import com.parrot.hellodrone.config.PrototypeConfig
import com.parrot.hellodrone.MainActivity
import com.parrot.hellodrone.R
import android.widget.*

    internal fun MainActivity.onTakeOffLandClick() {
        pilotingItfRef?.get()?.let { itf ->
            when {
                itf.canTakeOff() -> {
                    // Safety: im Simulation-Prototyp kein Takeoff erlauben (Landen bleibt möglich)
                    if (PrototypeConfig.currentPrototype == PrototypeType.SIMULATION) {
                        Toast.makeText(this, "Simulation-Prototype: Takeoff deactivated.", Toast.LENGTH_SHORT).show()
                        return@let
                    }

                    safetyLandingActive = false  // <- wichtig
                    // Falls Walk-Logging noch aktiv war, sauber beenden
                    if (isLogging) {
                        isLogging = false
                        stopWatchdog()
                        flightLogging = false
                        hrProcessor.resetQc()
                        resetAllSpeedControllers()
                        btnLogToggle.text = getString(R.string.btn_start_logging)
                        speedRecommendationTxt.visibility = View.GONE
                    }

                    // Update V4 Kalibrierungsprüfung vor Takeoff
                    if (requireCalibrationForTakeoff) {
                        if (baselineHr == null || walkCalibrationMeanHr == null) {
                            Toast.makeText(
                                this,
                                "Please do PreFlight-HRV AND Calibration before!",
                                Toast.LENGTH_LONG
                            ).show()
                            return
                        }
                    }
                    // Update V4 Filter & Controller frisch für den Flug
                    hrProcessor.resetQc()
                    resetAllSpeedControllers()
                    lastHrSmooth = 0  // Reset

                    // Jetzt: Session nur anlegen, wenn noch keine existiert.
                    /**if (csvLogger.getCurrentSessionId().isEmpty()) {
                    csvLogger.startNewSession()  // nur im echten Erstfall
                    }*/
                    ensureSessionExistsWithTimebase()

                    // neuen Abschnitt (Segment) für diesen Flug beginnen
                    segmentId++
                    csvLogger.logEvent("START_DRONE_WALK segment=$segmentId")

                    //Meta loggen ( mit Profil-Daten und Prototype-Type)
                    csvLogger.logSessionMeta(
                        condition = "intervention",  // Echter Flug mit Drohne
                        streckeMeters = null,
                        prototypeType = PrototypeConfig.currentPrototype.csvIdentifier,
                        participantId = currentProfile?.participantId ?: "ToFill",
                        participantAge = currentProfile?.age,
                        speedControlVersion = if (PrototypeConfig.usesHorizontalControl())
                            getCurrentVersionString() else null,
                        altitudeControlVersion = if (PrototypeConfig.usesVerticalControl())
                            getCurrentAltitudeVersionString() else null
                    )

                    flightLogging = true
                    startWatchdog()
                    lastHrLoggedAtMs = 0L
                    lastSimSentAtMs = 0L
                    polar.startScan()

                    // Safety: Joystick/Override-State vor Takeoff resetten (verhindert "stuck override")
                    manualOverrideActive = false
                    leftJoystickActive = false
                    rightJoystickActive = false
                    leftPointerId = MotionEvent.INVALID_POINTER_ID
                    rightPointerId = MotionEvent.INVALID_POINTER_ID
                    jsLeftX = 0f
                    jsLeftY = 0f
                    jsRightX = 0f
                    jsRightY = 0f
                    frozenVSet = null
                    updateThumbPosition(joystickLeftThumb, joystickLeftContainer, 0f, 0f)
                    updateThumbPosition(joystickRightThumb, joystickRightContainer, 0f, 0f)
                    updateOverrideStatusUI()

                    itf.takeOff()
                    Log.i("FLIGHT", "Takeoff: HR-Logging activated")
                }
                itf.canLand() -> {
                    Log.i("FLIGHT", "Land-Button pressed")

                    // 1. SOFORT alle Kommando-Quellen blockieren (KRITISCH: vor Driver-Stop!)
                    flightLogging = false      // ← Blockiert HR-Callback SOFORT
                    stopWatchdog()

                    // 2. Safety-Gates setzen
                    safetyLandingActive = true
                    manualOverrideActive = false

                    // 3. Joystick-State komplett zurücksetzen
                    leftJoystickActive = false
                    rightJoystickActive = false
                    leftPointerId = MotionEvent.INVALID_POINTER_ID
                    rightPointerId = MotionEvent.INVALID_POINTER_ID
                    jsLeftX = 0f
                    jsLeftY = 0f
                    jsRightX = 0f
                    jsRightY = 0f
                    frozenVSet = null

                    // 4. Driver stoppen + freigeben (NACH flightLogging = false!)
                    try {
                        altitudeDriver?.stop()
                        Log.d("FLIGHT", "AltitudeDriver stopped")
                    } catch (t: Throwable) {
                        Log.w("FLIGHT", "AltitudeDriver.stop() failed: ${t.message}")
                    }

                    try {
                        (flightDriver as? ManualDriver)?.stop()
                        Log.d("FLIGHT", "ManualDriver stopped")
                    } catch (t: Throwable) {
                        Log.w("FLIGHT", "ManualDriver.stop() failed: ${t.message}")
                    }

                    altitudeDriver = null
                    flightDriver = null

                    // 5. Land-Befehl senden
                    itf.land()
                    Log.i("FLIGHT", "Land-Befehl sent")

                    // 6. Cleanup
                    lastHrLoggedAtMs = 0L
                    lastSimSentAtMs = 0L
                    lastHrSmooth = 0
                    hrProcessor.resetQc()
                    resetAllSpeedControllers()
                    csvLogger.logEvent("END_DRONE_WALK segment=$segmentId")
                    Log.i("FLIGHT", "Landing finished")
                }
                else -> { /* keine Aktion */ }
            }
        }
    }

    internal fun MainActivity.emergencyLand() {
        Log.w("SAFETY", "Emergency Land triggered!")
        csvLogger.logEvent("EMERGENCY_LAND")

        // Safety-Gate setzen (blockiert alle weiteren Steuerkommandos)
        safetyLandingActive = true

        // 1) Joystick-State zurücksetzen
        leftJoystickActive = false
        rightJoystickActive = false
        leftPointerId = MotionEvent.INVALID_POINTER_ID
        rightPointerId = MotionEvent.INVALID_POINTER_ID
        jsLeftX = 0f
        jsLeftY = 0f
        jsRightX = 0f
        jsRightY = 0f
        manualOverrideActive = false
        frozenVSet = null

        // 2) UI: Thumbs zentrieren + Override-Status auf AUTO
        runOnUiThread {
            updateThumbPosition(joystickLeftThumb, joystickLeftContainer, 0f, 0f)
            updateThumbPosition(joystickRightThumb, joystickRightContainer, 0f, 0f)
            updateOverrideStatusUI()

            Toast.makeText(this, "EMERGENCY LANDING ACTIVATED!", Toast.LENGTH_LONG).show()
        }

        // Logging/Watchdog sofort stoppen (Race-frei)
        stopWatchdog()
        flightLogging = false
        isLogging = false

        // 3) Flug hart stoppen + landen (nur im Drone-Mode)
        if (!noDroneMode) {

            // Beide Sender stoppen (Prototype 2: AltitudeDriver + Joystick/ManualDriver)
            try {
                altitudeDriver?.stop()
                altitudeDriver = null  // Freigeben für Neuinitialisierung
            } catch (t: Throwable) {
                Log.w("SAFETY", "altitudeDriver.stop() failed: ${t.message}")
            }

            try {
                (flightDriver as? ManualDriver)?.stop()
                flightDriver = null    // Freigeben für Neuinitialisierung
            } catch (t: Throwable) {
                Log.w("SAFETY", "manualDriver.stop() failed: ${t.message}")
            }

            // Land-Befehl senden (robuster als nur canLand-Check)
            pilotingItfRef?.get()?.let { itf ->
                try {
                    // Optional: falls du activate nutzt/benötigst
                    // itf.activate()

                    itf.land()
                    Log.w("SAFETY", "Landing command sent via ManualCopterPilotingItf.")
                } catch (t: Throwable) {
                    Log.w("SAFETY", "EmergencyLand: land() failed: ${t.message}")
                }
            }
        }

        lastHrSmooth = 0
        lastSimSentAtMs = 0L  // Reset für Simulation
        lastHrLoggedAtMs = 0L
        hrProcessor.resetQc()

        resetAllSpeedControllers()

        runOnUiThread {
            btnLogToggle.text = getString(R.string.btn_start_logging)
        }

        // 5) Haptisches Feedback (falls vorhanden)
        if (isVibratorInitialized && vibrator.hasVibrator()) {
            val pattern = longArrayOf(0, 200, 100, 200)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        }
    }

    internal fun MainActivity.resetAllSpeedControllers() {
        if (isSpeedControllerV0Initialized) {
            speedControllerV0.reset()
            Log.d("SPEED", "V0 reset")
        }
        if (isSpeedControllerV1Initialized) {
            speedControllerV1.reset()
            Log.d("SPEED", "V1 reset")
        }
        if (isSpeedControllerV2Initialized) {
            speedControllerV2.reset()
            Log.d("SPEED", "V2 reset")
        }
    }
