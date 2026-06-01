package com.burnto.disk.data.model

/** Detected operating system family of an ISO image. */
enum class OsType(val label: String) {
    WINDOWS("Windows"),
    UBUNTU_DEBIAN("Ubuntu / Debian"),
    FEDORA_RHEL("Fedora / RHEL"),
    ARCH("Arch Linux"),
    GENERIC("Unknown / Generic")
}

/** Boot firmware support detected inside an ISO. */
enum class BootType(val label: String) {
    UEFI("UEFI supported"),
    LEGACY_BIOS("Legacy BIOS supported"),
    HYBRID("UEFI + Legacy BIOS"),
    UNKNOWN("Unknown")
}

/** Target CPU architecture of an ISO. */
enum class Architecture(val label: String) {
    X64("x64"),
    X86("x86"),
    ARM("ARM"),
    UNKNOWN("Unknown")
}

/**
 * Fully analysed metadata about a selected ISO/IMG source.
 *
 * @param sizeBytes total file size in bytes.
 * @param sha256 hex checksum, or null until computed.
 */
data class IsoInfo(
    val fileName: String,
    val path: String,
    val sizeBytes: Long,
    val osType: OsType = OsType.GENERIC,
    val bootType: BootType = BootType.UNKNOWN,
    val architecture: Architecture = Architecture.UNKNOWN,
    val sha256: String? = null,
    val hasLargeWim: Boolean = false
) {
    /** The recommended write-method badge text shown on the ISO info screen. */
    val recommendedMethod: String
        get() = when (bootType) {
            BootType.UEFI, BootType.HYBRID -> "FILE COPY — UEFI BOOT"
            BootType.LEGACY_BIOS -> "FILE COPY — LEGACY BOOT"
            BootType.UNKNOWN -> "FILE COPY"
        }
}

/** A connected USB mass-storage device as surfaced to the UI. */
data class UsbDeviceInfo(
    val deviceId: Int,
    val deviceName: String,
    val productName: String,
    val vendorId: Int,
    val productId: Int,
    val capacityBytes: Long,
    val filesystem: String,
    val hasPermission: Boolean
) {
    val displayName: String
        get() = productName.ifBlank { deviceName }
}

/** Streaming download progress for the download engine. */
sealed class DownloadState {
    data object Idle : DownloadState()

    data class Downloading(
        val fileName: String,
        val bytesReceived: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Float
    ) : DownloadState() {
        val percent: Int
            get() = if (totalBytes > 0) ((bytesReceived * 100) / totalBytes).toInt() else 0
    }

    data class Completed(val filePath: String, val sizeBytes: Long, val sha256: String?) : DownloadState()
    data class Failed(val error: String) : DownloadState()
}

/** A previously used ISO, persisted for the "Recent ISOs" list. */
data class RecentIso(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val lastUsedEpochMs: Long
)
