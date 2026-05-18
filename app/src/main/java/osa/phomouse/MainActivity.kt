package osa.phomouse

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.EditText
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.android.material.button.MaterialButton
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var viewFlipper: ViewFlipper
    private var mouseService: MouseService? = null
    private var isBound = false

    private lateinit var prefs: SharedPreferences
    
    private val dwellHandler = Handler(Looper.getMainLooper())
    private var isJoystickMoving = false
    private val dwellRunnable = Runnable {
        sendBluetoothCommand("LB", prefs.getInt("dwell_period", 500).toString())
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            setupBluetooth()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MouseService.LocalBinder
            mouseService = binder.getService()
            isBound = true
            autoConnect()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mouseService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences("PhomousePrefs", MODE_PRIVATE)
        if (prefs.getBoolean("dyslexic_mode", false)) {
            setTheme(R.style.Theme_Phomouse_Dyslexic)
        } else {
            setTheme(R.style.Theme_Phomouse)
        }
        
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        viewFlipper = findViewById(R.id.app_view_flipper)
        // Consolidate to controller screen (index 2 in original Flipper)
        viewFlipper.displayedChild = 2 

        checkPermissions()
        setupNavigation()
        setupControllerButtons()
        setupSettings()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewFlipper.displayedChild != 2) {
                    viewFlipper.displayedChild = 2
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        val intent = Intent(this, MouseService::class.java)
        bindService(intent, connection, BIND_AUTO_CREATE)
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        
        val missing = permissions.filter { 
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED 
        }
        
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            setupBluetooth()
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupBluetooth() {
        // Just ensuring BT is enabled
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter != null && !adapter.isEnabled) {
            // Optional: Request enable
        }
    }

    @SuppressLint("MissingPermission")
    private fun autoConnect() {
        val lastDevice = prefs.getString("last_bt_device", null)
        if (lastDevice != null) {
            mouseService?.connectToDevice(lastDevice)
            updateStatusBar("Connecting...")
        } else {
            updateStatusBar("No device paired")
        }
    }

    private fun updateStatusBar(text: String) {
        findViewById<TextView>(R.id.status_bar)?.text = text
    }

    private fun setupNavigation() {
        findViewById<ImageButton>(R.id.btn_home_controller).setOnClickListener { 
            // Stay on controller or show device list? Requirement says stay on one screen.
            // We'll keep it on controller.
        }
        findViewById<ImageButton>(R.id.btn_settings_controller).setOnClickListener { 
            viewFlipper.displayedChild = 4 // Settings screen
        }
        findViewById<ImageButton>(R.id.btn_back_settings).setOnClickListener { 
            viewFlipper.displayedChild = 2 
        }
    }

    private fun setupControllerButtons() {
        findViewById<MaterialButton>(R.id.btn_left_click).setOnClickListener { 
            sendBluetoothCommand("LB", prefs.getInt("dwell_period", 500).toString()) 
        }
        findViewById<MaterialButton>(R.id.btn_right_click).setOnClickListener { 
            sendBluetoothCommand("RB", prefs.getInt("dwell_period", 500).toString()) 
        }
        findViewById<MaterialButton>(R.id.btn_double_click).setOnClickListener {
            sendBluetoothCommand("LB", "0")
            it.postDelayed({ sendBluetoothCommand("LB", "0") }, 100)
        }
        findViewById<MaterialButton>(R.id.btn_scroll_up).setOnClickListener {
            sendBluetoothCommand("SU", prefs.getInt("scroll_amount", 1).toString())
        }
        findViewById<MaterialButton>(R.id.btn_scroll_down).setOnClickListener {
            sendBluetoothCommand("SD", prefs.getInt("scroll_amount", 1).toString())
        }
        
        var isDragging = false
        findViewById<MaterialButton>(R.id.btn_grab).setOnClickListener {
            isDragging = !isDragging
            if (isDragging) {
                sendBluetoothCommand("DS", prefs.getInt("sensitivity", 50).toString())
                (it as MaterialButton).text = "Release"
            } else {
                sendBluetoothCommand("DR", "0")
                (it as MaterialButton).text = "Grab"
            }
        }
    }

    private fun setupSettings() {
        findViewById<EditText>(R.id.edit_dwell).apply {
            setText(prefs.getInt("dwell_period", 500).toString())
            addTextChangedListener(createWatcher("dwell_period", 500))
        }
        findViewById<EditText>(R.id.edit_scroll).apply {
            setText(prefs.getInt("scroll_amount", 1).toString())
            addTextChangedListener(createWatcher("scroll_amount", 1))
        }
        findViewById<SwitchCompat>(R.id.switch_joystick).apply {
            isChecked = prefs.getBoolean("joystick_enabled", true)
            setOnCheckedChangeListener { _, checked -> prefs.edit { putBoolean("joystick_enabled", checked) } }
        }
        findViewById<SeekBar>(R.id.seekbar_sensitivity).apply {
            progress = prefs.getInt("sensitivity", 50)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, user: Boolean) { prefs.edit { putInt("sensitivity", p) } }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
    }

    private fun createWatcher(key: String, def: Int) = object : TextWatcher {
        override fun afterTextChanged(s: Editable?) { prefs.edit { putInt(key, s.toString().toIntOrNull() ?: def) } }
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
    }

    private fun sendBluetoothCommand(cmd: String, value: String) {
        mouseService?.sendCommand(cmd, value)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!prefs.getBoolean("joystick_enabled", true)) return super.dispatchGenericMotionEvent(event)
        
        val isJoy = event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        if (isJoy && event.action == MotionEvent.ACTION_MOVE) {
            val sensitivity = (prefs.getInt("sensitivity", 50) + 10) / 60f
            val dx = (event.getAxisValue(MotionEvent.AXIS_X) * 127 * sensitivity).roundToInt()
            val dy = (event.getAxisValue(MotionEvent.AXIS_Y) * 127 * sensitivity).roundToInt()
            
            if (dx != 0 || dy != 0) {
                isJoystickMoving = true
                dwellHandler.removeCallbacks(dwellRunnable)
                sendBluetoothCommand("MV", "$dx,$dy")
            } else if (isJoystickMoving) {
                isJoystickMoving = false
                val dwell = prefs.getInt("dwell_period", 500).toLong()
                if (dwell > 0) dwellHandler.postDelayed(dwellRunnable, dwell)
            }
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) { unbindService(connection); isBound = false }
    }
}
