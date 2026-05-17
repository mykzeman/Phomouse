### 1. The Core Architecture: WiFi (UDP) Mouse Bridge
**How it works:**
* **UDP Transport:** The app now sends mouse signals over WiFi using UDP packets on port 5555. This removes the complexities of Bluetooth HID registration and SPP collisions.
* **Easy Decoding:** Signals are sent as simple CSV strings: `buttons,dx,dy,wheel\n`. This allows any receiver script (Python, Node.js, etc.) to decode the signals with a single `split(',')` call.

### 2. "Hands-Off" Input Device Strategy
**How to fix it:**
* **OS-Level Input:** The app no longer manages connections to the Power Chair or other joysticks. Let the Android OS handle them. 
* **Native Interception:** `MainActivity.dispatchGenericMotionEvent` intercepts the native Android mouse/joystick movement. This means as long as the device works with Android, it works with Phomouse.
* **Dual Mode:** When Phomouse is in the foreground, it "captures" the mouse to bridge it to the PC. When in the background, the chair acts as a normal Android mouse for navigating the phone.

### 3. WiFi Discovery & Connection
**How to fix it:**
* **Discovery Broadcast:** The "Add Device" button sends a `PHOMOUSE_DISCOVER` broadcast. Any receiver on the network should respond with `PHOMOUSE_ACK:DeviceName` to be discovered.
* **Persistence:** Discovered and connected devices are saved in the "Paired Devices" list by their IP address for quick reconnection.

### 4. Background Reliability & Permissions
**How to fix it:**
* **Foreground Service:** The `MouseService` remains active in the background to maintain the WiFi bridge.
* **Permissions:** Updated for Android 14+ (API 34+). Now includes `FOREGROUND_SERVICE_CONNECTED_DEVICE` and requests `POST_NOTIFICATIONS` and `ACCESS_FINE_LOCATION` at runtime.
* **Power Management:** Requests to ignore battery optimizations to prevent UDP socket throttling.

### 5. Signal Format (Easy to Decode)
* **Format:** `buttons,dx,dy,wheel\n`
* **Port:** 5555 (UDP)
* **Logic:** 
    * `buttons`: Bitmask (1=Left, 2=Right)
    * `dx/dy`: Relative movement (-127 to 127)
    * `wheel`: Scroll amount (-127 to 127)
* **Example:** `1,10,-5,0\n` (Left click, Move +10X, -5Y)
