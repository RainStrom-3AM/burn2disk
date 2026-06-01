package com.burnto.disk.data.iso

import com.burnto.disk.data.model.Architecture
import com.burnto.disk.data.model.BootType
import com.burnto.disk.data.model.OsType
import java.io.File

/**
 * Inspects the directory listing of an ISO to classify the OS family, supported
 * boot firmware, and architecture, following the detection rules in the spec.
 *
 * The 4 GB+ FAT32 limitation hinges on the size of `install.wim`/`install.esd`,
 * so the detector also surfaces whether a large WIM is present.
 */
class IsoDetector {

    /** Aggregated detection result. */
    data class Detection(
        val osType: OsType,
        val bootType: BootType,
        val architecture: Architecture,
        val hasLargeWim: Boolean
    )

    private companion object {
        const val FAT32_FILE_LIMIT = 0xFFFFFFFFL // 4 GiB - 1 byte
    }

    fun detect(entries: List<IsoEntry>): Detection {
        // Normalise paths to lowercase, forward-slash for matching.
        val lowerPaths = entries.associateBy { it.fullPath.lowercase().replace('\\', '/') }
        val pathSet = lowerPaths.keys

        val osType = detectOs(pathSet)
        val bootType = detectBoot(pathSet)
        val arch = detectArch(pathSet)

        val hasLargeWim = entries.any {
            val n = it.name.lowercase()
            (n == "install.wim" || n == "install.esd") && it.sizeBytes > FAT32_FILE_LIMIT
        }

        return Detection(osType, bootType, arch, hasLargeWim)
    }

    private fun detectOs(paths: Set<String>): OsType {
        fun has(predicate: (String) -> Boolean) = paths.any(predicate)

        return when {
            has { it == "sources/install.wim" || it == "sources/install.esd" ||
                    it.endsWith("/install.wim") || it.endsWith("/install.esd") } -> OsType.WINDOWS

            has { it.startsWith("casper/") || it.endsWith("filesystem.squashfs") } -> OsType.UBUNTU_DEBIAN

            has { it.startsWith("liveos/") || it.endsWith("squashfs.img") } -> OsType.FEDORA_RHEL

            has { it.startsWith("arch/boot/") || it.startsWith("arch/") } -> OsType.ARCH

            else -> OsType.GENERIC
        }
    }

    private fun detectBoot(paths: Set<String>): BootType {
        val hasEfi = paths.any { it.startsWith("efi/") || it.contains("/efi/") || it == "efi" }
        val hasLegacy = paths.any {
            it.startsWith("isolinux/") || it.endsWith("isolinux.bin") ||
                it.endsWith("bootmgr") || it.startsWith("boot/")
        }
        return when {
            hasEfi && hasLegacy -> BootType.HYBRID
            hasEfi -> BootType.UEFI
            hasLegacy -> BootType.LEGACY_BIOS
            else -> BootType.UNKNOWN
        }
    }

    private fun detectArch(paths: Set<String>): Architecture {
        // Use UEFI bootloader filenames as the strongest architecture signal.
        return when {
            paths.any { it.endsWith("bootaa64.efi") || it.endsWith("/grubaa64.efi") } -> Architecture.ARM
            paths.any { it.endsWith("bootx64.efi") || it.endsWith("/grubx64.efi") } -> Architecture.X64
            paths.any { it.endsWith("bootia32.efi") || it.endsWith("/grubia32.efi") } -> Architecture.X86
            else -> Architecture.UNKNOWN
        }
    }

    /**
     * A lightweight fallback used when full ISO parsing is unavailable: classify
     * purely from the file name (e.g. "ubuntu-24.04-amd64.iso").
     */
    fun detectFromFileName(file: File): Detection {
        val n = file.name.lowercase()
        val os = when {
            n.contains("windows") || n.startsWith("win") || n.contains("win10") || n.contains("win11") -> OsType.WINDOWS
            n.contains("ubuntu") || n.contains("debian") || n.contains("mint") -> OsType.UBUNTU_DEBIAN
            n.contains("fedora") || n.contains("rhel") || n.contains("centos") || n.contains("rocky") -> OsType.FEDORA_RHEL
            n.contains("arch") -> OsType.ARCH
            else -> OsType.GENERIC
        }
        val arch = when {
            n.contains("amd64") || n.contains("x86_64") || n.contains("x64") -> Architecture.X64
            n.contains("aarch64") || n.contains("arm64") || n.contains("arm") -> Architecture.ARM
            n.contains("i386") || n.contains("i686") || n.contains("x86") -> Architecture.X86
            else -> Architecture.UNKNOWN
        }
        return Detection(os, BootType.UNKNOWN, arch, hasLargeWim = false)
    }
}
