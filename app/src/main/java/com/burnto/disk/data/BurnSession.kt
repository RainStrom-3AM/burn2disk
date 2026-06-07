package com.burnto.disk.data

import com.burnto.disk.data.model.BurnTarget
import com.burnto.disk.data.model.IsoInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the in-flight selection that flows across screens: the analysed ISO and
 * the chosen target device (USB OTG or SD card). Kept as an app-scoped singleton
 * so the burn service, the burn screen, and the result screen all observe the
 * same source of truth.
 */
@Singleton
class BurnSession @Inject constructor() {

    private val _iso = MutableStateFlow<IsoInfo?>(null)
    val iso: StateFlow<IsoInfo?> = _iso.asStateFlow()

    private val _target = MutableStateFlow<BurnTarget?>(null)
    val target: StateFlow<BurnTarget?> = _target.asStateFlow()

    fun setIso(info: IsoInfo) { _iso.value = info }
    fun setTarget(target: BurnTarget) { _target.value = target }

    fun clearTarget() { _target.value = null }

    fun reset() {
        _iso.value = null
        _target.value = null
    }
}
