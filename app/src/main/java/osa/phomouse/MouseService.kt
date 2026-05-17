package osa.phomouse

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

class MouseService : Service() {

    private val binder = LocalBinder()
    private val executor = Executors.newFixedThreadPool(2)
    private var socket: DatagramSocket? = null
    private var targetAddress: InetAddress? = null
    private val port = 5555

    var onDeviceDiscovered: ((String, String) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): MouseService = this@MouseService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, notification)
        }
        
        startReceiverThread()
    }

    private fun startReceiverThread() {
        executor.execute {
            try {
                if (socket == null) {
                    socket = DatagramSocket(port)
                    socket?.broadcast = true
                }
                val buffer = ByteArray(1024)
                while (!executor.isShutdown) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket?.receive(packet)
                        val message = String(packet.data, 0, packet.length).trim()
                        if (message.startsWith("PHOMOUSE_ACK:")) {
                            val name = message.substringAfter("PHOMOUSE_ACK:")
                            val address = packet.address.hostAddress
                            if (address != null) {
                                onDeviceDiscovered?.invoke(name, address)
                            }
                        }
                    } catch (e: Exception) {
                        if (!executor.isShutdown) Log.e("MouseService", "Packet receive error", e)
                    }
                }
            } catch (e: Exception) {
                Log.e("MouseService", "Receiver thread setup error", e)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "mouse_service",
                "Mouse Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "mouse_service")
            .setContentTitle("Phomouse WiFi Active")
            .setContentText("Sending mouse signals over WiFi")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun connectToReceiver(address: String) {
        executor.execute {
            try {
                targetAddress = InetAddress.getByName(address)
                Log.d("MouseService", "Connected to receiver at $address")
            } catch (e: Exception) {
                Log.e("MouseService", "Failed to resolve address $address", e)
            }
        }
    }

    fun sendMouseReport(buttons: Byte, dx: Byte, dy: Byte, wheel: Byte) {
        val address = targetAddress ?: return
        executor.execute {
            try {
                // Signal format: buttons,dx,dy,wheel
                val message = "$buttons,$dx,$dy,$wheel\n"
                val bytes = message.toByteArray()
                val packet = DatagramPacket(bytes, bytes.size, address, port)
                socket?.send(packet)
            } catch (e: Exception) {
                Log.e("MouseService", "Failed to send packet", e)
            }
        }
    }

    fun sendPublicAdvertise() {
        executor.execute {
            try {
                val broadcastAddress = InetAddress.getByName("255.255.255.255")
                val message = "PHOMOUSE_DISCOVER"
                val bytes = message.toByteArray()
                val packet = DatagramPacket(bytes, bytes.size, broadcastAddress, port)
                socket?.send(packet)
                Log.d("MouseService", "Sent discovery broadcast")
            } catch (e: Exception) {
                Log.e("MouseService", "Failed to send discovery", e)
            }
        }
    }

    fun forgetSerialDevice(address: String) {
        if (targetAddress?.hostAddress == address) {
            targetAddress = null
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        socket?.close()
        super.onDestroy()
    }
}
