package osa.phomouse

/**
 * DEPRECATED: This service has been replaced by [PcMouseService]
 * to follow the "Hands-Off" architecture for HID-class input devices.
 */
@Deprecated("Use PcMouseService instead")
class MouseService : android.app.Service() {
    override fun onBind(intent: android.content.Intent?): android.os.IBinder? = null
}
