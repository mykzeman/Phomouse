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
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.UUID
import java.util.concurrent.Executors

class MouseService : Service() {

    private val TAG = "MouseService"
    private val CHANNEL_ID = "MouseServiceChannel"
    private val NOTIFICATION_ID = 1

    private var bluetoothHidDevice: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): MouseService = this@MouseService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private val HID_SERVICE_UUID = UUID.fromString("00001812-0000-1000-8000-00805f9b34fb")

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
            Log.d(TAG, "onConnectionStateChanged: device=$device, state=$state")
            if (state == BluetoothProfile.STATE_CONNECTED) {
                connectedDevice = device
                stopAdvertising()
            }
        }
    }
    fun sendPublicAdvertise(){
        setupBluetooth()
        startAdvertising()

    }
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

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
        bluetoothHidDevice?.registerApp(sdpSettings, null, null, Executors.newSingleThreadExecutor(), hidDeviceCallback)
    }

    private fun startAdvertising() {
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

        advertiser.startAdvertising(settings, data, advertiseCallback)
    }

    private fun stopAdvertising() {
        bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "Advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising failed with error code: $errorCode")
        }
    }

    fun sendMouseReport(buttons: Byte, x: Byte, y: Byte, wheel: Byte) {
        val device = connectedDevice ?: return
        val report = byteArrayOf(buttons, x, y, wheel)
        bluetoothHidDevice?.sendReport(device, 0, report)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Mouse Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Phomouse Running")
            .setContentText("Ready to connect as HID Mouse")
            .setSmallIcon(R.drawable.ic_logo)
            .build()
    }

    override fun onDestroy() {
        stopAdvertising()
        bluetoothHidDevice?.unregisterApp()
        super.onDestroy()
    }
}
