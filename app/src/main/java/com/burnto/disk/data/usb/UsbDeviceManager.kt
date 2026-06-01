package com.burnto.disk.data.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.burnto.disk.data.model.UsbDeviceInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import me.jahnen.libaums.core.UsbMassStorageDevice
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Discovers USB OTG mass-storage devices, requests runtime permission, and
 * mounts them through libaums.
 *
 * libaums' [UsbMassStorageDevice.getMassStorageDevices] enumerates only devices
 * that expose the USB Mass Storage class, which is exactly what we want.
 */
@Singleton
class UsbDeviceManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    companion object {
        const val ACTION_USB_PERMISSION = "com.burnto.disk.USB_PERMISSION"
    }

    /** Lists currently connected USB mass-storage devices as UI models. */
    fun listDevices(): List<UsbDeviceInfo> {
        return UsbMassStorageDevice.getMassStorageDevices(context).map { msd ->
            val dev = msd.usbDevice
            val capacity = runCatching {
                msd.init()
                val partition = msd.partitions.firstOrNull()
                val fs = partition?.fileSystem
                Pair(fs?.capacity ?: 0L, fs?.let { describeFs(it.type) } ?: "Unknown")
            }.getOrDefault(Pair(0L, "Unknown")).also {
                runCatching { msd.close() }
            }

            UsbDeviceInfo(
                deviceId = dev.deviceId,
                deviceName = dev.deviceName,
                productName = productNameOf(dev),
                vendorId = dev.vendorId,
                productId = dev.productId,
                capacityBytes = capacity.first,
                filesystem = capacity.second,
                hasPermission = usbManager.hasPermission(dev)
            )
        }
    }

    /** Returns the raw libaums device handles (for the burn engine). */
    fun rawDevices(): List<UsbMassStorageDevice> =
        UsbMassStorageDevice.getMassStorageDevices(context).toList()

    fun findRawDeviceById(deviceId: Int): UsbMassStorageDevice? =
        rawDevices().firstOrNull { it.usbDevice.deviceId == deviceId }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    fun hasAnyDevice(): Boolean =
        UsbMassStorageDevice.getMassStorageDevices(context).isNotEmpty()

    /**
     * Requests permission for [device], suspending until the user responds.
     * Returns true if granted.
     */
    suspend fun requestPermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true

        return suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != ACTION_USB_PERMISSION) return
                    context.unregisterReceiver(this)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (cont.isActive) cont.resume(granted)
                }
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags
            )

            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }

            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
            usbManager.requestPermission(device, pi)
        }
    }

    private fun productNameOf(device: UsbDevice): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            device.productName ?: device.manufacturerName ?: "USB Drive"
        } else {
            "USB Drive"
        }
    }

    private fun describeFs(type: Int): String = when (type) {
        me.jahnen.libaums.core.partition.PartitionTypes.FAT32 -> "FAT32"
        me.jahnen.libaums.core.partition.PartitionTypes.FAT16 -> "FAT16"
        me.jahnen.libaums.core.partition.PartitionTypes.FAT12 -> "FAT12"
        me.jahnen.libaums.core.partition.PartitionTypes.NTFS_EXFAT -> "NTFS/exFAT"
        me.jahnen.libaums.core.partition.PartitionTypes.LINUX_EXT -> "ext"
        me.jahnen.libaums.core.partition.PartitionTypes.ISO9660 -> "ISO9660"
        me.jahnen.libaums.core.partition.PartitionTypes.APPLE_HFS_HFS_PLUS -> "HFS+"
        else -> "Unknown"
    }
}
