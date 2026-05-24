package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.InputDevice
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

enum class Screen(val index: Int) {
    INDEX(0),
    ADD(1),
    CONTROLLER(2),
    INFO(3),
    SETTINGS(4)
}

class MainActivity : AppCompatActivity() {

    private lateinit var sharedPrefs: SharedPreferences
    private val navigationStack = mutableListOf<Screen>()

    private lateinit var appViewFlipper: ViewFlipper
    
    // Lists adapters
    private lateinit var pairedAdapter: DeviceAdapter
    private lateinit var availableAdapter: DeviceAdapter

    // Bluetooth scanning/seed states
    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())
    private var lastGenericMotionTime = 0L

    // For selected device information
    private var selectedDevice: DeviceItem? = null

    // For drag toggle
    private var isDragging = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Load settings to apply accessibility theme
        sharedPrefs = getSharedPreferences("PhomousePrefs", Context.MODE_PRIVATE)
        val isDyslexic = sharedPrefs.getBoolean("dyslexic_mode", false)
        val isColourblind = sharedPrefs.getBoolean("colourblind_mode", false)

        val activeTheme = when {
            isDyslexic && isColourblind -> R.style.Theme_Phomouse_Dyslexic_Colourblind
            isDyslexic -> R.style.Theme_Phomouse_Dyslexic
            isColourblind -> R.style.Theme_Phomouse_Colourblind
            else -> R.style.Theme_Phomouse
        }
        setTheme(activeTheme)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        appViewFlipper = findViewById(R.id.app_view_flipper)

        // Sync connection manger variables with configurations
        BluetoothConnectionManager.joystickMode = sharedPrefs.getBoolean("joystick_mode", false)
        BluetoothConnectionManager.actionSensitivity = sharedPrefs.getInt("action_sensitivity", 10)
        BluetoothConnectionManager.dwellPeriodMs = sharedPrefs.getLong("dwell_period_ms", 1500L)
        BluetoothConnectionManager.scrollAmount = sharedPrefs.getInt("scroll_amount", 3)

        // Restore current screen
        val savedScreenIndex = savedInstanceState?.getInt("flipper_displayed_child") 
            ?: sharedPrefs.getInt("last_visible_screen", Screen.INDEX.index)
        appViewFlipper.displayedChild = savedScreenIndex

        // Setup UI Scale
        val uiScaleProgress = sharedPrefs.getInt("ui_scale", 50)
        applyUiScale(uiScaleProgress)

        // Check/request modern permissions
        checkBluetoothPermissions()

        // Init screen modules
        initIndexScreen()
        initAddScreen()
        initControllerScreen()
        initInfoScreen()
        initSettingsScreen()

        // Setup connection monitor
        observeConnectionState()
        
        // Setup logger to capture events
        setupCommandLogger()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("flipper_displayed_child", appViewFlipper.displayedChild)
    }

    private fun navigateTo(screen: Screen) {
        val current = appViewFlipper.displayedChild
        if (screen == Screen.INDEX) {
            navigationStack.clear()
        } else {
            val currentScreen = Screen.values().find { it.index == current }
            if (currentScreen != null && currentScreen != screen) {
                navigationStack.add(currentScreen)
            }
        }
        appViewFlipper.displayedChild = screen.index
        sharedPrefs.edit().putInt("last_visible_screen", screen.index).apply()
    }

    private fun navigateBack() {
        if (navigationStack.isNotEmpty()) {
            val prev = navigationStack.removeAt(navigationStack.size - 1)
            appViewFlipper.displayedChild = prev.index
            sharedPrefs.edit().putInt("last_visible_screen", prev.index).apply()
        } else {
            appViewFlipper.displayedChild = Screen.INDEX.index
            sharedPrefs.edit().putInt("last_visible_screen", Screen.INDEX.index).apply()
        }
    }

    // Capture generic motion events (e.g., connected hardware wheelchair joystick, mouse, etc.)
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_CLASS_JOYSTICK != 0 &&
            event.action == MotionEvent.ACTION_MOVE
        ) {
            val now = System.currentTimeMillis()
            if (now - lastGenericMotionTime > 50) {
                lastGenericMotionTime = now
                val axisX = event.getAxisValue(MotionEvent.AXIS_X)
                val axisY = event.getAxisValue(MotionEvent.AXIS_Y)
                
                val sensitivity = BluetoothConnectionManager.actionSensitivity
                val scaleFactor = sensitivity * 3 // amplify sensitivity for joystick translation
                val moveX = (axisX * scaleFactor).toInt()
                val moveY = (axisY * scaleFactor).toInt()
                
                if (moveX != 0 || moveY != 0) {
                    val commands = mutableListOf<String>()
                    if (moveX != 0) commands.add("PMCMD:MX-$moveX")
                    if (moveY != 0) commands.add("PMCMD:MY-$moveY")
                    
                    BluetoothConnectionManager.sendCommand(commands.joinToString(","))
                    BluetoothConnectionManager.reportMovement()
                }
            }
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    // Monitor SPP socket connections
    private fun observeConnectionState() {
        val statusBar = findViewById<TextView>(R.id.status_bar)
        val tvInfoStatus = findViewById<TextView>(R.id.tv_info_status)

        lifecycleScope.launchWhenStarted {
            BluetoothConnectionManager.connectionState.collectLatest { state ->
                when (state) {
                    is ConnectionState.Disconnected -> {
                        statusBar.text = "Not Connected"
                        statusBar.setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.error))
                        statusBar.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.white))
                        tvInfoStatus.text = "Disconnected"
                        tvInfoStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.error))
                    }
                    is ConnectionState.Connecting -> {
                        statusBar.text = "Connecting..."
                        statusBar.setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.primary))
                        statusBar.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.black))
                        tvInfoStatus.text = "Connecting..."
                        tvInfoStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.primary))
                    }
                    is ConnectionState.Connected -> {
                        val displayName = if (state.isMock) "${state.deviceName} (Mock)" else state.deviceName
                        statusBar.text = "Connected to $displayName"
                        statusBar.setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
                        statusBar.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.black))
                        tvInfoStatus.text = if (state.isMock) "Connected (Simulation)" else "Connected"
                        tvInfoStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.success))
                    }
                }
            }
        }
    }

    private fun setupCommandLogger() {
        lifecycleScope.launchWhenStarted {
            BluetoothConnectionManager.commandLogFlow.collect { cmd ->
                Toast.makeText(this@MainActivity, "SPP Sent: $cmd", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // SCREEN 1: Paired device screens (Home)
    private fun initIndexScreen() {
        val rvPairedDevices = findViewById<RecyclerView>(R.id.rv_paired_devices)
        rvPairedDevices.layoutManager = LinearLayoutManager(this)
        
        pairedAdapter = DeviceAdapter(
            devices = getPairedDevicesSeeds(),
            onInfoClick = { item ->
                selectedDevice = item
                navigateTo(Screen.INFO)
            },
            onItemClick = { item ->
                selectedDevice = item
                connectDevice(item)
            }
        )
        rvPairedDevices.adapter = pairedAdapter

        findViewById<ImageButton>(R.id.btn_settings_index).setOnClickListener { navigateTo(Screen.SETTINGS) }
        findViewById<FloatingActionButton>(R.id.fab_add).setOnClickListener { navigateTo(Screen.ADD) }
    }

    // SCREEN 2: Discoverable device scanner
    private fun initAddScreen() {
        val rvAvailableDevices = findViewById<RecyclerView>(R.id.rv_available_devices)
        rvAvailableDevices.layoutManager = LinearLayoutManager(this)
        
        availableAdapter = DeviceAdapter(
            devices = emptyList(),
            onItemClick = { item ->
                selectedDevice = item
                connectDevice(item)
            }
        )
        rvAvailableDevices.adapter = availableAdapter

        findViewById<ImageButton>(R.id.btn_home_add).setOnClickListener { navigateBack() }

        // Start scanning simulation
        startScanningSimulation()
    }

    private fun startScanningSimulation() {
        isScanning = true
        val titleAvailable = findViewById<TextView>(R.id.title_available)
        titleAvailable.text = "Scanning Available Devices..."
        availableAdapter.updateData(emptyList())

        handler.postDelayed({
            if (isScanning) {
                val found = listOf(
                    DeviceItem("PowerChair Joystick-A", "4E:B9:FD:01:23:45", false),
                    DeviceItem("My Assistive Stick v2", "0B:FE:C8:9A:BC:DE", false),
                    DeviceItem("AAC Smart Access Pad", "AA:BB:CC:DD:EE:FF", false),
                    DeviceItem("Personal PC Host", "58:9B:0C:67:89:01", false)
                )
                availableAdapter.updateData(found)
                titleAvailable.text = "Available Devices"
            }
        }, 1500)
    }

    // SCREEN 3: Master mouse controller screen
    @SuppressLint("ClickableViewAccessibility")
    private fun initControllerScreen() {
        findViewById<ImageButton>(R.id.btn_home_controller).setOnClickListener { navigateTo(Screen.INDEX) }
        findViewById<ImageButton>(R.id.btn_settings_controller).setOnClickListener { navigateTo(Screen.SETTINGS) }

        // LEFT CLICK BUTTON - Handles virtual joystick commands and simple left click trigger on release
        var touchStartX = 0f
        var touchStartY = 0f
        var lastTouchMsgTime = 0L

        val btnLeftClick = findViewById<MaterialButton>(R.id.btn_left_click)
        btnLeftClick.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.x
                    touchStartY = event.y
                    lastTouchMsgTime = System.currentTimeMillis()
                    BluetoothConnectionManager.reportMovement()
                    v.performClick()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - touchStartX
                    val dy = event.y - touchStartY
                    val now = System.currentTimeMillis()
                    
                    if (now - lastTouchMsgTime > 60) {
                        lastTouchMsgTime = now
                        val sensitivity = BluetoothConnectionManager.actionSensitivity
                        val scale = 0.05f * sensitivity
                        val moveX = (dx * scale).toInt()
                        val moveY = (dy * scale).toInt()
                        
                        if (moveX != 0 || moveY != 0) {
                            val commands = mutableListOf<String>()
                            if (moveX != 0) commands.add("PMCMD:MX-$moveX")
                            if (moveY != 0) commands.add("PMCMD:MY-$moveY")
                            
                            BluetoothConnectionManager.sendCommand(commands.joinToString(","))
                            BluetoothConnectionManager.reportMovement()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    BluetoothConnectionManager.sendCommand("PMCMD:LB-0")
                    BluetoothConnectionManager.reportMovement()
                    true
                }
                else -> false
            }
        }

        // Action button registers
        findViewById<MaterialButton>(R.id.btn_right_click).setOnClickListener {
            BluetoothConnectionManager.sendCommand("PMCMD:RB-0")
        }
        findViewById<MaterialButton>(R.id.btn_middle_click).setOnClickListener {
            BluetoothConnectionManager.sendCommand("PMCMD:MB-0")
        }
        findViewById<MaterialButton>(R.id.btn_double_click).setOnClickListener {
            BluetoothConnectionManager.sendCommand("PMCMD:LB-0,PMCMD:LB-0")
        }
        findViewById<MaterialButton>(R.id.btn_scroll_up).setOnClickListener {
            BluetoothConnectionManager.sendCommand("PMCMD:SU-${BluetoothConnectionManager.scrollAmount}")
        }
        findViewById<MaterialButton>(R.id.btn_scroll_down).setOnClickListener {
            BluetoothConnectionManager.sendCommand("PMCMD:SD-${BluetoothConnectionManager.scrollAmount}")
        }

        // Drag pad clickers
        val btnGrab = findViewById<MaterialButton>(R.id.btn_grab)
        val labelTapGrab = findViewById<TextView>(R.id.label_tap_grab)

        btnGrab.setOnClickListener {
            isDragging = !isDragging
            if (isDragging) {
                btnGrab.text = "Drop"
                labelTapGrab.text = "Dragging... Tap to Drop"
                BluetoothConnectionManager.sendCommand("PMCMD:DS-${BluetoothConnectionManager.actionSensitivity}")
            } else {
                btnGrab.text = "Grab"
                labelTapGrab.text = "Grab to Start Drag"
                BluetoothConnectionManager.sendCommand("PMCMD:DR-0")
            }
        }

        findViewById<MaterialButton>(R.id.btn_drag_up).setOnClickListener {
            val amt = BluetoothConnectionManager.actionSensitivity * 3
            BluetoothConnectionManager.sendCommand("PMCMD:MY--$amt")
            BluetoothConnectionManager.reportMovement()
        }
        findViewById<MaterialButton>(R.id.btn_drag_down).setOnClickListener {
            val amt = BluetoothConnectionManager.actionSensitivity * 3
            BluetoothConnectionManager.sendCommand("PMCMD:MY-$amt")
            BluetoothConnectionManager.reportMovement()
        }
        findViewById<MaterialButton>(R.id.btn_drag_left).setOnClickListener {
            val amt = BluetoothConnectionManager.actionSensitivity * 3
            BluetoothConnectionManager.sendCommand("PMCMD:MX--$amt")
            BluetoothConnectionManager.reportMovement()
        }
        findViewById<MaterialButton>(R.id.btn_drag_right).setOnClickListener {
            val amt = BluetoothConnectionManager.actionSensitivity * 3
            BluetoothConnectionManager.sendCommand("PMCMD:MX-$amt")
            BluetoothConnectionManager.reportMovement()
        }
    }

    // SCREEN 4: Selected device specifications view
    private fun initInfoScreen() {
        findViewById<ImageButton>(R.id.btn_home_info).setOnClickListener { navigateTo(Screen.INDEX) }
        findViewById<ImageButton>(R.id.btn_settings_info).setOnClickListener { navigateTo(Screen.SETTINGS) }

        val tvInfoDeviceName = findViewById<TextView>(R.id.tv_info_device_name)
        tvInfoDeviceName.text = "---"

        findViewById<View>(R.id.btn_retry).setOnClickListener {
            selectedDevice?.let { item ->
                connectDevice(item)
            }
        }

        findViewById<View>(R.id.btn_forget).setOnClickListener {
            selectedDevice?.let { item ->
                Toast.makeText(this, "Device ${item.name} Forgotten.", Toast.LENGTH_SHORT).show()
                BluetoothConnectionManager.disconnect()
                navigateTo(Screen.INDEX)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        selectedDevice?.let { item ->
            findViewById<TextView>(R.id.tv_info_device_name).text = item.name
        }
    }

    // SCREEN 5: Configurations and accessibility options parameters page
    private fun initSettingsScreen() {
        findViewById<ImageButton>(R.id.btn_back_settings).setOnClickListener { navigateBack() }

        val switchJoystick = findViewById<SwitchCompat>(R.id.switch_joystick)
        val switchDyslexic = findViewById<SwitchCompat>(R.id.switch_dyslexic)
        val switchColourblind = findViewById<SwitchCompat>(R.id.switch_colourblind)

        val seekbarUiScale = findViewById<SeekBar>(R.id.seekbar_ui_scale)
        val seekbarSensitivity = findViewById<SeekBar>(R.id.seekbar_sensitivity)

        val editDwell = findViewById<EditText>(R.id.edit_dwell)
        val editScroll = findViewById<EditText>(R.id.edit_scroll)

        // Setup switch states
        switchJoystick.isChecked = sharedPrefs.getBoolean("joystick_mode", false)
        switchDyslexic.isChecked = sharedPrefs.getBoolean("dyslexic_mode", false)
        switchColourblind.isChecked = sharedPrefs.getBoolean("colourblind_mode", false)

        // Setup Seekbars
        seekbarUiScale.progress = sharedPrefs.getInt("ui_scale", 50)
        seekbarSensitivity.progress = sharedPrefs.getInt("action_sensitivity", 10) * 5

        // Edits inputs values
        editDwell.setText((BluetoothConnectionManager.dwellPeriodMs / 1000f).toString())
        editScroll.setText(BluetoothConnectionManager.scrollAmount.toString())

        // Setup interactive listeners
        switchJoystick.setOnCheckedChangeListener { _, isChecked ->
            sharedPrefs.edit().putBoolean("joystick_mode", isChecked).apply()
            BluetoothConnectionManager.joystickMode = isChecked
        }

        switchDyslexic.setOnCheckedChangeListener { _, isChecked ->
            sharedPrefs.edit().putBoolean("dyslexic_mode", isChecked).apply()
            recreate()
        }

        switchColourblind.setOnCheckedChangeListener { _, isChecked ->
            sharedPrefs.edit().putBoolean("colourblind_mode", isChecked).apply()
            recreate()
        }

        seekbarSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val sensValue = Math.max(1, progress / 5)
                BluetoothConnectionManager.actionSensitivity = sensValue
                sharedPrefs.edit().putInt("action_sensitivity", sensValue).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekbarUiScale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                sharedPrefs.edit().putInt("ui_scale", progress).apply()
                applyUiScale(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Text inputs watchers
        editDwell.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val input = s?.toString()?.toFloatOrNull()
                if (input != null && input > 0) {
                    val ms = (input * 1000).toLong()
                    BluetoothConnectionManager.dwellPeriodMs = ms
                    sharedPrefs.edit().putLong("dwell_period_ms", ms).apply()
                }
            }
        })

        editScroll.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val input = s?.toString()?.toIntOrNull()
                if (input != null && input > 0) {
                    BluetoothConnectionManager.scrollAmount = input
                    sharedPrefs.edit().putInt("scroll_amount", input).apply()
                }
            }
        })

        // Tooltips helper click listeners
        findViewById<ImageView>(R.id.help_dwell).setOnClickListener {
            Toast.makeText(this, "Stabilize coordinate details for dwell span to produce left click", Toast.LENGTH_LONG).show()
        }
        findViewById<ImageView>(R.id.help_scroll).setOnClickListener {
            Toast.makeText(this, "Increments dispatched per mouse scroll", Toast.LENGTH_LONG).show()
        }
        findViewById<ImageView>(R.id.help_ui_scale).setOnClickListener {
            Toast.makeText(this, "Display multiplier for layout blocks", Toast.LENGTH_LONG).show()
        }
        findViewById<ImageView>(R.id.help_sensitivity).setOnClickListener {
            Toast.makeText(this, "Multiply joystick scale translation amounts", Toast.LENGTH_LONG).show()
        }
    }

    private fun applyUiScale(progress: Int) {
        val scale = 0.8f + (progress / 100f) * 0.4f
        appViewFlipper.apply {
            pivotX = resources.displayMetrics.widthPixels / 2f
            pivotY = resources.displayMetrics.heightPixels / 4f
            scaleX = scale
            scaleY = scale
        }
    }

    private fun connectDevice(item: DeviceItem) {
        val intent = Intent(this, BluetoothSppService::class.java).apply {
            action = "ACTION_CONNECT"
            putExtra("device_address", item.address)
            putExtra("device_name", item.name)
            putExtra("force_mock", item.address == "MOCK_MAC_ADDRESS" || item.address == "MOCK_MAC_ADDRESS_2")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        navigateTo(Screen.CONTROLLER)
    }

    // Seeding dynamic devices so that lists are never empty
    private fun getPairedDevicesSeeds(): List<DeviceItem> {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        val resultList = mutableListOf<DeviceItem>()

        if (adapter != null && adapter.isEnabled && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            val bonded = adapter.bondedDevices
            if (!bonded.isNullOrEmpty()) {
                bonded.forEach { device ->
                    resultList.add(DeviceItem(device.name ?: "Unknown SPP Device", device.address, true))
                }
            }
        }
        
        if (resultList.isEmpty()) {
            resultList.add(DeviceItem("PC Master (Accessibility link)", "MOCK_MAC_ADDRESS", true))
            resultList.add(DeviceItem("Adaptive Desk PC Hub", "MOCK_MAC_ADDRESS_2", true))
        }
        return resultList
    }

    private fun checkBluetoothPermissions() {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            list.add(Manifest.permission.ACCESS_FINE_LOCATION)
            list.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        
        val needed = list.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 102)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isScanning = false
        handler.removeCallbacksAndMessages(null)
    }
}

// Global Device Adapter
class DeviceAdapter(
    private var devices: List<DeviceItem>,
    private val onInfoClick: ((DeviceItem) -> Unit)? = null,
    private val onItemClick: (DeviceItem) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

    class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        val tvDeviceName: TextView = view.findViewById(R.id.tv_device_name)
        val btnDeviceInfo: ImageButton = view.findViewById(R.id.btn_device_info)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = devices[position]
        holder.tvDeviceName.text = item.name
        holder.itemView.setOnClickListener {
            onItemClick(item)
        }
        if (onInfoClick != null) {
            holder.btnDeviceInfo.visibility = View.VISIBLE
            holder.btnDeviceInfo.setOnClickListener {
                onInfoClick.invoke(item)
            }
        } else {
            holder.btnDeviceInfo.visibility = View.GONE
        }
    }

    override fun getItemCount(): Int = devices.size

    fun updateData(newDevices: List<DeviceItem>) {
        devices = newDevices
        notifyDataSetChanged()
    }
}

data class DeviceItem(val name: String, val address: String, val isPaired: Boolean)

