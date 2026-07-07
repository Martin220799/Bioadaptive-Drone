package com.parrot.hellodrone.main

import android.util.Log
import com.parrot.drone.groundsdk.device.DeviceState
import com.parrot.drone.groundsdk.device.instrument.BatteryInfo
import com.parrot.drone.groundsdk.device.peripheral.StreamServer
import com.parrot.drone.groundsdk.device.peripheral.stream.CameraLive
import com.parrot.drone.groundsdk.device.pilotingitf.Activable
import com.parrot.drone.groundsdk.device.pilotingitf.ManualCopterPilotingItf
import com.parrot.hellodrone.altitude.AltitudeDriver
import com.parrot.hellodrone.flight.ManualDriver
import com.parrot.hellodrone.config.PrototypeType
import com.parrot.hellodrone.config.PrototypeConfig
import com.parrot.hellodrone.MainActivity
import com.parrot.hellodrone.R

    /** ---------------- Drone/RC ---------------- */

    internal fun MainActivity.resetDroneUi() {
        droneStateTxt.text = DeviceState.ConnectionState.DISCONNECTED.toString()
        droneBatteryTxt.text = ""
        takeOffLandBt.isEnabled = false
        streamView.setStream(null)
    }

    internal fun MainActivity.startDroneMonitors() {
        monitorDroneState()
        monitorDroneBatteryChargeLevel()
        monitorPilotingInterface()
        monitorAltitude()  // Höhen-Telemetrie für Prototype 2
        startVideoStream()
    }

    internal fun MainActivity.stopDroneMonitors() {
        droneStateRef?.close(); droneStateRef = null
        droneBatteryInfoRef?.close(); droneBatteryInfoRef = null
        pilotingItfRef?.close(); pilotingItfRef = null
        altimeterRef?.close(); altimeterRef = null
        liveStreamRef?.close(); liveStreamRef = null
        streamServerRef?.close(); streamServerRef = null
        liveStream = null
    }

    internal fun MainActivity.startVideoStream() {
        streamServerRef = drone?.getPeripheral(StreamServer::class.java) { streamServer ->
            if (streamServer != null) {
                if (!streamServer.streamingEnabled()) streamServer.enableStreaming(true)
                if (liveStreamRef == null) {
                    liveStreamRef = streamServer.live { live ->
                        if (live != null) {
                            if (this.liveStream == null) streamView.setStream(live)
                            if (live.playState() != CameraLive.PlayState.PLAYING) live.play()
                        } else {
                            streamView.setStream(null)
                        }
                        this.liveStream = live
                    }
                }
            } else {
                liveStreamRef?.close(); liveStreamRef = null
                streamView.setStream(null)
            }
        }
    }

    internal fun MainActivity.monitorDroneState() {
        droneStateRef = drone?.getState { it?.let { state -> droneStateTxt.text = state.connectionState.toString() } }
    }

    internal fun MainActivity.monitorDroneBatteryChargeLevel() {
        droneBatteryInfoRef = drone?.getInstrument(BatteryInfo::class.java) {
            it?.let { info -> droneBatteryTxt.text = getString(R.string.percentage, info.charge) }
        }
    }

    internal fun MainActivity.monitorPilotingInterface() {
        pilotingItfRef = drone?.getPilotingItf(ManualCopterPilotingItf::class.java) { itf ->
            if (itf == null) {
                takeOffLandBt.isEnabled = false
            } else {
                managePilotingItfState(itf)
            }
        }
    }

    /**
     * Höhen-Telemetrie für Prototype 2 (Indoor Altitude)
     */
    internal fun MainActivity.monitorAltitude() {
        altimeterRef = drone?.getInstrument(com.parrot.drone.groundsdk.device.instrument.Altimeter::class.java) { altimeter ->
            if (altimeter != null) {
                // Takeoff Altitude = Relative Höhe über Startpunkt (in Metern)
                currentAltitude = altimeter.takeOffRelativeAltitude?.toFloat()

                // UI Update (nur wenn Prototype 2 aktiv)
                if (PrototypeConfig.currentPrototype == PrototypeType.INDOOR_ALTITUDE) {
                    runOnUiThread {
                        val targetAlt = altitudeController.getLastOutput()
                        altitudeStatusTxt.text = "Target: ${"%.2f".format(targetAlt)}m | Current: ${"%.2f".format(currentAltitude ?: 0f)}m"
                    }
                }
            } else {
                currentAltitude = null
            }
        }
    }

    internal fun MainActivity.managePilotingItfState(itf: ManualCopterPilotingItf) {
        when (itf.state) {
            Activable.State.UNAVAILABLE -> takeOffLandBt.isEnabled = false
            Activable.State.IDLE -> { takeOffLandBt.isEnabled = false; itf.activate() }
            Activable.State.ACTIVE -> {

                // WICHTIG: sobald Landing aktiv ist, KEINE Driver re-initialisieren
                if (PrototypeConfig.requiresRealDrone() && !noDroneMode && !safetyLandingActive) {

                    // ManualDriver wird für Joystick-Override in OUTDOOR + INDOOR_ALTITUDE benötigt
                    if (flightDriver == null) {
                        flightDriver = ManualDriver(itf, vMax = vSetCap)
                        Log.i("FLIGHT", "ManualDriver initialised (vMax=$vSetCap).")
                    }

                    // AltitudeDriver nur für Prototype 2
                    if (PrototypeConfig.currentPrototype == PrototypeType.INDOOR_ALTITUDE && altitudeDriver == null) {
                        altitudeDriver = AltitudeDriver(
                            itf = itf,
                            altMin = 0.8f,
                            altMax = 1.9f,
                            kP = 30.0f,
                            kD = 25.0f,
                            maxVerticalSpeed = 40,
                            deadzone = 0.05f,
                            maxConsecutiveNulls = 5,
                            onEmergencyLanding = { emergencyLand() }
                        )
                        Log.i("ALTITUDE", "AltitudeDriver initialised.")
                    }
                }

                when {
                    itf.canTakeOff() -> {
                        // Safety: Im Simulation-Prototyp kein Takeoff erlauben.
                        // (Hier KEIN Toast, weil managePilotingItfState() häufig aufgerufen wird.)
                        if (PrototypeConfig.currentPrototype == PrototypeType.SIMULATION) {
                            takeOffLandBt.isEnabled = false
                            takeOffLandBt.text = "Takeoff disabled (Simulation)"
                        } else {
                            takeOffLandBt.isEnabled = true
                            takeOffLandBt.text = getString(R.string.take_off)

                            // Landing ist fertig (am Boden) -> Safety wieder freigeben
                            if (safetyLandingActive) {
                                safetyLandingActive = false
                                Log.i("SAFETY", "Landing complete -> safetyLandingActive=false")
                            }
                        }
                    }
                    itf.canLand() -> { takeOffLandBt.isEnabled = true; takeOffLandBt.text = getString(R.string.land) }
                    else -> takeOffLandBt.isEnabled = false
                }
            }
        }
    }

    internal fun MainActivity.resetRcUi() {
        rcStateTxt.text = DeviceState.ConnectionState.DISCONNECTED.toString()
        rcBatteryTxt.text = ""
    }

    internal fun MainActivity.startRcMonitors() {
        monitorRcState()
        monitorRcBatteryChargeLevel()
    }

    internal fun MainActivity.stopRcMonitors() {
        rcStateRef?.close(); rcStateRef = null
        rcBatteryInfoRef?.close(); rcBatteryInfoRef = null
    }

    internal fun MainActivity.monitorRcState() {
        rcStateRef = rc?.getState { it?.let { state -> rcStateTxt.text = state.connectionState.toString() } }
    }

    internal fun MainActivity.monitorRcBatteryChargeLevel() {
        rcBatteryInfoRef = rc?.getInstrument(BatteryInfo::class.java) {
            it?.let { info -> rcBatteryTxt.text = getString(R.string.percentage, info.charge) }
        }
    }
