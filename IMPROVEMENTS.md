# Phomouse: WiFi Architecture & Improvements

The Phomouse app has been refactored to use WiFi (UDP) for signal transmission, providing a faster, more reliable link to the receiver script while maintaining accessibility features.

### 1. WiFi UDP Transmission
**The Challenge:** Bluetooth HID and SPP can be restrictive and prone to interference or pairing issues.
**The Solution:**
* **UDP Protocol:** Signals are sent as lightweight UDP packets to a configurable IP and Port.
* **Easy Decoding:** Signals use a simple string format: `M:dx,dy,scroll,buttons` (e.g., `M:10,-5,0,1`).
* **Low Latency:** UDP avoids the overhead of TCP handshakes, ensuring real-time response.

### 2. "Hands-Off" Input Capture
* **Dual-Mode Capture:** `MainActivity` uses `dispatchGenericMotionEvent` to intercept hardware input (mouse/joystick) from Power Chairs or other HID devices.
* **Seamless Transition:** When Phomouse is in the foreground, it bridges the input to WiFi. When minimized, the chair/mouse controls the Android OS normally.

### 3. Isolated WiFi Service (`WifiMouseService`)
* **Foreground Service:** Runs as a `dataSync` foreground service to prevent the OS from killing the connection during use.
* **Threaded IO:** All networking is handled on a dedicated background executor to keep the UI responsive.

### 4. Input Bridging & Accessibility
* **Throttled Reporting:** Signals are sent at a maximum rate of 100Hz (10ms intervals) to prevent flooding the receiver.
* **Dwell Clicking:** A configurable dwell timer automatically triggers a left click when the joystick is held still after movement.
* **Dynamic UI Scaling:** The interface supports scaling from 0.75x to 1.25x for accessibility.
* **Dyslexic Mode:** Support for specialized fonts and high-contrast themes.

### 5. Modern Permission Model
* Uses `INTERNET` and `ACCESS_WIFI_STATE` permissions.
* Implements `FOREGROUND_SERVICE_DATA_SYNC` for Android 14+ compatibility.
