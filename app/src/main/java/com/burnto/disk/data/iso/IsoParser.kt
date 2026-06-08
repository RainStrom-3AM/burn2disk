package com.burnto.disk.data.iso

import java.io.Closeable
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A single entry inside an ISO 9660 / Joliet filesystem.
 *
 * @param name the (Joliet-preferred) file or directory name with the `;1` version
 *             suffix stripped.
 * @param fullPath absolute path from the ISO root using `/` separators.
 * @param extentLba starting logical block address (LBA) of the entry's data.
 * @param sizeBytes data length in bytes.
 */
data class IsoEntry(
    val name: String,
    val fullPath: String,
    val isDirectory: Boolean,
    val extentLba: Long,
    val sizeBytes: Long
)

/**
 * A dependency-free ISO 9660 reader supporting Joliet long/Unicode names.
 *
 * The class is seek-heavy by nature (it jumps to `LBA * SECTOR_SIZE` constantly),
 * so it is backed by a [RandomAccessFile] rather than a stream. All multi-byte
 * numeric fields in ISO 9660 are stored "both-endian" (little then big); we read
 * the little-endian half.
 *
 * Usage:
 * ```
 * IsoParser(file).use { p ->
 *     p.open()
 *     val all = p.listAllEntries()
 * }
 * ```
 */
class IsoParser(private val raf: RandomAccessFile) : Closeable {

    constructor(path: String) : this(RandomAccessFile(path, "r"))

    companion object {
        const val SECTOR_SIZE = 2048
        private const val FIRST_DESCRIPTOR_LBA = 16L

        // Volume descriptor type codes.
        private const val VD_BOOT_RECORD = 0x00
        private const val VD_PRIMARY = 0x01
        private const val VD_SUPPLEMENTARY = 0x02
        private const val VD_TERMINATOR = 0xFF

        // Directory record flags.
        private const val FLAG_DIRECTORY = 0x02

        // Offsets within a volume descriptor.
        private const val ROOT_DIR_RECORD_OFFSET = 156
        private const val ROOT_DIR_RECORD_LEN = 34
    }

    private var rootRecord: DirRecord? = null
    private var rootRecordJoliet: DirRecord? = null
    private var usingJoliet = false

    private var _volumeLabel: String = ""
    val volumeLabel: String get() = _volumeLabel

    private var _systemIdentifier: String = ""
    val systemIdentifier: String get() = _systemIdentifier

    /** Raw root directory record, parsed from the volume descriptors. */
    private data class DirRecord(
        val extentLba: Long,
        val dataLength: Long
    )

    /**
     * Reads and parses the volume descriptor set, locating the primary and any
     * Joliet supplementary descriptor. Must be called before listing entries.
     */
    fun open() {
        var lba = FIRST_DESCRIPTOR_LBA
        while (true) {
            val sector = readSector(lba)
            val type = sector.get(0).toInt() and 0xFF
            val identifier = ByteArray(5)
            sector.position(1)
            sector.get(identifier)
            val id = String(identifier, Charsets.US_ASCII)

            if (id == "CD001") {
                when (type) {
                    VD_PRIMARY -> {
                        rootRecord = readRootRecord(sector)
                        _systemIdentifier = readString(sector, 8, 32)
                        _volumeLabel = readString(sector, 40, 32)
                    }
                    VD_SUPPLEMENTARY -> {
                        if (isJoliet(sector)) {
                            rootRecordJoliet = readRootRecord(sector)
                        }
                    }
                    VD_TERMINATOR -> break
                }
            } else {
                // Skip non-ISO 9660 descriptors (UDF, El Torito, etc.) and keep
                // scanning. UDF-bridge discs interleave these before the PVD.
            }

            lba++
            if (lba > FIRST_DESCRIPTOR_LBA + 64) break // safety bound
        }

        usingJoliet = rootRecordJoliet != null
        if (rootRecord == null && rootRecordJoliet == null) {
            throw IllegalStateException(
                "No ISO 9660 primary volume descriptor found. " +
                    "The image may be UDF-only or not a valid ISO 9660 disc."
            )
        }
    }

    /** The Joliet escape sequences identifying UCS-2 (UTF-16BE) name encoding. */
    private fun isJoliet(descriptor: ByteBuffer): Boolean {
        // Escape sequences live at offset 88, 32 bytes long.
        val esc = ByteArray(32)
        descriptor.position(88)
        descriptor.get(esc)
        // %/@  %/C  %/E   ==> 0x25 0x2F (0x40 | 0x43 | 0x45)
        for (i in 0 until 30) {
            if (esc[i].toInt() == 0x25 && esc[i + 1].toInt() == 0x2F) {
                val third = esc[i + 2].toInt()
                if (third == 0x40 || third == 0x43 || third == 0x45) return true
            }
        }
        return false
    }

    private fun readString(buffer: ByteBuffer, offset: Int, length: Int): String {
        val arr = ByteArray(length)
        buffer.position(offset)
        buffer.get(arr)
        return String(arr, Charsets.US_ASCII).trimEnd()
    }

    private fun readRootRecord(descriptor: ByteBuffer): DirRecord {
        val rec = ByteArray(ROOT_DIR_RECORD_LEN)
        descriptor.position(ROOT_DIR_RECORD_OFFSET)
        descriptor.get(rec)
        val bb = ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN)
        val extent = bb.getInt(2).toLong() and 0xFFFFFFFFL
        val length = bb.getInt(10).toLong() and 0xFFFFFFFFL
        return DirRecord(extent, length)
    }

    /** Reads a single 2048-byte logical sector at [lba] as a little-endian buffer. */
    fun readSector(lba: Long): ByteBuffer {
        val buf = ByteArray(SECTOR_SIZE)
        raf.seek(lba * SECTOR_SIZE)
        raf.readFully(buf)
        return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
    }

    /**
     * Recursively walks the directory tree and returns every file and directory.
     * Joliet names are preferred when available.
     */
    fun listAllEntries(): List<IsoEntry> {
        val root = (if (usingJoliet) rootRecordJoliet else rootRecord)
            ?: throw IllegalStateException("open() must be called first")
        val out = ArrayList<IsoEntry>()
        walk(root.extentLba, root.dataLength, "", out, HashSet())
        return out
    }

    /** Lists direct children of the directory whose data starts at [extentLba]. */
    fun listDirectory(extentLba: Long, dataLength: Long, parentPath: String): List<IsoEntry> {
        val out = ArrayList<IsoEntry>()
        readDirRecords(extentLba, dataLength, parentPath, out)
        return out
    }

    private fun walk(
        extentLba: Long,
        dataLength: Long,
        parentPath: String,
        out: MutableList<IsoEntry>,
        visited: MutableSet<Long>
    ) {
        if (!visited.add(extentLba)) return // guard against malformed cyclic ISOs
        val children = ArrayList<IsoEntry>()
        readDirRecords(extentLba, dataLength, parentPath, children)
        for (child in children) {
            out.add(child)
            if (child.isDirectory) {
                walk(child.extentLba, child.sizeBytes, child.fullPath, out, visited)
            }
        }
    }

    /**
     * Parses all directory records contained in the directory whose extent begins
     * at [extentLba] and spans [dataLength] bytes. Skips the `.` and `..` records.
     */
    private fun readDirRecords(
        extentLba: Long,
        dataLength: Long,
        parentPath: String,
        out: MutableList<IsoEntry>
    ) {
        val totalSectors = ((dataLength + SECTOR_SIZE - 1) / SECTOR_SIZE).toInt()
        for (s in 0 until totalSectors) {
            val sector = readSector(extentLba + s)
            var offset = 0
            while (offset < SECTOR_SIZE) {
                val recordLen = sector.get(offset).toInt() and 0xFF
                if (recordLen == 0) {
                    // Records do not span sector boundaries; the rest is padding.
                    break
                }
                if (recordLen < ROOT_DIR_RECORD_LEN || offset + recordLen > SECTOR_SIZE) break
                parseRecord(sector, offset, parentPath)?.let { out.add(it) }
                offset += recordLen
            }
        }
    }

    private fun parseRecord(sector: ByteBuffer, offset: Int, parentPath: String): IsoEntry? {
        val extLba = sector.getInt(offset + 2).toLong() and 0xFFFFFFFFL
        val dataLen = sector.getInt(offset + 10).toLong() and 0xFFFFFFFFL
        val flags = sector.get(offset + 25).toInt() and 0xFF
        val nameLen = sector.get(offset + 32).toInt() and 0xFF
        if (nameLen == 0) return null

        val rawName = ByteArray(nameLen)
        for (i in 0 until nameLen) {
            rawName[i] = sector.get(offset + 33 + i)
        }

        // Special records: 0x00 == ".", 0x01 == ".."
        if (nameLen == 1 && (rawName[0].toInt() == 0 || rawName[0].toInt() == 1)) {
            return null
        }

        val isDir = (flags and FLAG_DIRECTORY) != 0
        val name = decodeName(rawName, isDir)
        if (name.isEmpty()) return null

        val fullPath = if (parentPath.isEmpty()) name else "$parentPath/$name"
        return IsoEntry(
            name = name,
            fullPath = fullPath,
            isDirectory = isDir,
            extentLba = extLba,
            sizeBytes = dataLen
        )
    }

    private fun decodeName(raw: ByteArray, isDir: Boolean): String {
        val decoded = if (usingJoliet) {
            // Joliet names are UCS-2 / UTF-16 big-endian.
            String(raw, Charsets.UTF_16BE)
        } else {
            String(raw, Charsets.US_ASCII)
        }
        if (isDir) return decoded.trimEnd('\u0000')
        // Strip the ISO version suffix ";1".
        val semi = decoded.indexOf(';')
        val stripped = if (semi >= 0) decoded.substring(0, semi) else decoded
        // A trailing '.' on a file with no extension is allowed by the spec; drop it.
        return stripped.trimEnd('.', '\u0000')
    }

    override fun close() {
        raf.close()
    }
}
