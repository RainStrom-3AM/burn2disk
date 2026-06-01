package com.burnto.disk.viewmodel

import android.net.Uri
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
import com.burnto.disk.data.usb.BurnEngine
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

/** UI state for the standalone Format Disk flow. */
sealed class FormatUiState {
    data object Idle : FormatUiState()
    data class InProgress(val progress: Int) : FormatUiState()
    data class Done(val message: String) : FormatUiState()
    data class Error(val message: String) : FormatUiState()
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val isoRepository: IsoRepository,
    private val downloadManager: DownloadManager,
    private val recentIsoStore: RecentIsoStore,
    private val usbDeviceManager: UsbDeviceManager,
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

    private val _formatState = MutableStateFlow<FormatUiState>(FormatUiState.Idle)
    val formatState: StateFlow<FormatUiState> = _formatState.asStateFlow()

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
    fun formatDisk(volumeLabel: String) {
        val device = _connectedDevice.value ?: run {
            _formatState.value = FormatUiState.Error("Connect a USB drive via OTG first")
            return
        }
        viewModelScope.launch {
            _formatState.value = FormatUiState.InProgress(0)
            val result = burnEngine.formatDevice(
                deviceId = device.deviceId,
                volumeLabel = volumeLabel.ifBlank { "USB DISK" },
                capacityHintBytes = device.capacityBytes
            ) { pct -> _formatState.value = FormatUiState.InProgress(pct) }

            _formatState.value = when (result) {
                is BurnEngine.FormatResult.Success ->
                    FormatUiState.Done("Format complete — drive is ready")
                is BurnEngine.FormatResult.Failure ->
                    FormatUiState.Error(result.message)
            }
            // Refresh capacity so the UI reflects the freshly formatted drive.
            refreshUsb()
        }
    }

    fun resetFormatState() {
        _formatState.value = FormatUiState.Idle
    }
}
