package com.burnto.disk.data.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import me.jahnen.libaums.core.driver.BlockDeviceDriverFactory
import me.jahnen.libaums.core.usb.UsbCommunication
import me.jahnen.libaums.core.usb.UsbCommunicationFactory
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Builds and owns a *raw*, LBA-addressed [BlockDeviceDriver] for a USB mass
 * storage device.
 *
 * libaums only exposes the byte-addressed [me.jahnen.libaums.core.partition.Partition]
 * publicly, but the FAT32 formatter needs to write the boot sector, FATs and root
 * directory at absolute logical block addresses (LBA 0 onward) to create a
 * "superfloppy" (partition-table-less) FAT32 volume. So we replicate libaums'
 * endpoint discovery and construct the SCSI block device through the public
 * factories.
 *
 * The same raw device is reused afterwards to mount the freshly written FAT32
 * filesystem via [me.jahnen.libaums.core.fs.fat32.Fat32FileSystem.read].
 */
class RawUsbBlockDevice private constructor(
    private val communication: UsbCommunication,
    val blockDevice: BlockDeviceDriver
) {

    /** Capacity in bytes, available after [init]. */
    val capacityBytes: Long
        get() = blockDevice.blocks * blockDevice.blockSize.toLong()

    val blockSize: Int
        get() = blockDevice.blockSize

    /**
     * Initializes the SCSI device (issues READ CAPACITY, etc.). Retries a few
     * times because a drive can transiently report zero blocks right after a
     * heavy write burst or when its MBR is corrupt.
     */
    @Throws(IOException::class)
    fun init() {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                blockDevice.init()
                if (blockDevice.blocks > 0L) return
            } catch (e: Exception) {
                lastError = e
            }
            if (attempt < 2) Thread.sleep(150)
        }
        // If we still have zero blocks but no exception, fall through — callers
        // (formatter) can attempt MBR-clear recovery. Only rethrow a hard error
        // when init never succeeded at all.
        if (blockDevice.blocks <= 0L && lastError != null) throw IOException("USB init failed", lastError)
    }

    /**
     * Recovery for a corrupted/garbage MBR that makes the controller report a
     * bogus (often zero) capacity: zero LBA 0, then re-init so READ CAPACITY is
     * re-issued. Returns the (re-read) capacity in bytes. Best-effort.
     */
    fun clearMbrAndReinit(): Long {
        runCatching {
            val bs = blockDevice.blockSize.coerceAtLeast(512)
            val zero = ByteBuffer.allocate(bs)
            blockDevice.write(0, zero)
        }
        runCatching { blockDevice.init() }
        return capacityBytes
    }

    fun close() {
        runCatching { communication.close() }
    }

    companion object {
        private const val INTERFACE_SUBCLASS = 6   // SCSI transparent command set
        private const val INTERFACE_PROTOCOL = 80  // Bulk-only transport

        /**
         * Locates the mass-storage interface and bulk endpoints on [device] and
         * builds a raw block device for LUN 0.
         *
         * @throws IOException if the device exposes no compatible MSC interface.
         */
        @Throws(IOException::class)
        fun create(usbManager: UsbManager, device: UsbDevice): RawUsbBlockDevice {
            val usbInterface = findMassStorageInterface(device)
                ?: throw IOException("No USB mass-storage interface found on ${device.deviceName}")

            var inEndpoint: UsbEndpoint? = null
            var outEndpoint: UsbEndpoint? = null
            for (i in 0 until usbInterface.endpointCount) {
                val ep = usbInterface.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_OUT) outEndpoint = ep else inEndpoint = ep
                }
            }
            if (inEndpoint == null || outEndpoint == null) {
                throw IOException("Mass-storage bulk endpoints not found")
            }

            val communication = UsbCommunicationFactory.createUsbCommunication(
                usbManager, device, usbInterface, outEndpoint, inEndpoint
            )
            val blockDevice = BlockDeviceDriverFactory.createBlockDevice(communication, lun = 0)
            return RawUsbBlockDevice(communication, blockDevice)
        }

        private fun findMassStorageInterface(device: UsbDevice): UsbInterface? {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE &&
                    intf.interfaceSubclass == INTERFACE_SUBCLASS &&
                    intf.interfaceProtocol == INTERFACE_PROTOCOL
                ) {
                    return intf
                }
            }
            return null
        }
    }
}
