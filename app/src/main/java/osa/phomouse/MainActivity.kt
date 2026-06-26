package osa.phomouse

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.IOException
import java.io.OutputStream
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var viewFlipper: ViewFlipper
    private lateinit var prefs: SharedPreferences
    
    private val dwellHandler = Handler(Looper.getMainLooper())
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null
    private val dwellRunnable = Runnable {
        sendBluetoothCommand("LB", prefs.getInt("dwell_period", 1000).toString())
    }

    private lateinit var pairedAdapter: DeviceAdapter
    private lateinit var availableAdapter: DeviceAdapter
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    
    private var selectedDevice: DeviceItem? = null

    // Bluetooth serial communication members
    private val executor = Executors.newSingleThreadExecutor()
    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when(intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    device?.let {
                        val deviceClass = it.bluetoothClass?.majorDeviceClass
                        if (deviceClass == android.bluetooth.BluetoothClass.Device.Major.COMPUTER) {
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
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            setupBluetooth()
            loadPairedDevices()
        }
    }

    private var isDragging = false

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("PhomousePrefs", MODE_PRIVATE)
        val isDyslexic = prefs.getBoolean("dyslexic_mode", false)
        val uiScaleProgress = prefs.getInt("ui_scale", 50)

        // Base scale: Atkinson is 1.0, Cadman (Dyslexic) is 0.85
        val baseScale = if (isDyslexic) 0.85f else 1.0f
        // Multiplier: 0.5 to 1.5 based on seekbar (0-100)
        val multiplier = 0.5f + (uiScaleProgress / 100.0f)
        val finalScale = baseScale * multiplier

        val configuration: Configuration = newBase.resources.configuration
        configuration.fontScale = finalScale

        val context = newBase.createConfigurationContext(configuration)
        super.attachBaseContext(context)
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
        refreshJoystickUI()

        // Default screen to Index (0)
        val targetScreen = intent.getIntExtra("target_screen", 0)
        viewFlipper.displayedChild = targetScreen

        checkPermissions()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (viewFlipper.displayedChild) {
                    1, 2, 3, 4 -> {
                        viewFlipper.displayedChild = 0
                        dwellHandler.removeCallbacks(dwellRunnable)
                    }
                    0 -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        autoConnect()
        
        setupWindowInsets()

        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        registerReceiver(bluetoothReceiver, filter)
    }

    private fun setupWindowInsets() {
        val root = findViewById<View>(R.id.screen_settings) ?: return
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, insets.bottom)
            windowInsets
        }
    }

    override fun onResume() {
        super.onResume()
        refreshJoystickUI()
    }

    private fun setupAdapters() {
        pairedAdapter = DeviceAdapter(
            onItemClick = { device -> connectToDevice(device.address, device.name) },
            onInfoClick = { device -> showDeviceInfo(device) }
        )
        availableAdapter = DeviceAdapter(
            onItemClick = { device -> connectToDevice(device.address, device.name) },
            onInfoClick = { device -> showDeviceInfo(device) }
        )
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(deviceAddress: String, deviceName: String) {
        prefs.edit { putString("last_bt_device", deviceAddress) }
        viewFlipper.displayedChild = 2 // Move to controller
        updateStatusBar("Connecting to $deviceName...")

        executor.execute {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@execute
            val device: BluetoothDevice = adapter.getRemoteDevice(deviceAddress)
            
            try {
                disconnectInternal()
                bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                bluetoothSocket?.connect()
                outputStream = bluetoothSocket?.outputStream
                Log.d("MainActivity", "Connected to Bluetooth device: $deviceAddress")
                runOnUiThread { updateStatusBar("Connected to $deviceName") }
            } catch (e: IOException) {
                Log.e("MainActivity", "Connection failed", e)
                disconnectInternal()
                runOnUiThread { updateStatusBar("Connection failed") }
            }
        }
    }

    private fun disconnectInternal() {
        try {
            bluetoothSocket?.close()
        } catch (e: IOException) {
            Log.e("MainActivity", "Error closing socket", e)
        }
        bluetoothSocket = null
        outputStream = null
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
        val devices = pairedDevices
            .filter { it.bluetoothClass?.majorDeviceClass == android.bluetooth.BluetoothClass.Device.Major.COMPUTER }
            .map { DeviceItem(it.name ?: "Unknown", it.address, true) }
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return
        }
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
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                bluetoothAdapter?.cancelDiscovery()
            }
            viewFlipper.displayedChild = 0 
        }
    }

    private fun showDeviceInfo(device: DeviceItem) {
        selectedDevice = device
        findViewById<TextView>(R.id.tv_info_device_name)?.text = device.name
        findViewById<TextView>(R.id.tv_info_address)?.text = device.address
        
        val statusTv = findViewById<TextView>(R.id.tv_info_status)
        statusTv?.text = if (device.isPaired) "Paired" else "Available"
        
        if (device.isPaired && !prefs.getBoolean("colourblind_mode", false)) {
            statusTv?.setTextColor(ContextCompat.getColor(this, R.color.success))
        } else if (!device.isPaired) {
            statusTv?.setTextColor(ContextCompat.getColor(this, R.color.error))
        } else {
            statusTv?.setTextColor(ContextCompat.getColor(this, R.color.black))
        }
        
        viewFlipper.displayedChild = 3 // Info screen
    }

    private fun setupInfoScreen() {
        findViewById<ImageButton>(R.id.btn_home_info)?.setOnClickListener { viewFlipper.displayedChild = 0 }
        findViewById<ImageButton>(R.id.btn_settings_info)?.setOnClickListener { viewFlipper.displayedChild = 4 }

        findViewById<View>(R.id.btn_retry)?.setOnClickListener {
            selectedDevice?.let { device ->
                connectToDevice(device.address, device.name)
            }
        }

        findViewById<View>(R.id.btn_forget)?.setOnClickListener {
            selectedDevice?.let { device ->
                // Attempt to unpair (remove bond)
                try {
                    val btDevice = bluetoothAdapter?.getRemoteDevice(device.address)
                    val method = btDevice?.javaClass?.getMethod("removeBond")
                    method?.invoke(btDevice)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed to remove bond", e)
                }

                // Clear from preferences if it was the last device
                val lastDevice = prefs.getString("last_bt_device", null)
                if (lastDevice == device.address) {
                    prefs.edit { remove("last_bt_device") }
                }
                
                // If it's the currently connected device, disconnect it
                if (bluetoothSocket?.remoteDevice?.address == device.address) {
                    disconnectInternal()
                    updateStatusBar("Disconnected from ${device.name}")
                }
                
                // Navigate back
                viewFlipper.displayedChild = 0
                // Refresh list
                loadPairedDevices()
                
                android.widget.Toast.makeText(this, "Device forgotten", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun autoConnect() {
        val lastDevice = prefs.getString("last_bt_device", null)
        if (lastDevice != null) {
            connectToDevice(lastDevice, "Saved Device")
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
            dwellHandler.removeCallbacks(dwellRunnable)
        }
        findViewById<ImageButton>(R.id.btn_settings_controller)?.setOnClickListener { 
            viewFlipper.displayedChild = 4 
            dwellHandler.removeCallbacks(dwellRunnable)
        }
        findViewById<ImageButton>(R.id.btn_back_settings)?.setOnClickListener { 
            viewFlipper.displayedChild = 2 // Return to controller
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupControllerButtons() {
        val dwellValue = { prefs.getInt("dwell_period", 1000).toString() }
        val scrollValue = { prefs.getInt("scroll_amount", 50).toString() }
        val sensitivityValue = { prefs.getInt("sensitivity", 50) }

        val btnLeft = findViewById<MaterialButton>(R.id.btn_left_click)
        
        btnLeft?.setOnClickListener {
            sendBluetoothCommand("LB", dwellValue())
        }

        findViewById<MaterialButton>(R.id.btn_right_click)?.setOnClickListener { 
            if (!isDragging) sendBluetoothCommand("RB", dwellValue()) 
        }
        findViewById<MaterialButton>(R.id.btn_middle_click)?.setOnClickListener { 
            if (!isDragging) sendBluetoothCommand("MB", dwellValue()) 
        }
        findViewById<MaterialButton>(R.id.btn_double_click)?.setOnClickListener {
            if (!isDragging) {
                sendBluetoothCommand("LB", "0")
                it.postDelayed({ sendBluetoothCommand("LB", "0") }, 100)
            }
        }
        findViewById<MaterialButton>(R.id.btn_scroll_up)?.setOnClickListener {
            if (!isDragging) sendBluetoothCommand("SU", scrollValue())
        }
        findViewById<MaterialButton>(R.id.btn_scroll_down)?.setOnClickListener {
            if (!isDragging) sendBluetoothCommand("SD", scrollValue())
        }
        
        val btnGrab = findViewById<MaterialButton>(R.id.btn_grab)
        btnGrab?.setOnClickListener {
            isDragging = !isDragging
            if (isDragging) {
                sendBluetoothCommand("DS", sensitivityValue().toString())
                btnGrab.setText(R.string.btn_release)
            } else {
                sendBluetoothCommand("DR", "0")
                btnGrab.setText(R.string.btn_grab)
            }
        }

        // D-pad Movement: Now supports repeating and acceleration when held
        setupDpadButton(R.id.btn_drag_up, 0, -1)
        setupDpadButton(R.id.btn_drag_down, 0, 1)
        setupDpadButton(R.id.btn_drag_left, -1, 0)
        setupDpadButton(R.id.btn_drag_right, 1, 0)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDpadButton(id: Int, dx: Int, dy: Int) {
        val btn = findViewById<MaterialButton>(id)
        btn?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dwellHandler.removeCallbacks(dwellRunnable)
                    repeatHandler.removeCallbacks(repeatRunnable ?: return@setOnTouchListener true)
                    
                    repeatRunnable = object : Runnable {
                        override fun run() {
                            val sensitivity = prefs.getInt("sensitivity", 50).coerceAtLeast(25)
                            // Send smaller, more frequent increments for smoothness
                            // We divide by 5 to make it finer, but run it every 20ms
                            if (dx != 0) sendBluetoothCommand("MX", (dx * sensitivity / 5).toString())
                            if (dy != 0) sendBluetoothCommand("MY", (dy * sensitivity / 5).toString())
                            repeatHandler.postDelayed(this, 20)
                        }
                    }
                    repeatHandler.post(repeatRunnable!!)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    repeatHandler.removeCallbacks(repeatRunnable ?: return@setOnTouchListener true)
                    val dwell = prefs.getInt("dwell_period", 1000).toLong()
                    if (dwell > 0) {
                        dwellHandler.postDelayed(dwellRunnable, dwell)
                    }
                    true
                }
                else -> false
            }
        }
    }


    private fun setupSettings() {
        // Help icon explanations
        val helpTexts = mapOf(
            R.id.help_dwell to "Wait time before auto-click.",
            R.id.help_scroll to "Scroll distance per click.",
            R.id.help_ui_scale to "Size of buttons/text.",
            R.id.help_sensitivity to "Movement & drag speed.",
            R.id.help_joystick to getString(R.string.help_joystick)
        )
        helpTexts.forEach { (id, text) ->
            findViewById<View>(id)?.setOnClickListener {
                android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<EditText>(R.id.edit_dwell)?.apply {
            val dwellMs = prefs.getInt("dwell_period", 1000)
            setText((dwellMs / 1000f).toString())
            addTextChangedListener(createWatcher("dwell_period", 1000, true))
        }
        findViewById<EditText>(R.id.edit_scroll)?.apply {
            setText(prefs.getInt("scroll_amount", 50).toString())
            addTextChangedListener(createWatcher("scroll_amount", 50))
        }
        findViewById<SwitchCompat>(R.id.switch_joystick)?.apply {
            isChecked = prefs.getBoolean("joystick_enabled", true)
            setOnCheckedChangeListener { _, checked -> 
                prefs.edit { putBoolean("joystick_enabled", checked) }
                refreshJoystickUI()
            }
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
            val currentSens = prefs.getInt("sensitivity", 50)
            progress = currentSens
            val tvSens = findViewById<TextView>(R.id.tv_sensitivity_value)
            tvSens?.text = "$currentSens%"
            
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, user: Boolean) { 
                    val val25 = p.coerceAtLeast(25)
                    tvSens?.text = "$val25%"
                    prefs.edit { putInt("sensitivity", val25) } 
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

        findViewById<SeekBar>(R.id.seekbar_ui_scale)?.apply {
            val currentScale = prefs.getInt("ui_scale", 50)
            progress = currentScale
            val tvScale = findViewById<TextView>(R.id.tv_ui_scale_value)
            tvScale?.text = "$currentScale%"
            
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, user: Boolean) { 
                    tvScale?.text = "$p%"
                    prefs.edit { putInt("ui_scale", p) }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    restartActivity()
                }
            })
        }
    }

    private fun refreshJoystickUI() {
        val joystickEnabled = prefs.getBoolean("joystick_enabled", true)
        val container = findViewById<android.widget.LinearLayout>(R.id.controller_button_container) ?: return
        
        val btnLeft = findViewById<View>(R.id.btn_left_click)
        val rowClicks = findViewById<View>(R.id.row_clicks)
        val rowScroll = findViewById<View>(R.id.row_scroll)
        val dpadContainer = findViewById<View>(R.id.dpad_container)

        // Clear and re-add in desired order
        container.removeAllViews()

        if (joystickEnabled) {
            // Joystick Mode: D-pad at top
            dpadContainer?.let { 
                it.visibility = View.VISIBLE
                container.addView(it) 
            }
            rowScroll?.let { container.addView(it) }
            rowClicks?.let { container.addView(it) }
            btnLeft?.visibility = View.GONE
        } else {
            // Default Mode: Scroll -> Clicks -> Left Click -> D-pad
            rowScroll?.let { container.addView(it) }
            rowClicks?.let { container.addView(it) }
            btnLeft?.let { 
                it.visibility = View.VISIBLE
                container.addView(it)
            }
            dpadContainer?.let { 
                it.visibility = View.VISIBLE
                container.addView(it) 
            }
        }
        
        // Enlarge D-pad in Joystick Mode and apply UI Scale
        val uiScaleProgress = prefs.getInt("ui_scale", 50)
        val multiplier = 0.5f + (uiScaleProgress / 100.0f)
        val baseSize = if (joystickEnabled) 90 else 60
        val sizePx = (baseSize * resources.displayMetrics.density * multiplier).toInt()
        
        val dpadButtons = listOf(
            R.id.btn_drag_up, R.id.btn_drag_down, R.id.btn_drag_left, R.id.btn_drag_right, R.id.btn_grab,
            R.id.space1, R.id.space2, R.id.space3, R.id.space4
        )
        
        dpadButtons.forEach { id ->
            findViewById<View>(id)?.layoutParams = findViewById<View>(id)?.layoutParams?.apply {
                width = sizePx
                height = sizePx
            }
        }
    }


    private fun restartActivity() {
        val intent = intent
        intent.putExtra("target_screen", viewFlipper.displayedChild)
        finish()
        startActivity(intent)
        overridePendingTransition(0, 0)
    }

    private fun createWatcher(key: String, def: Int, isDwell: Boolean = false) = object : TextWatcher {
        override fun afterTextChanged(s: Editable?) { 
            val text = s.toString()
            if (text.isNotEmpty()) {
                if (isDwell) {
                    val seconds = text.toFloatOrNull() ?: (def / 1000f)
                    prefs.edit { putInt(key, (seconds * 1000).toInt()) }
                } else {
                    prefs.edit { putInt(key, text.toIntOrNull() ?: def) }
                }
            }
        }
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
    }

    private fun sendBluetoothCommand(cmd: String, value: String) {
        val message = "PMCMD:[$cmd]-{$value}\n"
        executor.execute {
            try {
                outputStream?.write(message.toByteArray())
                outputStream?.flush()
            } catch (e: IOException) {
                Log.e("MainActivity", "Failed to send command: $message", e)
            }
        }
    }



    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (_: Exception) {}
        disconnectInternal()
        repeatHandler.removeCallbacksAndMessages(null)
        executor.shutdown()
    }
}
