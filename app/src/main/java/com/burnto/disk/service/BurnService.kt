package com.burnto.disk.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.burnto.disk.Burn2DiskApp
import com.burnto.disk.MainActivity
import com.burnto.disk.R
import com.burnto.disk.data.model.BurnState
import com.burnto.disk.data.model.BurnTarget
import com.burnto.disk.data.sdcard.SdCardBurnEngine
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
 * Routes to [BurnEngine] for USB OTG raw-block writes and to [SdCardBurnEngine]
 * for SAF-based SD card file copies.
 */
@AndroidEntryPoint
class BurnService : Service() {

    @Inject lateinit var burnEngine: BurnEngine
    @Inject lateinit var sdCardBurnEngine: SdCardBurnEngine

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var burnJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isoName: String = "ISO"
    private var isSdBurn: Boolean = false

    companion object {
        const val ACTION_START = "com.burnto.disk.action.START_BURN"
        const val ACTION_CANCEL = "com.burnto.disk.action.CANCEL_BURN"
        const val EXTRA_ISO_PATH = "iso_path"
        const val EXTRA_ISO_NAME = "iso_name"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_CAPACITY = "capacity_bytes"
        const val EXTRA_TARGET_TYPE = "target_type"
        const val EXTRA_SD_URI = "sd_uri"
        const val TYPE_USB = "usb"
        const val TYPE_SD = "sd"
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
                val targetType = intent.getStringExtra(EXTRA_TARGET_TYPE) ?: TYPE_USB
                isSdBurn = targetType == TYPE_SD
                if (path != null) {
                    if (isSdBurn) {
                        val sdUri = intent.getStringExtra(EXTRA_SD_URI)?.let { Uri.parse(it) }
                        if (sdUri != null) {
                            startSdBurn(File(path), sdUri)
                        } else {
                            stopSelf()
                        }
                    } else {
                        if (deviceId != -1) {
                            startUsbBurn(File(path), deviceId, capacity)
                        } else {
                            stopSelf()
                        }
                    }
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

    private fun startUsbBurn(isoFile: File, deviceId: Int, capacityBytes: Long) {
        startForegroundWithNotification(buildNotification("Preparing...", 0, indeterminate = true))
        acquireWakeLock()

        burnEngine.state
            .onEach { state -> updateNotificationFor(state) }
            .launchIn(serviceScope)

        burnJob = serviceScope.launch {
            burnEngine.burn(isoFile, deviceId, capacityBytes)
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun startSdBurn(isoFile: File, sdUri: Uri) {
        startForegroundWithNotification(buildNotification("Preparing...", 0, indeterminate = true))
        acquireWakeLock()

        sdCardBurnEngine.state
            .onEach { state -> updateNotificationFor(state) }
            .launchIn(serviceScope)

        burnJob = serviceScope.launch {
            sdCardBurnEngine.burn(isoFile, sdUri)
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
                if (isSdBurn) "Copying files — ${state.percent}%" else "Writing files — ${state.percent}%",
                state.percent,
                false
            )
            is BurnState.Verifying -> Triple("Verifying...", state.progress, false)
            is BurnState.Success -> Triple(if (isSdBurn) "Copy complete" else "Burn complete", 100, false)
            is BurnState.Failed -> Triple(if (isSdBurn) "Copy failed" else "Burn failed", 0, false)
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

        val titlePrefix = if (isSdBurn) "Copy to SD" else "Burn Disk"
        return NotificationCompat.Builder(this, Burn2DiskApp.CHANNEL_ID)
            .setContentTitle(titlePrefix)
            .setContentText("$isoName — $percent%")
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
