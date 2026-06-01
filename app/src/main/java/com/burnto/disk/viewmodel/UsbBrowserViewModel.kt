package com.burnto.disk.viewmodel

import android.content.Context
import android.hardware.usb.UsbManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.burnto.disk.data.usb.RawUsbBlockDevice
import com.burnto.disk.data.usb.UsbDeviceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.fs.fat32.Fat32FileSystem
import javax.inject.Inject

/** One entry (file or directory) in the USB file browser. */
data class UsbFileEntry(
    val name: String,
    val path: String,        // path relative to root, '/'-separated, no leading '/'
    val isDirectory: Boolean,
    val sizeBytes: Long
)

/** UI state for the read-only USB file browser. */
sealed class BrowseState {
    data object Loading : BrowseState()
    data object NoDevice : BrowseState()
    data class Error(val message: String) : BrowseState()
    data class Listing(
        val path: String,
        val entries: List<UsbFileEntry>,
        val totalFiles: Int,
        val totalBytes: Long
    ) : BrowseState()
}

/**
 * Mounts the connected USB drive read-only via libaums and lets the user browse
 * its directory tree. Used for post-burn verification — the user sees boot/,
 * isolinux/, etc. without needing a PC.
 *
 * The mounted [FileSystem] and underlying raw device are kept open for the whole
 * browse session (navigation just re-lists directories) and closed in
 * [onCleared]. Browsing is strictly read-only; no writes go to the device.
 */
@HiltViewModel
class UsbBrowserViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val usbDeviceManager: UsbDeviceManager
) : ViewModel() {

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _state = MutableStateFlow<BrowseState>(BrowseState.Loading)
    val state: StateFlow<BrowseState> = _state.asStateFlow()

    private val _deviceName = MutableStateFlow("USB Drive")
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    private var raw: RawUsbBlockDevice? = null
    private var fs: FileSystem? = null

    // Directory navigation stack of relative paths ("" == root).
    private val backStack = ArrayDeque<String>()

    // Drive-wide totals, computed once on mount.
    private var driveFiles = 0
    private var driveBytes = 0L

    /** Mounts the first connected USB device and lists the root directory. */
    fun open() {
        viewModelScope.launch {
            _state.value = BrowseState.Loading
            val result = withContext(Dispatchers.IO) { mountAndScan() }
            _state.value = result
        }
    }

    private fun mountAndScan(): BrowseState {
        return try {
            val devices = usbDeviceManager.rawDevices()
            if (devices.isEmpty()) return BrowseState.NoDevice
            val msd = devices.first()
            val usbDevice = msd.usbDevice
            _deviceName.value = runCatching {
                usbDevice.productName ?: usbDevice.manufacturerName ?: "USB Drive"
            }.getOrDefault("USB Drive")

            if (!usbManager.hasPermission(usbDevice)) {
                // Permission must already be granted from a prior burn/format;
                // requesting here would need a suspend round-trip. Surface clearly.
                return BrowseState.Error("USB permission needed — connect and allow access, then retry.")
            }

            val device = RawUsbBlockDevice.create(usbManager, usbDevice).also { it.init() }
            raw = device
            val partitionStartLba = (1L shl 20) / device.blockSize
            val partitionDevice = me.jahnen.libaums.core.driver.ByteBlockDevice(
                device.blockDevice, partitionStartLba.toInt()
            )
            val mounted = Fat32FileSystem.read(partitionDevice)
                ?: return BrowseState.Error("Could not read the filesystem on this drive.")
            fs = mounted

            // One-time drive-wide totals (files + bytes).
            val totals = intArrayOf(0)
            val bytes = longArrayOf(0L)
            walkTotals(mounted.rootDirectory, totals, bytes, depth = 0)
            driveFiles = totals[0]
            driveBytes = bytes[0]

            backStack.clear()
            backStack.addLast("")
            listing("")
        } catch (e: Exception) {
            BrowseState.Error(e.message ?: "Failed to read USB drive")
        }
    }

    /** Builds a [BrowseState.Listing] for [path] (relative to root). */
    private fun listing(path: String): BrowseState {
        val mounted = fs ?: return BrowseState.Error("Drive not mounted")
        val dir = resolveDir(mounted, path)
        val entries = dir.listFiles().map { f ->
            UsbFileEntry(
                name = f.name,
                path = if (path.isEmpty()) f.name else "$path/${f.name}",
                isDirectory = f.isDirectory,
                sizeBytes = if (f.isDirectory) 0L else f.length
            )
        }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

        return BrowseState.Listing(path, entries, driveFiles, driveBytes)
    }

    private fun resolveDir(mounted: FileSystem, path: String): UsbFile {
        val root = mounted.rootDirectory
        if (path.isEmpty()) return root
        return root.search(path) ?: throw java.io.IOException("Path not found: /$path")
    }

    /** Recursively sums file count and bytes for the whole drive (bounded depth). */
    private fun walkTotals(dir: UsbFile, files: IntArray, bytes: LongArray, depth: Int) {
        if (depth > 32) return // guard against pathological trees
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (c in children) {
            if (c.isDirectory) {
                walkTotals(c, files, bytes, depth + 1)
            } else {
                files[0]++
                bytes[0] += runCatching { c.length }.getOrDefault(0L)
            }
        }
    }

    /** Navigates into the directory at [entry.path]. */
    fun navigateInto(entry: UsbFileEntry) {
        if (!entry.isDirectory) return
        viewModelScope.launch {
            _state.value = BrowseState.Loading
            backStack.addLast(entry.path)
            val result = withContext(Dispatchers.IO) {
                runCatching { listing(entry.path) }.getOrElse {
                    BrowseState.Error(it.message ?: "Could not open folder")
                }
            }
            _state.value = result
        }
    }

    /** Navigates to a breadcrumb [path] (truncating the back stack to it). */
    fun navigateToPath(path: String) {
        viewModelScope.launch {
            _state.value = BrowseState.Loading
            while (backStack.isNotEmpty() && backStack.last() != path) backStack.removeLast()
            if (backStack.isEmpty()) backStack.addLast("")
            val result = withContext(Dispatchers.IO) {
                runCatching { listing(path) }.getOrElse {
                    BrowseState.Error(it.message ?: "Could not open folder")
                }
            }
            _state.value = result
        }
    }

    /**
     * Handles back navigation. Returns true if it moved up a directory (stay in
     * the browser); false if already at root (caller should close the screen).
     */
    fun navigateUp(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeLast()
        val target = backStack.last()
        viewModelScope.launch {
            _state.value = BrowseState.Loading
            val result = withContext(Dispatchers.IO) {
                runCatching { listing(target) }.getOrElse {
                    BrowseState.Error(it.message ?: "Could not open folder")
                }
            }
            _state.value = result
        }
        return true
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { raw?.close() }
        raw = null
        fs = null
    }
}
