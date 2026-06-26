# Phomouse Version Log

## [0.2.0-pre2] - 2025-05-20

### Additions
- Added **Joystick Mode** (simplified): A toggleable layout that prioritizes D-pad usage by moving it to the top and enlarging buttons.
- Added real-time percentage indicators next to **Action Sensitivity** and **UI Scale** seekbars in the Settings screen.
- Created `agents.md` to define operational guidelines for AI assistants.

### Changes
- Redesigned D-pad buttons to use `OnTouchListener` with a repeating task (30ms interval) for smooth, continuous movement.
- Forced a minimum floor of **25%** for Action Sensitivity to ensure the device remains usable at low settings.
- Updated `refreshJoystickUI` to correctly handle layout transitions and dynamic scaling.
- Rewrote `IMPROVEMENTS.md` to reflect the current Bluetooth SPP protocol and input strategies.

### Removals
- Removed the old **Virtual Joystick** logic from the "Left Click" button (trackpad-style sliding).
- Removed hardware interception for `SOURCE_MOUSE` and `SOURCE_JOYSTICK` in `dispatchGenericMotionEvent` to simplify the core touch experience.
- Removed unused `InputDevice` imports and related flags.

### Bug Fixes
- **D-pad Responsiveness:** Fixed an issue where D-pad buttons were not registering clicks or movements due to conflicting touch/click listeners.
- **Visual Desync:** Fixed seekbar labels not updating in real-time while dragging.
- **Anti-Jerk:** Corrected the jerky cursor movement by implementing high-frequency relative delta updates instead of discrete large steps.
