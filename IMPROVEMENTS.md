  ### 1. The Core Architecture: Bluetooth Serial (SPP)
**How it works:**
* **Bluetooth SPP Transport:** The app sends mouse signals over Bluetooth Serial (SPP). 
* **Serial Protocol:** Signals follow a strict format: `PMCMD:[command]-{value}`.
* **Target Port:** The phone interfaces with the device via Bluetooth, intended to map to COM 3 (or the next available port) on the receiving end.

### 2. Command Protocol
* **Format:** `PMCMD:[XX]-{VV}`
* **Commands (XX):**
    * `LB`: Left Button click. Value: Dwell setting.
    * `RB`: Right Button click. Value: Dwell setting.
    * `MB`: Middle Button click. Value: Dwell setting.
    * `SU`: Scroll Up. Value: Scroll amount.
    * `SD`: Scroll Down. Value: Scroll amount.
    * `DS`: Drag Start. Value: Action sensitivity amount. (Persists until `DR` is sent).
    * `DR`: Drag Release. (Ends the drag action).

### 3. Consolidated Controller UI
* **Single Screen Interface:** All controller actions (clicks, scroll, drag) are kept on a single screen to simplify use and minimize navigation.

### 4. System Requirements
* **Minimum SDK:** 28.
* **Permissions:** Bluetooth permissions (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, etc.).

### 5. Input Strategy
* **Native Interception:** `MainActivity.dispatchGenericMotionEvent` captures system-level joystick/mouse events and translates them into the `PMCMD` serial protocol strings.
