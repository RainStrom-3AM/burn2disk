package com.burnto.disk.data

import com.burnto.disk.data.model.IsoInfo
import com.burnto.disk.data.model.UsbDeviceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the in-flight selection that flows across screens: the analysed ISO and
 * the chosen target device. Kept as an app-scoped singleton so the burn service,
 * the burn screen, and the result screen all observe the same source of truth.
 */
@Singleton
class BurnSession @Inject constructor() {

    private val _iso = MutableStateFlow<IsoInfo?>(null)
    val iso: StateFlow<IsoInfo?> = _iso.asStateFlow()

    private val _device = MutableStateFlow<UsbDeviceInfo?>(null)
    val device: StateFlow<UsbDeviceInfo?> = _device.asStateFlow()

    fun setIso(info: IsoInfo) { _iso.value = info }
    fun setDevice(device: UsbDeviceInfo) { _device.value = device }

    fun clearDevice() { _device.value = null }

    fun reset() {
        _iso.value = null
        _device.value = null
    }
}
