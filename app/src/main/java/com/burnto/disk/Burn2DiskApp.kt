package com.burnto.disk

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point. Sets up Hilt and registers the foreground-service
 * notification channel used by the burn and download services.
 */
@HiltAndroidApp
class Burn2DiskApp : Application() {

    companion object {
        const val CHANNEL_ID = "disk_burn"
        const val CHANNEL_NAME = "Burn Progress"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress while burning ISO images to USB"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
