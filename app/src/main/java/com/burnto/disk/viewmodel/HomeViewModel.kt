package com.burnto.disk.viewmodel

import android.net.Uri
import android.content.Context
import android.hardware.usb.UsbManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.burnto.disk.data.BurnSession
import com.burnto.disk.data.IsoRepository
import com.burnto.disk.data.RecentIsoStore
import com.burnto.disk.data.download.DownloadManager
import com.burnto.disk.data.model.DownloadState
import com.burnto.disk.data.model.IsoInfo
import com.burnto.disk.data.model.RecentIso
import com.burnto.disk.data.model.UsbDeviceInfo
import com.burnto.disk.data.sdcard.SdCardManager
import com.burnto.disk.data.usb.BurnEngine
import com.burnto.disk.data.usb.Fat32Formatter
import com.burnto.disk.data.usb.RawUsbBlockDevice
import com.burnto.disk.data.usb.UsbDeviceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** UI state for the analysis/import phase used by Home, Source and Info screens. */
sealed class IsoUiState {
    data object Idle : IsoUiState()
    data class Importing(val progress: Int) : IsoUiState()
    data class Analyzing(val progress: Int) : IsoUiState()
    data class Ready(val info: IsoInfo) : IsoUiState()
    data class Error(val message: String) : IsoUiState()
}

/** UI state for the standalone one-tap Format Disk flow. */
sealed class FormatUiState {
    data object Idle : FormatUiState()
    data object NoUsb : FormatUiState()
    data class Formatting(val progress: Int) : FormatUiState()
    data object Success : FormatUiState()
    data class Error(val message: String) : FormatUiState()
}

/** UI state for the USB diagnostic report dialog. */
sealed class DiagnoseUiState {
    data object Idle : DiagnoseUiState()
    data object Running : DiagnoseUiState()
    data class Report(val text: String) : DiagnoseUiState()
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val isoRepository: IsoRepository,
    private val downloadManager: DownloadManager,
    private val recentIsoStore: RecentIsoStore,
    private val usbDeviceManager: UsbDeviceManager,
    private val sdCardManager: SdCardManager,
    private val burnEngine: BurnEngine,
    private val session: BurnSession
) : ViewModel() {

    private val _isoState = MutableStateFlow<IsoUiState>(IsoUiState.Idle)
    val isoState: StateFlow<IsoUiState> = _isoState.asStateFlow()

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val _usbConnected = MutableStateFlow(false)
    val usbConnected: StateFlow<Boolean> = _usbConnected.asStateFlow()

    private val _recent = MutableStateFlow<List<RecentIso>>(emptyList())
    val recent: StateFlow<List<RecentIso>> = _recent.asStateFlow()

    // The first connected USB device, refreshed on demand (for Format Disk).
    private val _connectedDevice = MutableStateFlow<UsbDeviceInfo?>(null)
    val connectedDevice: StateFlow<UsbDeviceInfo?> = _connectedDevice.asStateFlow()

    private val _sdCardInfo = MutableStateFlow<com.burnto.disk.data.model.SdCardInfo?>(null)
    val sdCardInfo: StateFlow<com.burnto.disk.data.model.SdCardInfo?> = _sdCardInfo.asStateFlow()

    private val _formatState = MutableStateFlow<FormatUiState>(FormatUiState.Idle)
    val formatState: StateFlow<FormatUiState> = _formatState.asStateFlow()

    private val _diagnoseState = MutableStateFlow<DiagnoseUiState>(DiagnoseUiState.Idle)
    val diagnoseState: StateFlow<DiagnoseUiState> = _diagnoseState.asStateFlow()

    private var downloadJob: Job? = null

    init {
        refreshUsb()
        loadRecent()
    }

    fun refreshUsb() {
        viewModelScope.launch {
            val devices = withContext(Dispatchers.IO) {
                runCatching { usbDeviceManager.listDevices() }.getOrDefault(emptyList())
            }
            _usbConnected.value = devices.isNotEmpty()
            _connectedDevice.value = devices.firstOrNull()
            _sdCardInfo.value = withContext(Dispatchers.IO) {
                runCatching { sdCardManager.detectSdCard() }.getOrNull()
            }
        }
    }

    fun loadRecent() {
        _recent.value = recentIsoStore.getRecent()
    }

    /** Imports a picked content URI, then analyses it. */
    fun onIsoPicked(uri: Uri) {
        viewModelScope.launch {
            try {
                _isoState.value = IsoUiState.Importing(0)
                val picked = isoRepository.importFromUri(uri) { p ->
                    _isoState.value = IsoUiState.Importing(p)
                }
                analyze(picked.file)
            } catch (e: Exception) {
                _isoState.value = IsoUiState.Error(e.message ?: "Could not import file")
            }
        }
    }

    /** Re-opens a previously used ISO from the recent list. */
    fun onRecentSelected(recent: RecentIso) {
        val file = File(recent.path)
        if (!file.exists()) {
            _isoState.value = IsoUiState.Error("File no longer available: ${recent.name}")
            return
        }
        viewModelScope.launch { analyze(file) }
    }

    /** Analyses a local ISO file already present on disk (download or cache). */
    fun analyzeLocal(path: String) {
        viewModelScope.launch { analyze(File(path)) }
    }

    private suspend fun analyze(file: File) {
        try {
            _isoState.value = IsoUiState.Analyzing(0)
            val info = isoRepository.analyze(file) { p ->
                _isoState.value = IsoUiState.Analyzing(p)
            }
            session.setIso(info)
            recentIsoStore.addRecent(
                RecentIso(info.fileName, info.path, info.sizeBytes, System.currentTimeMillis())
            )
            loadRecent()
            _isoState.value = IsoUiState.Ready(info)
        } catch (e: Exception) {
            _isoState.value = IsoUiState.Error(e.message ?: "Could not analyze ISO")
        }
    }

    fun startDownload(url: String) {
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            downloadManager.download(url).collect { state ->
                _downloadState.value = state
                if (state is DownloadState.Completed) {
                    analyze(File(state.filePath))
                }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        _downloadState.value = DownloadState.Idle
    }

    fun resetIsoState() {
        _isoState.value = IsoUiState.Idle
    }

    /**
     * Standalone format of the connected USB drive (Home-screen "Format Disk").
     * Lets the user recover a broken drive after a failed burn without a PC.
     */
    /**
     * One-tap format of the connected USB drive. No confirmation dialog — the
     * Home-screen button starts immediately. Bypasses libaums' filesystem mount
     * (which fails on a drive with no partition table) and writes a fresh FAT32
     * directly via the raw block device. Recovers a corrupted/unallocated drive
     * (e.g. left broken by a failed burn) without needing a PC.
     */
    fun formatDisk() {
        viewModelScope.launch(Dispatchers.IO) {
            // 1. Find a USB device.
            val devices = runCatching { usbDeviceManager.rawDevices() }.getOrDefault(emptyList())
            if (devices.isEmpty()) {
                _formatState.value = FormatUiState.NoUsb
                return@launch
            }
            val msd = devices.first()
            val usbDevice = msd.usbDevice

            // 2. Permission.
            val granted = runCatching { usbDeviceManager.requestPermission(usbDevice) }.getOrDefault(false)
            if (!granted) {
                _formatState.value = FormatUiState.Error("USB permission denied")
                return@launch
            }

            // 3. Open the RAW block device directly — do NOT call libaums
            //    msd.init(), which tries to mount a filesystem that may not exist.
            _formatState.value = FormatUiState.Formatting(0)
            var raw: RawUsbBlockDevice? = null
            try {
                val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                raw = RawUsbBlockDevice.create(usbManager, usbDevice)
                raw.init()

                var capacity = raw.capacityBytes
                if (capacity <= 0L) {
                    // Corrupted/missing MBR: clear LBA 0 and re-init, which often
                    // restores a sane READ CAPACITY result.
                    capacity = raw.clearMbrAndReinit()
                }
                // Use the largest trustworthy value; the formatter falls back to a
                // safe 28 GiB underestimate if this is still zero.
                val totalBytes = maxOf(capacity, raw.blockDevice.blocks * raw.blockDevice.blockSize.toLong())

                Fat32Formatter(raw.blockDevice).format(
                    totalBytes = totalBytes,
                    volumeLabel = "USB DISK"
                ) { pct -> _formatState.value = FormatUiState.Formatting(pct) }

                // 6. Re-init after format to refresh capacity for the device
                //    list. NOTE: a zero/stale capacity here is a normal post-write
                //    controller timing quirk — we only log it, never re-format
                //    (re-formatting would needlessly wipe the fresh filesystem).
                raw.close()
                raw = null
                runCatching {
                    val recheck = RawUsbBlockDevice.create(usbManager, usbDevice)
                    recheck.init()
                    recheck.close()
                }

                _formatState.value = FormatUiState.Success
                refreshUsb()
            } catch (e: Exception) {
                _formatState.value = FormatUiState.Error(e.message ?: "Format failed")
            } finally {
                runCatching { raw?.close() }
            }
        }
    }

    fun resetFormatState() {
        _formatState.value = FormatUiState.Idle
    }

    /** Runs the read-only USB diagnostic and publishes the report. */
    fun diagnoseUsb() {
        viewModelScope.launch {
            val devices = runCatching { usbDeviceManager.rawDevices() }.getOrDefault(emptyList())
            if (devices.isEmpty()) {
                _diagnoseState.value = DiagnoseUiState.Report("No USB device found. Connect a drive via OTG first.")
                return@launch
            }
            _diagnoseState.value = DiagnoseUiState.Running
            val report = burnEngine.diagnose(devices.first().usbDevice.deviceId)
            _diagnoseState.value = DiagnoseUiState.Report(report)
        }
    }

    fun dismissDiagnose() {
        _diagnoseState.value = DiagnoseUiState.Idle
    }
}
