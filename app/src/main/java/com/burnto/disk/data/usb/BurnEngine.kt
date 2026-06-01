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

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val COPY_BUFFER = 64 * 1024
        private const val ISO_SECTOR = IsoParser.SECTOR_SIZE
        private const val FAT32_FILE_LIMIT = 0xFFFFFFFFL
        private const val PROGRESS_INTERVAL_MS = 500L
        // Files that must be split for FAT32.
        private val SPLITTABLE = setOf("install.wim", "install.esd")
    }

    fun resetState() {
        _state.value = BurnState.Idle
    }

    private fun emitLog(message: String, isFileName: Boolean = false) {
        _log.tryEmit(BurnLogLine(message, isFileName))
    }

    /**
     * Executes the full burn. Suspends until completion or failure. Cancellation
     * of the calling coroutine aborts the burn (leaving the USB in an
     * intermediate state, as warned in the UI).
     */
    suspend fun burn(isoFile: File, deviceId: Int) {
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
            val capacity = raw.capacityBytes
            emitLog("Capacity: ${formatBytes(capacity)} · block ${raw.blockSize} B")

            // Pre-flight size check.
            val isoSize = isoFile.length()
            if (isoSize > capacity) {
                throw BurnException(
                    "ISO larger than USB capacity",
                    "Use a larger USB drive"
                )
            }

            // --- Step 3: format FAT32 ---
            val label = isoFile.nameWithoutExtension
            _state.value = BurnState.Formatting(0)
            emitLog("Formatting FAT32...")
            Fat32Formatter(raw.blockDevice).format(capacity, label) { pct ->
                _state.value = BurnState.Formatting(pct)
            }
            emitLog("Format complete")

            // --- Step 3b: mount fresh filesystem ---
            // The new FAT32 partition starts 1 MiB in; wrap the raw device with that offset.
            val partitionStartLba = (1L shl 20) / raw.blockSize
            val partitionDevice = me.jahnen.libaums.core.driver.ByteBlockDevice(
                raw.blockDevice, partitionStartLba.toInt()
            )
            val fs: FileSystem = Fat32FileSystem.read(partitionDevice)
                ?: throw BurnException("Format verification failed", "USB drive may be write-protected or damaged")
            val root = fs.rootDirectory

            // --- Step 4: parse ISO ---
            emitLog("Parsing ISO filesystem...")
            val entries = RandomAccessFile(isoFile, "r").use { rafForList ->
                IsoParser(rafForList).let { parser ->
                    parser.open()
                    parser.listAllEntries()
                }
            }
            emitLog("${entries.size} entries found")

            // --- Steps 5-7: copy ---
            copyEntries(isoFile, entries, root, fs, startMs)

            // --- Step 8: finalize ---
            emitLog("Flushing and unmounting...")
            raw.close()
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
     * Copies all ISO entries to the USB filesystem. Directories are created first
     * (sorted by depth), then files. An oversized install.wim/esd is extracted to
     * cache, split into .swm parts via wimlib-imagex, and the parts copied instead.
     */
    private suspend fun copyEntries(
        isoFile: File,
        entries: List<IsoEntry>,
        root: UsbFile,
        fs: FileSystem,
        startMs: Long
    ) {
        // Compute the real byte total (a large WIM contributes its actual size).
        val totalBytes = entries.filter { !it.isDirectory }.sumOf { it.sizeBytes }

        // Pre-create directories, shallowest first.
        val dirs = entries.filter { it.isDirectory }.sortedBy { it.fullPath.count { c -> c == '/' } }
        val dirCache = HashMap<String, UsbFile>()
        dirCache[""] = root
        for (dir in dirs) {
            coroutineContext.ensureActive()
            mkdirs(root, dir.fullPath, dirCache)
        }

        var bytesWritten = 0L
        var lastReport = 0L
        var lastBytesAtReport = 0L
        var lastTimeAtReport = startMs

        val raf = RandomAccessFile(isoFile, "r")
        raf.use {
            val files = entries.filter { !it.isDirectory }
            for (entry in files) {
                coroutineContext.ensureActive()

                val name = entry.name.lowercase()
                if (name in SPLITTABLE && entry.sizeBytes > FAT32_FILE_LIMIT) {
                    bytesWritten += handleLargeWim(raf, entry, root, dirCache)
                    continue
                }

                val parentPath = entry.fullPath.substringBeforeLast('/', "")
                val parentDir = dirCache[parentPath] ?: mkdirs(root, parentPath, dirCache)
                emitLog(entry.fullPath, isFileName = true)

                val target = parentDir.createFile(entry.name)
                bytesWritten += writeIsoExtentToUsb(raf, entry.extentLba, entry.sizeBytes, target)
                target.close()

                // Throttled progress reporting (~every 500 ms).
                val now = System.currentTimeMillis()
                if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                    val elapsedSec = (now - lastTimeAtReport) / 1000f
                    val speed = if (elapsedSec > 0) (bytesWritten - lastBytesAtReport) / elapsedSec else 0f
                    val remaining = if (speed > 0) ((totalBytes - bytesWritten) / speed).toInt() else 0
                    _state.value = BurnState.Copying(
                        currentFile = entry.fullPath,
                        bytesWritten = bytesWritten,
                        totalBytes = totalBytes,
                        speedMBps = speed / (1024 * 1024),
                        remainingSeconds = remaining
                    )
                    lastReport = now
                    lastBytesAtReport = bytesWritten
                    lastTimeAtReport = now
                }
            }
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

    /**
     * Extracts an oversized install.wim from the ISO to cache, splits it into
     * `install.swm`/`install2.swm`/... under the same `sources/` directory on the
     * USB, and copies the parts. Returns total bytes written for progress.
     */
    private suspend fun handleLargeWim(
        raf: RandomAccessFile,
        entry: IsoEntry,
        root: UsbFile,
        dirCache: HashMap<String, UsbFile>
    ): Long {
        emitLog("Large WIM detected (${formatBytes(entry.sizeBytes)}); splitting...")
        if (!wimSplitter.isSupportedAbi()) {
            throw BurnException(
                "WIM splitting unavailable on this CPU",
                "Use a USB drive formatted as exFAT/NTFS, or a device with arm64 support"
            )
        }

        // 1. Extract the whole WIM to cache.
        val wimCacheDir = File(context.cacheDir, "wim").apply { mkdirs() }
        val srcWim = File(wimCacheDir, "install.wim")
        emitLog("Extracting WIM to cache...")
        extractIsoExtentToFile(raf, entry.extentLba, entry.sizeBytes, srcWim)

        // 2. Split into .swm parts.
        val outDir = File(wimCacheDir, "parts").apply { mkdirs() }
        outDir.listFiles()?.forEach { it.delete() }
        _state.value = BurnState.Formatting(99) // brief indeterminate-ish marker
        val result = wimSplitter.split(srcWim, outDir) { line -> emitLog(line) }
        if (!result.success) {
            throw BurnException("WIM split failed", "Try a different USB drive or ISO")
        }

        // 3. Copy parts into sources/ on the USB.
        val sources = dirCache["sources"] ?: mkdirs(root, "sources", dirCache)
        var written = 0L
        for (part in result.partFiles) {
            coroutineContext.ensureActive()
            emitLog("sources/${part.name}", isFileName = true)
            val target = sources.createFile(part.name)
            written += copyLocalFileToUsb(part, target)
            target.close()
            part.delete()
        }
        srcWim.delete()
        emitLog("WIM split into ${result.partFiles.size} parts")
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
            current = if (existing != null) {
                existing
            } else {
                val dir = runCatching { current.search(part) }.getOrNull()
                    ?: current.createDirectory(part)
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
        if (length == 0L) {
            target.length = 0
            return@withContext 0L
        }
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
            bb.clear()
            bb.put(buffer, 0, toRead)
            bb.flip()
            target.write(offset, bb)
            offset += toRead
            remaining -= toRead
        }
        target.flush()
        length
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
                    bb.clear()
                    bb.put(buffer, 0, read)
                    bb.flip()
                    target.write(offset, bb)
                    offset += read
                }
            }
            target.flush()
            src.length()
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
