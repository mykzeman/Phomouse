### Phomouse Core Architecture

**Bluetooth Serial (SPP) Communication**
* **Transport:** The app acts as a Bluetooth Central device, sending mouse control signals over a standard Serial Port Profile (SPP) connection.
* **Protocol:** Commands follow the format `PMCMD:[command]-{value}\n`.
* **Receiving End:** The PC runs a Python script that listens on a virtual COM port created by the Bluetooth pairing.

### Command Set
* `LB`: Left Button Click. Value: Dwell time (or 0 for instant).
* `RB`: Right Button Click. Value: Dwell time.
* `MB`: Middle Button Click. Value: Dwell time.
* `MX`: Mouse X-axis movement. Value: Relative movement amount.
* `MY`: Mouse Y-axis movement. Value: Relative movement amount.
* `SU`: Scroll Up. Value: Scroll amount.
* `SD`: Scroll Down. Value: Scroll amount.
* `DS`: Drag Start. Value: Sensitivity.
* `DR`: Drag Release. Value: 0.

### Input Strategies

#### 1. D-pad & Smooth Movement
*   The D-pad allows for continuous, smooth directional movement using an **OnTouchListener**.
*   When held, it sends frequent, small movement updates (every 20ms) for a fluid experience on the PC.
*   **Action Sensitivity:** Now has a minimum floor of 25% to ensure movement is always detectable.
*   **Dwell Click:** After any movement finishes (releasing a D-pad button), a timer starts based on the **Dwell Period**. When it expires, an `LB` command is sent automatically.

#### 2. Joystick Mode
*   Toggleable in Settings.
*   **UI Layout:** Rearranges the controller screen to place the D-pad at the top and enlarges the buttons for easier access.
*   **Behavior:** Designed for users who rely primarily on the D-pad. The "Left Click" button is hidden to prevent accidental triggers, as the dwell timer handles clicking.

### Recent Fixes
* **Smooth Scrolling/Movement:** Transitioned from discrete click-based movement to touch-based repeating updates, eliminating jerkiness.
* **Visual Feedback:** Added percentage indicators for Sensitivity and UI Scale settings.
