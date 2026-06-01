package com.burnto.disk.data.iso

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Computes SHA-256 (and helper SHA-1) digests on device with progress reporting.
 *
 * Hashing multi-GB ISOs is slow, so callers pass an [onProgress] callback that
 * receives a 0-100 percentage roughly every 8 MiB. The work runs on
 * [Dispatchers.Default] and honours coroutine cancellation.
 */
class ChecksumCalculator {

    companion object {
        private const val BUFFER_SIZE = 1 shl 16 // 64 KiB
        private const val PROGRESS_INTERVAL_BYTES = 8L shl 20 // 8 MiB
    }

    /** Computes the lowercase hex SHA-256 of [file]. */
    suspend fun sha256(file: File, onProgress: (Int) -> Unit = {}): String =
        digest(file, "SHA-256", onProgress)

    /** Computes the lowercase hex SHA-1 of [file]. */
    suspend fun sha1(file: File, onProgress: (Int) -> Unit = {}): String =
        digest(file, "SHA-1", onProgress)

    private suspend fun digest(
        file: File,
        algorithm: String,
        onProgress: (Int) -> Unit
    ): String = withContext(Dispatchers.Default) {
        val md = MessageDigest.getInstance(algorithm)
        val total = file.length().coerceAtLeast(1)
        var processed = 0L
        var lastReported = 0L

        file.inputStream().buffered(BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                md.update(buffer, 0, read)
                processed += read
                if (processed - lastReported >= PROGRESS_INTERVAL_BYTES) {
                    lastReported = processed
                    onProgress(((processed * 100) / total).toInt().coerceIn(0, 100))
                }
            }
        }
        onProgress(100)
        toHex(md.digest())
    }

    /** Digests an arbitrary stream of known length (used for written-data verification). */
    suspend fun sha256Stream(
        input: InputStream,
        totalBytes: Long,
        onProgress: (Int) -> Unit = {}
    ): String = withContext(Dispatchers.Default) {
        val md = MessageDigest.getInstance("SHA-256")
        val total = totalBytes.coerceAtLeast(1)
        var processed = 0L
        var lastReported = 0L
        val buffer = ByteArray(BUFFER_SIZE)
        input.use {
            while (true) {
                coroutineContext.ensureActive()
                val read = it.read(buffer)
                if (read < 0) break
                md.update(buffer, 0, read)
                processed += read
                if (processed - lastReported >= PROGRESS_INTERVAL_BYTES) {
                    lastReported = processed
                    onProgress(((processed * 100) / total).toInt().coerceIn(0, 100))
                }
            }
        }
        onProgress(100)
        toHex(md.digest())
    }

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
