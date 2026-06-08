package com.burnto.disk.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.burnto.disk.data.BurnSession
import com.burnto.disk.data.model.BurnLogLine
import com.burnto.disk.data.model.BurnState
import com.burnto.disk.data.model.BurnTarget
import com.burnto.disk.data.model.IsoInfo
import com.burnto.disk.data.model.RecentIso
import com.burnto.disk.data.model.UsbDeviceInfo
import com.burnto.disk.data.sdcard.SdCardBurnEngine
import com.burnto.disk.data.sdcard.SdCardManager
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class BurnViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val usbDeviceManager: UsbDeviceManager,
    private val sdCardManager: SdCardManager,
    private val burnEngine: BurnEngine,
    private val sdCardBurnEngine: SdCardBurnEngine,
    private val session: BurnSession
) : ViewModel() {

    val iso: StateFlow<IsoInfo?> = session.iso
    val burnState: StateFlow<BurnState> = combine(
        burnEngine.state,
        sdCardBurnEngine.state,
        session.target
    ) { usbState, sdState, target ->
        if (target is BurnTarget.SdCard) sdState else usbState
    }.stateIn(viewModelScope, SharingStarted.Eagerly, BurnState.Idle)

    /** Accumulated burn-log lines for the collapsible log view. */
    val logLines: StateFlow<List<BurnLogLine>> = merge(burnEngine.log, sdCardBurnEngine.log)
        .scan(emptyList<BurnLogLine>()) { acc, line -> (acc + line).takeLast(500) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _devices = MutableStateFlow<List<UsbDeviceInfo>>(emptyList())
    val devices: StateFlow<List<UsbDeviceInfo>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    val selectedTarget: StateFlow<BurnTarget?> = session.target

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

    fun selectUsbDevice(device: UsbDeviceInfo) {
        session.setTarget(BurnTarget.UsbOtg(device))
    }

    fun selectSdCard(info: com.burnto.disk.data.model.SdCardInfo) {
        session.setTarget(BurnTarget.SdCard(info))
    }

    /** Kicks off the foreground burn service for the current ISO + target. */
    fun startBurn() {
        val isoInfo = session.iso.value ?: return
        val target = session.target.value ?: return
        burnEngine.resetState()
        sdCardBurnEngine.resetState()
        val intent = Intent(context, BurnService::class.java).apply {
            action = BurnService.ACTION_START
            putExtra(BurnService.EXTRA_ISO_PATH, isoInfo.path)
            putExtra(BurnService.EXTRA_ISO_NAME, isoInfo.fileName)
            putExtra(BurnService.EXTRA_TARGET_TYPE, when (target) {
                is BurnTarget.UsbOtg -> BurnService.TYPE_USB
                is BurnTarget.SdCard -> BurnService.TYPE_SD
            })
            when (target) {
                is BurnTarget.UsbOtg -> {
                    putExtra(BurnService.EXTRA_DEVICE_ID, target.info.deviceId)
                    putExtra(BurnService.EXTRA_CAPACITY, target.info.capacityBytes)
                }
                is BurnTarget.SdCard -> {
                    putExtra(BurnService.EXTRA_SD_URI, target.info.uri.toString())
                }
            }
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

    /** Re-reads the written files from the USB and verifies them against the ISO. */
    fun verifyBurn() {
        val isoInfo = session.iso.value ?: return
        val target = session.target.value ?: return
        if (target !is BurnTarget.UsbOtg) return
        viewModelScope.launch {
            burnEngine.verify(File(isoInfo.path), target.info.deviceId, target.info.capacityBytes)
        }
    }

    fun resetBurn() {
        burnEngine.resetState()
        sdCardBurnEngine.resetState()
        session.clearTarget()
    }
}
