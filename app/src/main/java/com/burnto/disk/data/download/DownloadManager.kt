package com.burnto.disk.data.download

import android.content.Context
import com.burnto.disk.data.iso.ChecksumCalculator
import com.burnto.disk.data.model.DownloadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Streams an ISO download to the app cache with progress, HTTP Range resume, and
 * a post-download SHA-256. Emits a cold [Flow] of [DownloadState].
 *
 * Magnet links are explicitly rejected (no BitTorrent support).
 */
@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val checksumCalculator: ChecksumCalculator
) {
    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_INTERVAL_MS = 400L
    }

    private val isoDir: File
        get() = File(context.cacheDir, "isos").apply { mkdirs() }

    /** Returns the cache file an [url] would download to. */
    fun targetFileFor(url: String): File = File(isoDir, fileNameFromUrl(url))

    /**
     * Downloads [url] into the cache directory. Resumes a partial download via the
     * `Range` header when a `.part` file already exists.
     */
    fun download(url: String): Flow<DownloadState> = flow {
        emit(DownloadState.Idle)

        if (url.startsWith("magnet:", ignoreCase = true)) {
            emit(DownloadState.Failed("Magnet links are not supported. Use a direct HTTP(S) link."))
            return@flow
        }
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            emit(DownloadState.Failed("Enter a valid http(s) URL."))
            return@flow
        }

        val fileName = fileNameFromUrl(url)
        val finalFile = File(isoDir, fileName)
        val partFile = File(isoDir, "$fileName.part")

        val existingBytes = if (partFile.exists()) partFile.length() else 0L

        val requestBuilder = Request.Builder().url(url)
        if (existingBytes > 0) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) {
                    emit(DownloadState.Failed("Server returned HTTP ${response.code}"))
                    return@flow
                }
                val body = response.body
                    ?: run {
                        emit(DownloadState.Failed("Empty response body"))
                        return@flow
                    }

                // If the server ignored Range (200 not 206), restart from scratch.
                val resuming = response.code == 206 && existingBytes > 0
                val startOffset = if (resuming) existingBytes else 0L
                val contentLength = body.contentLength().let { if (it >= 0) it + startOffset else -1L }

                val append = resuming
                if (!resuming && partFile.exists()) partFile.delete()

                var received = startOffset
                var lastEmit = 0L
                var lastBytes = startOffset
                var lastTime = System.currentTimeMillis()

                body.byteStream().use { input ->
                    java.io.FileOutputStream(partFile, append).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            received += read

                            val now = System.currentTimeMillis()
                            if (now - lastEmit >= PROGRESS_INTERVAL_MS) {
                                val secs = (now - lastTime) / 1000f
                                val speed = if (secs > 0) (received - lastBytes) / secs else 0f
                                emit(
                                    DownloadState.Downloading(
                                        fileName = fileName,
                                        bytesReceived = received,
                                        totalBytes = contentLength,
                                        speedBytesPerSec = speed
                                    )
                                )
                                lastEmit = now
                                lastBytes = received
                                lastTime = now
                            }
                        }
                        output.flush()
                    }
                }
            }

            // Move .part to final and checksum it.
            if (finalFile.exists()) finalFile.delete()
            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
            }

            val sha = runCatching { checksumCalculator.sha256(finalFile) }.getOrNull()
            emit(DownloadState.Completed(finalFile.absolutePath, finalFile.length(), sha))
        } catch (e: IOException) {
            emit(DownloadState.Failed(e.message ?: "Network error. Check your connection and try again."))
        }
    }.flowOn(Dispatchers.IO)

    private fun fileNameFromUrl(url: String): String {
        val cleaned = url.substringBefore('?').substringBefore('#')
        val last = cleaned.substringAfterLast('/').ifBlank { "download.iso" }
        // Ensure a sane extension.
        return if (last.contains('.')) last else "$last.iso"
    }
}
