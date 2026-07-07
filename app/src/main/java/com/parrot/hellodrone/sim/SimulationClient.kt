package com.parrot.hellodrone.sim

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * WebSocket client for sending biometric data to Python simulation server
 * Handles connection management, auto-reconnect, and JSON serialization
 *
 * FIXES:
 * - Reconnect attempts reset after successful connection duration
 * - Error callback on disconnect for user feedback
 * - Better logging for debugging
 */
class SimulationClient(
    private val onConnectionStatus: (connected: Boolean, message: String) -> Unit,
    private val onError: (error: String) -> Unit
) {

    companion object {
        private const val TAG = "SimulationClient"
        private const val RECONNECT_DELAY_MS = 2000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_ATTEMPT_RESET_MS = 60_000L  // Reset after 60s successful connection

        // Rate-limit for repetitive logs when not connected
        private const val NOT_CONNECTED_LOG_INTERVAL_MS = 5000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var webSocket: WebSocket? = null
    private var serverUrl: String = ""

    // Connection state: webSocket != null is NOT enough.
    @Volatile private var isOpen: Boolean = false

    private var isIntentionalDisconnect = false
    private var reconnectAttempts = 0
    private var lastSuccessfulConnectionMs = 0L


    // Last successful send timestamp (helps diagnose "connected but stale" states)
    private var lastSendOkMs: Long = 0L

    // Prevent multiple reconnect schedules at the same time
    private var reconnectRunnable: Runnable? = null

    private var lastNotConnectedLogMs: Long = 0L

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)  // WebSocket: no read timeout (pings handle liveness)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)  // Client-side keepalive
        .build()

    private val webSocketListener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "✓ WebSocket connected: ${response.message}")
            this@SimulationClient.webSocket = webSocket
            isOpen = true
            reconnectAttempts = 0
            lastSuccessfulConnectionMs = System.currentTimeMillis()
            lastSendOkMs = lastSuccessfulConnectionMs
            cancelReconnect()
            onConnectionStatus(true, "Connected to simulation")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "Received message: $text")
            // Optional future feature: server commands
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            Log.d(TAG, "Received bytes: ${bytes.hex()}")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closing: $code / $reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.w(TAG, "✗ WebSocket closed: $code / $reason")
            isOpen = false
            this@SimulationClient.webSocket = null
            onConnectionStatus(false, "Disconnected")

            // Notify user via error callback
            if (!isIntentionalDisconnect) {
                onError("Simulation disconnected (code $code)")
            }

            scheduleReconnectIfNeeded("closed")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "✗ WebSocket failure: ${t.message}", t)
            isOpen = false
            this@SimulationClient.webSocket = null
            onConnectionStatus(false, "Connection failed")
            onError("Connection error: ${t.message}")
            scheduleReconnectIfNeeded("failure")
        }
    }

    /**
     * Connect to simulation server
     * @param url WebSocket URL (e.g., "ws://192.168.1.100:8765")
     */
    fun connect(url: String) {
        // If we are already open, do nothing
        if (isOpen) {
            Log.d(TAG, "Already connected (open).")
            return
        }

        // If a socket object exists but isn't open (stale), clear it
        if (webSocket != null && !isOpen) {
            Log.w(TAG, "Found stale WebSocket reference. Clearing.")
            webSocket = null
        }

        this.serverUrl = url
        this.isIntentionalDisconnect = false
        cancelReconnect()
        Log.d(TAG, "Connecting to: $url")

        val request = Request.Builder()
            .url(url)
            .build()

        // This will trigger onOpen/onFailure
        webSocket = client.newWebSocket(request, webSocketListener)
    }

    /**
     * Disconnect from simulation server
     */
    fun disconnect() {
        Log.d(TAG, "Disconnecting (intentional)...")
        isIntentionalDisconnect = true
        cancelReconnect()
        isOpen = false
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        onConnectionStatus(false, "Disconnected (intentional)")
    }

    /**
     * Send biometric data to simulation
     */
    fun sendData(data: SimulationMessage) {
        if (!isOpen || webSocket == null) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastNotConnectedLogMs > NOT_CONNECTED_LOG_INTERVAL_MS) {
                lastNotConnectedLogMs = now
                Log.w(TAG, "WebSocket not connected. Dropping outgoing data.")
                // Notify via error callback for user feedback
                onError("Simulation disconnected - data not sent")
            }
            return
        }

        try {
            val json = buildJsonMessage(data)
            val ws = webSocket
            val success = ws?.send(json) ?: false
            if (!success) {
                Log.e(TAG, "Failed to send message (buffer full / stale socket). Forcing reconnect.")
                onError("Failed to send data to simulation (stale socket)")
                forceReconnect("send_failed")
                return
            }
            lastSendOkMs = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending data: ${e.message}", e)
            onError("Send error: ${e.message}")
            forceReconnect("send_exception")
        }
    }

    /**
     * Build JSON message from biometric data.
     * Normalizations:
     *  - zone -> lowercase
     *  - timestamp -> pass-through but if missing/blank, keep as empty
     */
    private fun buildJsonMessage(data: SimulationMessage): String {
        val zoneNorm = data.zone.trim().lowercase(Locale.US)
        val ts = data.timestamp.trim()

        val json = JSONObject().apply {
            put("timestamp", ts)
            put("seq", data.seq)
            put("session_id", data.session_id)
            put("hr_raw", data.hr_raw)
            put("hr_smooth", data.hr_smooth)
            put("zone", zoneNorm)
            put("v_set", data.v_set)
            put("speedCtrVersion", data.speedCtrVersion)
            put("thresholds", JSONObject().apply {
                put("green_yellow", data.thresholds.green_yellow)
                put("yellow_red", data.thresholds.yellow_red)
            })
            put("segment_id", data.segment_id)
            put("connection_stable", data.connection_stable)
            put("qc_state", data.qc_state)
        }

        return json.toString()
    }

    /**
     * Check if currently connected (socket open).
     */
    fun isConnected(): Boolean = isOpen

    /**
     * Cleanup resources (call from Activity.onDestroy).
     * Note: After cleanup, this instance should not reconnect again.
     */
    fun cleanup() {
        disconnect()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    // -------------------------
    // Reconnect helpers
    // -------------------------

    private fun forceReconnect(reason: String) {
        if (isIntentionalDisconnect) return
        if (serverUrl.isBlank()) return

        Log.w(TAG, "Forcing reconnect due to: $reason")
        isOpen = false

        try {
            webSocket?.cancel()
        } catch (_: Exception) {
            // ignore
        }

        webSocket = null
        onConnectionStatus(false, "Disconnected ($reason) - reconnecting...")

        cancelReconnect()
        scheduleReconnectIfNeeded(reason)
    }

    private fun scheduleReconnectIfNeeded(reason: String) {
        if (isIntentionalDisconnect) return
        if (serverUrl.isBlank()) return

        // Reset attempts if last connection was >60s ago
        if (System.currentTimeMillis() - lastSuccessfulConnectionMs > RECONNECT_ATTEMPT_RESET_MS) {
            Log.d(TAG, "Resetting reconnect attempts (last success >60s ago)")
            reconnectAttempts = 0
        }

        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            val errorMsg = "Max reconnect attempts reached. Please reconnect manually."
            Log.e(TAG, errorMsg)
            onError(errorMsg)
            return
        }

        if (reconnectRunnable != null) {
            // already scheduled
            return
        }

        reconnectAttempts++
        Log.d(TAG, "Scheduling reconnect ($reconnectAttempts/$MAX_RECONNECT_ATTEMPTS) after $reason...")

        val runnable = Runnable {
            reconnectRunnable = null
            connect(serverUrl)
        }
        reconnectRunnable = runnable
        mainHandler.postDelayed(runnable, RECONNECT_DELAY_MS)
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
    }
}

/**
 * Data class for simulation messages
 */
data class SimulationMessage(
    val timestamp: String,
    val seq: Long,
    val session_id: String,
    val hr_raw: Int,
    val hr_smooth: Int,
    val zone: String,  // "green", "yellow", "red" (will be normalized)
    val v_set: Double,
    val speedCtrVersion: String,  // "V0", "V1", "V2"
    val thresholds: Thresholds,
    val segment_id: Int,
    val connection_stable: Boolean,
    val qc_state: String  // "OK", "LOST", etc.
)

data class Thresholds(
    val green_yellow: Int,
    val yellow_red: Int
)