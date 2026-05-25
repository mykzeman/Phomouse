### 1. The Core Architecture: Bluetooth Serial (SPP)
**How it works:**
* **Bluetooth SPP Transport:** The app acts as the Central device, sending mouse signals over a standard Bluetooth Serial (SPP) connection to the PC.
* **Serial Protocol:** Signals follow a strict, easy-to-parse string format: `PMCMD:[command]-[value]`. Multiple commands can be sent at once by separating them with commas (e.g., `PMCMD:MX-10,PMCMD:MY--5`).
* **Target Port:** The phone interfaces with the PC via Bluetooth SPP, which maps to an incoming COM port (e.g., COM 3, or the next available port) on the receiving computer running the Python script.

### 2. Command Protocol
* **Format:** `PMCMD:[XX]-[VV]`
* **Commands (XX):**
    * `LB`: Left Button click. Value: `0` *(Note: The Dwell timer is tracked natively by the Android app. When the timer finishes, it sends this command to execute instantly on the PC, preventing input lag).*
    * `RB`: Right Button click. Value: `0`.
    * `MB`: Middle Button click. Value: `0`.
    * `MX`: Mouse Movement (Horizontal). Value: X relative movement amount.
    * `MY`: Mouse Movement (Vertical). Value: Y relative movement amount.
    * `SU`: Scroll Up. Value: Scroll amount.
    * `SD`: Scroll Down. Value: Scroll amount.
    * `DS`: Drag Start. Value: Action sensitivity amount. (Holds the left mouse button down. Persists until `DR` is sent).
    * `DR`: Drag Release. Value: `0`. (Releases the left mouse button, ending the drag action).

### 3. Input Strategy
* **Native Interception:** `MainActivity.dispatchGenericMotionEvent` captures system-level input events from connected hardware (like your Bluetooth power chair). It throttles the data, translates it into the `PMCMD:MX` and `PMCMD:MY` serial protocol strings, and sends them to the PC.
* **Click Joystick (Virtual):** The left-click button on the Controller Screen acts as a virtual joystick. Moving your finger within the boundaries of the button sends `MX` and `MY` commands. Releasing the button triggers an `LB` command.

### 4. Application Flow
**How it works:**
1. **Devices Screen (Home/Index):** The landing page of the application. It lists all currently paired devices.
    * Click on a device to open the **Controller Screen**.
    * Click the `i` icon to view device information (**Info Screen**).
    * Click the `+` button to navigate to the **Add Device Screen**.
2. **Add Device Screen:** Allows you to scan for and add a new device to be paired. (Requires Android Bluetooth permissions to be granted). Click on a discovered device to pair it. You can navigate back to the **Devices Screen** at any time to connect to an already paired device.
3. **Controller Screen:** The main interface to control your PC.
    * **Prerequisite:** The target PC must have the Python script running and listening on the designated port (e.g., COM3).
    * **Features:** Contains all UI elements for left click, right click, middle click, scroll up/down, dragging, and the virtual Click Joystick.
    * **Navigation:** You can navigate to the **Home** or **Settings** screens from here. *Crucially, navigating away does not interrupt your active Bluetooth SPP connection.*
4. **Info Screen:** Displays all relevant hardware and connection information about a selected device. You can navigate back to the **Devices Screen** or to the **Settings Screen** from here.
5. **Settings Screen:** Contains the core configuration options (UI Scale, Dwell Period, Action Sensitivity, and Scroll Amount). Adjusting these updates `SharedPreferences`. You can navigate back to whatever screen you previously came from without interrupting the background Foreground Service connection.