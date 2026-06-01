package com.burnto.disk.service

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.burnto.disk.Burn2DiskApp
import com.burnto.disk.R
import com.burnto.disk.data.download.DownloadManager
import com.burnto.disk.data.model.DownloadState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that downloads an ISO with a progress notification so the
 * transfer continues if the user leaves the app. Progress for the UI is observed
 * through the shared [DownloadManager] flow started by the ViewModel; this
 * service exists mainly to satisfy the spec's notification + background
 * requirement for long downloads.
 */
@AndroidEntryPoint
class DownloadService : Service() {

    @Inject lateinit var downloadManager: DownloadManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    companion object {
        const val ACTION_START = "com.burnto.disk.action.START_DOWNLOAD"
        const val ACTION_CANCEL = "com.burnto.disk.action.CANCEL_DOWNLOAD"
        const val EXTRA_URL = "url"
        private const val NOTIFICATION_ID = 2001
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL)
                if (url.isNullOrBlank()) {
                    stopSelf()
                } else {
                    start(url)
                }
            }
            ACTION_CANCEL -> {
                job?.cancel()
                stopForegroundCompat()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun start(url: String) {
        startForegroundWithNotification(buildNotification("Starting download...", 0, true))
        job = scope.launch {
            downloadManager.download(url).collect { state ->
                when (state) {
                    is DownloadState.Downloading ->
                        update(buildNotification(state.fileName, state.percent, false))
                    is DownloadState.Completed -> {
                        update(buildNotification("Download complete", 100, false))
                        stopForegroundCompat()
                        stopSelf()
                    }
                    is DownloadState.Failed -> {
                        update(buildNotification("Download failed", 0, false))
                        stopForegroundCompat()
                        stopSelf()
                    }
                    DownloadState.Idle -> Unit
                }
            }
        }
    }

    private fun buildNotification(content: String, percent: Int, indeterminate: Boolean): Notification {
        return NotificationCompat.Builder(this, Burn2DiskApp.CHANNEL_ID)
            .setContentTitle("Downloading ISO")
            .setContentText("$content — $percent%")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, indeterminate)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun update(notification: Notification) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification)
    }

    private fun startForegroundWithNotification(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
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
        scope.cancel()
        super.onDestroy()
    }
}
