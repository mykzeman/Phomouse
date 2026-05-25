package osa.phomouse

import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.util.*
import java.util.concurrent.Executors

class MouseService : Service() {

    private val binder = LocalBinder()
    private val executor = Executors.newSingleThreadExecutor()
    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    inner class LocalBinder : Binder() {
        fun getService(): MouseService = this@MouseService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    @SuppressLint("MissingPermission")
    fun connectToDevice(deviceAddress: String) {
        executor.execute {
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter() ?: return@execute
            val device: BluetoothDevice = bluetoothAdapter.getRemoteDevice(deviceAddress)
            
            try {
                bluetoothSocket?.close()
                bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                bluetoothSocket?.connect()
                outputStream = bluetoothSocket?.outputStream
                Log.d("MouseService", "Connected to Bluetooth device: $deviceAddress")
            } catch (e: IOException) {
                Log.e("MouseService", "Connection failed", e)
                try { bluetoothSocket?.close() } catch (e2: IOException) {}
                bluetoothSocket = null
                outputStream = null
            }
        }
    }

    fun sendCommand(command: String, value: String) {
        val message = "PMCMD:[$command]-{$value}\n"
        executor.execute {
            try {
                outputStream?.write(message.toByteArray())
                outputStream?.flush()
            } catch (e: IOException) {
                Log.e("MouseService", "Failed to send command: $message", e)
            }
        }
    }

    fun disconnect() {
        executor.execute {
            try {
                bluetoothSocket?.close()
            } catch (e: IOException) {
                Log.e("MouseService", "Error closing socket", e)
            }
            bluetoothSocket = null
            outputStream = null
        }
    }

    override fun onDestroy() {
        disconnect()
        executor.shutdown()
        super.onDestroy()
    }
}
