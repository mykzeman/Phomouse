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

#### 1. D-pad & Movement
*   The D-pad allows for discrete directional movement using standard **MaterialButton** components for maximum reliability.
*   Each tap sends an `MX` or `MY` command based on the current **Action Sensitivity**.
*   **Dwell Click:** After any movement finishes (releasing a D-pad button), a timer starts based on the **Dwell Period**. When it expires, an `LB` command is sent automatically.

#### 2. Joystick Mode
*   Toggleable in Settings.
*   **UI Layout:** Rearranges the controller screen to place the D-pad at the top and enlarges the buttons for easier access.
*   **Behavior:** Designed for users who rely primarily on the D-pad. The "Left Click" button is hidden to prevent accidental triggers, as the dwell timer handles clicking.

### Recent Fixes
* **D-pad Reliability:** Fully redid the D-pad buttons to use `MaterialButton` instead of generic `View` references in code, ensuring `setOnClickListener` works consistently across all Android versions.
* **Simplified Interaction:** Removed complex hardware interception to focus on a stable, touch-first D-pad experience.
