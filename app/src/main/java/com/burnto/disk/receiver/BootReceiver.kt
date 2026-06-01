package com.burnto.disk.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives BOOT_COMPLETED. Burn2Disk has no persistent background work to resume
 * after a reboot (a burn cannot survive a power cycle), so this simply clears any
 * stale partial downloads left in the cache from an interrupted session.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i(TAG, "Boot completed; cleaning stale partial downloads")
        runCatching {
            val isoDir = java.io.File(context.cacheDir, "isos")
            isoDir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
        }
    }
}
