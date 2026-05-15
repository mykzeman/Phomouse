# Phomouse Improvements & Architecture Updates

This document detail how the app has been updated to meet your specific requirements for a reliable bridge between your input devices (like the power chair joystick) and your PC.

## 1. Dynamic Device & Connection Management
- **Dynamic Lists**: The hardcoded "Lorem Ipsum" entries have been removed. The app now uses a `RecyclerView` with a `DeviceAdapter` to show actual Bluetooth devices.
- **Background Bridge**: The `MouseService` is a **Foreground Service**, ensuring the connection remains active even when the app is in the background.

## 2. Input Persistence & Tracking
- **Device Tracking**: Added a `connectedLocalInputDevices` variable in `MouseService`. It uses the Android `InputManager` to track all mice, joysticks, and other input devices currently connected to your phone.
- **Connection Isolation**: The logic for the PC connection (HID) is completely separated from the local input connections (Serial/Mouse). 
    - **No Accidental Closures**: When the phone disconnects from the PC, or during the Bluetooth advertising and registration phases, the app **explicitly avoids** closing any active serial ports or disconnecting local input devices.
    - **Variable Tracking**: The state of these connections is maintained in the `activeSerialConnections` map and `connectedLocalInputDevices` list, ensuring you can monitor them at any time.

## 3. Power Chair Joystick Support
- **Dual Mode Capture**: The `MainActivity` now captures both `SOURCE_JOYSTICK` and `SOURCE_MOUSE` events. This ensures that if your power chair acts as a standard Android mouse, its movements and button clicks are still captured and bridged to the PC.
- **Serial (SPP) Support**: The `MouseService` includes a `connectSerialDevice` method using the standard SPP UUID, ready for devices that require a raw serial connection.

## 4. Settings & Accessibility Implementation
All options from `screen_settings.xml` are now functional:
- **Joystick Mode**: Toggle to enable/disable the input bridge.
- **Dwell Period**: Configurable delay (in ms) to perform a left-click automatically after movement stops (perfect for hands-free operation).
- **Scroll Amount**: Configurable sensitivity for scroll wheel actions.
- **UI Scale & Action Sensitivity**: Wired to the control logic and saved in `SharedPreferences`.
- **Accessibility**: State persistence for **Dyslexic** and **Colourblind** modes is implemented, ready for UI theme adjustments.

## 5. Reliability
- **Throttling**: Mouse reports are limited to 100 per second (10ms) to ensure smooth movement on the PC without lag or Bluetooth congestion.
- **Battery Safety**: The app requests to be excluded from battery optimizations to prevent Android from sleeping the connection.
