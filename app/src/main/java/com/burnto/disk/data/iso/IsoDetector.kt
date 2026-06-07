package com.burnto.disk.data.iso

import com.burnto.disk.data.model.Architecture
import com.burnto.disk.data.model.BootType
import com.burnto.disk.data.model.OsType
import java.io.File

/**
 * Inspects the directory listing of an ISO to classify the OS family, supported
 * boot firmware, and architecture, following the detection rules in the spec.
 *
 * The 4 GB+ FAT32 limitation hinges on the size of install.wim/install.esd,
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
        const val ASSUMED_X64_MIN_BYTES = 100L * 1024 * 1024 // 100 MB
    }

    fun detect(
        entries: List<IsoEntry>,
        volumeLabel: String = "",
        systemIdentifier: String = "",
        totalSizeBytes: Long = 0L
    ): Detection {
        // Normalise paths to lowercase, forward-slash for matching.
        val pathSet = entries.map { it.fullPath.lowercase().replace('\\', '/') }.toSet()
        val volLower = volumeLabel.lowercase()

        val osType = detectOs(pathSet, volLower)
        val bootType = detectBoot(pathSet)
        val arch = detectArch(pathSet, volumeLabel, systemIdentifier, totalSizeBytes)

        val hasLargeWim = entries.any {
            val n = it.name.lowercase()
            (n == "install.wim" || n == "install.esd") && it.sizeBytes > FAT32_FILE_LIMIT
        }

        return Detection(osType, bootType, arch, hasLargeWim)
    }

    private fun detectOs(paths: Set<String>, volumeLabel: String): OsType {
        return when {
            // 1. Windows
            paths.contains("sources/install.wim") ||
                paths.contains("sources/install.esd") ||
                paths.contains("sources/boot.wim") ||
                (paths.contains("autorun.inf") && paths.contains("setup.exe")) ||
                (paths.contains("bootmgr") && paths.contains("boot/bcd")) -> OsType.WINDOWS

            // 5. Kali Linux (checked before generic Ubuntu/Debian to avoid false generic match)
            paths.contains("live/filesystem.squashfs") &&
                "kali" in volumeLabel -> OsType.KALI

            // 6. Linux Mint (checked before generic Ubuntu to avoid false generic match)
            paths.contains("casper/filesystem.squashfs") &&
                "mint" in volumeLabel -> OsType.MINT

            // 2. Ubuntu / Debian
            paths.contains("casper/filesystem.squashfs") ||
                paths.contains("casper/filesystem.squashfs.gpg") ||
                paths.contains("install/filesystem.squashfs") ||
                paths.any { it.startsWith("dists/") } ||
                paths.contains("ubuntu") ||
                paths.contains("debian") -> {
                when {
                    "debian" in volumeLabel -> OsType.DEBIAN
                    else -> OsType.UBUNTU
                }
            }

            // 3. Fedora / RHEL / Rocky / Alma
            paths.contains("liveos/squashfs.img") ||
                paths.contains("liveos/ext3fs.img") ||
                paths.contains("images/install.img") -> OsType.FEDORA

            // 4. Arch Linux
            paths.contains("arch/boot/x86_64/vmlinuz-linux") ||
                paths.any { it.startsWith("arch/boot/") } -> OsType.ARCH

            // 7. Generic Linux
            paths.any {
                it.startsWith("casper/") ||
                it.startsWith("live/") ||
                it.startsWith("boot/")
            } -> OsType.LINUX_GENERIC

            else -> OsType.UNKNOWN
        }
    }

    private fun detectBoot(paths: Set<String>): BootType {
        val hasEfi = paths.any { it.startsWith("efi/") }
        val hasLegacy = paths.any { it.startsWith("isolinux/") }
        return when {
            hasEfi && hasLegacy -> BootType.HYBRID
            hasEfi -> BootType.UEFI
            hasLegacy -> BootType.LEGACY_BIOS
            else -> BootType.UNKNOWN
        }
    }

    private fun detectArch(
        paths: Set<String>,
        volumeLabel: String,
        systemIdentifier: String,
        totalSizeBytes: Long
    ): Architecture {
        val combined = (volumeLabel + " " + systemIdentifier).lowercase()

        return when {
            paths.contains("efi/boot/bootx64.efi") -> Architecture.X64
            paths.contains("efi/boot/bootia32.efi") -> Architecture.X86
            paths.contains("efi/boot/bootaa64.efi") -> Architecture.ARM64
            paths.any { it.startsWith("arch/boot/x86_64/") } -> Architecture.X64
            paths.any { it.startsWith("arch/boot/i686/") } -> Architecture.X86
            "amd64" in combined || "x86_64" in combined -> Architecture.X64
            "i386" in combined || "i686" in combined -> Architecture.X86
            "arm64" in combined || "aarch64" in combined -> Architecture.ARM64
            else -> if (totalSizeBytes > ASSUMED_X64_MIN_BYTES) Architecture.X64_ASSUMED else Architecture.UNKNOWN
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
            n.contains("ubuntu") -> OsType.UBUNTU
            n.contains("debian") -> OsType.DEBIAN
            n.contains("mint") -> OsType.MINT
            n.contains("fedora") || n.contains("rhel") || n.contains("centos") || n.contains("rocky") || n.contains("alma") -> OsType.FEDORA
            n.contains("arch") -> OsType.ARCH
            n.contains("kali") -> OsType.KALI
            else -> OsType.UNKNOWN
        }
        val arch = when {
            n.contains("aarch64") || n.contains("arm64") -> Architecture.ARM64
            n.contains("amd64") || n.contains("x86_64") || n.contains("x64") -> Architecture.X64
            n.contains("armhf") || n.contains("armv7") || n.contains("-arm") -> Architecture.ARM
            n.contains("i386") || n.contains("i686") || n.contains("x86") -> Architecture.X86
            file.length() > ASSUMED_X64_MIN_BYTES -> Architecture.X64_ASSUMED
            else -> Architecture.UNKNOWN
        }
        return Detection(os, BootType.UNKNOWN, arch, hasLargeWim = false)
    }
}
