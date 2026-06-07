package com.burnto.disk.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.burnto.disk.data.iso.ChecksumCalculator
import com.burnto.disk.data.iso.IsoDetector
import com.burnto.disk.data.iso.IsoParser
import com.burnto.disk.data.model.IsoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Resolves ISO sources (content URIs or local paths) into a usable local [File]
 * and produces analysed [IsoInfo] (OS, boot type, architecture, checksum).
 *
 * Content URIs from the system picker are copied into the app cache because the
 * burn engine needs a seekable [java.io.RandomAccessFile], which content URIs do
 * not provide directly.
 */
@Singleton
class IsoRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val detector: IsoDetector,
    private val checksumCalculator: ChecksumCalculator
) {
    private val isoDir: File
        get() = File(context.cacheDir, "isos").apply { mkdirs() }

    /** In-memory LRU cache keyed by absolute path; cap at 10 entries. */
    private val lruCache = object : LinkedHashMap<String, IsoInfo>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IsoInfo>?): Boolean {
            return size > 10
        }
    }

    data class PickedFile(val file: File, val displayName: String, val size: Long)

    /**
     * Copies a picked content [uri] into the cache and returns the local file.
     * Reports copy progress as 0-100.
     */
    suspend fun importFromUri(uri: Uri, onProgress: (Int) -> Unit = {}): PickedFile =
        withContext(Dispatchers.IO) {
            val (name, size) = queryNameAndSize(uri)
            val dest = File(isoDir, name)
            val total = size.coerceAtLeast(1)
            var copied = 0L

            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().buffered().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        onProgress(((copied * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            } ?: throw IllegalStateException("Cannot open selected file")

            PickedFile(dest, name, dest.length())
        }

    /**
     * Performs full analysis of a local ISO [file]: parses the filesystem,
     * detects OS/boot/arch, and computes SHA-256.
     */
    suspend fun analyze(
        file: File,
        onChecksumProgress: (Int) -> Unit = {}
    ): IsoInfo = withContext(Dispatchers.IO) {
        synchronized(lruCache) {
            lruCache[file.absolutePath]
        }?.let { return@withContext it }

        val detection = runCatching {
            IsoParser(file.absolutePath).use { parser ->
                parser.open()
                detector.detect(parser.listAllEntries(), parser.volumeLabel, parser.systemIdentifier, file.length())
            }
        }.getOrElse {
            // Fall back to filename-based heuristics if parsing fails (e.g. raw .img).
            detector.detectFromFileName(file)
        }

        val sha = checksumCalculator.sha256(file, onChecksumProgress)

        val info = IsoInfo(
            fileName = file.name,
            path = file.absolutePath,
            sizeBytes = file.length(),
            osType = detection.osType,
            bootType = detection.bootType,
            architecture = detection.architecture,
            sha256 = sha,
            hasLargeWim = detection.hasLargeWim
        )
        synchronized(lruCache) { lruCache[file.absolutePath] = info }
        info
    }

    private fun queryNameAndSize(uri: Uri): Pair<String, Long> {
        var name = "image.iso"
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
            }
        }
        return name to size
    }
}
