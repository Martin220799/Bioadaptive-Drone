package com.parrot.hellodrone.bio

import android.annotation.SuppressLint
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.core.app.ActivityCompat
import java.util.UUID

@SuppressLint("MissingPermission")
class PolarH10BleClient(
    private val context: Context,
    private val onHrData: (hr: Int, rrIntervals: List<Int>, timestampMs: Long) -> Unit,
    private val onStatus: (String) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    // Serialize HR callbacks onto a dedicated background thread so that:
    //  - stateful processing in MainActivity stays deterministic
    //  - BLE binder threads are not blocked by heavy downstream work
    private val hrThread = HandlerThread("PolarH10-HR").apply { start() }
    private val hrHandler = Handler(hrThread.looper)

    private val stateLock = Any()
    private var isScanning = false
    private var isConnected = false
    private var isConnecting = false
    @Volatile
    private var manualDisconnect = false

    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5

    private var gatt: BluetoothGatt? = null

    private val HEART_RATE_SERVICE = UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb")
    private val HEART_RATE_MEASUREMENT = UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb")
    private val CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return

            // Android 12+: Lesen von device.name erfordert BLUETOOTH_CONNECT.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    onStatus("BLUETOOTH_CONNECT Permission fehlt (device.name).")
                    return
                }
            }

            val name = device.name ?: return

            if (!name.contains("Polar H10", ignoreCase = true)) return

            // Guard against duplicate connectGatt() calls (can happen if multiple scan results are
            // delivered back-to-back or startScan() is triggered repeatedly while connecting).
            val shouldConnect = synchronized(stateLock) {
                if (isConnected || isConnecting || gatt != null) {
                    false
                } else {
                    isConnecting = true
                    manualDisconnect = false
                    true
                }
            }
            if (!shouldConnect) return

            stopScan()
            onStatus("Polar H10 gefunden: $name - Verbinde …")
            connect(device)
        }

        override fun onScanFailed(errorCode: Int) {
            synchronized(stateLock) { isScanning = false }
            onStatus("Scan fehlgeschlagen: $errorCode")
        }
    }

    fun startScan() {
        val statusMsg: String? = synchronized(stateLock) {
            when {
                isConnected -> "Bereits mit Polar H10 verbunden."
                isConnecting || gatt != null -> "Verbindung wird bereits aufgebaut …"
                isScanning -> "Scan läuft bereits …"
                else -> null
            }
        }
        if (statusMsg != null) {
            onStatus(statusMsg)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                onStatus("BLE Scan-Permission fehlt (BLUETOOTH_SCAN).")
                return
            }
        } else {
            // Android 6-11: BLE-Scan erfordert Location-Permission
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                onStatus("BLE Scan-Permission fehlt (ACCESS_FINE_LOCATION).")
                return
            }
        }

        val adapter = BluetoothAdapter.getDefaultAdapter()
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            // Kein BLE-Adapter/Scanner (z. B. Emulator ohne Bluetooth)
            onStatus("BLE nicht verfügbar (kein Bluetooth-Scanner).")
            return
        }

        synchronized(stateLock) {
            isScanning = true
            reconnectAttempts = 0
            manualDisconnect = false
        }
        onStatus("Starte Scan nach Polar H10 …")
        scanner.startScan(scanCallback)
    }

    private fun stopScan() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val scanner = adapter?.bluetoothLeScanner ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return
        } else {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        }

        synchronized(stateLock) {
            if (!isScanning) return
            isScanning = false
        }
        scanner.stopScan(scanCallback)
    }

    private fun connect(device: android.bluetooth.BluetoothDevice) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                synchronized(stateLock) { isConnecting = false }
                onStatus("BLUETOOTH_CONNECT Permission fehlt.")
                return
            }
        }

        // Close any previous GATT explicitly before creating a new one.
        val previous: BluetoothGatt? = synchronized(stateLock) {
            val prev = gatt
            gatt = null
            prev
        }
        previous?.let { runCatching { it.close() } }

        val newGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }

        synchronized(stateLock) {
            gatt = newGatt
            isConnecting = true
        }
    }

    fun disconnect() {
        manualDisconnect = true
        stopScan()

        val localGatt: BluetoothGatt? = synchronized(stateLock) {
            isConnected = false
            isConnecting = false
            val tmp = gatt
            gatt = null
            tmp
        }

        try {
            localGatt?.disconnect()
        } catch (_: Exception) { /* ignore */ }

        try {
            localGatt?.close()
        } catch (_: Exception) { /* ignore */ }

        // Shut down HR thread - the client is typically destroyed together with the Activity.
        runCatching { hrThread.quitSafely() }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                synchronized(stateLock) {
                    isConnected = true
                    isConnecting = false
                    reconnectAttempts = 0
                }
                onStatus("Polar H10 verbunden.")
                runCatching { g.discoverServices() }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val shouldReconnect: Boolean = synchronized(stateLock) {
                    isConnected = false
                    isConnecting = false
                    val allow = !manualDisconnect && reconnectAttempts < maxReconnectAttempts
                    allow
                }

                onStatus("Verbindung verloren.")

                runCatching { g.close() }
                synchronized(stateLock) {
                    if (gatt === g) gatt = null
                }

                if (shouldReconnect) {
                    reconnectAttempts++
                    val attempt = reconnectAttempts
                    onStatus("Reconnect Versuch $attempt / $maxReconnectAttempts …")
                    mainHandler.postDelayed({ startScan() }, 2000)
                } else if (!manualDisconnect) {
                    onStatus("Reconnect fehlgeschlagen.")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return

            val hrService = g.getService(HEART_RATE_SERVICE) ?: return
            val hrChar = hrService.getCharacteristic(HEART_RATE_MEASUREMENT) ?: return

            enableNotifications(g, hrChar)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == HEART_RATE_MEASUREMENT) {
                val bytes = characteristic.value ?: return
                val ts = System.currentTimeMillis()
                parseHrMeasurement(bytes, ts)
            }
        }
    }

    private fun enableNotifications(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        runCatching { g.setCharacteristicNotification(characteristic, true) }

        val desc = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG) ?: return
        desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        }
        runCatching { g.writeDescriptor(desc) }
    }

    private fun parseHrMeasurement(data: ByteArray, timestampMs: Long) {
        if (data.isEmpty()) return

        val flags = data[0].toInt()
        val hrUint16 = (flags and 0x01) != 0

        var index = 1
        val hr: Int = if (hrUint16) {
            if (data.size < index + 2) return
            ((data[index].toInt() and 0xFF) or ((data[index + 1].toInt() and 0xFF) shl 8)).also { index += 2 }
        } else {
            if (data.size < index + 1) return
            (data[index].toInt() and 0xFF).also { index += 1 }
        }

        val rrList = mutableListOf<Int>()
        val rrPresent = (flags and 0x10) != 0
        if (rrPresent) {
            while (index + 1 < data.size) {
                val rr = (data[index].toInt() and 0xFF) or ((data[index + 1].toInt() and 0xFF) shl 8)
                rrList.add(rr)
                index += 2
            }
        }

        // Ensure deterministic single-thread delivery of HR samples.
        hrHandler.post {
            onHrData(hr, rrList, timestampMs)
        }
    }
}
