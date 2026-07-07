package com.parrot.hellodrone.main

import android.util.Log
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date
import com.parrot.hellodrone.bio.Zone
import com.parrot.hellodrone.bio.QcState
import com.parrot.hellodrone.config.PrototypeType
import com.parrot.hellodrone.config.PrototypeConfig
import com.parrot.hellodrone.sim.SimulationClient
import com.parrot.hellodrone.sim.SimulationMessage
import com.parrot.hellodrone.sim.Thresholds
import com.parrot.hellodrone.MainActivity
import com.parrot.hellodrone.R
import com.parrot.hellodrone.SpeedControlVersion
import com.parrot.hellodrone.SIM_DEFAULT_IP
import com.parrot.hellodrone.SIM_PORT
import android.widget.*

    internal fun MainActivity.setupSimulation() {
        // Default IP setzen
        if (editTextSimIp.text.isNullOrBlank()) {
            editTextSimIp.setText(SIM_DEFAULT_IP)
        }

        // SimulationClient mit Callbacks initialisieren
        simulationClient = SimulationClient(
            onConnectionStatus = { connected, message ->
                runOnUiThread {
                    textViewSimStatus.text = message
                    textViewSimStatus.setTextColor(
                        if (connected) getColor(android.R.color.holo_green_light)
                        else getColor(android.R.color.holo_red_light)
                    )

                    buttonSimConnect.text = if (connected)
                        getString(R.string.sim_disconnect)
                    else getString(R.string.sim_connect)

                    // NUR messageSeq resetten, NICHT sessionId!
                    if (connected) {
                        messageSeq.set(0L)
                        lastSimSentAtMs = 0L
                        simPrototypeMismatchWarned = false
                        Log.i("SIMULATION", "Connected - messageSeq reset, sessionId=${csvLogger.getCurrentSessionId()}")
                    }
                }
            },
            onError = { error ->
                runOnUiThread {
                    Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                    textViewSimStatus.text = error
                    textViewSimStatus.setTextColor(getColor(android.R.color.holo_red_light))
                }
            }
        )

        // Initial UI State basierend auf aktuellem Prototyp
        val isSimPrototype = (PrototypeConfig.currentPrototype == PrototypeType.SIMULATION)
        updateSimulationUIState(isSimPrototype)

        // Connect Button
        buttonSimConnect.setOnClickListener {
            if (simulationClient.isConnected()) {
                simulationClient.disconnect()
                return@setOnClickListener
            }

            // Prüfe ob Simulation-Prototype aktiv ist
            if (PrototypeConfig.currentPrototype != PrototypeType.SIMULATION) {
                Toast.makeText(this, "Select 'Simulation' prototype first!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val ip = editTextSimIp.text.toString().trim()
            if (ip.isEmpty()) {
                Toast.makeText(this, "Enter IP address!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val url = "ws://$ip:$SIM_PORT"
            textViewSimStatus.text = "Connecting to $ip..."
            textViewSimStatus.setTextColor(getColor(android.R.color.holo_blue_light))

            simulationClient.connect(url)
        }
    }

    internal fun MainActivity.updateSimulationUIState(enabled: Boolean) {
        editTextSimIp.isEnabled = enabled
        buttonSimConnect.isEnabled = enabled

        textViewSimStatus.text = when {
            !enabled -> "Select 'Simulation' above to enable"
            simulationClient.isConnected() -> "Connected"
            else -> "Ready to connect"
        }

        textViewSimStatus.setTextColor(when {
            !enabled -> getColor(android.R.color.darker_gray)
            simulationClient.isConnected() -> getColor(android.R.color.holo_green_light)
            else -> getColor(android.R.color.holo_blue_light)
        })
    }

    /**
     * Sendet biometrische Daten an Desktop-Simulation (Prototype 1).
     *
     * WICHTIG: Wird NUR aufgerufen wenn:
     * - WebSocket verbunden ist
     * - Optional: currentPrototype == SIMULATION
     */
    internal fun MainActivity.sendToSimulationIfEnabled(
        hrRaw: Int,
        hrSmooth: Int,
        zone: Zone,
        vSetCmd: Float,
        qcState: QcState,
        connectionStable: Boolean,
        segmentId: Int
    ) {
        // Guard 1: Connection Check
        if (!simulationClient.isConnected()) {
            return  // Silent fail - SimulationClient loggt bereits Warning
        }

        // Guard 2: Prototype Check (optional, aber empfohlen für Sicherheit)
        if (PrototypeConfig.currentPrototype != PrototypeType.SIMULATION) {
            // Warnung nur EINMAL pro Verbindung/State, sonst Log-Spam bei 5 Hz
            if (!simPrototypeMismatchWarned) {
                Log.w("SIMULATION", "Client connected but not in SIMULATION prototype mode!")
                simPrototypeMismatchWarned = true
            }
            return
        } else {
            // Wenn wieder korrekt im SIMULATION-Prototyp -> Flag zurücksetzen
            simPrototypeMismatchWarned = false
        }

        // Thresholds vom ZoneManager holen
        val thYellow = zoneManager.getThresholdYellow() ?: 0
        val thRed = zoneManager.getThresholdRed() ?: 0

        // SessionID aus CsvLogger synchronisieren (KRITISCH für wissenschaftliche Zuordnung!)
        val currentSessionId = csvLogger.getCurrentSessionId()
        if (sessionId != currentSessionId) {
            sessionId = currentSessionId
            Log.i("SIMULATION", "SessionID synchronized: $sessionId")
        }

        // Timestamp (ISO 8601 format, API 24 kompatibel)
        // Format: "2025-01-10T14:23:45.123+0100"
        val timestampFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
        val timestamp = timestampFormat.format(Date())

        // Speed Controller Version String
        val versionStr = when (speedControlVersion) {
            SpeedControlVersion.ORIGINAL -> "V0"
            SpeedControlVersion.TREND -> "V1"
            SpeedControlVersion.OPTIMAL -> "V2"
        }

        // Message konstruieren
        val msg = SimulationMessage(
            timestamp = timestamp,
            seq = messageSeq.getAndIncrement(),  // Post-increment für nächste Message
            session_id = sessionId,
            hr_raw = hrRaw,
            hr_smooth = hrSmooth,
            zone = zone.name.lowercase(Locale.ROOT),  // Konsistent mit CSV
            v_set = vSetCmd.toDouble(),
            speedCtrVersion = versionStr,
            thresholds = Thresholds(
                green_yellow = thYellow,
                yellow_red = thRed
            ),
            segment_id = segmentId,
            connection_stable = connectionStable,
            qc_state = qcState.name  // "OK", "DEGRADED", "LOST"
        )

        // Senden via WebSocket
        simulationClient.sendData(msg)
    }
