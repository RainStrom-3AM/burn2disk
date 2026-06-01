package com.burnto.disk.data.usb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * Writes a FAT32 filesystem's directory tree and file data **directly** to the
 * raw [BlockDeviceDriver], bypassing libaums' `UsbFile` entirely.
 *
 * Why: libaums issues one SCSI WRITE(10) per `UsbFile.write()` call, so a 64 KiB
 * chunked copy means thousands of round-trips and ~0.1 MB/s. By allocating
 * contiguous clusters and writing each file as one large sequential burst
 * (benchmarked chunk size), throughput jumps 10-30x.
 *
 * Design (all in-memory, flushed in big bursts):
 *  - The FAT is modelled as an in-memory `IntArray` (one 32-bit entry per
 *    cluster), populated as chains are allocated, then flushed to both FAT
 *    copies in large sequential writes. This both initialises unused entries to
 *    0 (free) and records every chain — no slow per-chunk read-modify-write.
 *  - Allocation is strictly contiguous, so each file's clusters form one LBA run
 *    that can be written in a single sequential pass.
 *  - Directory entries (with full VFAT long-filename support) are buffered per
 *    directory and flushed once.
 *
 * Construction takes the [FatGeometry] returned by [Fat32Formatter.format].
 * (The spec listed the individual geometry fields as constructor params; they
 * are exactly the fields of [FatGeometry], passed here as one object.)
 */
class FastUsbWriter(
    private val blockDevice: BlockDeviceDriver,
    private val geo: FatGeometry,
    private val optimalChunkBytes: Int,
    private val volumeLabel: String
) {
    private val bytesPerSector = geo.bytesPerSector
    private val sectorsPerCluster = geo.sectorsPerCluster
    private val clusterBytes = geo.clusterBytes.toInt()

    // In-memory FAT: index = cluster number, value = next cluster / EOC / 0(free).
    // Sized for clusters 0..(totalClusters+1).
    private val fat = IntArray((geo.totalClusters + 2).toInt())

    private var nextFreeCluster: Long = 2L

    // Reusable I/O buffers (allocated once; up to optimalChunkBytes each).
    private val ioChunk: ByteArray by lazy(LazyThreadSafetyMode.NONE) { ByteArray(optimalChunkBytes) }
    private val ioBuf: ByteBuffer by lazy(LazyThreadSafetyMode.NONE) {
        ByteBuffer.allocate(optimalChunkBytes)
    }

    // path -> first cluster of that directory's chain
    private val dirClusterMap = HashMap<String, Long>()
    // path -> accumulated 32-byte directory entries
    private val dirEntries = HashMap<String, MutableList<ByteArray>>()
    // path -> short names already used in that directory (for ~N uniqueness)
    private val usedShortNames = HashMap<String, MutableSet<String>>()

    companion object {
        private const val ATTR_VOLUME_ID = 0x08
        private const val ATTR_DIRECTORY = 0x10
        private const val ATTR_ARCHIVE = 0x20
        private const val ATTR_LFN = 0x0F
        private const val EOC = 0x0FFFFFFF
        private const val FAT_FLUSH_BURST = 1 shl 20 // 1 MiB FAT flush bursts
    }

    init {
        // Reserved FAT entries.
        if (fat.size > 0) fat[0] = 0x0FFFFFF8.toInt()
        if (fat.size > 1) fat[1] = EOC
        if (fat.size > 2) fat[2] = EOC  // root directory cluster (chain re-set on allocateRoot)
    }

    /**
     * Seeds the root directory and its volume-label entry. The BPB fixes the root
     * at cluster 2, so allocation begins there; [allocateRoot] then claims the
     * root's cluster run before any other directory/file.
     */
    fun setRootCluster(rootCluster: Long) {
        dirClusterMap[""] = rootCluster
        dirEntries[""] = mutableListOf<ByteArray>().apply {
            add(buildVolumeLabelEntry(volumeLabel))
        }
        usedShortNames[""] = mutableSetOf()
        // Allocation starts at the root cluster; allocateRoot() consumes it.
        nextFreeCluster = rootCluster
    }

    /**
     * Allocates the root directory's contiguous cluster run (must be the FIRST
     * allocation, so root stays at cluster 2 as the BPB declares).
     */
    fun allocateRoot(clusterCount: Long) {
        val rootCluster = dirClusterMap[""] ?: error("setRootCluster not called")
        require(nextFreeCluster == rootCluster) { "allocateRoot must be the first allocation" }
        val first = allocateClusters(clusterCount)
        check(first == rootCluster) { "root cluster mismatch" }
        writeFatChain(first, clusterCount)
        flushFatSectors(first, clusterCount)
    }

    /**
     * Number of 32-byte directory slots a child with [name] will occupy:
     * the long-filename entries plus the single 8.3 alias. Callers use this to
     * size directory clusters before allocation.
     */
    fun slotsForName(name: String): Int = lfnEntryCount(name) + 1

    /** Allocates [count] contiguous clusters and returns the first cluster number. */
    fun allocateClusters(count: Long): Long {
        require(count > 0) { "count must be > 0" }
        val first = nextFreeCluster
        nextFreeCluster += count
        // clusters are numbered 2..(totalClusters+1)
        if (nextFreeCluster > geo.totalClusters + 2) {
            throw IOException("USB drive full")
        }
        return first
    }

    /** Records a contiguous cluster chain in the in-memory FAT. */
    fun writeFatChain(firstCluster: Long, clusterCount: Long) {
        if (clusterCount == 0L) return
        for (i in 0 until clusterCount) {
            val index = (firstCluster + i).toInt()
            if (index < 0 || index >= fat.size) {
                throw IOException("Cluster $index out of FAT bounds (size=${fat.size})")
            }
            fat[index] = if (i == clusterCount - 1) EOC else (firstCluster + i + 1).toInt()
        }
    }

    /**
     * Immediately flushes only the FAT sectors that hold the entries for the
     * cluster run [firstCluster]..[firstCluster+clusterCount-1], to both FAT
     * copies. Called right after each [writeFatChain] so a file's chain is on
     * disk as soon as its data is — if the burn is interrupted, completed files
     * remain visible/recoverable instead of appearing as 0 bytes.
     */
    fun flushFatSectors(firstCluster: Long, clusterCount: Long) {
        if (clusterCount == 0L) return
        val entriesPerSector = bytesPerSector / 4
        val firstSector = firstCluster / entriesPerSector
        val lastSector = (firstCluster + clusterCount - 1) / entriesPerSector
        val count = (lastSector - firstSector + 1).toInt()
        val buf = ByteBuffer.allocate(count * bytesPerSector).order(ByteOrder.LITTLE_ENDIAN)
        val baseEntry = firstSector * entriesPerSector
        for (i in 0 until count * entriesPerSector) {
            val idx = (baseEntry + i)
            buf.putInt(i * 4, if (idx < fat.size) fat[idx.toInt()] else 0)
        }
        buf.position(0); buf.limit(count * bytesPerSector)
        blockDevice.write(geo.fatStartLba + firstSector, buf)
        buf.position(0); buf.limit(count * bytesPerSector)
        blockDevice.write(geo.fat2StartLba + firstSector, buf)
    }

    /** Registers a subdirectory so its children can be added by path. */
    fun registerDirectory(fullPath: String, firstCluster: Long) {
        dirClusterMap[fullPath] = firstCluster
        dirEntries.getOrPut(fullPath) { mutableListOf() }
        usedShortNames.getOrPut(fullPath) { mutableSetOf() }
    }

    /** First cluster of a registered directory (root is the BPB root cluster). */
    fun dirClusterOf(path: String): Long =
        dirClusterMap[path] ?: throw IOException("Directory not registered: $path")

    /** Adds the mandatory "." and ".." entries to a (non-root) directory. */
    fun addDotEntries(fullPath: String, selfCluster: Long, parentCluster: Long) {
        val list = dirEntries.getOrPut(fullPath) { mutableListOf() }
        // ".." pointing at the root is stored as cluster 0 per the FAT spec.
        val dotDotCluster = if (parentCluster == geo.rootCluster) 0L else parentCluster
        list.add(buildDotEntry(".", selfCluster))
        list.add(buildDotEntry("..", dotDotCluster))
    }

    /**
     * Queues a directory entry (LFN block + 8.3 alias) for [parentPath].
     * The short alias is made unique within the parent directory.
     */
    fun addDirectoryEntry(
        parentPath: String,
        name: String,
        firstCluster: Long,
        sizeBytes: Long,
        isDirectory: Boolean
    ) {
        val used = usedShortNames.getOrPut(parentPath) { mutableSetOf() }
        val shortName = generateShortName(name, used)
        used.add(shortName)

        val list = dirEntries.getOrPut(parentPath) { mutableListOf() }
        val checksum = shortNameChecksum(shortName)
        // LFN entries are stored in reverse (highest sequence first).
        val lfn = buildLfnEntries(name, checksum)
        list.addAll(lfn)
        list.add(buildShortEntry(shortName, firstCluster, sizeBytes, isDirectory))
    }

    /**
     * Writes a file's bytes from the ISO at [extentLba] to its contiguous
     * cluster run starting at [firstCluster], in large sequential bursts.
     * [onProgress] receives the delta written since the previous call.
     */
    suspend fun writeFileFromIso(
        raf: RandomAccessFile,
        extentLba: Long,
        sizeBytes: Long,
        firstCluster: Long,
        onProgress: (Long) -> Unit
    ): Long = withContext(Dispatchers.IO) {
        if (sizeBytes == 0L) return@withContext 0L

        val isoSector = 2048L
        raf.seek(extentLba * isoSector)

        // Clusters are contiguous, so writing is a single sequential LBA run.
        var lba = geo.clusterToLba(firstCluster)
        val chunk = ioChunk
        val buf = ioBuf
        var remaining = sizeBytes
        var written = 0L

        while (remaining > 0) {
            coroutineContext.ensureActive()
            val toRead = minOf(optimalChunkBytes.toLong(), remaining).toInt()
            raf.readFully(chunk, 0, toRead)

            // Pad up to a whole-sector boundary; the block device only accepts
            // multiples of the sector size.
            val padded = ((toRead + bytesPerSector - 1) / bytesPerSector) * bytesPerSector
            buf.clear()
            buf.put(chunk, 0, toRead)
            // Zero the tail of the final partial sector.
            for (i in toRead until padded) buf.put(i, 0)
            buf.position(0)
            buf.limit(padded)
            blockDevice.write(lba, buf)

            lba += (padded / bytesPerSector).toLong()
            written += toRead
            remaining -= toRead
            onProgress(toRead.toLong())
        }
        written
    }

    /** Writes a local file's bytes to its contiguous cluster run (for .swm parts). */
    suspend fun writeLocalFile(
        file: java.io.File,
        firstCluster: Long,
        onProgress: (Long) -> Unit
    ): Long = withContext(Dispatchers.IO) {
        var lba = geo.clusterToLba(firstCluster)
        val chunk = ioChunk
        val buf = ioBuf
        var written = 0L
        file.inputStream().buffered(optimalChunkBytes).use { input ->
            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(chunk)
                if (read < 0) break
                val padded = ((read + bytesPerSector - 1) / bytesPerSector) * bytesPerSector
                buf.clear()
                buf.put(chunk, 0, read)
                for (i in read until padded) buf.put(i, 0)
                buf.position(0)
                buf.limit(padded)
                blockDevice.write(lba, buf)
                lba += (padded / bytesPerSector).toLong()
                written += read
                onProgress(read.toLong())
            }
        }
        written
    }

    /** Absolute device LBA of a cluster (exposed for the write batcher). */
    fun lbaOfCluster(cluster: Long): Long = geo.clusterToLba(cluster)

    /**
     * Streams a large file from the ISO to its contiguous cluster run using a
     * read-ahead pipeline: a producer coroutine reads the next chunk from the ISO
     * (fast internal storage) while the consumer writes the previous chunk to USB
     * (slow). A bounded [kotlinx.coroutines.channels.Channel] of pre-filled
     * buffers (FIFO) preserves order; the consumer advances the LBA
     * deterministically, so data always lands contiguously and in sequence.
     */
    suspend fun writeFileFromIsoPipelined(
        raf: RandomAccessFile,
        extentLba: Long,
        sizeBytes: Long,
        firstCluster: Long,
        onProgress: (Long) -> Unit
    ): Long {
        if (sizeBytes == 0L) return 0L
        val isoSector = 2048L
        val startLba = geo.clusterToLba(firstCluster)

        return kotlinx.coroutines.coroutineScope {
            // Channel carries (buffer, validLen). Capacity 2 = at most 2 chunks
            // queued. Buffers are heap-backed (libaums needs .array()).
            val channelCapacity = 2
            val channel = Channel<Pair<ByteBuffer, Int>>(capacity = channelCapacity)

            // Reused buffer pool. Max buffers live at once = queued (capacity) +
            // one being written by the consumer + one being filled by the
            // producer = capacity + 2. A smaller pool would let the producer
            // overwrite a buffer the consumer is still writing → data corruption.
            val poolSize = channelCapacity + 2
            val bufPool = Array(poolSize) { ByteBuffer.allocate(optimalChunkBytes + bytesPerSector) }
            var poolIdx = 0

            val producer = launch(Dispatchers.IO) {
                raf.seek(extentLba * isoSector)
                var remaining = sizeBytes
                val tmp = ByteArray(optimalChunkBytes)
                try {
                    while (remaining > 0) {
                        coroutineContext.ensureActive()
                        val toRead = minOf(optimalChunkBytes.toLong(), remaining).toInt()
                        raf.readFully(tmp, 0, toRead)
                        val padded = ((toRead + bytesPerSector - 1) / bytesPerSector) * bytesPerSector
                        val buf = bufPool[poolIdx % poolSize]
                        poolIdx++
                        buf.clear()
                        buf.put(tmp, 0, toRead)
                        // Zero the tail (buffer is reused, so re-zero the pad region).
                        for (i in toRead until padded) buf.put(i, 0)
                        buf.position(0)
                        buf.limit(padded)
                        channel.send(buf to toRead)
                        remaining -= toRead
                    }
                } finally {
                    channel.close()
                }
            }

            var lba = startLba
            var written = 0L
            withContext(Dispatchers.IO) {
                for ((buf, validLen) in channel) {
                    coroutineContext.ensureActive()
                    blockDevice.write(lba, buf)
                    lba += (buf.limit() / bytesPerSector).toLong()
                    written += validLen
                    onProgress(validLen.toLong())
                }
            }
            producer.join()
            written
        }
    }

    /** Bytes a file of [sizeBytes] occupies once padded to whole clusters. */
    fun paddedClusterBytes(sizeBytes: Long): Int {
        val clusters = if (sizeBytes == 0L) 1L else (sizeBytes + clusterBytes - 1) / clusterBytes
        return (clusters * clusterBytes).toInt()
    }

    /** Creates a coalescing write batcher bound to this writer's block device. */
    fun newBatcher(): WriteBatcher = WriteBatcher()

    /**
     * Coalesces many small files into a single large SCSI WRITE.
     *
     * libaums issues one WRITE(10) per [BlockDeviceDriver.write] call, so writing
     * hundreds of tiny files individually is dominated by per-command overhead.
     * This accumulates consecutive files' (cluster-padded) data into one heap
     * buffer and flushes the whole run in a single write.
     *
     * Correctness: a single write must target one contiguous LBA region, so the
     * batcher only appends a file when its start LBA equals the LBA immediately
     * after the buffered data. A non-contiguous file (or a full buffer) forces a
     * flush and starts a fresh batch — it can never land data at the wrong LBA.
     *
     * The buffer is a HEAP [ByteBuffer] (not direct): libaums' bulk transfer calls
     * `buffer.array()`, which throws on direct buffers.
     */
    inner class WriteBatcher {
        // 80% of currently-available heap, capped at 32 MiB, floored at the
        // benchmarked chunk size, rounded down to a whole number of clusters.
        private var bufferSize: Int = computeBufferSize()
        private var buffer: ByteBuffer = ByteBuffer.allocate(bufferSize)
        private var bufferStartLba: Long = -1L
        private var filesInBatch: Int = 0
        private var flushCount: Int = 0
        private var filesCoalesced: Int = 0

        val batchWrites: Int get() = flushCount
        val coalescedFiles: Int get() = filesCoalesced

        private fun computeBufferSize(): Int {
            val rt = Runtime.getRuntime()
            val avail = rt.maxMemory() - rt.totalMemory() + rt.freeMemory()
            val target = minOf((avail * 0.8).toLong(), 32L * 1024 * 1024)
                .toInt().coerceAtLeast(optimalChunkBytes)
            // Round down to a whole number of clusters (>= 1 cluster).
            return (target / clusterBytes).coerceAtLeast(1) * clusterBytes
        }

        /** Largest single file (padded) this batcher's buffer can hold. */
        fun canHold(paddedSize: Int): Boolean = paddedSize <= bufferSize

        /** LBA the next contiguous file must start at to extend the batch. */
        private fun expectedNextLba(): Long =
            bufferStartLba + (buffer.position() / bytesPerSector)

        /**
         * Appends one file's pre-read bytes to the batch, flushing first if it is
         * not contiguous with the current batch or would overflow the buffer.
         */
        suspend fun queue(data: ByteArray, validLen: Int, fileLba: Long) {
            val paddedSize = run {
                val clusters = ((validLen + clusterBytes - 1) / clusterBytes).coerceAtLeast(1)
                clusters * clusterBytes
            }
            val contiguous = bufferStartLba != -1L && fileLba == expectedNextLba()
            if (buffer.position() == 0) {
                bufferStartLba = fileLba
            } else if (!contiguous || paddedSize > buffer.remaining()) {
                flush()
                bufferStartLba = fileLba
            }
            buffer.put(data, 0, validLen)
            // Zero-pad the tail of the file's last cluster. Use Arrays.fill on the
            // backing array (a fast JVM intrinsic) instead of a per-byte loop.
            // NOTE: we cannot just advance the position — the buffer is reused
            // across batches and clear() does not re-zero it, so the pad region
            // could otherwise contain stale bytes from a previous file.
            val padLen = paddedSize - validLen
            if (padLen > 0) {
                val pos = buffer.position()
                java.util.Arrays.fill(buffer.array(), pos, pos + padLen, 0.toByte())
                buffer.position(pos + padLen)
            }
            filesInBatch++
            filesCoalesced++
        }

        /** Writes the accumulated buffer to the device in a single write. */
        suspend fun flush() = withContext(Dispatchers.IO) {
            if (buffer.position() == 0) return@withContext
            val len = buffer.position()
            buffer.position(0)
            buffer.limit(len)
            blockDevice.write(bufferStartLba, buffer)
            buffer.clear()
            bufferStartLba = -1L
            filesInBatch = 0
            flushCount++

            // Memory guard: if free heap is getting tight, shrink the buffer for
            // the rest of the burn to avoid OOM on low-RAM devices.
            val rt = Runtime.getRuntime()
            val avail = rt.maxMemory() - rt.totalMemory() + rt.freeMemory()
            if (avail < 20L * 1024 * 1024 && bufferSize > clusterBytes * 2) {
                bufferSize = (bufferSize / 2 / clusterBytes).coerceAtLeast(1) * clusterBytes
                buffer = ByteBuffer.allocate(bufferSize)
            }
        }
    }

    /** Writes every queued directory's entries to its cluster run. */
    fun flushDirectories() {
        for ((path, entries) in dirEntries) {
            val firstCluster = dirClusterMap[path] ?: continue
            val bytesNeeded = entries.size * 32
            // Round up to whole clusters (directory clusters were allocated to fit).
            val totalBytes = ((bytesNeeded + clusterBytes - 1) / clusterBytes) * clusterBytes
            val buf = ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (e in entries) buf.put(e)
            // Remaining bytes are 0x00 (end-of-directory marker), already zeroed.
            buf.clear()
            blockDevice.write(geo.clusterToLba(firstCluster), buf)
        }
    }

    /** Flushes the in-memory FAT to both FAT copies in large bursts. */
    /**
     * Finalises the FAT. The region was zeroed at format time and every allocated
     * chain was flushed incrementally via [flushFatSectors] as its file was
     * written, so here we only need to (re)write the reserved first sector of each
     * FAT copy — making finalisation nearly instant instead of a multi-MB end
     * phase that froze the progress UI.
     */
    fun flushFat() {
        val entriesPerSector = bytesPerSector / 4
        val buf = ByteBuffer.allocate(bytesPerSector).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until entriesPerSector) {
            buf.putInt(i * 4, if (i < fat.size) fat[i] else 0)
        }
        buf.position(0); buf.limit(bytesPerSector)
        blockDevice.write(geo.fatStartLba, buf)
        buf.position(0); buf.limit(bytesPerSector)
        blockDevice.write(geo.fat2StartLba, buf)
    }

    /** Updates the FSInfo sector with the real free-cluster count and next-free hint. */
    fun updateFsInfo() {
        val usedClusters = nextFreeCluster - 2 // clusters 2..nextFree-1 are in use
        val freeClusters = (geo.totalClusters - usedClusters).coerceAtLeast(0)
        val b = ByteBuffer.allocate(bytesPerSector).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0, 0x41615252)               // "RRaA"
        b.putInt(484, 0x61417272)             // "rrAa"
        b.putInt(488, freeClusters.toInt())
        b.putInt(492, nextFreeCluster.toInt())
        b.put(508, 0x00)
        b.put(509, 0x00)
        b.put(510, 0x55.toByte())
        b.put(511, 0xAA.toByte())
        b.clear()
        blockDevice.write(geo.fsInfoLba, b)
    }

    // ---------------------------------------------------------------------
    // Directory entry construction
    // ---------------------------------------------------------------------

    private fun buildVolumeLabelEntry(label: String): ByteArray {
        val entry = ByteArray(32)
        val name = normalizeLabel(label).toByteArray(Charsets.US_ASCII)
        for (i in 0 until 11) entry[i] = if (i < name.size) name[i] else ' '.code.toByte()
        entry[11] = ATTR_VOLUME_ID.toByte()
        return entry
    }

    private fun buildDotEntry(dot: String, cluster: Long): ByteArray {
        val entry = ByteArray(32)
        val bb = ByteBuffer.wrap(entry).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 11) entry[i] = ' '.code.toByte()
        for (i in dot.indices) entry[i] = dot[i].code.toByte()
        entry[11] = ATTR_DIRECTORY.toByte()
        bb.putShort(20, ((cluster shr 16) and 0xFFFF).toShort())
        bb.putShort(26, (cluster and 0xFFFF).toShort())
        bb.putInt(28, 0)
        stampTimes(bb)
        return entry
    }

    private fun buildShortEntry(
        shortName: String,
        firstCluster: Long,
        sizeBytes: Long,
        isDirectory: Boolean
    ): ByteArray {
        val entry = ByteArray(32)
        val bb = ByteBuffer.wrap(entry).order(ByteOrder.LITTLE_ENDIAN)
        // shortName is exactly 11 bytes (8 + 3, space padded).
        for (i in 0 until 11) entry[i] = shortName[i].code.toByte()
        entry[11] = (if (isDirectory) ATTR_DIRECTORY else ATTR_ARCHIVE).toByte()
        bb.putShort(20, ((firstCluster shr 16) and 0xFFFF).toShort())
        bb.putShort(26, (firstCluster and 0xFFFF).toShort())
        bb.putInt(28, if (isDirectory) 0 else sizeBytes.toInt())
        stampTimes(bb)
        return entry
    }

    private fun stampTimes(bb: ByteBuffer) {
        val cal = java.util.Calendar.getInstance()
        val fatDate = ((cal.get(java.util.Calendar.YEAR) - 1980) shl 9) or
            ((cal.get(java.util.Calendar.MONTH) + 1) shl 5) or
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        val fatTime = (cal.get(java.util.Calendar.HOUR_OF_DAY) shl 11) or
            (cal.get(java.util.Calendar.MINUTE) shl 5) or
            (cal.get(java.util.Calendar.SECOND) / 2)
        bb.putShort(14, fatTime.toShort()) // creation time
        bb.putShort(16, fatDate.toShort()) // creation date
        bb.putShort(18, fatDate.toShort()) // last access date
        bb.putShort(22, fatTime.toShort()) // write time
        bb.putShort(24, fatDate.toShort()) // write date
    }

    // ---------------------------------------------------------------------
    // VFAT long filenames
    // ---------------------------------------------------------------------

    /** Number of 13-char LFN entries needed for [name]. */
    private fun lfnEntryCount(name: String): Int {
        val len = name.length
        return ((len + 12) / 13).coerceAtLeast(1)
    }

    /**
     * Builds the LFN entries for [name] in the order they must appear on disk
     * (highest sequence number first, ending right before the 8.3 entry).
     */
    private fun buildLfnEntries(name: String, checksum: Int): List<ByteArray> {
        val count = lfnEntryCount(name)
        val chars = name.toCharArray()
        val result = ArrayList<ByteArray>(count)
        // Build sequence 1..count, then reverse so the on-disk order is correct.
        for (seq in 1..count) {
            val entry = ByteArray(32)
            val isLast = seq == count
            entry[0] = (if (isLast) (seq or 0x40) else seq).toByte()
            entry[11] = ATTR_LFN.toByte()
            entry[12] = 0
            entry[13] = checksum.toByte()
            entry[26] = 0
            entry[27] = 0

            // 13 UTF-16LE chars laid out at offsets 1..10, 14..25, 28..31.
            val charOffsets = intArrayOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)
            for (c in 0 until 13) {
                val globalIndex = (seq - 1) * 13 + c
                val off = charOffsets[c]
                val value: Int = when {
                    globalIndex < chars.size -> chars[globalIndex].code
                    globalIndex == chars.size -> 0       // null terminator
                    else -> 0xFFFF                       // padding
                }
                entry[off] = (value and 0xFF).toByte()
                entry[off + 1] = ((value shr 8) and 0xFF).toByte()
            }
            result.add(entry)
        }
        result.reverse()
        return result
    }

    private fun shortNameChecksum(shortName: String): Int {
        var sum = 0
        for (i in 0 until 11) {
            val c = shortName[i].code and 0xFF
            sum = (((sum and 1) shl 7) + (sum ushr 1) + c) and 0xFF
        }
        return sum
    }

    /**
     * Generates a unique, valid 8.3 short alias (11 chars, space-padded) for
     * [name] within a directory, using the classic `BASE~N.EXT` scheme.
     */
    private fun generateShortName(name: String, used: Set<String>): String {
        val dot = name.lastIndexOf('.')
        val rawBase = if (dot > 0) name.substring(0, dot) else name
        val rawExt = if (dot > 0) name.substring(dot + 1) else ""

        val base = sanitizeShort(rawBase)
        val ext = sanitizeShort(rawExt).take(3)

        // Try BASE~1 .. BASE~999999 until unique.
        var n = 1
        while (true) {
            val suffix = "~$n"
            val baseRoom = (8 - suffix.length).coerceAtLeast(1)
            val candidateBase = (base.take(baseRoom) + suffix).take(8)
            val packed = pack83(candidateBase, ext)
            if (packed !in used) return packed
            n++
            if (n > 999_999) {
                // Extremely unlikely; fall back to a time-based unique name.
                val uniq = (System.nanoTime() and 0xFFFFFF).toString(16).uppercase().take(6)
                return pack83("$uniq~1".take(8), ext)
            }
        }
    }

    /** Uppercases and strips characters not allowed in 8.3 names. */
    private fun sanitizeShort(s: String): String {
        val sb = StringBuilder()
        for (c in s.uppercase()) {
            when {
                c.isLetterOrDigit() && c.code < 128 -> sb.append(c)
                c in "$%'-_@~`!(){}^#&" -> sb.append(c)
                else -> sb.append('_')
            }
        }
        return sb.toString().ifEmpty { "_" }
    }

    /** Packs an 8.3 name into the 11-byte space-padded on-disk form. */
    private fun pack83(base: String, ext: String): String {
        val b = base.take(8).padEnd(8, ' ')
        val e = ext.take(3).padEnd(3, ' ')
        return b + e
    }

    private fun normalizeLabel(label: String): String {
        val cleaned = label.uppercase()
            .filter { it.isLetterOrDigit() || it == ' ' || it == '_' || it == '-' }
            .take(11)
        return cleaned.ifBlank { "BURN2DISK" }
    }
}
