package osa.phomouse

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.Toast
import android.widget.ViewFlipper
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var viewFlipper: ViewFlipper
    private var mouseService: MouseService? = null
    private var isBound = false

    private var currentButtons: Byte = 0
    private var lastReportTime = 0L
    private val THROTTLE_MS = 10L // Max 100 reports/sec

    private lateinit var prefs: SharedPreferences
    
    private val dwellHandler = Handler(Looper.getMainLooper())
    private var isJoystickMoving = false
    private val dwellRunnable = Runnable {
        performClick(0x01.toByte()) // Left Click on dwell
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MouseService.LocalBinder
            mouseService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mouseService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Toast.makeText(this, "HID Device requires Android 9 (API 28) or higher", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        viewFlipper = findViewById(R.id.app_view_flipper)
        prefs = getSharedPreferences("PhomousePrefs", Context.MODE_PRIVATE)

        checkPermissions()
        requestBatteryOptimizations()
        setupNavigation()
        setupControllerButtons()
        setupSettings()

        val intent = Intent(this, MouseService::class.java)
        startForegroundService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 101)
        }
    }

    private fun requestBatteryOptimizations() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }
    }

    private fun setupNavigation() {
        findViewById<FloatingActionButton>(R.id.fab_add).setOnClickListener {
            viewFlipper.displayedChild = 1 ;
            mouseService?.sendPublicAdvertise();
        }
        findViewById<ImageButton>(R.id.btn_settings_index).setOnClickListener { viewFlipper.displayedChild = 4 }

        listOf(R.id.item_device_1, R.id.item_device_2, R.id.item_device_3).forEach { id ->
            findViewById<View>(id)?.setOnClickListener { viewFlipper.displayedChild = 2 }
        }

        listOf(R.id.btn_info_1, R.id.btn_info_2, R.id.btn_info_3).forEach { id ->
            findViewById<View>(id)?.setOnClickListener { viewFlipper.displayedChild = 3 }
        }

        findViewById<ImageButton>(R.id.btn_home_add).setOnClickListener { viewFlipper.displayedChild = 0 }
        
        listOf(R.id.available_device_1, R.id.available_device_2, R.id.available_device_3).forEach { id ->
            findViewById<View>(id)?.setOnClickListener { viewFlipper.displayedChild = 2 }
        }

        findViewById<ImageButton>(R.id.btn_home_controller).setOnClickListener { viewFlipper.displayedChild = 0 }
        findViewById<ImageButton>(R.id.btn_settings_controller).setOnClickListener { viewFlipper.displayedChild = 4 }
        findViewById<ImageButton>(R.id.btn_home_info).setOnClickListener { viewFlipper.displayedChild = 0 }
        findViewById<ImageButton>(R.id.btn_back_settings).setOnClickListener { viewFlipper.displayedChild = 0 }
    }

    private fun setupControllerButtons() {
        findViewById<MaterialButton>(R.id.btn_left_click).setOnClickListener { performClick(0x01.toByte()) }
        findViewById<MaterialButton>(R.id.btn_right_click).setOnClickListener { performClick(0x02.toByte()) }
        
        findViewById<MaterialButton>(R.id.btn_double_click).setOnClickListener {
            performClick(0x01.toByte())
            it.postDelayed({ performClick(0x01.toByte()) }, 200)
        }

        findViewById<MaterialButton>(R.id.btn_scroll_up).setOnClickListener {
            sendMouseReport(dx = 0, dy = 0, wheel = 1)
            it.postDelayed({ sendMouseReport(dx = 0, dy = 0, wheel = 0) }, 50)
        }

        findViewById<MaterialButton>(R.id.btn_scroll_down).setOnClickListener {
            sendMouseReport(dx = 0, dy = 0, wheel = (-1).toByte())
            it.postDelayed({ sendMouseReport(dx = 0, dy = 0, wheel = 0) }, 50)
        }

        setupDragButton(R.id.btn_drag_up, 0, -20)
        setupDragButton(R.id.btn_drag_down, 0, 20)
        setupDragButton(R.id.btn_drag_left, -20, 0)
        setupDragButton(R.id.btn_drag_right, 20, 0)

        var isGrabbed = false
        findViewById<MaterialButton>(R.id.btn_grab).setOnClickListener {
            isGrabbed = !isGrabbed
            currentButtons = if (isGrabbed) 0x01.toByte() else 0x00.toByte()
            sendMouseReport()
            (it as MaterialButton).text = if (isGrabbed) "Release" else "Grab"
        }
    }

    private fun setupSettings() {
        val dwellEdit = findViewById<EditText>(R.id.edit_dwell)
        dwellEdit.setText(prefs.getInt("dwell_period", 500).toString())
        dwellEdit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val value = s.toString().toIntOrNull() ?: 500
                prefs.edit().putInt("dwell_period", value).apply()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        findViewById<SwitchCompat>(R.id.switch_joystick).apply {
            isChecked = prefs.getBoolean("joystick_enabled", true)
            setOnCheckedChangeListener { _, isChecked -> prefs.edit().putBoolean("joystick_enabled", isChecked).apply() }
        }
        
        findViewById<SeekBar>(R.id.seekbar_sensitivity).apply {
            progress = prefs.getInt("sensitivity", 50)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    prefs.edit().putInt("sensitivity", progress).apply()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
    }

    private fun sendMouseReport(dx: Byte = 0, dy: Byte = 0, wheel: Byte = 0) {
        val currentTime = System.currentTimeMillis()
        if (dx != 0.toByte() || dy != 0.toByte()) {
            if (currentTime - lastReportTime < THROTTLE_MS) return
        }
        
        mouseService?.sendMouseReport(currentButtons, dx, dy, wheel)
        lastReportTime = currentTime
    }

    private fun performClick(button: Byte) {
        val originalButtons = currentButtons
        currentButtons = (currentButtons.toInt() or button.toInt()).toByte()
        sendMouseReport()
        window.decorView.postDelayed({
            currentButtons = originalButtons
            sendMouseReport()
        }, 50)
    }

    private fun setupDragButton(id: Int, dx: Int, dy: Int) {
        findViewById<MaterialButton>(id).setOnClickListener {
            sendMouseReport(dx = dx.toByte(), dy = dy.toByte())
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!prefs.getBoolean("joystick_enabled", true)) return super.onGenericMotionEvent(event)

        if (event.source and android.view.InputDevice.SOURCE_JOYSTICK == android.view.InputDevice.SOURCE_JOYSTICK &&
            event.action == MotionEvent.ACTION_MOVE) {
            
            val x = event.getAxisValue(MotionEvent.AXIS_X)
            val y = event.getAxisValue(MotionEvent.AXIS_Y)
            
            val sensitivity = (prefs.getInt("sensitivity", 50) + 10) / 60f
            val hidX = (x * 127 * sensitivity).roundToInt().coerceIn(-127, 127).toByte()
            val hidY = (y * 127 * sensitivity).roundToInt().coerceIn(-127, 127).toByte()
            
            if (hidX != 0.toByte() || hidY != 0.toByte()) {
                isJoystickMoving = true
                dwellHandler.removeCallbacks(dwellRunnable)
                sendMouseReport(dx = hidX, dy = hidY)
            } else if (isJoystickMoving) {
                isJoystickMoving = false
                val dwellTime = prefs.getInt("dwell_period", 500).toLong()
                if (dwellTime > 0) {
                    dwellHandler.postDelayed(dwellRunnable, dwellTime)
                }
            }
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_1 -> {
                currentButtons = (currentButtons.toInt() or 0x01).toByte()
                sendMouseReport()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_2 -> {
                currentButtons = (currentButtons.toInt() or 0x02).toByte()
                sendMouseReport()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_1 -> {
                currentButtons = (currentButtons.toInt() and 0x01.inv()).toByte()
                sendMouseReport()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_2 -> {
                currentButtons = (currentButtons.toInt() and 0x02.inv()).toByte()
                sendMouseReport()
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    override fun onBackPressed() {
        if (viewFlipper.displayedChild != 0) {
            viewFlipper.displayedChild = 0
        } else {
            super.onBackPressed()
        }
    }
}
