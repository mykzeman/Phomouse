package osa.phomouse

import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MainActivity : AppCompatActivity() {

    private lateinit var viewFlipper: ViewFlipper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Set the content view to the organized activity_main layout
        setContentView(R.layout.activity_main)

        viewFlipper = findViewById(R.id.app_view_flipper)

        setupTestingButton()
        setupNavigation()
    }

    private fun setupTestingButton() {
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
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "HID Device connected! 🎉", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "HID Device disconnected!", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                BluetoothProfile.HID_DEVICE
            )
        }
    }

    private fun setupNavigation() {
        // Navigation from Index (Screen 0)
        findViewById<FloatingActionButton>(R.id.fab_add).setOnClickListener {
            viewFlipper.displayedChild = 1 // Go to Add Device
        }
        findViewById<ImageButton>(R.id.btn_settings_index).setOnClickListener {
            viewFlipper.displayedChild = 4 // Go to Settings
        }
        // Using the sample item to navigate to Info
        findViewById<ImageButton>(R.id.btn_info_sample).setOnClickListener {
            viewFlipper.displayedChild = 3 // Go to Device Info
        }

        // Navigation from Add Device (Screen 1)
        findViewById<ImageButton>(R.id.btn_home_add).setOnClickListener {
            viewFlipper.displayedChild = 0 // Back Home
        }
        findViewById<TextView>(R.id.available_device_sample).setOnClickListener {
            viewFlipper.displayedChild = 2 // Go to Controller
        }

        // Navigation from Controller (Screen 2)
        findViewById<ImageButton>(R.id.btn_home_controller).setOnClickListener {
            viewFlipper.displayedChild = 0 // Back Home
        }

        // Navigation from Device Info (Screen 3)
        findViewById<ImageButton>(R.id.btn_home_info).setOnClickListener {
            viewFlipper.displayedChild = 0 // Back Home
        }

        // Navigation from Settings (Screen 4)
        findViewById<ImageButton>(R.id.btn_back_settings).setOnClickListener {
            viewFlipper.displayedChild = 0 // Back Home
        }
    }

    override fun onBackPressed() {
        // If not on home screen, back button returns home
        if (viewFlipper.displayedChild != 0) {
            viewFlipper.displayedChild = 0
        } else {
            super.onBackPressed()
        }
    }
}
