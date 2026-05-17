package osa.phomouse

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
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
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.android.material.button.MaterialButton
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var viewFlipper: ViewFlipper
    private var wifiMouseService: WifiMouseService? = null
    private var isBound = false

    private var currentButtons: Int = 0
    private var lastReportTime = 0L
    private val throttleMs = 10L // 100Hz as per IMPROVEMENTS.md

    private lateinit var prefs: SharedPreferences
    
    private val dwellHandler = Handler(Looper.getMainLooper())
    private var isJoystickMoving = false
    private val dwellRunnable = Runnable {
        performLeftClick()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as WifiMouseService.LocalBinder
            wifiMouseService = binder.getService()
            isBound = true
            syncServiceTarget()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            wifiMouseService = null
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
        applyUiScale(prefs.getInt("ui_scale", 50))

        requestBatteryOptimizations()
        setupNavigation()
        setupControllerButtons()
        setupSettings()

        val intent = Intent(this, WifiMouseService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, connection, BIND_AUTO_CREATE)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewFlipper.displayedChild != 0) {
                    viewFlipper.displayedChild = 0
                    updateStatus()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        
        updateStatus()
    }

    private fun applyUiScale(scale: Int) {
        val factor = 0.75f + (scale / 100f) * 0.5f
        viewFlipper.scaleX = factor
        viewFlipper.scaleY = factor
    }

    private fun updateStatus() {
        val ip = prefs.getString("receiver_ip", "")
        val port = prefs.getInt("receiver_port", 8888)
        
        val statusText = if (ip.isNullOrBlank()) "WiFi Target Not Set" else "Target: $ip:$port"
        val statusColor = if (ip.isNullOrBlank()) R.color.error else R.color.accent

        findViewById<TextView>(R.id.status_bar)?.apply {
            text = statusText
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, statusColor))
        }
        
        findViewById<TextView>(R.id.tv_current_target)?.apply {
            text = if (ip.isNullOrBlank()) "Tap to configure WiFi" else "Sending to $ip:$port"
        }
    }

    private fun syncServiceTarget() {
        val ip = prefs.getString("receiver_ip", "") ?: ""
        val port = prefs.getInt("receiver_port", 8888)
        wifiMouseService?.updateTarget(ip, port)
    }

    private fun requestBatteryOptimizations() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            try { startActivity(intent) } catch (e: Exception) {}
        }
    }

    private fun setupNavigation() {
        findViewById<View>(R.id.fab_add).setOnClickListener {
            viewFlipper.displayedChild = 4 // Settings
        }
        findViewById<ImageButton>(R.id.btn_settings_index).setOnClickListener { viewFlipper.displayedChild = 4 }
        findViewById<ImageButton>(R.id.btn_home_controller).setOnClickListener { viewFlipper.displayedChild = 0 }
        findViewById<ImageButton>(R.id.btn_settings_controller).setOnClickListener { viewFlipper.displayedChild = 4 }
        findViewById<ImageButton>(R.id.btn_back_settings).setOnClickListener { 
            viewFlipper.displayedChild = 0 
            updateStatus()
        }
        
        findViewById<View>(R.id.rv_paired_devices).setOnClickListener {
            val ip = prefs.getString("receiver_ip", "")
            if (ip.isNullOrBlank()) {
                viewFlipper.displayedChild = 4 // Settings
            } else {
                viewFlipper.displayedChild = 2 // Controller
            }
        }
    }

    private fun setupControllerButtons() {
        findViewById<MaterialButton>(R.id.btn_left_click).setOnClickListener { performLeftClick() }
        findViewById<MaterialButton>(R.id.btn_right_click).setOnClickListener { performClick(0x02) }
        findViewById<MaterialButton>(R.id.btn_double_click).setOnClickListener {
            performLeftClick()
            it.postDelayed({ performLeftClick() }, 200)
        }
        findViewById<MaterialButton>(R.id.btn_scroll_up).setOnClickListener {
            val amt = prefs.getInt("scroll_amount", 1)
            sendMouseReport(scroll = amt)
            it.postDelayed({ sendMouseReport(scroll = 0) }, 50)
        }
        findViewById<MaterialButton>(R.id.btn_scroll_down).setOnClickListener {
            val amt = prefs.getInt("scroll_amount", 1)
            sendMouseReport(scroll = -amt)
            it.postDelayed({ sendMouseReport(scroll = 0) }, 50)
        }
        var isGrabbed = false
        findViewById<MaterialButton>(R.id.btn_grab).setOnClickListener {
            isGrabbed = !isGrabbed
            currentButtons = if (isGrabbed) 0x01 else 0x00
            sendMouseReport()
            (it as MaterialButton).text = if (isGrabbed) "Release" else "Grab"
        }
        
        setupDragButton(R.id.btn_drag_up, 0, -10)
        setupDragButton(R.id.btn_drag_down, 0, 10)
        setupDragButton(R.id.btn_drag_left, -10, 0)
        setupDragButton(R.id.btn_drag_right, 10, 0)
    }

    private fun setupSettings() {
        findViewById<EditText>(R.id.edit_ip).apply {
            setText(prefs.getString("receiver_ip", ""))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { 
                    prefs.edit { putString("receiver_ip", s.toString()) }
                    syncServiceTarget()
                }
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            })
        }
        findViewById<EditText>(R.id.edit_port).apply {
            setText(prefs.getInt("receiver_port", 8888).toString())
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { 
                    val port = s.toString().toIntOrNull() ?: 8888
                    prefs.edit { putInt("receiver_port", port) }
                    syncServiceTarget()
                }
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            })
        }
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
        findViewById<SwitchCompat>(R.id.switch_dyslexic).apply {
            isChecked = prefs.getBoolean("dyslexic_mode", false)
            setOnCheckedChangeListener { _, checked -> prefs.edit { putBoolean("dyslexic_mode", checked) }; recreate() }
        }
        findViewById<SwitchCompat>(R.id.switch_colourblind).apply {
            isChecked = prefs.getBoolean("colourblind_mode", false)
            setOnCheckedChangeListener { _, checked -> prefs.edit { putBoolean("colourblind_mode", checked) } }
        }
        findViewById<SeekBar>(R.id.seekbar_ui_scale).apply {
            progress = prefs.getInt("ui_scale", 50)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, user: Boolean) { 
                    prefs.edit { putInt("ui_scale", p) }
                    applyUiScale(p)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
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

    private fun sendMouseReport(dx: Int = 0, dy: Int = 0, scroll: Int = 0) {
        val now = System.currentTimeMillis()
        if ((dx != 0 || dy != 0) && now - lastReportTime < throttleMs) return
        wifiMouseService?.sendMouseReport(dx, dy, scroll, currentButtons)
        lastReportTime = now
    }

    private fun performClick(button: Int) {
        val old = currentButtons
        currentButtons = currentButtons or button
        sendMouseReport()
        window.decorView.postDelayed({ currentButtons = old; sendMouseReport() }, 50)
    }

    private fun performLeftClick() {
        wifiMouseService?.sendMouseReport(0, 0, 0, 1)
        Handler(Looper.getMainLooper()).postDelayed({
            wifiMouseService?.sendMouseReport(0, 0, 0, 0)
        }, 50)
    }

    private fun setupDragButton(id: Int, dx: Int, dy: Int) {
        findViewById<MaterialButton>(id).setOnClickListener { sendMouseReport(dx, dy) }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!prefs.getBoolean("joystick_enabled", true)) return super.dispatchGenericMotionEvent(event)
        
        val isMouseOrJoystick = event.source and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE ||
                                event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK

        if (isMouseOrJoystick) {
            handleChairInput(event)
            return true 
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun handleChairInput(event: MotionEvent) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastReportTime < throttleMs) return
        lastReportTime = currentTime

        val sensitivity = (prefs.getInt("sensitivity", 50) + 10) / 60f
        val uiScaleFactor = 0.75f + (prefs.getInt("ui_scale", 50) / 100f) * 0.5f
        
        val dx: Float
        val dy: Float

        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) {
            dx = event.getAxisValue(MotionEvent.AXIS_X)
            dy = event.getAxisValue(MotionEvent.AXIS_Y)
        } else {
            val relX = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
            val relY = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
            if (relX == 0f && relY == 0f) {
                dx = event.getAxisValue(MotionEvent.AXIS_X) / 10f
                dy = event.getAxisValue(MotionEvent.AXIS_Y) / 10f
            } else {
                dx = relX; dy = relY
            }
        }
        
        val hidX = (dx * 127 * sensitivity * uiScaleFactor).roundToInt().coerceIn(-127, 127)
        val hidY = (dy * 127 * sensitivity * uiScaleFactor).roundToInt().coerceIn(-127, 127)
        val scroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL).roundToInt()
        
        dwellHandler.removeCallbacks(dwellRunnable)
        if (hidX != 0 || hidY != 0) {
            isJoystickMoving = true
            sendMouseReport(hidX, hidY, scroll)
        } else if (isJoystickMoving) {
            isJoystickMoving = false
            val dwell = prefs.getInt("dwell_period", 500).toLong()
            if (dwell > 0) dwellHandler.postDelayed(dwellRunnable, dwell)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_1 -> { currentButtons = currentButtons or 0x01; sendMouseReport(); return true }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_2 -> { currentButtons = currentButtons or 0x02; sendMouseReport(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_1 -> { currentButtons = currentButtons and 0x01.inv(); sendMouseReport(); return true }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_2 -> { currentButtons = currentButtons and 0x02.inv(); sendMouseReport(); return true }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) { unbindService(connection); isBound = false }
    }
}
