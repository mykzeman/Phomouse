package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val deviceName: String, val address: String, val isMock: Boolean = false) : ConnectionState()
}

object BluetoothConnectionManager {
    val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val commandLogFlow = MutableSharedFlow<String>(extraBufferCapacity = 50)
    
    var activeSocket: BluetoothSocket? = null
    var activeDevice: BluetoothDevice? = null
    var isMockConnection = false
    
    // Configurable settings
    var actionSensitivity: Int = 10
    var dwellPeriodMs: Long = 1500L
    var scrollAmount: Int = 3
    var joystickMode: Boolean = false

    // Dwell tracker variables
    private var lastDwellJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var hasMovedSinceLastClick = false

    fun sendCommand(cmd: String) {
        scope.launch {
            commandLogFlow.emit(cmd)
        }
        
        // Protocol format: "PMCMD:[cmd]-[val]"
        val formatted = "$cmd\n"
        
        if (isMockConnection) {
            // Simulated write
            return
        }
        
        val socket = activeSocket
        if (socket != null && socket.isConnected) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val out: OutputStream = socket.outputStream
                    out.write(formatted.toByteArray())
                    out.flush()
                } catch (e: IOException) {
                    // Handle disconnect
                    disconnect()
                }
            }
        }
    }

    // Handles native movement interception tracking to trigger dwell clicks
    fun reportMovement() {
        if (dwellPeriodMs <= 0) return
        
        hasMovedSinceLastClick = true
        
        // Reset dwell timer
        lastDwellJob?.cancel()
        lastDwellJob = scope.launch {
            kotlinx.coroutines.delay(dwellPeriodMs)
            if (hasMovedSinceLastClick) {
                // Execute dwell click instantly
                sendCommand("PMCMD:LB-0")
                hasMovedSinceLastClick = false
            }
        }
    }

    fun disconnect() {
        lastDwellJob?.cancel()
        hasMovedSinceLastClick = false
        try {
            activeSocket?.close()
        } catch (e: Exception) {}
        activeSocket = null
        activeDevice = null
        isMockConnection = false
        connectionState.value = ConnectionState.Disconnected
    }
}

class BluetoothSppService : Service() {

    private val NOTIFICATION_ID = 8812
    private val CHANNEL_ID = "Phomouse_SPP_Channel"
    private var connectScope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val deviceAddress = intent?.getStringExtra("device_address")
        val deviceName = intent?.getStringExtra("device_name") ?: "PC Device"
        val forceMock = intent?.getBooleanExtra("force_mock", false) ?: false

        if (action == "ACTION_CONNECT" && deviceAddress != null) {
            startForeground(NOTIFICATION_ID, buildNotification("Connecting to $deviceName..."))
            connectToDevice(deviceAddress, deviceName, forceMock)
        } else if (action == "ACTION_DISCONNECT") {
            BluetoothConnectionManager.disconnect()
            stopForeground(true)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun connectToDevice(address: String, name: String, forceMock: Boolean) {
        BluetoothConnectionManager.disconnect()
        BluetoothConnectionManager.connectionState.value = ConnectionState.Connecting

        connectScope.launch {
            if (forceMock || address == "MOCK_MAC_ADDRESS") {
                // Initiate mock mode
                kotlinx.coroutines.delay(1000) // fake connecting delay
                BluetoothConnectionManager.isMockConnection = true
                BluetoothConnectionManager.connectionState.value = ConnectionState.Connected(name, address, isMock = true)
                updateNotification("Connected to $name (Mock Mode)")
                showToast("Connected to $name (Mock)")
                return@launch
            }

            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bluetoothManager?.adapter
            if (adapter == null || !adapter.isEnabled) {
                // Bluetooth is not available/enabled - fallback to Mock mode
                kotlinx.coroutines.delay(800)
                BluetoothConnectionManager.isMockConnection = true
                BluetoothConnectionManager.connectionState.value = ConnectionState.Connected(name, address, isMock = true)
                updateNotification("Connected to $name (Mock Mode)")
                showToast("Bluetooth disabled. Fallback to Mock $name")
                return@launch
            }

            try {
                val device = adapter.getRemoteDevice(address)
                // Standard SPP UUID
                val sppUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
                val socket = device.createRfcommSocketToServiceRecord(sppUuid)
                
                // Cancel discovery as it slows down connection
                adapter.cancelDiscovery()
                
                socket.connect()
                
                BluetoothConnectionManager.activeSocket = socket
                BluetoothConnectionManager.activeDevice = device
                BluetoothConnectionManager.isMockConnection = false
                BluetoothConnectionManager.connectionState.value = ConnectionState.Connected(device.name ?: name, address, isMock = false)
                
                updateNotification("Connected in SPP line to ${device.name ?: name}")
                showToast("Connected to ${device.name ?: name}")
            } catch (e: Exception) {
                e.printStackTrace()
                // Auto Fallback to Mock mode to prevent dead-end or crashes in simulation environments
                BluetoothConnectionManager.isMockConnection = true
                BluetoothConnectionManager.connectionState.value = ConnectionState.Connected(name, address, isMock = true)
                updateNotification("Connected to $name (Mock Mode)")
                showToast("Could not open SPP. Connected in simulation.")
            }
        }
    }

    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent: PendingIntent = Intent(this, MainActivity::class.java).let { notificationIntent ->
            PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Phomouse SPP")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Phomouse Bluetooth Connection Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        BluetoothConnectionManager.disconnect()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
