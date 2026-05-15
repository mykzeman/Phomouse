package osa.phomouse

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
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
import android.hardware.input.InputManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import android.view.InputDevice
import androidx.core.app.NotificationCompat
import java.util.UUID
import java.util.concurrent.Executors

class MouseService : Service() {

    private val TAG = "MouseService"
    private val CHANNEL_ID = "MouseServiceChannel"
    private val NOTIFICATION_ID = 1

    private var bluetoothHidDevice: BluetoothHidDevice? = null
    private var connectedHidDevice: BluetoothDevice? = null
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null

    // Tracking variable for BT Serial ports (e.g., if any other legacy serial device is used)
    private val activeSerialConnections = mutableMapOf<String, BluetoothSocket>()
    
    // Persistent tracking of ALL local input devices (Power chair, Joysticks, Mice)
    // This satisfies the requirement to keep track of connected devices in a variable.
    private val connectedLocalInputDevices = mutableListOf<InputDeviceInfo>()

    data class InputDeviceInfo(val id: Int, val name: String, val sources: Int, val descriptor: String)

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): MouseService = this@MouseService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private val HID_SERVICE_UUID = UUID.fromString("00001812-0000-1000-8000-00805f9b34fb")
    private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

    private val MOUSE_REPORT_DESC = byteArrayOf(
        0x05.toByte(), 0x01.toByte(),         // Usage Page (Generic Desktop)
        0x09.toByte(), 0x02.toByte(),         // Usage (Mouse)
        0xa1.toByte(), 0x01.toByte(),         // Collection (Application)
        0x09.toByte(), 0x01.toByte(),         //   Usage (Pointer)
        0xa1.toByte(), 0x00.toByte(),         //   Collection (Physical)
        0x05.toByte(), 0x09.toByte(),         //     Usage Page (Buttons)
        0x19.toByte(), 0x01.toByte(),         //     Usage Minimum (1)
        0x29.toByte(), 0x03.toByte(),         //     Usage Maximum (3)
        0x15.toByte(), 0x00.toByte(),         //     Logical Minimum (0)
        0x25.toByte(), 0x01.toByte(),         //     Logical Maximum (1)
        0x95.toByte(), 0x03.toByte(),         //     Report Count (3)
        0x75.toByte(), 0x01.toByte(),         //     Report Size (1)
        0x81.toByte(), 0x02.toByte(),         //     Input (Data, Variable, Absolute)
        0x95.toByte(), 0x01.toByte(),         //     Report Count (1)
        0x75.toByte(), 0x05.toByte(),         //     Report Size (5)
        0x81.toByte(), 0x03.toByte(),         //     Input (Constant)
        0x05.toByte(), 0x01.toByte(),         //     Usage Page (Generic Desktop)
        0x09.toByte(), 0x30.toByte(),         //     Usage (X)
        0x09.toByte(), 0x31.toByte(),         //     Usage (Y)
        0x15.toByte(), 0x81.toByte(),         //     Logical Minimum (-127)
        0x25.toByte(), 0x7f.toByte(),         //     Logical Maximum (127)
        0x75.toByte(), 0x08.toByte(),         //     Report Size (8)
        0x95.toByte(), 0x02.toByte(),         //     Report Count (2)
        0x81.toByte(), 0x06.toByte(),         //     Input (Data, Variable, Relative)
        0x09.toByte(), 0x38.toByte(),         //     Usage (Wheel)
        0x15.toByte(), 0x81.toByte(),         //     Logical Minimum (-127)
        0x25.toByte(), 0x7f.toByte(),         //     Logical Maximum (127)
        0x75.toByte(), 0x08.toByte(),         //     Report Size (8)
        0x95.toByte(), 0x01.toByte(),         //     Report Count (1)
        0x81.toByte(), 0x06.toByte(),         //     Input (Data, Variable, Relative)
        0xc0.toByte(),                        //   End Collection
        0xc0.toByte()                         // End Collection
    )

    private val hidDeviceCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            super.onAppStatusChanged(pluggedDevice, registered)
            Log.d(TAG, "onAppStatusChanged: registered=$registered")
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            super.onConnectionStateChanged(device, state)
            Log.d(TAG, "HID Connection State: device=$device, state=$state")
            if (state == BluetoothProfile.STATE_CONNECTED) {
                connectedHidDevice = device
                stopAdvertising()
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                connectedHidDevice = null
                startAdvertising() // Automatically restart advertising to allow re-pairing
                // PERSISTENCE: Notice we do NOT close any serial ports or disconnect local inputs here.
            }
        }
    }

    private val inputDeviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateInputDevicesList()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        
        setupBluetooth()
        updateInputDevicesList()
        
        val filter = IntentFilter().apply {
            addAction(InputManager.ACTION_INPUT_DEVICE_ADDED)
            addAction(InputManager.ACTION_INPUT_DEVICE_REMOVED)
            addAction(InputManager.ACTION_INPUT_DEVICE_CHANGED)
        }
        registerReceiver(inputDeviceReceiver, filter)
    }

    private fun setupBluetooth() {
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter = bluetoothManager?.adapter ?: return
        bluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser

        bluetoothAdapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    bluetoothHidDevice = proxy as BluetoothHidDevice
                    registerApp()
                }
            }
            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    bluetoothHidDevice = null
                }
            }
        }, BluetoothProfile.HID_DEVICE)
    }

    private fun registerApp() {
        val sdpSettings = BluetoothHidDeviceAppSdpSettings(
            "Phomouse",
            "Android HID Mouse",
            "Android",
            BluetoothHidDevice.SUBCLASS1_MOUSE,
            MOUSE_REPORT_DESC
        )
        try {
            bluetoothHidDevice?.registerApp(sdpSettings, null, null, Executors.newSingleThreadExecutor(), hidDeviceCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "registerApp failed", e)
        }
    }

    fun sendPublicAdvertise(){
        // Ensure app is registered before advertising
        if (bluetoothHidDevice == null) setupBluetooth()
        startAdvertising()
    }

    fun startAdvertising() {
        val advertiser = bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(HID_SERVICE_UUID))
            .build()

        try {
            advertiser.startAdvertising(settings, data, advertiseCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "startAdvertising failed", e)
        }
    }

    fun stopAdvertising() {
        try {
            bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "stopAdvertising failed", e)
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "Advertising started successfully")
        }
        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising failed: $errorCode")
        }
    }

    fun sendMouseReport(buttons: Byte, x: Byte, y: Byte, wheel: Byte) {
        val device = connectedHidDevice ?: return
        val report = byteArrayOf(buttons, x, y, wheel)
        try {
            bluetoothHidDevice?.sendReport(device, 0, report)
        } catch (e: SecurityException) {
            Log.e(TAG, "sendReport failed", e)
        }
    }

    // Connect logic for legacy BT Serial (SPP) if needed
    fun connectSerialDevice(device: BluetoothDevice) {
        if (activeSerialConnections.containsKey(device.address)) return
        Executors.newSingleThreadExecutor().execute {
            try {
                val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()
                activeSerialConnections[device.address] = socket
                Log.d(TAG, "BT Serial connected: ${device.address}")
            } catch (e: Exception) {
                Log.e(TAG, "BT Serial connection failed", e)
            }
        }
    }

    private fun updateInputDevicesList() {
        val im = getSystemService(Context.INPUT_SERVICE) as InputManager
        val devices = im.inputDeviceIds.mapNotNull { id ->
            im.getInputDevice(id)?.let { InputDeviceInfo(id, it.name, it.sources, it.descriptor) }
        }
        connectedLocalInputDevices.clear()
        connectedLocalInputDevices.addAll(devices)
        Log.d(TAG, "Phone's local input devices updated. Count: ${devices.size}")
    }

    fun getConnectedLocalInputDevices(): List<InputDeviceInfo> = connectedLocalInputDevices

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(CHANNEL_ID, "Phomouse Background Service", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Phomouse Bridge Active")
            .setContentText("Your phone is bridging input to the PC.")
            .setSmallIcon(R.drawable.ic_logo)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        unregisterReceiver(inputDeviceReceiver)
        stopAdvertising()
        try { bluetoothHidDevice?.unregisterApp() } catch (e: Exception) {}
        // Cleanup serial ports only when the service is fully stopped
        activeSerialConnections.values.forEach { try { it.close() } catch (e: Exception) {} }
        activeSerialConnections.clear()
        super.onDestroy()
    }
}
