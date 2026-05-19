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
    * `MV`: Mouse Movement. Value: x,y relative movement.
    * `SU`: Scroll Up. Value: Scroll amount.
    * `SD`: Scroll Down. Value: Scroll amount.
    * `DS`: Drag Start. Value: Action sensitivity amount. (Persists until `DR` is sent).
    * `DR`: Drag Release. (Ends the drag action).

### 3. Input Strategy
* **Native Interception:** `MainActivity.dispatchGenericMotionEvent` captures system-level joystick/mouse events and translates them into the `PMCMD` serial protocol strings.
* **Click Joystick:** The left click button on the controller screen acts as a virtual joystick. Moving within the button sends `MV` commands. Releasing the button triggers an `LB` command.
### 4. Application Flow
**How it works:**
1. **Devices Screen** : The Index page. Lists the paired devices. Click on the device to open the controller page. Or visit the device information by pressing the `i`. Or click on the `+` button to add a new device.
2. **Add Device Screen** : Add a new device to be paired. Must have the bluetooth permissions enabled on the device. Click on the device to pair. You can back to the Home screen to connect to an already paired device. 
3. **Controller Screen** : Control your device from here. You need to have the script running and active, and you need to make sure that your mobile device has a COM3 serial port open to make it work. Has all the actions to control your device (left click, right click, middle click, scroll up, scroll down, dragging). Can either go to the Home screen or the Settings screen. Going to the Settings screen won't interrupt your connection. 
4. **Info Screen** : Displays all the relevant information about the device. You can either go to the Home screen or the Settings screen. 
5. **Settings Screen** : Has the necessary settings, you can go back to whatever screen you were on. 
