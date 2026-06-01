package com.burnto.disk.data.usb

import me.jahnen.libaums.core.driver.BlockDeviceDriver
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Geometry of a freshly formatted FAT32 volume, returned by [Fat32Formatter.format]
 * so the fast writer can address the FAT and data regions directly (in absolute
 * device LBAs).
 */
data class FatGeometry(
    val partitionStartLba: Long,
    val partitionSectors: Long,
    val bytesPerSector: Int,
    val sectorsPerCluster: Int,
    val reservedSectors: Int,
    val numFats: Int,
    val fatSizeSectors: Long,
    val fatStartLba: Long,
    val fat2StartLba: Long,
    val dataStartLba: Long,
    val rootCluster: Long,
    val totalClusters: Long,
    val fsInfoLba: Long
) {
    val clusterBytes: Long get() = sectorsPerCluster.toLong() * bytesPerSector
    fun clusterToLba(cluster: Long): Long = dataStartLba + (cluster - 2) * sectorsPerCluster
}

/**
 * A minimal, dependency-free FAT32 formatter that writes a fresh, bootable
 * filesystem directly to a raw, LBA-addressed [BlockDeviceDriver].
 *
 * libaums only *mounts* an existing filesystem (it has no `mkfs`), so we lay
 * down the structures ourselves:
 *
 *  1. An **MBR** at LBA 0 with a single FAT32 (type 0x0C, "FAT32 LBA") partition,
 *     1 MiB aligned (starts at LBA 2048). A real partition table is what most
 *     UEFI/BIOS firmware expects to boot from, and it lets libaums re-discover
 *     the volume through its normal partition-scanning flow.
 *  2. Inside the partition: boot sector / BPB, FSInfo, a backup boot sector,
 *     two zeroed FATs with the reserved + root-dir chain entries, and an empty
 *     root directory cluster holding only the volume-label entry.
 *
 * Cluster size and FAT size follow the thresholds Microsoft's `format` /
 * `mkfs.fat` use for FAT32.
 *
 * Every write must be a whole number of blocks: [BlockDeviceDriver.write]
 * requires `buffer.remaining() % blockSize == 0` and treats the offset as an LBA.
 *
 * This is destructive: it overwrites the partition table and the entire data
 * region's metadata.
 */
class Fat32Formatter(
    private val blockDevice: BlockDeviceDriver
) {

    private val blockSize: Int = blockDevice.blockSize

    companion object {
        // 1 MiB partition alignment expressed in 512-byte units; scaled by block size.
        private const val ALIGNMENT_BYTES = 1L shl 20
        private const val RESERVED_SECTORS = 32
        private const val NUM_FATS = 2
        private const val ROOT_CLUSTER = 2L
        private const val FSINFO_SECTOR = 1
        private const val BACKUP_BOOT_SECTOR = 6
        // Cap a single SCSI WRITE(10) burst at 512 KiB to reduce per-call
        // libaums overhead on bulk writes while staying controller-friendly.
        private const val MAX_BURST_BYTES = 512 * 1024
    }

    /**
     * Formats the device as FAT32 within a single MBR partition and returns the
     * resulting [FatGeometry] for direct block-level writing.
     *
     * @param totalBytes usable capacity of the whole device in bytes.
     * @param volumeLabel up to 11 chars; uppercased and padded.
     * @param onProgress 0-100 progress callback.
     */
    fun format(
        totalBytes: Long,
        volumeLabel: String,
        onProgress: (Int) -> Unit = {}
    ): FatGeometry {
        require(blockSize >= 512) { "Unsupported block size $blockSize" }
        val bytesPerSector = blockSize

        // A corrupted/missing MBR can make the controller briefly report zero
        // capacity. Rather than abort, fall back to a conservative 28 GiB (a safe
        // underestimate for a 32 GB drive) so the user can still recover the
        // drive. The resulting partition just won't span the very end of an
        // oversized stick, which is harmless and re-formattable later.
        val FALLBACK_BYTES = 28L * 1024 * 1024 * 1024
        val effectiveBytes = if (totalBytes <= 0L) FALLBACK_BYTES else totalBytes
        val deviceSectors = effectiveBytes / bytesPerSector
        require(deviceSectors > 0) { "Device reports zero sectors" }

        val partitionStartLba = ALIGNMENT_BYTES / bytesPerSector // = 2048 for 512-byte sectors
        require(deviceSectors > partitionStartLba + 65536) { "Device too small for FAT32" }
        val partitionSectors = deviceSectors - partitionStartLba

        val sectorsPerCluster = chooseSectorsPerCluster(effectiveBytes, bytesPerSector)
        val fatSizeSectors = computeFatSize(partitionSectors, sectorsPerCluster, bytesPerSector)
        val dataSectors = partitionSectors - RESERVED_SECTORS - (NUM_FATS * fatSizeSectors)
        val clusterCount = dataSectors / sectorsPerCluster
        check(clusterCount >= 65525) {
            "Volume too small for FAT32 ($clusterCount clusters); needs >= 65525"
        }

        onProgress(1)

        // --- MBR at LBA 0 ---
        writeMbr(partitionStartLba, partitionSectors, bytesPerSector)
        onProgress(3)

        // --- Boot sector / BPB (+ backup) ---
        val boot = buildBootSector(
            bytesPerSector, sectorsPerCluster, partitionSectors,
            fatSizeSectors, partitionStartLba, volumeLabel
        )
        writeSectorAbs(partitionStartLba, boot)
        writeSectorAbs(partitionStartLba + BACKUP_BOOT_SECTOR, boot)

        // --- FSInfo (+ backup) ---
        val fsInfo = buildFsInfo(clusterCount)
        writeSectorAbs(partitionStartLba + FSINFO_SECTOR, fsInfo)
        writeSectorAbs(partitionStartLba + BACKUP_BOOT_SECTOR + FSINFO_SECTOR, fsInfo)
        onProgress(6)

        // --- Initialise FATs ---
        // Zero the entire FAT region first (large sequential bursts, shown under
        // the Formatting progress) so every unused cluster entry is 0 (free).
        // This is required for a correct free-space map and to avoid stale chains
        // from a previously-used drive. The burn then flushes each allocated
        // chain incrementally, and the reserved entries are written below.
        zeroFatRegion(partitionStartLba, fatSizeSectors, bytesPerSector, onProgress)
        writeFatHeads(partitionStartLba, fatSizeSectors, bytesPerSector)
        onProgress(90)

        // --- Root directory cluster: only the volume-label entry ---
        // (FastUsbWriter rewrites the root cluster with the real directory entries,
        //  preserving this volume-label entry at the head.)
        val firstDataSector = partitionStartLba + RESERVED_SECTORS + NUM_FATS * fatSizeSectors
        val rootDirSector = firstDataSector + (ROOT_CLUSTER - 2) * sectorsPerCluster
        writeRootDirectory(rootDirSector, sectorsPerCluster, bytesPerSector, volumeLabel)

        onProgress(100)

        val fatStartLba = partitionStartLba + RESERVED_SECTORS
        val fat2StartLba = fatStartLba + fatSizeSectors
        return FatGeometry(
            partitionStartLba = partitionStartLba,
            partitionSectors = partitionSectors,
            bytesPerSector = bytesPerSector,
            sectorsPerCluster = sectorsPerCluster,
            reservedSectors = RESERVED_SECTORS,
            numFats = NUM_FATS,
            fatSizeSectors = fatSizeSectors,
            fatStartLba = fatStartLba,
            fat2StartLba = fat2StartLba,
            dataStartLba = firstDataSector,
            rootCluster = ROOT_CLUSTER,
            totalClusters = clusterCount, // number of addressable data clusters (numbered 2..clusterCount+1)
            fsInfoLba = partitionStartLba + FSINFO_SECTOR
        )
    }

    /** Standard FAT32 FAT-size formula (FatGen103). */
    private fun computeFatSize(partitionSectors: Long, sectorsPerCluster: Int, bytesPerSector: Int): Long {
        val rootDirSectors = 0L // FAT32 has no fixed-size root dir
        val tmpVal1 = partitionSectors - (RESERVED_SECTORS + rootDirSectors)
        val tmpVal2 = (256L * sectorsPerCluster + NUM_FATS) / 2
        return (tmpVal1 + (tmpVal2 - 1)) / tmpVal2
    }

    /** Microsoft's cluster-size table for FAT32. */
    private fun chooseSectorsPerCluster(totalBytes: Long, bytesPerSector: Int): Int {
        val mib = totalBytes / (1024 * 1024)
        val clusterBytes = when {
            mib <= 260 -> 512
            mib <= 8 * 1024 -> 4096
            mib <= 16 * 1024 -> 8192
            mib <= 32 * 1024 -> 16384
            else -> 32768
        }
        return (clusterBytes / bytesPerSector).coerceAtLeast(1)
    }

    private fun writeMbr(partitionStartLba: Long, partitionSectors: Long, bytesPerSector: Int) {
        val b = ByteBuffer.allocate(bytesPerSector).order(ByteOrder.LITTLE_ENDIAN)

        // Partition table entry #1 begins at offset 446.
        val p = 446
        b.put(p, 0x80.toByte())              // bootable flag (active)
        // CHS start (legacy, set to a benign value; LBA fields are authoritative).
        b.put(p + 1, 0x20); b.put(p + 2, 0x21); b.put(p + 3, 0x00)
        b.put(p + 4, 0x0C)                   // partition type: FAT32 LBA
        // CHS end (approximate).
        b.put(p + 5, 0xFE.toByte()); b.put(p + 6, 0xFF.toByte()); b.put(p + 7, 0xFF.toByte())
        b.putInt(p + 8, partitionStartLba.toInt())
        b.putInt(p + 12, partitionSectors.toInt())

        // Boot signature.
        b.put(510, 0x55.toByte())
        b.put(511, 0xAA.toByte())
        writeSectorAbs(0, b.array())
    }

    private fun buildBootSector(
        bytesPerSector: Int,
        sectorsPerCluster: Int,
        partitionSectors: Long,
        fatSizeSectors: Long,
        partitionStartLba: Long,
        volumeLabel: String
    ): ByteArray {
        val b = ByteBuffer.allocate(bytesPerSector).order(ByteOrder.LITTLE_ENDIAN)

        b.put(0, 0xEB.toByte()); b.put(1, 0x58.toByte()); b.put(2, 0x90.toByte())
        putString(b, 3, "MSWIN4.1", 8)

        b.putShort(11, bytesPerSector.toShort())
        b.put(13, sectorsPerCluster.toByte())
        b.putShort(14, RESERVED_SECTORS.toShort())
        b.put(16, NUM_FATS.toByte())
        b.putShort(17, 0)                       // root entries (0 for FAT32)
        b.putShort(19, 0)                       // total sectors 16-bit (0 -> use 32-bit)
        b.put(21, 0xF8.toByte())                // media descriptor: fixed disk
        b.putShort(22, 0)                       // FAT size 16 (0 for FAT32)
        b.putShort(24, 63)                      // sectors per track
        b.putShort(26, 255)                     // heads
        b.putInt(28, partitionStartLba.toInt()) // hidden sectors = partition LBA
        b.putInt(32, partitionSectors.toInt())  // total sectors 32-bit

        // FAT32 extended BPB.
        b.putInt(36, fatSizeSectors.toInt())
        b.putShort(40, 0)                       // ext flags (mirroring enabled)
        b.putShort(42, 0)                       // fs version
        b.putInt(44, ROOT_CLUSTER.toInt())
        b.putShort(48, FSINFO_SECTOR.toShort())
        b.putShort(50, BACKUP_BOOT_SECTOR.toShort())
        b.put(64, 0x80.toByte())                // drive number
        b.put(66, 0x29.toByte())                // extended boot signature
        b.putInt(67, generateVolumeId())
        putString(b, 71, normalizeLabel(volumeLabel), 11)
        putString(b, 82, "FAT32   ", 8)

        b.put(510, 0x55.toByte())
        b.put(511, 0xAA.toByte())
        return b.array()
    }

    /**
     * Builds the FAT32 boot sector from an existing [FatGeometry] (used by the
     * post-burn boot-sector verify+rewrite recovery). Produces the same layout as
     * [buildBootSector] but reads field values from [geo] instead of recomputing.
     */
    fun buildPublicBootSector(geo: FatGeometry, volumeLabel: String): ByteArray {
        val bps = geo.bytesPerSector
        val b = ByteBuffer.allocate(bps).order(ByteOrder.LITTLE_ENDIAN)

        b.put(0, 0xEB.toByte()); b.put(1, 0x58.toByte()); b.put(2, 0x90.toByte())
        putString(b, 3, "MSWIN4.1", 8)

        b.putShort(11, bps.toShort())
        b.put(13, geo.sectorsPerCluster.toByte())
        b.putShort(14, geo.reservedSectors.toShort())
        b.put(16, geo.numFats.toByte())
        b.putShort(17, 0)
        b.putShort(19, 0)
        b.put(21, 0xF8.toByte())
        b.putShort(22, 0)
        b.putShort(24, 63)
        b.putShort(26, 255)
        b.putInt(28, geo.partitionStartLba.toInt())
        b.putInt(32, geo.partitionSectors.toInt())

        b.putInt(36, geo.fatSizeSectors.toInt())
        b.putShort(40, 0)
        b.putShort(42, 0)
        b.putInt(44, geo.rootCluster.toInt())
        b.putShort(48, FSINFO_SECTOR.toShort())
        b.putShort(50, BACKUP_BOOT_SECTOR.toShort())
        b.put(64, 0x80.toByte())
        b.put(66, 0x29.toByte())
        b.putInt(67, generateVolumeId())
        putString(b, 71, normalizeLabel(volumeLabel), 11)
        putString(b, 82, "FAT32   ", 8)

        b.put(510, 0x55.toByte())
        b.put(511, 0xAA.toByte())
        return b.array()
    }

    private fun buildFsInfo(clusterCount: Long): ByteArray {
        val b = ByteBuffer.allocate(blockSize).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0, 0x41615252)                 // "RRaA"
        b.putInt(484, 0x61417272)               // "rrAa"
        val freeClusters = (clusterCount - 1).coerceAtLeast(0)
        b.putInt(488, freeClusters.toInt())     // free cluster count
        b.putInt(492, 3)                        // next free cluster hint
        b.put(510, 0x55.toByte())
        b.put(511, 0xAA.toByte())
        return b.array()
    }

    /**
     * Zeroes both FAT copies in large sequential bursts so every cluster entry
     * starts free (0). Runs under the Formatting progress (6→88%). On a 28 GB
     * drive the two FATs are ~56 MB, written here in one fast pass instead of at
     * the end of the burn (which previously froze the progress UI).
     */
    private fun zeroFatRegion(
        partitionStartLba: Long,
        fatSizeSectors: Long,
        bytesPerSector: Int,
        onProgress: (Int) -> Unit
    ) {
        // Guard: the FAT region must begin after the reserved area (boot sector at
        // partitionStartLba, FSInfo, backup boot at +6). RESERVED_SECTORS=32, so
        // FAT1 starts at +32 — well clear of the boot sector. Catch any off-by-one.
        val fatStartCheck = partitionStartLba + RESERVED_SECTORS
        require(fatStartCheck >= partitionStartLba + 2) {
            "FAT start overlaps boot sector! fatStart=$fatStartCheck partition=$partitionStartLba"
        }
        val burstSectors = (MAX_BURST_BYTES / bytesPerSector).coerceAtLeast(1)
        val zero = ByteBuffer.allocate(burstSectors * bytesPerSector)
        val totalSectors = fatSizeSectors * NUM_FATS
        var done = 0L
        for (fat in 0 until NUM_FATS) {
            val fatStart = partitionStartLba + RESERVED_SECTORS + fat * fatSizeSectors
            var sector = 0L
            while (sector < fatSizeSectors) {
                val n = minOf(burstSectors.toLong(), fatSizeSectors - sector).toInt()
                zero.clear()
                zero.limit(n * bytesPerSector)
                blockDevice.write(fatStart + sector, zero)
                sector += n
                done += n
                onProgress((6 + (done * 82 / totalSectors)).toInt().coerceIn(6, 88))
            }
        }
    }

    private fun writeFatHeads(
        partitionStartLba: Long,
        fatSizeSectors: Long,
        bytesPerSector: Int
    ) {
        for (fat in 0 until NUM_FATS) {
            val head = ByteBuffer.allocate(bytesPerSector).order(ByteOrder.LITTLE_ENDIAN)
            head.putInt(0, 0x0FFFFFF8.toInt())  // cluster 0: media descriptor + EOC bits
            head.putInt(4, 0x0FFFFFFF)          // cluster 1: EOC / clean-shutdown flags
            head.putInt(8, 0x0FFFFFFF)          // cluster 2 (root dir): EOC
            head.clear()
            val fatStart = partitionStartLba + RESERVED_SECTORS + fat * fatSizeSectors
            blockDevice.write(fatStart, head)
        }
    }

    private fun writeRootDirectory(
        rootDirSector: Long,
        sectorsPerCluster: Int,
        bytesPerSector: Int,
        volumeLabel: String
    ) {
        // First sector holds the volume-label entry; remaining sectors are zero.
        val first = ByteBuffer.allocate(bytesPerSector).order(ByteOrder.LITTLE_ENDIAN)
        val label = normalizeLabel(volumeLabel).toByteArray(Charsets.US_ASCII)
        for (i in 0 until 11) {
            first.put(i, if (i < label.size) label[i] else ' '.code.toByte())
        }
        first.put(11, 0x08) // ATTR_VOLUME_ID
        first.clear()
        blockDevice.write(rootDirSector, first)

        if (sectorsPerCluster > 1) {
            val zero = ByteBuffer.allocate(bytesPerSector * (sectorsPerCluster - 1))
            zero.clear()
            blockDevice.write(rootDirSector + 1, zero)
        }
    }

    private fun writeSectorAbs(lba: Long, data: ByteArray) {
        val buf = ByteBuffer.allocate(blockSize)
        buf.put(data, 0, minOf(data.size, blockSize))
        buf.clear()
        blockDevice.write(lba, buf)
    }

    private fun putString(b: ByteBuffer, offset: Int, value: String, length: Int) {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        for (i in 0 until length) {
            b.put(offset + i, if (i < bytes.size) bytes[i] else ' '.code.toByte())
        }
    }

    private fun normalizeLabel(label: String): String {
        val cleaned = label.uppercase()
            .filter { it.isLetterOrDigit() || it == ' ' || it == '_' || it == '-' }
            .take(11)
        return cleaned.ifBlank { "BURN2DISK" }
    }

    private fun generateVolumeId(): Int = (System.currentTimeMillis() and 0xFFFFFFFF).toInt()
}
