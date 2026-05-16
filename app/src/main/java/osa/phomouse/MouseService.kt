package osa.phomouse

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.input.InputManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MouseService : Service() {

    private val tag = "MouseService"
    private val channelId = "MouseServiceChannel"
    private val notificationId = 1

    private var bluetoothHidDevice: BluetoothHidDevice? = null
    private var connectedHidDevice: BluetoothDevice? = null
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var isHidAppRegistered = false

    private lateinit var servicePrefs: SharedPreferences
    private val desiredSerialAddresses = ConcurrentHashMap.newKeySet<String>()
    private val activeSerialConnections = ConcurrentHashMap<String, BluetoothSocket>()
    private val connectingAddresses = ConcurrentHashMap.newKeySet<String>()
    
    private val connectionExecutor = Executors.newCachedThreadPool()
    private val connectedLocalInputDevices = mutableListOf<InputDeviceInfo>()
    
    private var wakeLock: PowerManager.WakeLock? = null

    data class InputDeviceInfo(val id: Int, val name: String, val sources: Int, val descriptor: String)

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): MouseService = this@MouseService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private val hidServiceUuid = UUID.fromString("00001812-0000-1000-8000-00805f9b34fb")
    private val sppUuid = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

    private val mouseReportDesc = byteArrayOf(
        0x05.toByte(), 0x01.toByte(), 0x09.toByte(), 0x02.toByte(), 0xa1.toByte(), 0x01.toByte(),
        0x09.toByte(), 0x01.toByte(), 0xa1.toByte(), 0x00.toByte(), 0x05.toByte(), 0x09.toByte(),
        0x19.toByte(), 0x01.toByte(), 0x29.toByte(), 0x03.toByte(), 0x15.toByte(), 0x00.toByte(),
        0x25.toByte(), 0x01.toByte(), 0x95.toByte(), 0x03.toByte(), 0x75.toByte(), 0x01.toByte(),
        0x81.toByte(), 0x02.toByte(), 0x95.toByte(), 0x01.toByte(), 0x75.toByte(), 0x05.toByte(),
        0x81.toByte(), 0x03.toByte(), 0x05.toByte(), 0x01.toByte(), 0x09.toByte(), 0x30.toByte(),
        0x09.toByte(), 0x31.toByte(), 0x15.toByte(), 0x81.toByte(), 0x25.toByte(), 0x7f.toByte(),
        0x75.toByte(), 0x08.toByte(), 0x95.toByte(), 0x02.toByte(), 0x81.toByte(), 0x06.toByte(),
        0x09.toByte(), 0x38.toByte(), 0x15.toByte(), 0x81.toByte(), 0x25.toByte(), 0x7f.toByte(),
        0x75.toByte(), 0x08.toByte(), 0x95.toByte(), 0x01.toByte(), 0x81.toByte(), 0x06.toByte(),
        0xc0.toByte(), 0xc0.toByte()
    )

    private val hidDeviceCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(registered: Boolean) {
            super.onAppStatusChanged(registered)
            isHidAppRegistered = registered
            Log.d(tag, "HID App registration status: $registered")
            if (registered) {
                // Now safe to initiate SPP connections
                reconnectAllSerial()
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            super.onConnectionStateChanged(device, state)
            Log.d(tag, "HID Connection state changed: $state")
            if (state == BluetoothProfile.STATE_CONNECTED) {
                connectedHidDevice = device
                stopAdvertising()
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                connectedHidDevice = null
                startAdvertising()
            }
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return

            if (intent.action == BluetoothDevice.ACTION_ACL_DISCONNECTED) {
                if (desiredSerialAddresses.contains(device.address)) {
                    Log.d(tag, "Local serial device ${device.address} lost. Scheduling reconnect...")
                    activeSerialConnections.remove(device.address)?.let {
                        try { it.close() } catch (e: Exception) {}
                    }
                    performSerialConnect(device)
                }
            }
        }
    }

    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = updateInputDevicesList()
        override fun onInputDeviceRemoved(deviceId: Int) = updateInputDevicesList()
        override fun onInputDeviceChanged(deviceId: Int) = updateInputDevicesList()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(tag, "Service Creating...")
        createNotificationChannel()
        startForeground(notificationId, createNotification())
        
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Phomouse:ServiceWakeLock")
        wakeLock?.acquire()

        servicePrefs = getSharedPreferences("MouseServicePrefs", MODE_PRIVATE)
        val saved = servicePrefs.getStringSet("desired_serial_addresses", emptySet())
        desiredSerialAddresses.addAll(saved ?: emptySet())
        
        setupBluetooth()
        updateInputDevicesList()
        // reconnectAllSerial() - Moved to onAppStatusChanged(true)
        
        getSystemService(InputManager::class.java)?.registerInputDeviceListener(inputDeviceListener, null)
        val filter = IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        registerReceiver(bluetoothReceiver, filter)
    }

    private fun setupBluetooth() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter ?: return
        bluetoothLeAdvertiser = adapter.bluetoothLeAdvertiser
        adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    bluetoothHidDevice = proxy as BluetoothHidDevice
                    registerApp()
                }
            }
            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    bluetoothHidDevice = null
                    isHidAppRegistered = false
                }
            }
        }, BluetoothProfile.HID_DEVICE)
    }

    private fun registerApp() {
        val settings = BluetoothHidDeviceAppSdpSettings("Phomouse", "HID Mouse", "Android",
            BluetoothHidDevice.SUBCLASS1_MOUSE, mouseReportDesc)
        try {
            bluetoothHidDevice?.registerApp(settings, null, null, Executors.newSingleThreadExecutor(), hidDeviceCallback)
        } catch (e: SecurityException) { Log.e(tag, "HID register failed", e) }
    }

    fun sendPublicAdvertise() {
        if (bluetoothHidDevice == null) setupBluetooth()
        startAdvertising()
    }

    fun startAdvertising() {
        val advertiser = bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        val data = AdvertiseData.Builder().setIncludeDeviceName(true).addServiceUuid(ParcelUuid(hidServiceUuid)).build()
        try { advertiser.startAdvertising(settings, data, advertiseCallback) } catch (e: SecurityException) {}
    }

    fun stopAdvertising() {
        try { bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) } catch (e: SecurityException) {}
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) { Log.d(tag, "ADV success") }
        override fun onStartFailure(errorCode: Int) { Log.e(tag, "ADV fail: $errorCode") }
    }

    fun sendMouseReport(buttons: Byte, dx: Byte, dy: Byte, wheel: Byte) {
        val device = connectedHidDevice ?: return
        try { bluetoothHidDevice?.sendReport(device, 0, byteArrayOf(buttons, dx, dy, wheel)) } catch (e: SecurityException) {}
    }

    fun connectSerialDevice(device: BluetoothDevice) {
        if (!desiredSerialAddresses.contains(device.address)) {
            desiredSerialAddresses.add(device.address)
            saveDesiredAddresses()
        }
        if (isHidAppRegistered) {
            performSerialConnect(device)
        } else {
            Log.w(tag, "Delaying SPP connect to ${device.address} until HID app is registered.")
        }
    }

    fun forgetSerialDevice(address: String) {
        desiredSerialAddresses.remove(address)
        saveDesiredAddresses()
        activeSerialConnections.remove(address)?.let {
            try { it.close() } catch (e: Exception) {}
        }
    }

    private fun saveDesiredAddresses() {
        servicePrefs.edit().putStringSet("desired_serial_addresses", desiredSerialAddresses.toSet()).apply()
    }

    private fun reconnectAllSerial() {
        if (!isHidAppRegistered) return
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return
        desiredSerialAddresses.forEach { address ->
            try {
                val device = adapter.getRemoteDevice(address)
                performSerialConnect(device)
            } catch (e: Exception) { Log.e(tag, "Reconnect failed for $address", e) }
        }
    }

    private fun performSerialConnect(device: BluetoothDevice) {
        if (activeSerialConnections.containsKey(device.address) || connectingAddresses.contains(device.address)) {
            Log.d(tag, "Connection already active or in progress for ${device.address}")
            return
        }
        connectingAddresses.add(device.address)
        connectionExecutor.execute {
            var backoffMs = 3000L // Start with 3 seconds as per IMPROVEMENTS.md
            try {
                while (desiredSerialAddresses.contains(device.address) && !activeSerialConnections.containsKey(device.address)) {
                    var socket: BluetoothSocket? = null
                    try {
                        Log.d(tag, "Attempting serial connect to ${device.address}...")
                        socket = device.createRfcommSocketToServiceRecord(sppUuid)
                        socket.connect()
                        activeSerialConnections[device.address] = socket
                        Log.d(tag, "Connected to serial device: ${device.address}")
                        startSerialReader(device.address, socket)
                        break
                    } catch (e: Exception) {
                        Log.e(tag, "Serial connect fail for ${device.address}: ${e.message}")
                        socket?.let { try { it.close() } catch (ex: Exception) {} }
                        try { 
                            TimeUnit.MILLISECONDS.sleep(backoffMs)
                            // Exponential backoff
                            backoffMs = when(backoffMs) {
                                3000L -> 5000L
                                5000L -> 10000L
                                else -> (backoffMs * 1.5).toLong().coerceAtMost(60000L)
                            }
                        } catch (ie: InterruptedException) { break }
                    }
                }
            } finally {
                connectingAddresses.remove(device.address)
            }
        }
    }

    private fun startSerialReader(address: String, socket: BluetoothSocket) {
        connectionExecutor.execute {
            try {
                val inputStream = socket.inputStream
                val buffer = ByteArray(1024)
                while (activeSerialConnections.containsKey(address)) {
                    val read = inputStream.read(buffer)
                    if (read == -1) {
                        Log.d(tag, "Serial stream EOF for $address")
                        break
                    }
                }
            } catch (e: IOException) {
                Log.e(tag, "Serial read IOException for $address: ${e.message}")
            } finally {
                activeSerialConnections.remove(address)?.let {
                    try { it.close() } catch (ex: Exception) {}
                }
                if (desiredSerialAddresses.contains(address)) {
                    Log.d(tag, "Re-initiating connection for $address")
                    val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                    adapter?.getRemoteDevice(address)?.let { performSerialConnect(it) }
                }
            }
        }
    }

    private fun updateInputDevicesList() {
        val im = getSystemService(InputManager::class.java) ?: return
        val devices = im.inputDeviceIds.toList().mapNotNull { id ->
            im.getInputDevice(id)?.let { InputDeviceInfo(id, it.name, it.sources, it.descriptor) }
        }
        connectedLocalInputDevices.clear()
        connectedLocalInputDevices.addAll(devices)
    }

    fun getConnectedLocalInputDevices(): List<InputDeviceInfo> = connectedLocalInputDevices

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, "Phomouse Background Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(chan)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Phomouse Bridge Active")
            .setContentText("Bridging input to PC.")
            .setSmallIcon(R.drawable.ic_logo)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        Log.d(tag, "Service Destroying...")
        wakeLock?.let { if (it.isHeld) it.release() }
        getSystemService(InputManager::class.java)?.unregisterInputDeviceListener(inputDeviceListener)
        try { unregisterReceiver(bluetoothReceiver) } catch (e: Exception) {}
        stopAdvertising()
        try { bluetoothHidDevice?.unregisterApp() } catch (e: Exception) {}
        activeSerialConnections.values.forEach { try { it.close() } catch (e: Exception) {} }
        activeSerialConnections.clear()
        connectionExecutor.shutdownNow()
        super.onDestroy()
    }
}
