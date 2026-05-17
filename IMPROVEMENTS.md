### 1. The Core Contradiction: SPP vs. Standard Android Mouse
**How to fix it:**
* **Do not use SPP for the Chair:** If you want the chair to act as a normal mouse for the phone in the background, **let the Android OS handle the connection to the chair**. Remove the power chair from the app's internal "Variable Tracking / Auto-Reconnect" serial logic entirely.
* Your `MainActivity` Dual Mode Capture (`dispatchGenericMotionEvent`) is actually the perfect setup! When the app is in the foreground, `MainActivity` intercepts the Android mouse movements and sends them to the PC. When the app is in the background, `MainActivity` is paused, it stops intercepting, and the chair goes back to controlling your phone. You don't need SPP for this to work.

### 2. The Bluetooth Radio Collision (HID Registration vs. SPP)
**How to fix it:**
* **Sequence the Connections:** Never attempt an SPP connection or an auto-reconnect while `registerApp` is firing.
* Wait for the `onAppStatusChanged()` callback to return `true` (meaning the PC HID profile is fully registered and stable) **before** you allow the `MouseService` to initiate any SPP connections for secondary serial devices.

### 3. The "Variable Tracking" & Auto-Reconnect Loop
**How to fix it:**
* **Implement Exponential Backoff:** If a serial connection drops, do not immediately reconnect. Put the reconnection logic in a separate background thread with a delay (e.g., wait 3 seconds, then 5 seconds, then 10 seconds).
* Check the socket state properly. Ensure you call `socket.close()` on the dropped connection before trying to instantiate a new `createRfcommSocketToServiceRecord`.

### 4. Background Service Constraints
**How to fix it:**
* **Threading:** Ensure your `connectSerialDevice` runs inside a standard Java `Thread`, `ExecutorService`, or Kotlin `Coroutine` (using `Dispatchers.IO`).
* **Permissions Check:** Ensure your app has `BLUETOOTH_CONNECT` and `BLUETOOTH_SCAN`. More importantly, ensure your Foreground Service in the `AndroidManifest.xml` includes `foregroundServiceType="connectedDevice"`.

### Summary of the Corrected Architecture
To achieve your exact requirements safely, your app flow should look like this:

1. **Bluetooth Setup Phase:**
    * The app launches. The Foreground Service starts.
    * The phone's Bluetooth connects to the PC via `BluetoothHidDevice.registerApp()`.
    * **Wait** for the success callback. Do not attempt any other Bluetooth operations yet.
2. **Local Input Phase:**
    * **Power Chair (HID):** Handled entirely by the Android OS. It connects as a standard mouse. Your app does *not* try to connect to it via code.
    * **Other Serial Devices (SPP):** If you have *other* hardware that strictly uses SPP, the Foreground Service now spins up an IO thread to connect them using `connectSerialDevice` and exponential backoff.
3. **Control Phase (Foreground):**
    * You open the app. `MainActivity` is on screen.
    * You move the power chair joystick. Android receives this natively. `MainActivity.dispatchGenericMotionEvent` intercepts the movement.
    * `MainActivity` applies your **UI Scale**, **Action Sensitivity**, and **Scroll Amount** preferences.
    * It throttles the data to 10ms (100 reports/sec) and shoots it over the PC HID connection.
    * Movement stops. The **Dwell Period** timer starts. Once elapsed, it fires a click to the PC.
4. **Phone Control Phase (Background):**
    * You press the home button. `MainActivity` is paused.
    * The PC connection remains alive in the Foreground Service.
    * Because `MainActivity` is no longer intercepting inputs, the power chair acts as a standard Android mouse, allowing you to use your phone's apps natively.
