package osa.phomouse

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
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

    private lateinit var pairedAdapter: DeviceAdapter
    private lateinit var availableAdapter: DeviceAdapter
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when(intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let {
                        val name = it.name ?: "Unknown Device"
                        val item = DeviceItem(name, it.address, false)
                        val currentList = availableAdapter.currentList.toMutableList()
                        if (currentList.none { d -> d.address == item.address }) {
                            currentList.add(item)
                            availableAdapter.submitList(currentList)
                        }
                    }
                }
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            setupBluetooth()
            loadPairedDevices()
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
        
        val isDyslexic = prefs.getBoolean("dyslexic_mode", false)
        val isColourblind = prefs.getBoolean("colourblind_mode", false)

        when {
            isDyslexic && isColourblind -> setTheme(R.style.Theme_Phomouse_Dyslexic_Colourblind)
            isDyslexic -> setTheme(R.style.Theme_Phomouse_Dyslexic)
            isColourblind -> setTheme(R.style.Theme_Phomouse_Colourblind)
            else -> setTheme(R.style.Theme_Phomouse)
        }
        
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        viewFlipper = findViewById(R.id.app_view_flipper)
        
        setupAdapters()
        setupIndexScreen()
        setupAddDeviceScreen()
        setupInfoScreen()
        setupNavigation()
        setupControllerButtons()
        setupSettings()

        // Default screen to Index (0)
        val targetScreen = intent.getIntExtra("target_screen", 0)
        viewFlipper.displayedChild = targetScreen

        checkPermissions()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (viewFlipper.displayedChild) {
                    1, 2, 3, 4 -> viewFlipper.displayedChild = 0 
                    0 -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        val serviceIntent = Intent(this, MouseService::class.java)
        bindService(serviceIntent, connection, BIND_AUTO_CREATE)
        
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        registerReceiver(bluetoothReceiver, filter)
    }

    private fun setupAdapters() {
        pairedAdapter = DeviceAdapter(
            onItemClick = { device -> connectToDevice(device) },
            onInfoClick = { device -> showDeviceInfo(device) }
        )
        availableAdapter = DeviceAdapter(
            onItemClick = { device -> connectToDevice(device) },
            onInfoClick = { device -> showDeviceInfo(device) }
        )
    }

    private fun connectToDevice(device: DeviceItem) {
        prefs.edit { putString("last_bt_device", device.address) }
        mouseService?.connectToDevice(device.address)
        viewFlipper.displayedChild = 2 // Move to controller
        updateStatusBar("Connecting to ${device.name}...")
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
            loadPairedDevices()
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupBluetooth() {
        if (bluetoothAdapter != null && !bluetoothAdapter.isEnabled) {
            // Optional: Request enable
        }
    }

    @SuppressLint("MissingPermission")
    private fun loadPairedDevices() {
        val pairedDevices = bluetoothAdapter?.bondedDevices ?: emptySet()
        val devices = pairedDevices.map { DeviceItem(it.name ?: "Unknown", it.address, true) }
        pairedAdapter.submitList(devices)
    }

    private fun setupIndexScreen() {
        findViewById<RecyclerView>(R.id.rv_paired_devices)?.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = pairedAdapter
        }
        findViewById<FloatingActionButton>(R.id.fab_add)?.setOnClickListener {
            viewFlipper.displayedChild = 1 // Add screen
            startDiscovery()
        }
        findViewById<ImageButton>(R.id.btn_settings_index)?.setOnClickListener {
            viewFlipper.displayedChild = 4 // Settings
        }
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        if (bluetoothAdapter?.isDiscovering == true) {
            bluetoothAdapter.cancelDiscovery()
        }
        availableAdapter.submitList(emptyList())
        bluetoothAdapter?.startDiscovery()
    }

    private fun setupAddDeviceScreen() {
        findViewById<RecyclerView>(R.id.rv_available_devices)?.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = availableAdapter
        }
        findViewById<ImageButton>(R.id.btn_home_add)?.setOnClickListener { 
            bluetoothAdapter?.cancelDiscovery()
            viewFlipper.displayedChild = 0 
        }
    }

    private fun showDeviceInfo(device: DeviceItem) {
        findViewById<TextView>(R.id.tv_info_device_name)?.text = device.name
        findViewById<TextView>(R.id.tv_info_status)?.text = if (device.isPaired) "Paired" else "Available"
        viewFlipper.displayedChild = 3 // Info screen
    }

    private fun setupInfoScreen() {
        findViewById<ImageButton>(R.id.btn_home_info)?.setOnClickListener { viewFlipper.displayedChild = 0 }
        findViewById<ImageButton>(R.id.btn_settings_info)?.setOnClickListener { viewFlipper.displayedChild = 4 }
    }

    @SuppressLint("MissingPermission")
    private fun autoConnect() {
        val lastDevice = prefs.getString("last_bt_device", null)
        if (lastDevice != null) {
            mouseService?.connectToDevice(lastDevice)
            updateStatusBar("Connecting to device...")
        } else {
            updateStatusBar("No device paired")
        }
    }

    private fun updateStatusBar(text: String) {
        findViewById<TextView>(R.id.status_bar)?.text = text
    }

    private fun setupNavigation() {
        findViewById<ImageButton>(R.id.btn_home_controller)?.setOnClickListener { 
            viewFlipper.displayedChild = 0
        }
        findViewById<ImageButton>(R.id.btn_settings_controller)?.setOnClickListener { 
            viewFlipper.displayedChild = 4 
        }
        findViewById<ImageButton>(R.id.btn_back_settings)?.setOnClickListener { 
            viewFlipper.displayedChild = 2 // Return to controller
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupControllerButtons() {
        val dwellValue = { prefs.getInt("dwell_period", 500).toString() }
        val scrollValue = { prefs.getInt("scroll_amount", 1).toString() }
        val sensitivityValue = { prefs.getInt("sensitivity", 50) }

        val btnLeft = findViewById<MaterialButton>(R.id.btn_left_click)
        
        // VIRTUAL JOYSTICK ON LEFT CLICK BUTTON
        btnLeft?.setOnTouchListener { v, event ->
            val joystickEnabled = prefs.getBoolean("joystick_enabled", true)
            if (joystickEnabled) {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val centerX = v.width / 2f
                        val centerY = v.height / 2f
                        // Map relative position to -127 to 127
                        val dx = ((event.x - centerX) / centerX * 127).roundToInt().coerceIn(-127, 127)
                        val dy = ((event.y - centerY) / centerY * 127).roundToInt().coerceIn(-127, 127)
                        
                        if (dx != 0 || dy != 0) {
                            sendBluetoothCommand("MV", "$dx,$dy")
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        v.isPressed = false
                        // Trigger Left Click on Release as per IMPROVEMENTS.md
                        sendBluetoothCommand("LB", dwellValue())
                        v.performClick()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        true
                    }
                    else -> false
                }
            } else {
                false
            }
        }

        btnLeft?.setOnClickListener {
            // Only send command here if virtual joystick is DISABLED
            if (!prefs.getBoolean("joystick_enabled", true)) {
                sendBluetoothCommand("LB", dwellValue())
            }
        }

        findViewById<MaterialButton>(R.id.btn_right_click)?.setOnClickListener { 
            sendBluetoothCommand("RB", dwellValue()) 
        }
        findViewById<MaterialButton>(R.id.btn_middle_click)?.setOnClickListener { 
            sendBluetoothCommand("MB", dwellValue()) 
        }
        findViewById<MaterialButton>(R.id.btn_double_click)?.setOnClickListener {
            sendBluetoothCommand("LB", "0")
            it.postDelayed({ sendBluetoothCommand("LB", "0") }, 100)
        }
        findViewById<MaterialButton>(R.id.btn_scroll_up)?.setOnClickListener {
            sendBluetoothCommand("SU", scrollValue())
        }
        findViewById<MaterialButton>(R.id.btn_scroll_down)?.setOnClickListener {
            sendBluetoothCommand("SD", scrollValue())
        }
        
        var isDragging = false
        val btnGrab = findViewById<MaterialButton>(R.id.btn_grab)
        btnGrab?.setOnClickListener {
            isDragging = !isDragging
            if (isDragging) {
                sendBluetoothCommand("DS", sensitivityValue().toString())
                btnGrab.text = "Rel."
            } else {
                sendBluetoothCommand("DR", "0")
                btnGrab.text = "Grab"
            }
        }

        // D-pad Movement: Disabled if joystick mode is active
        val onDpadClick = { dx: Int, dy: Int ->
            if (!prefs.getBoolean("joystick_enabled", true)) {
                val step = (sensitivityValue() / 5).coerceAtLeast(1)
                sendBluetoothCommand("MV", "${dx * step},${dy * step}")
            }
        }

        findViewById<MaterialButton>(R.id.btn_drag_up)?.setOnClickListener { onDpadClick(0, -1) }
        findViewById<MaterialButton>(R.id.btn_drag_down)?.setOnClickListener { onDpadClick(0, 1) }
        findViewById<MaterialButton>(R.id.btn_drag_left)?.setOnClickListener { onDpadClick(-1, 0) }
        findViewById<MaterialButton>(R.id.btn_drag_right)?.setOnClickListener { onDpadClick(1, 0) }
    }

    private fun setupSettings() {
        findViewById<EditText>(R.id.edit_dwell)?.apply {
            setText(prefs.getInt("dwell_period", 500).toString())
            addTextChangedListener(createWatcher("dwell_period", 500))
        }
        findViewById<EditText>(R.id.edit_scroll)?.apply {
            setText(prefs.getInt("scroll_amount", 1).toString())
            addTextChangedListener(createWatcher("scroll_amount", 1))
        }
        findViewById<SwitchCompat>(R.id.switch_joystick)?.apply {
            isChecked = prefs.getBoolean("joystick_enabled", true)
            setOnCheckedChangeListener { _, checked -> prefs.edit { putBoolean("joystick_enabled", checked) } }
        }
        
        findViewById<SwitchCompat>(R.id.switch_dyslexic)?.apply {
            isChecked = prefs.getBoolean("dyslexic_mode", false)
            setOnCheckedChangeListener { _, checked -> 
                prefs.edit { putBoolean("dyslexic_mode", checked) }
                restartActivity()
            }
        }

        findViewById<SwitchCompat>(R.id.switch_colourblind)?.apply {
            isChecked = prefs.getBoolean("colourblind_mode", false)
            setOnCheckedChangeListener { _, checked -> 
                prefs.edit { putBoolean("colourblind_mode", checked) }
                restartActivity()
            }
        }

        findViewById<SeekBar>(R.id.seekbar_sensitivity)?.apply {
            progress = prefs.getInt("sensitivity", 50)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, user: Boolean) { prefs.edit { putInt("sensitivity", p) } }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
    }

    private fun restartActivity() {
        val intent = intent
        intent.putExtra("target_screen", viewFlipper.displayedChild)
        finish()
        startActivity(intent)
        overridePendingTransition(0, 0)
    }

    private fun createWatcher(key: String, def: Int) = object : TextWatcher {
        override fun afterTextChanged(s: Editable?) { 
            val text = s.toString()
            if (text.isNotEmpty()) {
                prefs.edit { putInt(key, text.toIntOrNull() ?: def) }
            }
        }
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
    }

    private fun sendBluetoothCommand(cmd: String, value: String) {
        mouseService?.sendCommand(cmd, value)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        // Physical joystick handling: Only active if Joystick Mode is enabled
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
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {}
        if (isBound) { unbindService(connection); isBound = false }
    }
}
