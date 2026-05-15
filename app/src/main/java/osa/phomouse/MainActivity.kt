package osa.phomouse

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var viewFlipper: ViewFlipper
    private var mouseService: MouseService? = null
    private var isBound = false

    private var currentButtons: Byte = 0
    private var lastReportTime = 0L
    private val throttleMs = 10L

    private lateinit var prefs: SharedPreferences
    
    private val dwellHandler = Handler(Looper.getMainLooper())
    private var isJoystickMoving = false
    private val dwellRunnable = Runnable {
        performClick(0x01.toByte()) // Left Click on dwell
    }

    private lateinit var pairedAdapter: DeviceAdapter
    private lateinit var availableAdapter: DeviceAdapter
    private var bluetoothAdapter: BluetoothAdapter? = null

    private var selectedDevice: DeviceItem? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MouseService.LocalBinder
            mouseService = binder.getService()
            isBound = true
            updatePairedDevices()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mouseService = null
            isBound = false
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_FOUND) {
                val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                device?.let {
                    try {
                        val name = it.name ?: "Unknown Device"
                        val address = it.address
                        val newList = availableAdapter.currentList.toMutableList()
                        if (newList.none { item -> item.address == address }) {
                            newList.add(DeviceItem(name, address, false))
                            availableAdapter.submitList(newList)
                        }
                    } catch (e: SecurityException) {
                        Log.e("MainActivity", "Discovery access denied", e)
                    }
                }
            }
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
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        setupRecyclerViews()
        checkPermissions()
        requestBatteryOptimizations()
        setupNavigation()
        setupControllerButtons()
        setupSettings()
        setupInfoButtons()

        val intent = Intent(this, MouseService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, connection, BIND_AUTO_CREATE)

        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        registerReceiver(receiver, filter)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewFlipper.displayedChild != 0) {
                    viewFlipper.displayedChild = 0
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun setupRecyclerViews() {
        pairedAdapter = DeviceAdapter(
            onItemClick = { device ->
                selectedDevice = device
                viewFlipper.displayedChild = 2
                updateStatusBar(device.name)
            },
            onInfoClick = { device ->
                selectedDevice = device
                showDeviceInfo(device)
            }
        )
        findViewById<RecyclerView>(R.id.rv_paired_devices).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = pairedAdapter
        }

        availableAdapter = DeviceAdapter(
            onItemClick = { deviceItem ->
                selectedDevice = deviceItem
                val bluetoothManager = getSystemService(BluetoothManager::class.java)
                val device = bluetoothManager?.adapter?.getRemoteDevice(deviceItem.address)
                device?.let { mouseService?.connectSerialDevice(it) }
                viewFlipper.displayedChild = 2
                updateStatusBar(deviceItem.name)
            },
            onInfoClick = { device ->
                selectedDevice = device
                showDeviceInfo(device)
            }
        )
        findViewById<RecyclerView>(R.id.rv_available_devices).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = availableAdapter
        }
    }

    private fun updateStatusBar(deviceName: String?) {
        findViewById<TextView>(R.id.status_bar).apply {
            text = if (deviceName != null) "Connected to $deviceName" else "Not Connected"
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, if (deviceName != null) R.color.accent else R.color.error))
        }
    }

    private fun showDeviceInfo(device: DeviceItem) {
        findViewById<TextView>(R.id.tv_info_device_name).text = device.name
        val statusText = findViewById<TextView>(R.id.tv_info_status)
        statusText.text = if (device.isPaired) "Paired" else "Available"
        statusText.setTextColor(ContextCompat.getColor(this, if (device.isPaired) R.color.success else R.color.error))
        viewFlipper.displayedChild = 3
    }

    private fun setupInfoButtons() {
        findViewById<View>(R.id.btn_retry).setOnClickListener {
            mouseService?.sendPublicAdvertise()
        }
        findViewById<View>(R.id.btn_forget).setOnClickListener {
            viewFlipper.displayedChild = 0
        }
        findViewById<ImageButton>(R.id.btn_settings_info).setOnClickListener { viewFlipper.displayedChild = 4 }
    }

    private fun updatePairedDevices() {
        try {
            val pairedDevices = bluetoothAdapter?.bondedDevices
            val items = pairedDevices?.map { DeviceItem(it.name ?: "Unknown", it.address, true) } ?: emptyList()
            pairedAdapter.submitList(items)
        } catch (e: SecurityException) {
            Log.e("MainActivity", "Security error accessing bonded devices", e)
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.addAll(listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_SCAN))
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
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
        findViewById<FloatingActionButton>(R.id.fab_add).setOnClickListener {
            viewFlipper.displayedChild = 1
            availableAdapter.submitList(emptyList())
            try { bluetoothAdapter?.startDiscovery() } catch (e: SecurityException) {}
            mouseService?.sendPublicAdvertise()
        }
        findViewById<ImageButton>(R.id.btn_home_add).setOnClickListener { 
            try { bluetoothAdapter?.cancelDiscovery() } catch (e: SecurityException) {}
            viewFlipper.displayedChild = 0 
        }
        findViewById<ImageButton>(R.id.btn_settings_index).setOnClickListener { viewFlipper.displayedChild = 4 }
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
            val amt = prefs.getInt("scroll_amount", 1).toByte()
            sendMouseReport(wheel = amt)
            it.postDelayed({ sendMouseReport(wheel = 0) }, 50)
        }
        findViewById<MaterialButton>(R.id.btn_scroll_down).setOnClickListener {
            val amt = prefs.getInt("scroll_amount", 1).toByte()
            sendMouseReport(wheel = (-amt).toByte())
            it.postDelayed({ sendMouseReport(wheel = 0) }, 50)
        }
        var isGrabbed = false
        findViewById<MaterialButton>(R.id.btn_grab).setOnClickListener {
            isGrabbed = !isGrabbed
            currentButtons = if (isGrabbed) 0x01.toByte() else 0x00.toByte()
            sendMouseReport()
            (it as MaterialButton).text = if (isGrabbed) "Release" else "Grab"
        }
        
        setupDragButton(R.id.btn_drag_up, 0, -10)
        setupDragButton(R.id.btn_drag_down, 0, 10)
        setupDragButton(R.id.btn_drag_left, -10, 0)
        setupDragButton(R.id.btn_drag_right, 10, 0)
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
                override fun onProgressChanged(sb: SeekBar?, p: Int, user: Boolean) { prefs.edit { putInt("ui_scale", p) } }
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

    private fun sendMouseReport(dx: Byte = 0, dy: Byte = 0, wheel: Byte = 0) {
        val now = System.currentTimeMillis()
        if ((dx != 0.toByte() || dy != 0.toByte()) && now - lastReportTime < throttleMs) return
        mouseService?.sendMouseReport(currentButtons, dx, dy, wheel)
        lastReportTime = now
    }

    private fun performClick(button: Byte) {
        val old = currentButtons
        currentButtons = (currentButtons.toInt() or button.toInt()).toByte()
        sendMouseReport()
        window.decorView.postDelayed({ currentButtons = old; sendMouseReport() }, 50)
    }

    private fun setupDragButton(id: Int, dx: Int, dy: Int) {
        findViewById<MaterialButton>(id).setOnClickListener { sendMouseReport(dx.toByte(), dy.toByte()) }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!prefs.getBoolean("joystick_enabled", true)) return super.onGenericMotionEvent(event)
        val isJoy = event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        val isMouse = event.source and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE

        if ((isJoy || isMouse) && event.action == MotionEvent.ACTION_MOVE) {
            val sensitivity = (prefs.getInt("sensitivity", 50) + 10) / 60f
            val dx: Float
            val dy: Float

            if (isJoy) {
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
            
            val hidX = (dx * 127 * sensitivity).roundToInt().coerceIn(-127, 127).toByte()
            val hidY = (dy * 127 * sensitivity).roundToInt().coerceIn(-127, 127).toByte()
            
            if (hidX != 0.toByte() || hidY != 0.toByte()) {
                isJoystickMoving = true
                dwellHandler.removeCallbacks(dwellRunnable)
                sendMouseReport(hidX, hidY)
            } else if (isJoystickMoving) {
                isJoystickMoving = false
                val dwell = prefs.getInt("dwell_period", 500).toLong()
                if (dwell > 0) dwellHandler.postDelayed(dwellRunnable, dwell)
            }
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_1 -> { currentButtons = (currentButtons.toInt() or 0x01).toByte(); sendMouseReport(); return true }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_2 -> { currentButtons = (currentButtons.toInt() or 0x02).toByte(); sendMouseReport(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_1 -> { currentButtons = (currentButtons.toInt() and 0x01.inv()).toByte(); sendMouseReport(); return true }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_2 -> { currentButtons = (currentButtons.toInt() and 0x02.inv()).toByte(); sendMouseReport(); return true }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(receiver) } catch (e: Exception) {}
        if (isBound) { unbindService(connection); isBound = false }
    }
}
