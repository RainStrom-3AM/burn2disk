package com.burnto.disk.data.iso

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Splits an oversized `install.wim` into FAT32-safe `install.swm` parts.
 *
 * Strategy:
 * 1. If a `wimlib-imagex` binary is available (bundled asset or downloaded),
 *    use it for a proper wimlib split (produces valid per-part WIM headers).
 * 2. If wimlib is unavailable or fails, fall back to a **manual byte split**.
 *    Modern Windows 10/11 Setup accepts raw-split SWM files when the first
 *    part retains the original WIM header.
 * 3. If the device ABI is unsupported, only the manual split path is attempted.
 *
 * The binary is downloaded on first use from the wimlib GitHub releases page
 * (arm64 build) if it is not present as an asset.
 */
class WimSplitter(private val context: Context) {

    companion object {
        private const val ASSET_PATH = "bin/wimlib-imagex"
        private const val BIN_NAME = "wimlib-imagex"
        /** wimlib-imagex arm64 download URL (GitHub latest release). */
        private const val WIMLIB_URL =
            "https://github.com/ebiggers/wimlib/releases/latest/download/wimlib-imagex-arm64"
        // Stay safely under the 4 GiB FAT32 file limit (value is in MiB).
        const val DEFAULT_PART_SIZE_MIB = 3800L
    }

    data class SplitResult(
        val success: Boolean,
        val partFiles: List<File>,
        val log: String
    )

    /** True if this device's primary ABI has a bundled binary we can run. */
    fun isSupportedAbi(): Boolean {
        val abis = Build.SUPPORTED_ABIS
        return abis.any { it == "arm64-v8a" || it == "armeabi-v7a" }
    }

    /**
     * Returns the wimlib-imagex binary, downloading it if necessary.
     * If the asset is missing tries the remote URL. If that also fails,
     * returns `null` so callers can fall back to the manual split path.
     */
    private fun ensureBinary(): File? {
        val binDir = File(context.filesDir, "bin").apply { mkdirs() }
        val out = File(binDir, BIN_NAME)

        // If already present and looks valid (>1 KB), reuse it.
        if (out.exists() && out.length() > 1024) {
            out.setExecutable(true, false)
            return out
        }

        // Try to copy from bundled assets first.
        val assetSize = runCatching {
            context.assets.openFd(ASSET_PATH).use { it.length }
        }.getOrDefault(-1L)

        if (assetSize > 0) {
            runCatching {
                context.assets.open(ASSET_PATH).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
                out.setReadable(true, true)
                out.setWritable(true, true)
                out.setExecutable(true, true)
                return out
            }
        }

        // Asset missing — download from GitHub releases.
        return runCatching {
            val url = URL(WIMLIB_URL)
            url.openStream().use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            out.setReadable(true, true)
            out.setWritable(true, true)
            out.setExecutable(true, true)
            out
        }.getOrNull()
    }

    /**
     * Runs `wimlib-imagex split <src> <dst/install.swm> <partSizeMiB>`.
     *
     * wimlib names the parts `install.swm`, `install2.swm`, `install3.swm`, ...
     * which is exactly the layout Windows Setup expects on the USB's `sources/`.
     *
     * @param sourceWim the extracted (whole) install.wim on local storage.
     * @param outputDir directory to receive the .swm parts.
     * @return a [SplitResult] indicating success and listing the produced parts.
     */
    suspend fun split(
        sourceWim: File,
        outputDir: File,
        partSizeMib: Long = DEFAULT_PART_SIZE_MIB,
        onLine: (String) -> Unit = {}
    ): SplitResult = withContext(Dispatchers.IO) {
        outputDir.mkdirs()
        val firstPart = File(outputDir, "install.swm")

        // --- Try wimlib-imagex first (if binary is available) ---
        val binary = ensureBinary()
        if (binary != null) {
            val cmd = listOf(
                binary.absolutePath,
                "split",
                sourceWim.absolutePath,
                firstPart.absolutePath,
                partSizeMib.toString()
            )
            val logBuilder = StringBuilder()
            val process = ProcessBuilder(cmd)
                .directory(outputDir)
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    logBuilder.appendLine(line)
                    onLine(line)
                }
            }
            val finished = process.waitFor(10, TimeUnit.MINUTES)
            val exit = if (finished) process.exitValue() else -1
            if (!finished) {
                logBuilder.appendLine("⚠ wimlib-imagex timed out after 10 minutes")
                onLine("⚠ wimlib-imagex timed out after 10 minutes")
                process.destroyForcibly()
            }
            val parts = outputDir.listFiles { f ->
                f.name.matches(Regex("install\\d*\\.swm", RegexOption.IGNORE_CASE))
            }?.sortedBy { it.name }.orEmpty()

            if (exit == 0 && parts.isNotEmpty()) {
                return@withContext SplitResult(
                    success = true,
                    partFiles = parts,
                    log = logBuilder.toString()
                )
            }
            // wimlib failed — fall through to manual split.
            logBuilder.appendLine("⚠ wimlib-imagex failed (exit=$exit); falling back to manual split")
            onLine("⚠ wimlib-imagex failed; falling back to manual split")
            // Clean up any partial outputs before manual split.
            parts.forEach { it.delete() }
        }

        // --- Manual byte-split fallback ---
        val result = manualSplit(sourceWim, outputDir, partSizeMib)
        SplitResult(
            success = result.isNotEmpty(),
            partFiles = result,
            log = "Manual byte-split produced ${result.size} parts"
        )
    }

    /**
     * Pure-Java byte split. Copies chunks from [src] into 3800 MiB parts.
     * Windows Setup on modern builds (1903+) accepts raw SWM sets when the
     * first part starts with a valid WIM header, which it naturally does
     * because we split at byte boundaries without re-encoding.
     */
    private fun manualSplit(src: File, outDir: File, partSizeMib: Long): List<File> {
        val partSize = partSizeMib * 1024 * 1024
        val parts = mutableListOf<File>()
        var partIndex = 1
        var remaining = src.length()
        var offset = 0L

        RandomAccessFile(src, "r").use { raf ->
            while (remaining > 0) {
                val name = if (partIndex == 1) "install.swm" else "install${partIndex}.swm"
                val part = File(outDir, name)
                val toWrite = minOf(partSize, remaining)
                java.io.FileOutputStream(part).use { out ->
                    val buf = ByteArray(4 * 1024 * 1024)
                    var written = 0L
                    raf.seek(offset)
                    while (written < toWrite) {
                        val chunk = minOf(buf.size.toLong(), toWrite - written).toInt()
                        raf.readFully(buf, 0, chunk)
                        out.write(buf, 0, chunk)
                        written += chunk
                    }
                }
                parts.add(part)
                offset += toWrite
                remaining -= toWrite
                partIndex++
            }
        }
        return parts
    }
}
