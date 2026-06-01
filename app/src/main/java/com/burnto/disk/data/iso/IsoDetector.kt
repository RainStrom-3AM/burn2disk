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
        const val ASSUMED_X64_MIN_BYTES = 200L * 1024 * 1024 // 200 MB
    }

    fun detect(entries: List<IsoEntry>, totalSizeBytes: Long = 0L): Detection {
        // Normalise paths to lowercase, forward-slash for matching.
        val lowerPaths = entries.associateBy { it.fullPath.lowercase().replace('\\', '/') }
        val pathSet = lowerPaths.keys

        val osType = detectOs(pathSet)
        val bootType = detectBoot(pathSet)
        val arch = detectArch(pathSet, totalSizeBytes)

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

    /**
     * Detects target architecture from, in order of confidence:
     *  1. UEFI boot binary filenames anywhere in the tree
     *     (bootx64/grubx64/...→x64, bootia32→x86, bootaa64→ARM64, bootarm→ARM).
     *  2. The `arch/boot/<name>` subdirectory used by Arch and others
     *     (x86_64→x64, i686→x86, aarch64→ARM64).
     *  3. Any path token containing a known arch keyword (amd64, arm64, ...).
     *  4. Fallback: if still unknown and the image is larger than 200 MB,
     *     assume x64 (the overwhelmingly common desktop case) rather than
     *     reporting "Unknown".
     */
    private fun detectArch(paths: Set<String>, totalSizeBytes: Long): Architecture {
        // 1. EFI boot binary filenames (strongest signal).
        fun endsWithEfi(vararg names: String) =
            paths.any { p -> names.any { p.endsWith(it) } }

        when {
            endsWithEfi("bootaa64.efi", "grubaa64.efi", "/aa64.efi") -> return Architecture.ARM64
            endsWithEfi("bootarm.efi", "grubarm.efi") -> return Architecture.ARM
            endsWithEfi("bootx64.efi", "grubx64.efi", "mmx64.efi", "shimx64.efi") -> return Architecture.X64
            endsWithEfi("bootia32.efi", "grubia32.efi", "mmia32.efi") -> return Architecture.X86
        }

        // 2. arch/boot/<name> subdirectory (Arch-style) and generic path tokens.
        val joined = paths.joinToString("\n")
        when {
            joined.contains("/x86_64/") || joined.contains("arch/boot/x86_64") ||
                joined.contains("amd64") || joined.contains("x86_64") || joined.contains("/x64/") ->
                return Architecture.X64
            joined.contains("aarch64") || joined.contains("arm64") || joined.contains("/aa64/") ->
                return Architecture.ARM64
            joined.contains("/i686/") || joined.contains("/i386/") || joined.contains("arch/boot/i686") ->
                return Architecture.X86
        }

        // 3. Fallback: large images are almost certainly x64 desktop installers.
        if (totalSizeBytes > ASSUMED_X64_MIN_BYTES) return Architecture.X64_ASSUMED

        return Architecture.UNKNOWN
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
