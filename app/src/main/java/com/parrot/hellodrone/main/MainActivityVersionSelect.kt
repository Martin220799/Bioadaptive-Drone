package com.parrot.hellodrone.main

import android.util.Log
import com.parrot.hellodrone.config.PrototypeType
import com.parrot.hellodrone.config.PrototypeConfig
import com.parrot.hellodrone.MainActivity
import com.parrot.hellodrone.R
import com.parrot.hellodrone.SpeedControlVersion
import com.parrot.hellodrone.AltitudeControlVersion
import android.widget.*

    internal fun MainActivity.onSpeedVersionChanged(checkedId: Int) {
        val newVersion = when (checkedId) {
            R.id.rbSpeedV0 -> SpeedControlVersion.ORIGINAL
            R.id.rbSpeedV1 -> SpeedControlVersion.TREND
            R.id.rbSpeedV2 -> SpeedControlVersion.OPTIMAL
            else -> return
        }

        // Prüfe ob während aktiver Session gewechselt wird
        if (flightLogging) {
            Toast.makeText(
                this,
                "Version changed during logging! Session stopped.",
                Toast.LENGTH_LONG
            ).show()

            // Logging stoppen
            stopWatchdog()
            flightLogging = false
            isLogging = false
            btnLogToggle.text = getString(R.string.btn_start_logging)
        }

        // Aktiven Controller wechseln
        speedController = when (newVersion) {
            SpeedControlVersion.ORIGINAL -> {
                Log.i("SPEED", "Switch to V0 (Original)")
                speedControllerV0
            }
            SpeedControlVersion.TREND -> {
                Log.i("SPEED", "Switch to V1 (Trend)")
                speedControllerV1
            }
            SpeedControlVersion.OPTIMAL -> {
                Log.i("SPEED", "Switch to V2 (Optimal)")
                speedControllerV2
            }
        }

        // Aktualisiere speedControlVersion Variable
        speedControlVersion = newVersion

        // Alle Controller resetten
        resetAllSpeedControllers()

        Toast.makeText(
            this,
            "Speed Controller changed to: ${getVersionDisplayName(newVersion)}",
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * Callback für Prototyp-Wechsel
     */
    internal fun MainActivity.onPrototypeChanged(checkedId: Int) {
        val newPrototype = when (checkedId) {
            R.id.rbPrototypeOutdoor -> PrototypeType.OUTDOOR
            R.id.rbPrototypeAltitude -> PrototypeType.INDOOR_ALTITUDE
            R.id.rbPrototypeSimulation -> PrototypeType.SIMULATION
            else -> return
        }

        // Prüfe ob während aktiver Session gewechselt wird
        if (flightLogging) {
            Toast.makeText(
                this,
                " Prototype-Switch during active Logging! Session stopped.",
                Toast.LENGTH_LONG
            ).show()

            // Logging stoppen
            stopWatchdog()
            flightLogging = false
            isLogging = false
            btnLogToggle.text = getString(R.string.btn_start_logging)
        }

        // Prototyp wechseln
        PrototypeConfig.setPrototype(newPrototype)
        Log.i("PROTOTYPE", "Prototype changed to: ${newPrototype.displayName}")

        // Driver zurücksetzen (wird bei nächstem Takeoff neu initialisiert)
        flightDriver = null
        altitudeDriver = null

        // Controller resetten
        resetAllSpeedControllers()
        resetAllAltitudeControllers()

        // Simulation UI State aktualisieren
        val isSimPrototype = (newPrototype == PrototypeType.SIMULATION)
        updateSimulationUIState(isSimPrototype)

        // Auto-Disconnect wenn weg von Simulation gewechselt wird
        if (!isSimPrototype && simulationClient.isConnected()) {
            simulationClient.disconnect()
            Log.i("SIMULATION", "Auto-disconnect: Switched away from SIMULATION")
        }

        // Reset Warn-Flag (Prototype vs. Simulation-Connection)
        simPrototypeMismatchWarned = false

        // UI/Driver-State sofort aktualisieren (z.B. Takeoff im Simulation-Prototyp sperren)
        pilotingItfRef?.get()?.let { managePilotingItfState(it) }

        Toast.makeText(
            this,
            "Prototyp changed to: ${newPrototype.displayName}",
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * Callback für Altitude Controller Version Wechsel (Prototype 2)
     */
    internal fun MainActivity.onAltitudeVersionChanged(checkedId: Int) {
        val newVersion = when (checkedId) {
            R.id.rbAltitudeA0 -> AltitudeControlVersion.A0
            R.id.rbAltitudeA1 -> AltitudeControlVersion.A1
            R.id.rbAltitudeA2 -> AltitudeControlVersion.A2
            else -> return
        }

        // Prüfe ob während aktiver Session gewechselt wird
        if (flightLogging) {
            Toast.makeText(
                this,
                "Version Switch during Logging! Session stopped.",
                Toast.LENGTH_LONG
            ).show()

            // Logging stoppen
            stopWatchdog()
            flightLogging = false
            isLogging = false
            btnLogToggle.text = getString(R.string.btn_start_logging)
        }

        // Aktiven Controller wechseln
        altitudeController = when (newVersion) {
            AltitudeControlVersion.A0 -> {
                Log.i("ALTITUDE", "Switch to A0 (Baseline)")
                altitudeControllerA0
            }
            AltitudeControlVersion.A1 -> {
                Log.i("ALTITUDE", "Switch to A1 (Trend)")
                altitudeControllerA1
            }
            AltitudeControlVersion.A2 -> {
                Log.i("ALTITUDE", "Switch to A2 (Position+Trend)")
                altitudeControllerA2
            }
        }

        // Aktualisiere altitudeControlVersion Variable
        altitudeControlVersion = newVersion

        // Alle Altitude Controller resetten
        resetAllAltitudeControllers()

        Toast.makeText(
            this,
            "Altitude Controller switched to: ${getAltitudeVersionDisplayName(newVersion)}",
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * Gibt den Display-Namen für eine Version zurück
     */
    internal fun MainActivity.getVersionDisplayName(version: SpeedControlVersion): String {
        return when (version) {
            SpeedControlVersion.ORIGINAL -> "V0 - Original"
            SpeedControlVersion.TREND -> "V1 - Trend"
            SpeedControlVersion.OPTIMAL -> "V2 - Optimal"
        }
    }

    /**
     * Display Name für Altitude Controller Versionen
     */
    internal fun MainActivity.getAltitudeVersionDisplayName(version: AltitudeControlVersion): String {
        return when (version) {
            AltitudeControlVersion.A0 -> "A0 - Baseline"
            AltitudeControlVersion.A1 -> "A1 - Trend"
            AltitudeControlVersion.A2 -> "A2 - Position+Trend"
        }
    }

    /**
     * Gibt die aktuelle Version als String zurück (für CSV-Logging)
     */
    internal fun MainActivity.getCurrentVersionString(): String {
        return when {
            speedController === speedControllerV0 -> "V0"
            speedController === speedControllerV1 -> "V1"
            speedController === speedControllerV2 -> "V2"
            else -> {
                Log.e("SPEED", "Unknown SpeedController! speedController=$speedController")
                "UNKNOWN"
            }
        }
    }

    /**
     * Gibt die aktuelle Altitude Version als String zurück (für CSV-Logging)
     */
    internal fun MainActivity.getCurrentAltitudeVersionString(): String {
        return when {
            altitudeController === altitudeControllerA0 -> "A0"
            altitudeController === altitudeControllerA1 -> "A1"
            altitudeController === altitudeControllerA2 -> "A2"
            else -> {
                Log.e("ALTITUDE", "Unknown AltitudeController!")
                "UNKNOWN"
            }
        }
    }

    /**
     * Setzt alle Altitude Controller zurück
     */
    internal fun MainActivity.resetAllAltitudeControllers() {
        altitudeControllerA0.reset()
        altitudeControllerA1.reset()
        altitudeControllerA2.reset()
        Log.i("ALTITUDE", "All Altitude Controller reset")
    }
