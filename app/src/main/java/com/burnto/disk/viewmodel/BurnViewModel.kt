package com.burnto.disk.viewmodel

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.burnto.disk.data.BurnSession
import com.burnto.disk.data.model.BurnLogLine
import com.burnto.disk.data.model.BurnState
import com.burnto.disk.data.model.IsoInfo
import com.burnto.disk.data.model.UsbDeviceInfo
import com.burnto.disk.data.usb.BurnEngine
import com.burnto.disk.data.usb.UsbDeviceManager
import com.burnto.disk.service.BurnService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class BurnViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val usbDeviceManager: UsbDeviceManager,
    private val burnEngine: BurnEngine,
    private val session: BurnSession
) : ViewModel() {

    val iso: StateFlow<IsoInfo?> = session.iso
    val burnState: StateFlow<BurnState> = burnEngine.state

    /** Accumulated burn-log lines for the collapsible log view. */
    val logLines: StateFlow<List<BurnLogLine>> = burnEngine.log
        .scan(emptyList<BurnLogLine>()) { acc, line -> (acc + line).takeLast(500) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _devices = MutableStateFlow<List<UsbDeviceInfo>>(emptyList())
    val devices: StateFlow<List<UsbDeviceInfo>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    val selectedDevice: StateFlow<UsbDeviceInfo?> = session.device

    fun refreshDevices() {
        viewModelScope.launch {
            _scanning.value = true
            val list = withContext(Dispatchers.IO) {
                runCatching { usbDeviceManager.listDevices() }.getOrDefault(emptyList())
            }
            _devices.value = list
            _scanning.value = false
        }
    }

    fun selectDevice(device: UsbDeviceInfo) {
        session.setDevice(device)
    }

    /** Kicks off the foreground burn service for the current ISO + device. */
    fun startBurn() {
        val isoInfo = session.iso.value ?: return
        val device = session.device.value ?: return
        burnEngine.resetState()
        val intent = Intent(context, BurnService::class.java).apply {
            action = BurnService.ACTION_START
            putExtra(BurnService.EXTRA_ISO_PATH, isoInfo.path)
            putExtra(BurnService.EXTRA_DEVICE_ID, device.deviceId)
            putExtra(BurnService.EXTRA_ISO_NAME, isoInfo.fileName)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun cancelBurn() {
        val intent = Intent(context, BurnService::class.java).apply {
            action = BurnService.ACTION_CANCEL
        }
        context.startService(intent)
    }

    fun resetBurn() {
        burnEngine.resetState()
    }
}
