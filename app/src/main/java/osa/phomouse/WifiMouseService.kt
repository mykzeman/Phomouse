package osa.phomouse

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

class WifiMouseService : Service() {

    private val binder = LocalBinder()
    private val executor = Executors.newSingleThreadExecutor()
    private var socket: DatagramSocket? = null
    
    private var targetIp: String = ""
    private var targetPort: Int = 8888

    inner class LocalBinder : Binder() {
        fun getService(): WifiMouseService = this@WifiMouseService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        startForegroundService()
        try {
            socket = DatagramSocket()
        } catch (e: Exception) {
            Log.e("WifiMouseService", "Failed to create socket", e)
        }
    }

    private fun startForegroundService() {
        val channelId = "wifi_mouse_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "WiFi Mouse Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Phomouse WiFi Active")
            .setContentText("Sending signals over WiFi")
            .setSmallIcon(R.drawable.ic_logo)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1, notification)
    }

    fun updateTarget(ip: String, port: Int) {
        targetIp = ip
        targetPort = port
    }

    /**
     * Sends a mouse report over UDP.
     * Format: "M:dx,dy,scroll,buttons"
     * Example: "M:10,-5,0,1"
     */
    fun sendMouseReport(dx: Int, dy: Int, scroll: Int, buttons: Int) {
        if (targetIp.isEmpty()) return

        executor.execute {
            try {
                val message = "M:$dx,$dy,$scroll,$buttons"
                val data = message.toByteArray()
                val address = InetAddress.getByName(targetIp)
                val packet = DatagramPacket(data, data.size, address, targetPort)
                socket?.send(packet)
            } catch (e: Exception) {
                Log.e("WifiMouseService", "Error sending packet", e)
            }
        }
    }

    override fun onDestroy() {
        socket?.close()
        executor.shutdown()
        super.onDestroy()
    }
}
