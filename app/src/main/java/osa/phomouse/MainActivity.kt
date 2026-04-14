package osa.phomouse

import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnConnect = findViewById<Button>(R.id.btnConnect)

        btnConnect.setOnClickListener {
            val bluetoothManager = getSystemService(BluetoothManager::class.java)
            val bluetoothAdapter = bluetoothManager?.adapter

            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
                Toast.makeText(this, "Bluetooth is not enabled!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            bluetoothAdapter.getProfileProxy(
                this,
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        val hidDevice = proxy as BluetoothHidDevice
                        runOnUiThread {
                            Toast.makeText(
                                this@MainActivity,
                                "HID Device connected! 🎉",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        runOnUiThread {
                            Toast.makeText(
                                this@MainActivity,
                                "HID Device disconnected!",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                BluetoothProfile.HID_DEVICE
            )
        }
    }
}
