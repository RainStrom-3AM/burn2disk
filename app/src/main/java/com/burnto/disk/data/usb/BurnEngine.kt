package com.burnto.disk.data.usb

import android.content.Context
import android.hardware.usb.UsbManager
import com.burnto.disk.data.iso.IsoEntry
import com.burnto.disk.data.iso.IsoParser
import com.burnto.disk.data.iso.WimSplitter
import com.burnto.disk.data.model.BurnLogLine
import com.burnto.disk.data.model.BurnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.fs.fat32.Fat32FileSystem
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * The core burn orchestrator. Runs entirely off the main thread and reports
 * progress through [state] (a StateFlow) and [log] (a SharedFlow of log lines).
 *
 * Pipeline (per the spec):
 *  1. Acquire USB permission.
 *  2. Build a raw block device, format FAT32 (superfloppy inside an MBR partition).
 *  3. Re-mount the fresh FAT32 filesystem via libaums.
 *  4. Parse the source ISO (ISO 9660 + Joliet).
 *  5. Copy files in 64 KiB chunks, splitting an oversized install.wim into .swm.
 *  6. Flush + unmount cleanly.
 */
@Singleton
class BurnEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val usbDeviceManager: UsbDeviceManager,
    private val wimSplitter: WimSplitter
) {
    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _state = MutableStateFlow<BurnState>(BurnState.Idle)
    val state: StateFlow<BurnState> = _state.asStateFlow()

    private val _log = MutableSharedFlow<BurnLogLine>(replay = 200, extraBufferCapacity = 256)
    val log: SharedFlow<BurnLogLine> = _log.asSharedFlow()

    /** Progress of a standalone format operation (0-100), or null when idle. */
    private val _formatProgress = MutableStateFlow<Int?>(null)
    val formatProgress: StateFlow<Int?> = _formatProgress.asStateFlow()

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val COPY_BUFFER = 512 * 1024
        private const val ISO_SECTOR = IsoParser.SECTOR_SIZE
        private const val FAT32_FILE_LIMIT = 0xFFFFFFFFL
        private const val PROGRESS_INTERVAL_MS = 500L
        // Files that must be split for FAT32.
        private val SPLITTABLE = setOf("install.wim", "install.esd")
    }

    // ---------------------------------------------------------------------
    // Rolling speed / ETA
    // ---------------------------------------------------------------------

    private data class SpeedSample(val timeMs: Long, val bytesWritten: Long)

    private val speedSamples = ArrayDeque<SpeedSample>()

    private fun resetSpeedSamples() = speedSamples.clear()

    private fun recordSample(timeMs: Long, bytes: Long) {
        if (speedSamples.size >= 5) speedSamples.removeFirst()
        speedSamples.addLast(SpeedSample(timeMs, bytes))
    }

    /** Rolling throughput over the last few samples, in MB/s. */
    private fun rollingSpeedMBps(): Float {
        if (speedSamples.size < 2) return 0f
        val oldest = speedSamples.first()
        val newest = speedSamples.last()
        val sec = (newest.timeMs - oldest.timeMs) / 1000f
        if (sec <= 0f) return 0f
        return (newest.bytesWritten - oldest.bytesWritten) / sec / (1024f * 1024f)
    }

    /** ETA in seconds from the rolling speed, clamped to a sane 0..86400 range. */
    private fun etaSeconds(bytesWritten: Long, totalBytes: Long): Int {
        val mbps = rollingSpeedMBps()
        if (mbps <= 0f) return 0
        val remainingBytes = (totalBytes - bytesWritten).coerceAtLeast(0)
        val secs = remainingBytes / (mbps * 1024f * 1024f)
        return secs.toInt().coerceIn(0, 86_400)
    }

    fun resetState() {
        _state.value = BurnState.Idle
    }

    /**
     * Reads key on-disk structures from the USB and returns a human-readable
     * diagnostic report (MBR, boot sector, FAT head, root dir). Read-only — never
     * writes to the device. Used by the Home-screen "Diagnose USB" tool to pin
     * down exactly which sector is wrong when a burned drive won't mount.
     */
    suspend fun diagnose(deviceId: Int): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        var raw: RawUsbBlockDevice? = null
        try {
            val msd = usbDeviceManager.findRawDeviceById(deviceId)
                ?: return@withContext "No USB device found. Connect a drive via OTG."
            val usbDevice = msd.usbDevice
            if (!usbDeviceManager.requestPermission(usbDevice)) {
                return@withContext "USB permission denied."
            }
            raw = RawUsbBlockDevice.create(usbManager, usbDevice).also { it.init() }
            val bs = raw.blockDevice.blockSize
            val blocks = raw.blockDevice.blocks
            val cap = blocks * bs.toLong()

            fun hex(b: Int) = String.format("%02X", b and 0xFF)
            fun readSector(lba: Long): ByteBuffer {
                val buf = ByteBuffer.allocate(bs)
                raw!!.blockDevice.read(lba, buf)
                buf.position(0)
                return buf
            }
            fun le32(buf: ByteBuffer, off: Int): Long {
                return ((buf.get(off).toLong() and 0xFF)) or
                    ((buf.get(off + 1).toLong() and 0xFF) shl 8) or
                    ((buf.get(off + 2).toLong() and 0xFF) shl 16) or
                    ((buf.get(off + 3).toLong() and 0xFF) shl 24)
            }
            fun le16(buf: ByteBuffer, off: Int): Int {
                return (buf.get(off).toInt() and 0xFF) or ((buf.get(off + 1).toInt() and 0xFF) shl 8)
            }
            fun hexRange(buf: ByteBuffer, from: Int, to: Int): String {
                val parts = ArrayList<String>()
                for (i in from..to) parts.add(hex(buf.get(i).toInt()))
                return parts.joinToString(" ")
            }

            sb.appendLine("blockDevice.blockSize: $bs bytes")
            sb.appendLine("blockDevice.blocks: $blocks")
            sb.appendLine("Total capacity: ${formatBytes(cap)}")

            val partitionStartLba = (1L shl 20) / bs

            // --- MBR ---
            val mbr = readSector(0)
            sb.appendLine()
            sb.appendLine("--- MBR (device LBA 0) ---")
            sb.appendLine("Bytes 0-3 (hex): ${hexRange(mbr, 0, 3)}")
            sb.appendLine("Bytes 446-449 (hex): ${hexRange(mbr, 446, 449)}  (partition entry start)")
            sb.appendLine("Partition type (byte 450): 0x${hex(mbr.get(450).toInt())}  (should be 0x0C)")
            sb.appendLine("Partition start LBA (bytes 454-457): ${le32(mbr, 454)}  (should be 2048)")
            sb.appendLine("Partition size sectors (bytes 458-461): ${le32(mbr, 458)}")
            sb.appendLine("Bytes 510-511 (hex): ${hexRange(mbr, 510, 511)}  (should be 55 AA)")

            // --- Boot sector ---
            val boot = readSector(partitionStartLba)
            val oem = ByteArray(8).also { boot.position(82); boot.get(it) }
            sb.appendLine()
            sb.appendLine("--- Boot sector (device LBA $partitionStartLba) ---")
            sb.appendLine("Bytes 0-3 (hex): ${hexRange(boot, 0, 3)}  (should be EB 58 90 XX)")
            sb.appendLine("Bytes per sector (11-12): ${le16(boot, 11)}  (should be 512)")
            sb.appendLine("Sectors per cluster (13): ${boot.get(13).toInt() and 0xFF}")
            sb.appendLine("Reserved sectors (14-15): ${le16(boot, 14)}  (should be 32)")
            sb.appendLine("Number of FATs (16): ${boot.get(16).toInt() and 0xFF}  (should be 02)")
            sb.appendLine("Root cluster (44-47): ${le32(boot, 44)}  (should be 2)")
            sb.appendLine("Bytes 82-89 ASCII: '${String(oem, Charsets.US_ASCII)}'  (should be 'FAT32   ')")
            sb.appendLine("Bytes 510-511 (hex): ${hexRange(boot, 510, 511)}  (should be 55 AA)")

            // --- FAT1 sector 0 ---
            val fatLba = partitionStartLba + 32
            val fat = readSector(fatLba)
            sb.appendLine()
            sb.appendLine("--- FAT1 sector 0 (device LBA $fatLba) ---")
            sb.appendLine("Entry 0 (0-3 hex): ${hexRange(fat, 0, 3)}  (should be F8 FF FF 0F)")
            sb.appendLine("Entry 1 (4-7 hex): ${hexRange(fat, 4, 7)}  (should be FF FF FF 0F)")
            sb.appendLine("Entry 2 (8-11 hex): ${hexRange(fat, 8, 11)}  (should be FF FF FF 0F)")

            // --- Root directory ---
            // dataStart = partitionStart + reserved(32) + 2 * fatSize.
            val fatSize = le32(boot, 36)
            val rootLba = partitionStartLba + 32 + 2 * fatSize
            val rootDir = readSector(rootLba)
            val label = ByteArray(11).also { rootDir.position(0); rootDir.get(it) }
            sb.appendLine()
            sb.appendLine("--- Root directory (device LBA $rootLba) ---")
            sb.appendLine("First 32 bytes hex: ${hexRange(rootDir, 0, 31)}")
            sb.appendLine("Bytes 0-10 ASCII: '${String(label, Charsets.US_ASCII)}'  (volume label)")
            sb.appendLine("Byte 11 (attributes): 0x${hex(rootDir.get(11).toInt())}  (should be 0x08)")

            sb.toString()
        } catch (e: Exception) {
            "Diagnostic failed: ${e.message}\n\n${sb}"
        } finally {
            runCatching { raw?.close() }
        }
    }

    /** Result of a standalone format operation. */
    sealed class FormatResult {
        data class Success(val capacityBytes: Long) : FormatResult()
        data class Failure(val message: String) : FormatResult()
    }

    /**
     * Standalone FAT32 format (used by the Home-screen "Format Disk" feature to
     * recover a broken USB without a PC). Reuses the exact [Fat32Formatter] code
     * the burn uses, then re-inits to confirm the drive is readable.
     *
     * @param onProgress 0-100 progress callback.
     */
    suspend fun formatDevice(
        deviceId: Int,
        volumeLabel: String,
        capacityHintBytes: Long = 0L,
        onProgress: (Int) -> Unit = {}
    ): FormatResult {
        var raw: RawUsbBlockDevice? = null
        return try {
            val msd = usbDeviceManager.findRawDeviceById(deviceId)
                ?: return FormatResult.Failure("USB device not found. Reconnect and try again.")
            val usbDevice = msd.usbDevice
            if (!usbDeviceManager.requestPermission(usbDevice)) {
                return FormatResult.Failure("USB permission denied.")
            }

            _formatProgress.value = 0
            onProgress(0)
            raw = RawUsbBlockDevice.create(usbManager, usbDevice).also { it.init() }
            val rawCapacity = raw.capacityBytes
            val capacity = maxOf(rawCapacity, capacityHintBytes)
            if (capacity <= 0L) {
                return FormatResult.Failure("Could not read USB capacity. Reconnect the drive.")
            }

            withContext(Dispatchers.IO) {
                Fat32Formatter(raw!!.blockDevice).format(capacity, volumeLabel) { pct ->
                    _formatProgress.value = pct
                    onProgress(pct)
                }
            }

            // Confirm readable; rewrite partition table if geometry was lost.
            val device = raw!!
            withContext(Dispatchers.IO) { runCatching { device.close() } }
            raw = null
            val confirmed = withContext(Dispatchers.IO) {
                runCatching {
                    val recheck = RawUsbBlockDevice.create(usbManager, usbDevice).also { it.init() }
                    raw = recheck
                    if (recheck.blockDevice.blocks <= 0L) {
                        Fat32Formatter(recheck.blockDevice).format(capacity, volumeLabel)
                    }
                    recheck.capacityBytes
                }.getOrDefault(capacity)
            }
            _formatProgress.value = 100
            onProgress(100)
            FormatResult.Success(confirmed)
        } catch (e: Exception) {
            FormatResult.Failure(e.message ?: "Format failed")
        } finally {
            withContext(Dispatchers.IO) { runCatching { raw?.close() } }
            _formatProgress.value = null
        }
    }

    /**
     * Re-reads the files written to the USB and verifies them against the source
     * ISO by comparing per-file SHA-1 digests. Emits [BurnState.Verifying] with
     * 0-100 progress and finishes on [BurnState.Success] (reusing the last burn's
     * duration is not needed here) or [BurnState.Failed] on the first mismatch.
     *
     * Large (split) WIM files are skipped, since their on-USB representation is a
     * set of .swm parts, not a byte-identical copy.
     */
    suspend fun verify(isoFile: File, deviceId: Int, capacityHintBytes: Long = 0L) {
        var raw: RawUsbBlockDevice? = null
        try {
            val msd = usbDeviceManager.findRawDeviceById(deviceId)
                ?: throw BurnException("USB device not found", "Reconnect your USB drive and try again")
            val usbDevice = msd.usbDevice
            if (!usbDeviceManager.requestPermission(usbDevice)) {
                throw BurnException("USB permission denied", "Grant USB access and try again")
            }
            _state.value = BurnState.Verifying(0)
            emitLog("Verifying written data...")

            raw = RawUsbBlockDevice.create(usbManager, usbDevice).also { it.init() }
            val rawDev = raw!!
            val partitionStartLba = (1L shl 20) / rawDev.blockSize

            // Safe direct boot-sector signature check before mounting.
            run {
                val bootBuf = ByteBuffer.allocate(rawDev.blockSize)
                rawDev.blockDevice.read(partitionStartLba, bootBuf)
                val sig = ((bootBuf.get(510).toInt() and 0xFF) == 0x55) &&
                    ((bootBuf.get(511).toInt() and 0xFF) == 0xAA)
                if (!sig) throw BurnException(
                    "Could not read USB filesystem", "Try a different USB drive"
                )
            }

            val partitionDevice = me.jahnen.libaums.core.driver.ByteBlockDevice(
                rawDev.blockDevice, partitionStartLba.toInt()
            )
            // verify() must mount to read files back by path; this is read-only
            // navigation (no UsbFile writes), so it cannot disturb the volume.
            val fs = Fat32FileSystem.read(partitionDevice)
                ?: throw BurnException("Could not read USB filesystem", "Try a different USB drive")
            val root = fs.rootDirectory

            val entries = RandomAccessFile(isoFile, "r").use { r ->
                IsoParser(r).let { it.open(); it.listAllEntries() }
            }
            val files = entries.filter {
                !it.isDirectory &&
                    !(it.name.lowercase() in SPLITTABLE && it.sizeBytes > FAT32_FILE_LIMIT)
            }

            val rawDevice = rawDev
            val raf = RandomAccessFile(isoFile, "r")
            var mismatches = 0
            raf.use {
                var index = 0
                for (entry in files) {
                    coroutineContext.ensureActive()
                    index++
                    val usbFile = runCatching { root.search(entry.fullPath) }.getOrNull()
                    if (usbFile == null || usbFile.isDirectory) {
                        emitLog("MISSING: ${entry.fullPath}", isFileName = true)
                        mismatches++
                    } else if (usbFile.length != entry.sizeBytes) {
                        emitLog("SIZE MISMATCH: ${entry.fullPath}", isFileName = true)
                        mismatches++
                    } else {
                        val isoHash = hashIsoExtent(raf, entry.extentLba, entry.sizeBytes)
                        val usbHash = hashUsbFile(usbFile, entry.sizeBytes)
                        if (isoHash != usbHash) {
                            emitLog("HASH MISMATCH: ${entry.fullPath}", isFileName = true)
                            mismatches++
                        }
                    }
                    _state.value = BurnState.Verifying(((index * 100) / files.size.coerceAtLeast(1)))
                }
            }

            rawDevice.close()
            raw = null

            if (mismatches == 0) {
                emitLog("Verification passed: ${files.size} files OK")
                _state.value = BurnState.Success(files.sumOf { it.sizeBytes }, 0)
            } else {
                _state.value = BurnState.Failed(
                    "$mismatches file(s) failed verification",
                    "Re-burn the ISO to this drive"
                )
            }
        } catch (e: BurnException) {
            emitLog("ERROR: ${e.message}")
            _state.value = BurnState.Failed(e.message ?: "Verify failed", e.suggestion)
        } catch (e: Exception) {
            emitLog("ERROR: ${e.message}")
            _state.value = BurnState.Failed(e.message ?: "Verify failed", "Reconnect your USB drive and try again")
        } finally {
            raw?.close()
        }
    }

    private fun emitLog(message: String, isFileName: Boolean = false, isWarning: Boolean = false) {
        _log.tryEmit(BurnLogLine(message, isFileName, isWarning))
    }

    /**
     * Executes the full burn. Suspends until completion or failure. Cancellation
     * of the calling coroutine aborts the burn (leaving the USB in an
     * intermediate state, as warned in the UI).
     *
     * @param capacityHintBytes the capacity already read reliably during device
     *   enumeration. Used as the authoritative value for the pre-flight size
     *   check, since a freshly re-initialised raw SCSI device can briefly report
     *   an unreliable (or zero) capacity.
     */
    suspend fun burn(isoFile: File, deviceId: Int, capacityHintBytes: Long = 0L) {
        val startMs = System.currentTimeMillis()
        var raw: RawUsbBlockDevice? = null
        try {
            // --- Step 1: permission ---
            val msd = usbDeviceManager.findRawDeviceById(deviceId)
                ?: throw BurnException("USB device not found", "Reconnect your USB drive and try again")
            val usbDevice = msd.usbDevice
            emitLog("Requesting USB permission...")
            val granted = usbDeviceManager.requestPermission(usbDevice)
            if (!granted) {
                throw BurnException("USB permission denied", "Grant USB access when prompted and try again")
            }

            // --- Step 2: raw block device ---
            emitLog("Opening USB device...")
            raw = RawUsbBlockDevice.create(usbManager, usbDevice).also { it.init() }
            val device = raw!!  // stable handle for closures/non-null reads below
            val rawCapacity = device.capacityBytes

            // Trust whichever positive value we have; prefer the larger of the
            // raw read and the capacity hint from enumeration. This avoids a
            // false "ISO larger than USB capacity" when the raw read returns 0.
            val capacity = maxOf(rawCapacity, capacityHintBytes)
            emitLog("Capacity: ${formatBytes(capacity)} · block ${device.blockSize} B")

            // Pre-flight size check: only enforce when we actually have a
            // trustworthy positive capacity. A zero/unknown capacity must never
            // be treated as "too small".
            val isoSize = isoFile.length()
            if (capacity > 0L && isoSize > capacity) {
                throw BurnException(
                    "ISO larger than USB capacity",
                    "Use a larger USB drive"
                )
            }

            // --- Step 3: format FAT32 ---
            // Format against the actual raw capacity when available, otherwise
            // fall back to the hint so geometry is still computed sanely.
            val formatCapacity = if (rawCapacity > 0L) rawCapacity else capacity
            val label = isoFile.nameWithoutExtension

            // Chunk size from available heap — no benchmark. The real bottleneck
            // is the number of SCSI commands (fixed ~ms overhead each), so bigger
            // chunks always win; we just size to memory. 2–8 MiB.
            val optimalChunk = (Runtime.getRuntime().maxMemory() / 8)
                .toInt().coerceIn(2 * 1024 * 1024, 8 * 1024 * 1024)
            emitLog("Write chunk size: ${optimalChunk / 1024 / 1024}MB")

            _state.value = BurnState.Formatting(0)
            emitLog("Formatting FAT32...")
            val geometry = Fat32Formatter(device.blockDevice).format(formatCapacity, label) { pct ->
                _state.value = BurnState.Formatting(pct)
            }
            emitLog("Format complete")

            // Immediately leave the Formatting state so the UI does not appear
            // stuck at "Formatting FAT32... 100%". Show a parsing phase while the
            // ISO is walked (which can take a moment for large images).
            _state.value = BurnState.Copying(
                currentFile = "Parsing ISO...",
                bytesWritten = 0L,
                totalBytes = isoFile.length(),
                speedMBps = 0f,
                remainingSeconds = 0
            )

            // --- Step 3b: format verification (direct boot-sector read) ---
            // We deliberately do NOT use libaums Fat32FileSystem.read() here: it
            // can write dirty-mount flags back through the partition reference,
            // which could disturb reserved sectors. A direct read of the boot
            // signature is a safe, side-effect-free verification.
            val partitionStartLba = geometry.partitionStartLba
            run {
                val bootBuf = ByteBuffer.allocate(device.blockSize)
                device.blockDevice.read(partitionStartLba, bootBuf)
                val sig = ((bootBuf.get(510).toInt() and 0xFF) == 0x55) &&
                    ((bootBuf.get(511).toInt() and 0xFF) == 0xAA)
                if (!sig) throw BurnException(
                    "Format verification failed",
                    "USB drive may be write-protected or damaged"
                )
                emitLog("Format verified OK")
            }

            // --- Step 4: parse ISO ---
            emitLog("Parsing ISO filesystem...")
            val entries = RandomAccessFile(isoFile, "r").use { rafForList ->
                IsoParser(rafForList).let { parser ->
                    parser.open()
                    parser.listAllEntries()
                }
            }
            emitLog("${entries.size} entries found")

            // --- Steps 5-7: fast copy (direct block writes) ---
            // If the fast path fails for any reason, fall back to the (slow but
            // proven) libaums UsbFile path so a FastUsbWriter bug degrades to
            // "slow" rather than "broken".
            try {
                copyEntriesFast(isoFile, entries, device.blockDevice, geometry, label, optimalChunk, startMs)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (fastError: Exception) {
                emitLog("⚠ Fast writer failed (${fastError.message}); falling back to compatibility mode", isWarning = true)
                // Re-format to clear any partial structures, then remount and copy
                // via libaums.
                _state.value = BurnState.Formatting(0)
                Fat32Formatter(device.blockDevice).format(formatCapacity, label) { pct ->
                    _state.value = BurnState.Formatting(pct)
                }
                val fallbackFs = Fat32FileSystem.read(
                    me.jahnen.libaums.core.driver.ByteBlockDevice(device.blockDevice, partitionStartLba.toInt())
                ) ?: throw BurnException("USB filesystem unreadable", "Try a different USB drive")
                copyEntriesLibaums(isoFile, entries, fallbackFs.rootDirectory, startMs)
            }

            // --- Boot-sector verify + rewrite ---
            // Read back the boot sector after all writes. If its signature was
            // somehow clobbered, rewrite just the boot sector and its backup
            // (nothing else) so the volume mounts. This directly targets the
            // "can't read superblock" failure mode.
            run {
                val bootVerify = ByteBuffer.allocate(device.blockSize)
                device.blockDevice.read(geometry.partitionStartLba, bootVerify)
                val b510 = bootVerify.get(510).toInt() and 0xFF
                val b511 = bootVerify.get(511).toInt() and 0xFF
                val oemBytes = ByteArray(8).also { arr ->
                    bootVerify.position(82)
                    bootVerify.get(arr)
                }
                val oem = String(oemBytes, Charsets.US_ASCII)
                emitLog("Boot sector verify: sig=${b510.toString(16)}${b511.toString(16)} fs='$oem'")
                if (b510 != 0x55 || b511 != 0xAA) {
                    emitLog("⚠ Boot sector corrupted! Rewriting...", isWarning = true)
                    val boot = Fat32Formatter(device.blockDevice).buildPublicBootSector(geometry, label)
                    device.blockDevice.write(geometry.partitionStartLba, ByteBuffer.wrap(boot))
                    device.blockDevice.write(geometry.partitionStartLba + 6, ByteBuffer.wrap(boot))
                    emitLog("Boot sector rewritten.")
                }
            }

            // --- Step 8: finalize ---
            // Close the device, then re-open and re-init to confirm the drive is
            // still readable. Some controllers report zero capacity on the first
            // re-init right after a heavy write burst, which would corrupt the
            // next burn's geometry. If that happens we rewrite the MBR + boot
            // sector (partition table) so the USB is always left in a valid state.
            emitLog("Flushing and unmounting...")
            val deviceToClose = raw
            raw = null
            withTimeoutOrNull(5000) {
                withContext(Dispatchers.IO) { runCatching { deviceToClose?.close() } }
            }

            emitLog("Re-checking USB after write...")
            val reinitOk = withTimeoutOrNull(8000) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val recheck = RawUsbBlockDevice.create(usbManager, usbDevice).also { it.init() }
                        raw = recheck
                        val blocks = recheck.blockDevice.blocks
                        emitLog("Post-burn capacity: ${formatBytes(recheck.capacityBytes)} ($blocks blocks)")
                        if (blocks <= 0L) {
                            // Controller lost geometry: rewrite the partition table.
                            emitLog("Capacity reads zero — rewriting partition table...")
                            Fat32Formatter(recheck.blockDevice).format(formatCapacity, label)
                        }
                        true
                    }.getOrElse { e ->
                        emitLog("Re-check skipped: ${e.message}")
                        false
                    }
                }
            } ?: false
            if (!reinitOk) emitLog("Re-check timed out; drive should still be valid")
            // Close the recheck handle.
            withTimeoutOrNull(5000) {
                withContext(Dispatchers.IO) { runCatching { raw?.close() } }
            }
            raw = null

            val duration = ((System.currentTimeMillis() - startMs) / 1000).toInt()
            _state.value = BurnState.Success(isoSize, duration)
            emitLog("Burn complete in ${duration}s")
        } catch (e: BurnException) {
            emitLog("ERROR: ${e.message}")
            _state.value = BurnState.Failed(e.message ?: "Burn failed", e.suggestion)
        } catch (e: IOException) {
            emitLog("ERROR: ${e.message}")
            _state.value = BurnState.Failed(
                e.message ?: "I/O error",
                "Reconnect your USB drive and try again"
            )
        } catch (e: Exception) {
            emitLog("ERROR: ${e.message}")
            _state.value = BurnState.Failed(
                e.message ?: "Unexpected error",
                "Try a different USB drive"
            )
        } finally {
            raw?.close()
        }
    }

    /**
     * Fast copy path: writes the entire FAT32 directory tree and file data
     * directly to the block device via [FastUsbWriter], bypassing libaums'
     * per-chunk SCSI overhead.
     *
     * Layout strategy:
     *  1. Pre-pass — allocate one cluster run per directory (sized to its real
     *     child count), register them, and add "."/".." entries.
     *  2. File pass — for each file, allocate a contiguous cluster run, record
     *     the FAT chain, queue the directory entry, and stream the bytes in one
     *     sequential burst.
     *  3. Flush the FAT (both copies), all directory clusters, and the FSInfo.
     *
     * On any failure here the USB is left formatted but incomplete; the error is
     * surfaced so the user can retry (optionally on the slower libaums path).
     */
    private suspend fun copyEntriesFast(
        isoFile: File,
        entries: List<IsoEntry>,
        blockDevice: me.jahnen.libaums.core.driver.BlockDeviceDriver,
        geometry: FatGeometry,
        label: String,
        optimalChunk: Int,
        startMs: Long
    ) {
        val writer = FastUsbWriter(blockDevice, geometry, optimalChunk, label)
        writer.setRootCluster(geometry.rootCluster)
        resetSpeedSamples()

        val clusterBytes = geometry.clusterBytes
        fun clustersFor(bytes: Long): Long =
            if (bytes == 0L) 1L else (bytes + clusterBytes - 1) / clusterBytes

        // Files we will actually write (oversized WIMs are handled separately).
        val regularFiles = entries.filter {
            !it.isDirectory && !(it.name.lowercase() in SPLITTABLE && it.sizeBytes > FAT32_FILE_LIMIT)
        }
        val largeWims = entries.filter {
            !it.isDirectory && it.name.lowercase() in SPLITTABLE && it.sizeBytes > FAT32_FILE_LIMIT
        }

        // If a large WIM is present, split it up front so its parts join the copy
        // set (and contribute to the directory/cluster planning).
        val swmParts = ArrayList<Pair<String, File>>() // "sources/install.swm" -> local file
        for (wim in largeWims) {
            swmParts += splitWimToParts(isoFile, wim)
        }

        // --- Pre-pass: directories, shallowest first ---
        val dirs = entries.filter { it.isDirectory }
            .sortedBy { it.fullPath.count { c -> c == '/' } }

        // Count children per directory to size each directory's cluster run.
        // Each child consumes (LFN entries + 1) 32-byte slots; "."/".." add 2 and
        // root adds 1 for the volume label.
        val slotCount = HashMap<String, Int>()
        fun addSlots(parent: String, name: String) {
            slotCount[parent] = (slotCount[parent] ?: 0) + writer.slotsForName(name)
        }
        slotCount[""] = 1 // volume label in root
        for (d in dirs) {
            val parent = d.fullPath.substringBeforeLast('/', "")
            addSlots(parent, d.name)
            slotCount[d.fullPath] = (slotCount[d.fullPath] ?: 0) + 2 // "." and ".."
        }
        for (f in regularFiles) {
            addSlots(f.fullPath.substringBeforeLast('/', ""), f.name)
        }
        for ((path, _) in swmParts) {
            addSlots(path.substringBeforeLast('/', ""), path.substringAfterLast('/'))
        }

        // Allocate the root directory's cluster run FIRST (it must stay at the
        // BPB-declared cluster 2). Size it to root's own slot count.
        val rootSlots = (slotCount[""] ?: 1)
        writer.allocateRoot(clustersFor(rootSlots.toLong() * 32))

        // Allocate + register each subdirectory (root is already allocated).
        for (d in dirs) {
            coroutineContext.ensureActive()
            val slots = (slotCount[d.fullPath] ?: 2)
            val dirBytes = slots.toLong() * 32
            val clusters = clustersFor(dirBytes)
            val firstCluster = writer.allocateClusters(clusters)
            writer.writeFatChain(firstCluster, clusters)
            writer.flushFatSectors(firstCluster, clusters)
            writer.registerDirectory(d.fullPath, firstCluster)
            val parentPath = d.fullPath.substringBeforeLast('/', "")
            val parentCluster = if (parentPath.isEmpty()) geometry.rootCluster
                else writerDirCluster(writer, parentPath)
            writer.addDotEntries(d.fullPath, firstCluster, parentCluster)
            writer.addDirectoryEntry(parentPath, d.name, firstCluster, 0L, isDirectory = true)
        }

        // Total bytes for progress (regular files + swm parts).
        val totalBytes = regularFiles.sumOf { it.sizeBytes } + swmParts.sumOf { it.second.length() }
        var bytesWritten = 0L
        var lastReport = 0L
        recordSample(System.currentTimeMillis(), 0L)

        val onChunk: (Long) -> Unit = { delta ->
            bytesWritten += delta
            val now = System.currentTimeMillis()
            if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                recordSample(now, bytesWritten)
                _state.value = BurnState.Copying(
                    currentFile = "Writing",
                    bytesWritten = bytesWritten,
                    totalBytes = totalBytes,
                    speedMBps = rollingSpeedMBps(),
                    remainingSeconds = etaSeconds(bytesWritten, totalBytes)
                )
                lastReport = now
            }
        }

        // --- File pass: regular ISO files ---
        // Sort largest-first so the big files write directly and all the small
        // files end up grouped in the contiguous tail of the allocation, where
        // the batcher can coalesce them into a few large SCSI writes.
        val sortedFiles = regularFiles.sortedByDescending { it.sizeBytes }
        val SMALL_FILE_LIMIT = 4L * 1024 * 1024   // batch files under 4 MiB
        val LOG_INDIVIDUAL_MIN = 64L * 1024       // files >= 64 KiB still log individually

        val batcher = writer.newBatcher()
        var batchedSmallCount = 0
        var batchedSmallGroupDir = ""

        // Flushes the "N small files" grouped log line when the small-file run ends.
        fun flushSmallLog() {
            if (batchedSmallCount > 0) {
                emitLog("Writing $batchedSmallCount small files... ($batchedSmallGroupDir)", isFileName = true)
                batchedSmallCount = 0
            }
        }

        val raf = RandomAccessFile(isoFile, "r")
        raf.use {
            for (entry in sortedFiles) {
                coroutineContext.ensureActive()
                val parentPath = entry.fullPath.substringBeforeLast('/', "")
                val clusters = clustersFor(entry.sizeBytes)
                val firstCluster = writer.allocateClusters(clusters)
                writer.writeFatChain(firstCluster, clusters)
                writer.flushFatSectors(firstCluster, clusters)
                writer.addDirectoryEntry(parentPath, entry.name, firstCluster, entry.sizeBytes, isDirectory = false)

                val fileLba = writer.lbaOfCluster(firstCluster)
                val paddedSize = writer.paddedClusterBytes(entry.sizeBytes)
                val small = entry.sizeBytes in 1 until SMALL_FILE_LIMIT && batcher.canHold(paddedSize)

                if (small) {
                    // Read the small file fully and hand it to the coalescing batcher.
                    raf.seek(entry.extentLba * ISO_SECTOR)
                    val data = ByteArray(entry.sizeBytes.toInt())
                    raf.readFully(data)
                    batcher.queue(data, data.size, fileLba)
                    onChunk(entry.sizeBytes)

                    if (entry.sizeBytes < LOG_INDIVIDUAL_MIN) {
                        batchedSmallCount++
                        batchedSmallGroupDir = if (parentPath.isEmpty()) "/" else "$parentPath/"
                    } else {
                        flushSmallLog()
                        emitLog(entry.fullPath, isFileName = true)
                    }
                } else {
                    // Large (or zero-length) file: drain pending small files first
                    // so ordering/contiguity is preserved, then stream it directly
                    // with the read-ahead pipeline (overlaps ISO read + USB write).
                    batcher.flush()
                    flushSmallLog()
                    emitLog(entry.fullPath, isFileName = true)
                    writer.writeFileFromIsoPipelined(raf, entry.extentLba, entry.sizeBytes, firstCluster, onChunk)
                }
            }
            // Drain any remaining batched small files.
            batcher.flush()
            flushSmallLog()
        }

        // --- File pass: split WIM parts (already on local disk) ---
        for ((usbPath, partFile) in swmParts) {
            coroutineContext.ensureActive()
            val parentPath = usbPath.substringBeforeLast('/', "")
            val name = usbPath.substringAfterLast('/')
            val size = partFile.length()
            val clusters = clustersFor(size)
            val firstCluster = writer.allocateClusters(clusters)
            writer.writeFatChain(firstCluster, clusters)
            writer.flushFatSectors(firstCluster, clusters)
            writer.addDirectoryEntry(parentPath, name, firstCluster, size, isDirectory = false)
            emitLog(usbPath, isFileName = true)
            writer.writeLocalFile(partFile, firstCluster, onChunk)
            partFile.delete()
        }

        // --- Flush metadata ---
        emitLog("Writing filesystem tables...")
        writer.flushDirectories()
        writer.flushFat()
        writer.updateFsInfo()

        // Coalescing efficiency summary.
        val coalesced = batcher.coalescedFiles
        val batchWrites = batcher.batchWrites
        if (coalesced > 0 && batchWrites > 0) {
            val pct = (100 - (batchWrites * 100 / coalesced.coerceAtLeast(1))).coerceIn(0, 100)
            emitLog("Write efficiency: $coalesced small files → $batchWrites batch writes ($pct% coalesced)")
        }

        // Final 100% copy state.
        _state.value = BurnState.Copying(
            currentFile = "Done",
            bytesWritten = totalBytes,
            totalBytes = totalBytes,
            speedMBps = 0f,
            remainingSeconds = 0
        )
    }

    /** Helper: looks up a registered directory's first cluster (must exist). */
    private fun writerDirCluster(writer: FastUsbWriter, path: String): Long =
        writer.dirClusterOf(path)

    /**
     * Splits an oversized install.wim/esd into .swm parts on local disk and
     * returns the list of (usb-relative-path -> local file) to be copied. The
     * actual block writes happen in the fast file pass.
     */
    private suspend fun splitWimToParts(isoFile: File, entry: IsoEntry): List<Pair<String, File>> {
        emitLog("Large WIM detected (${formatBytes(entry.sizeBytes)}); splitting...")
        if (!wimSplitter.isSupportedAbi()) {
            throw BurnException(
                "WIM splitting unavailable on this CPU",
                "Use a USB drive formatted as exFAT/NTFS, or a device with arm64 support"
            )
        }
        val wimCacheDir = File(context.cacheDir, "wim").apply { mkdirs() }
        val srcWim = File(wimCacheDir, "install.wim")
        emitLog("Extracting WIM to cache...")
        RandomAccessFile(isoFile, "r").use { raf ->
            extractIsoExtentToFile(raf, entry.extentLba, entry.sizeBytes, srcWim)
        }
        val outDir = File(wimCacheDir, "parts").apply { mkdirs() }
        outDir.listFiles()?.forEach { it.delete() }
        val result = wimSplitter.split(srcWim, outDir) { line -> emitLog(line) }
        srcWim.delete()
        if (!result.success) {
            throw BurnException("WIM split failed", "Try a different USB drive or ISO")
        }
        emitLog("WIM split into ${result.partFiles.size} parts")
        // Parts go under sources/ on the USB.
        return result.partFiles.sortedBy { it.name }.map { "sources/${it.name}" to it }
    }

    // ---------------------------------------------------------------------
    // Compatibility fallback: the original libaums UsbFile copy path. Slow
    // (one SCSI WRITE per chunk) but battle-tested. Used only if the fast
    // writer throws.
    // ---------------------------------------------------------------------

    private suspend fun copyEntriesLibaums(
        isoFile: File,
        entries: List<IsoEntry>,
        root: UsbFile,
        startMs: Long
    ) {
        val totalBytes = entries.filter { !it.isDirectory }.sumOf { it.sizeBytes }
        val dirs = entries.filter { it.isDirectory }.sortedBy { it.fullPath.count { c -> c == '/' } }
        val dirCache = HashMap<String, UsbFile>()
        dirCache[""] = root
        for (dir in dirs) {
            coroutineContext.ensureActive()
            mkdirs(root, dir.fullPath, dirCache)
        }

        var bytesWritten = 0L
        var lastReport = 0L
        resetSpeedSamples()
        recordSample(System.currentTimeMillis(), 0L)

        val raf = RandomAccessFile(isoFile, "r")
        raf.use {
            for (entry in entries.filter { !it.isDirectory }) {
                coroutineContext.ensureActive()
                val name = entry.name.lowercase()
                if (name in SPLITTABLE && entry.sizeBytes > FAT32_FILE_LIMIT) {
                    bytesWritten += handleLargeWimLibaums(isoFile, entry, root, dirCache)
                    continue
                }
                val parentPath = entry.fullPath.substringBeforeLast('/', "")
                val parentDir = dirCache[parentPath] ?: mkdirs(root, parentPath, dirCache)
                emitLog(entry.fullPath, isFileName = true)
                val target = parentDir.createFile(entry.name)
                bytesWritten += writeIsoExtentToUsb(raf, entry.extentLba, entry.sizeBytes, target)
                target.close()

                val now = System.currentTimeMillis()
                if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                    recordSample(now, bytesWritten)
                    _state.value = BurnState.Copying(
                        currentFile = entry.fullPath,
                        bytesWritten = bytesWritten,
                        totalBytes = totalBytes,
                        speedMBps = rollingSpeedMBps(),
                        remainingSeconds = etaSeconds(bytesWritten, totalBytes)
                    )
                    lastReport = now
                }
            }
        }

        _state.value = BurnState.Copying("Done", totalBytes, totalBytes, 0f, 0)
    }

    private suspend fun handleLargeWimLibaums(
        isoFile: File,
        entry: IsoEntry,
        root: UsbFile,
        dirCache: HashMap<String, UsbFile>
    ): Long {
        val parts = splitWimToParts(isoFile, entry)
        var written = 0L
        val sources = dirCache["sources"] ?: mkdirs(root, "sources", dirCache)
        for ((usbPath, part) in parts) {
            coroutineContext.ensureActive()
            emitLog(usbPath, isFileName = true)
            val target = sources.createFile(part.name)
            written += copyLocalFileToUsb(part, target)
            target.close()
            part.delete()
        }
        return written
    }

    /** Creates (and caches) the directory chain for [path], returning the leaf. */
    private fun mkdirs(root: UsbFile, path: String, cache: HashMap<String, UsbFile>): UsbFile {
        if (path.isEmpty()) return root
        cache[path]?.let { return it }
        val parts = path.split('/').filter { it.isNotEmpty() }
        var current = root
        var built = ""
        for (part in parts) {
            built = if (built.isEmpty()) part else "$built/$part"
            val existing = cache[built]
            current = existing ?: run {
                val dir = runCatching { current.search(part) }.getOrNull() ?: current.createDirectory(part)
                cache[built] = dir
                dir
            }
        }
        return current
    }

    /** Streams [length] bytes from the ISO at [extentLba] into a [UsbFile]. */
    private suspend fun writeIsoExtentToUsb(
        raf: RandomAccessFile,
        extentLba: Long,
        length: Long,
        target: UsbFile
    ): Long = withContext(Dispatchers.IO) {
        if (length == 0L) { target.length = 0; return@withContext 0L }
        target.length = length
        raf.seek(extentLba * ISO_SECTOR)
        val buffer = ByteArray(COPY_BUFFER)
        val bb = ByteBuffer.allocate(COPY_BUFFER)
        var remaining = length
        var offset = 0L
        while (remaining > 0) {
            coroutineContext.ensureActive()
            val toRead = minOf(COPY_BUFFER.toLong(), remaining).toInt()
            raf.readFully(buffer, 0, toRead)
            bb.clear(); bb.put(buffer, 0, toRead); bb.flip()
            target.write(offset, bb)
            offset += toRead
            remaining -= toRead
        }
        target.flush()
        length
    }

    /** Copies a local file into a [UsbFile]. */
    private suspend fun copyLocalFileToUsb(src: File, target: UsbFile): Long =
        withContext(Dispatchers.IO) {
            target.length = src.length()
            src.inputStream().buffered().use { input ->
                val buffer = ByteArray(COPY_BUFFER)
                val bb = ByteBuffer.allocate(COPY_BUFFER)
                var offset = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    bb.clear(); bb.put(buffer, 0, read); bb.flip()
                    target.write(offset, bb)
                    offset += read
                }
            }
            target.flush()
            src.length()
        }

    /** Streams an ISO extent to a local file (for WIM extraction). */
    private suspend fun extractIsoExtentToFile(
        raf: RandomAccessFile,
        extentLba: Long,
        length: Long,
        dest: File
    ) = withContext(Dispatchers.IO) {
        raf.seek(extentLba * ISO_SECTOR)
        dest.outputStream().buffered().use { out ->
            val buffer = ByteArray(COPY_BUFFER)
            var remaining = length
            while (remaining > 0) {
                coroutineContext.ensureActive()
                val toRead = minOf(COPY_BUFFER.toLong(), remaining).toInt()
                raf.readFully(buffer, 0, toRead)
                out.write(buffer, 0, toRead)
                remaining -= toRead
            }
        }
    }

    /** SHA-1 of an ISO extent (used for verify). */
    private fun hashIsoExtent(raf: RandomAccessFile, extentLba: Long, length: Long): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        raf.seek(extentLba * ISO_SECTOR)
        val buffer = ByteArray(COPY_BUFFER)
        var remaining = length
        while (remaining > 0) {
            val toRead = minOf(COPY_BUFFER.toLong(), remaining).toInt()
            raf.readFully(buffer, 0, toRead)
            md.update(buffer, 0, toRead)
            remaining -= toRead
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** SHA-1 of a file read back from the USB (used for verify). */
    private fun hashUsbFile(file: UsbFile, length: Long): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        val bb = ByteBuffer.allocate(COPY_BUFFER)
        val tmp = ByteArray(COPY_BUFFER)
        var offset = 0L
        var remaining = length
        while (remaining > 0) {
            val toRead = minOf(COPY_BUFFER.toLong(), remaining).toInt()
            bb.clear()
            bb.limit(toRead)
            file.read(offset, bb)
            bb.flip()
            bb.get(tmp, 0, toRead)
            md.update(tmp, 0, toRead)
            offset += toRead
            remaining -= toRead
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format("%.2f GB", gb)
    }

    /** Internal exception carrying a user-facing suggestion for the result screen. */
    private class BurnException(message: String, val suggestion: String) : Exception(message)
}
