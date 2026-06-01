package com.burnto.disk.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * Listens for OTG attach/detach broadcasts. The app primarily polls
 * [com.burnto.disk.data.usb.UsbDeviceManager] when screens resume, but this
 * receiver lets the system launch/notify the app when a stick is plugged in
 * (paired with the manifest USB_DEVICE_ATTACHED intent-filter).
 */
class UsbReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "UsbReceiver"

        /** Local broadcast action the UI can observe for live attach/detach. */
        const val ACTION_USB_STATE_CHANGED = "com.burnto.disk.USB_STATE_CHANGED"
        const val EXTRA_ATTACHED = "attached"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                Log.i(TAG, "USB device attached")
                notifyStateChanged(context, attached = true)
            }
            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                Log.i(TAG, "USB device detached")
                notifyStateChanged(context, attached = false)
            }
        }
    }

    private fun notifyStateChanged(context: Context, attached: Boolean) {
        val broadcast = Intent(ACTION_USB_STATE_CHANGED).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_ATTACHED, attached)
        }
        context.sendBroadcast(broadcast)
    }
}
