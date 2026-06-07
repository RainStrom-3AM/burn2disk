package com.burnto.disk.data.sdcard

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.burnto.disk.data.iso.IsoParser
import com.burnto.disk.data.model.BurnException
import com.burnto.disk.data.model.BurnState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

/**
 * Copies ISO contents to an SD card via the Storage Access Framework.
 *
 * Unlike [com.burnto.disk.data.usb.BurnEngine], this does NOT use raw block writes,
 * does NOT format the card, and does NOT create a bootable MBR/FAT32 image.
 * It simply extracts the ISO files one by one into a folder on the SD card.
 *
 * This is useful for carrying ISO contents or for devices that boot from SD
 * via a bootloader that reads the individual files (e.g. Raspberry Pi, some
 * UEFI implementations).
 */
class SdCardBurnEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val _state = MutableStateFlow<BurnState>(BurnState.Idle)
    val state: StateFlow<BurnState> = _state.asStateFlow()

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    fun resetState() {
        _state.value = BurnState.Idle
        _logLines.value = emptyList()
    }

    /**
     * Copies the contents of [isoFile] to the SD card root represented by [sdCardUri].
     * A folder named after the ISO (without extension) is created and populated.
     */
    suspend fun burn(isoFile: File, sdCardUri: Uri) = withContext(Dispatchers.IO) {
        resetState()
        val startMs = System.currentTimeMillis()
        emitLog("Parsing ISO filesystem...")

        val entries = RandomAccessFile(isoFile, "r").use { raf ->
            IsoParser(raf).let { parser ->
                parser.open()
                parser.listAllEntries()
            }
        }
        emitLog("${entries.size} entries found")
        if (entries.isEmpty()) {
            throw BurnException("ISO contains no files", "The image may be corrupted or unsupported")
        }

        val destRoot = DocumentFile.fromTreeUri(context, sdCardUri)
            ?: throw BurnException("Cannot access SD card", "Grant SD card permission first")

        val folderName = isoFile.nameWithoutExtension
        val existing = destRoot.findFile(folderName)
        existing?.delete()
        val destFolder = destRoot.createDirectory(folderName)
            ?: throw BurnException(
                "Cannot create folder on SD card",
                "SD card may be full or write-protected"
            )
        emitLog("Writing to SD Card/$folderName/")

        val totalBytes = entries.filter { !it.isDirectory }.sumOf { it.sizeBytes }
        var bytesWritten = 0L

        // Create directory structure first.
        val dirMap = HashMap<String, DocumentFile>()
        dirMap[""] = destFolder

        val dirs = entries.filter { it.isDirectory }
            .sortedBy { it.fullPath.count { c -> c == '/' } }

        for (dir in dirs) {
            coroutineContext.ensureActive()
            val parentPath = dir.fullPath.substringBeforeLast('/', "")
            val parent = dirMap[parentPath] ?: destFolder
            val created = parent.createDirectory(dir.name)
                ?: throw BurnException("Cannot create directory ${dir.name}", "SD card write failed")
            dirMap[dir.fullPath] = created
        }

        // Copy files.
        val raf = RandomAccessFile(isoFile, "r")
        raf.use { isoRaf ->
            for (entry in entries.filter { !it.isDirectory }) {
                coroutineContext.ensureActive()
                val parentPath = entry.fullPath.substringBeforeLast('/', "")
                val parent = dirMap[parentPath] ?: destFolder
                val mime = "application/octet-stream"
                val destFile = parent.createFile(mime, entry.name)
                    ?: throw BurnException(
                        "Cannot create file ${entry.name}",
                        "SD card may be full"
                    )

                emitLog(entry.fullPath)
                context.contentResolver.openOutputStream(destFile.uri)?.use { out ->
                    isoRaf.seek(entry.extentLba * IsoParser.SECTOR_SIZE)
                    val buf = ByteArray(4 * 1024 * 1024) // 4 MiB buffer
                    var remaining = entry.sizeBytes
                    while (remaining > 0) {
                        coroutineContext.ensureActive()
                        val toRead = minOf(buf.size.toLong(), remaining).toInt()
                        isoRaf.readFully(buf, 0, toRead)
                        out.write(buf, 0, toRead)
                        bytesWritten += toRead
                        remaining -= toRead
                        _state.value = BurnState.Copying(
                            currentFile = entry.fullPath,
                            bytesWritten = bytesWritten,
                            totalBytes = totalBytes,
                            speedMBps = 0f,
                            remainingSeconds = 0
                        )
                    }
                } ?: throw BurnException(
                    "Cannot write ${entry.name}",
                    "SD card write stream failed"
                )
            }
        }

        val durationSec = ((System.currentTimeMillis() - startMs) / 1000).toInt()
        _state.value = BurnState.Success(totalBytes, durationSec)
    }

    private fun emitLog(message: String) {
        _logLines.value = (_logLines.value + message).takeLast(500)
    }
}
