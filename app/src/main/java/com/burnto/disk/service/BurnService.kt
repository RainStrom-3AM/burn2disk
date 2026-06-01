package com.burnto.disk.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.burnto.disk.Burn2DiskApp
import com.burnto.disk.MainActivity
import com.burnto.disk.R
import com.burnto.disk.data.model.BurnState
import com.burnto.disk.data.usb.BurnEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers

/**
 * Foreground service that runs the burn off the UI lifecycle, holds a wake lock
 * so the burn survives screen-off, and surfaces progress through a notification.
 *
 * The actual work lives in [BurnEngine]; this service is the Android lifecycle
 * host and the wake-lock / notification owner.
 */
@AndroidEntryPoint
class BurnService : Service() {

    @Inject lateinit var burnEngine: BurnEngine

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var burnJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isoName: String = "ISO"

    companion object {
        const val ACTION_START = "com.burnto.disk.action.START_BURN"
        const val ACTION_CANCEL = "com.burnto.disk.action.CANCEL_BURN"
        const val EXTRA_ISO_PATH = "iso_path"
        const val EXTRA_ISO_NAME = "iso_name"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_CAPACITY = "capacity_bytes"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val path = intent.getStringExtra(EXTRA_ISO_PATH)
                val deviceId = intent.getIntExtra(EXTRA_DEVICE_ID, -1)
                val capacity = intent.getLongExtra(EXTRA_CAPACITY, 0L)
                isoName = intent.getStringExtra(EXTRA_ISO_NAME) ?: "ISO"
                if (path != null && deviceId != -1) {
                    startBurn(File(path), deviceId, capacity)
                } else {
                    stopSelf()
                }
            }
            ACTION_CANCEL -> {
                cancelBurn()
            }
        }
        return START_NOT_STICKY
    }

    private fun startBurn(isoFile: File, deviceId: Int, capacityBytes: Long) {
        startForegroundWithNotification(buildNotification("Preparing...", 0, indeterminate = true))
        acquireWakeLock()

        // Mirror engine state into the notification.
        burnEngine.state
            .onEach { state -> updateNotificationFor(state) }
            .launchIn(serviceScope)

        burnJob = serviceScope.launch {
            burnEngine.burn(isoFile, deviceId, capacityBytes)
            // Keep the service alive briefly so the terminal state is observed,
            // then tear down.
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun cancelBurn() {
        burnJob?.cancel()
        releaseWakeLock()
        stopForegroundCompat()
        stopSelf()
    }

    private fun updateNotificationFor(state: BurnState) {
        val (text, percent, indeterminate) = when (state) {
            is BurnState.Idle -> Triple("Preparing...", 0, true)
            is BurnState.Formatting -> Triple("Formatting FAT32...", state.progress, false)
            is BurnState.Copying -> Triple(
                "Writing files — ${state.percent}%",
                state.percent,
                false
            )
            is BurnState.Verifying -> Triple("Verifying...", state.progress, false)
            is BurnState.Success -> Triple("Burn complete", 100, false)
            is BurnState.Failed -> Triple("Burn failed", 0, false)
        }
        val notif = buildNotification(text, percent, indeterminate)
        notificationManager().notify(NOTIFICATION_ID, notif)
    }

    private fun buildNotification(content: String, percent: Int, indeterminate: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, Burn2DiskApp.CHANNEL_ID)
            .setContentTitle("Burn Disk")
            .setContentText("Burning $isoName to USB — $percent%")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, indeterminate)
            .setContentIntent(contentIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun startForegroundWithNotification(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        // PARTIAL_WAKE_LOCK keeps the CPU running through screen-off; the burn
        // screen separately keeps the display on via FLAG_KEEP_SCREEN_ON.
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Burn2Disk::BurnWakeLock").apply {
            setReferenceCounted(false)
            acquire(60 * 60 * 1000L) // 1 hour safety timeout
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun notificationManager(): android.app.NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
}
