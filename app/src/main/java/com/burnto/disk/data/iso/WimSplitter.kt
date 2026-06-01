package com.burnto.disk.data.iso

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Splits an oversized `install.wim` into FAT32-safe `install.swm` parts using a
 * bundled, precompiled `wimlib-imagex` ARM64 binary.
 *
 * The binary lives at `assets/bin/wimlib-imagex`. On first use it is copied to
 * `filesDir/bin/`, marked executable, and invoked through [ProcessBuilder].
 *
 * Per the agreed strategy, this is the reliable path for producing `.swm` sets
 * that Windows Setup accepts (a `.swm` set is NOT a byte-split of the source).
 */
class WimSplitter(private val context: Context) {

    companion object {
        private const val ASSET_PATH = "bin/wimlib-imagex"
        private const val BIN_NAME = "wimlib-imagex"
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
     * Extracts and prepares the executable, returning its [File] handle.
     * Idempotent: re-copies only if missing or size-mismatched.
     */
    private fun ensureBinary(): File {
        val binDir = File(context.filesDir, "bin").apply { mkdirs() }
        val out = File(binDir, BIN_NAME)

        val assetSize = runCatching {
            context.assets.openFd(ASSET_PATH).use { it.length }
        }.getOrDefault(-1L)

        if (!out.exists() || (assetSize > 0 && out.length() != assetSize)) {
            context.assets.open(ASSET_PATH).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        }
        // chmod 700 — owner rwx only.
        out.setReadable(true, true)
        out.setWritable(true, true)
        out.setExecutable(true, true)
        return out
    }

    /**
     * Runs `wimlib-imagex split <src> <dst/install.swm> <partSizeMiB>`.
     *
     * wimlib names the parts `install.swm`, `install2.swm`, `install3.swm`, ...
     * which is exactly the layout Windows Setup expects on the USB's `sources/`.
     *
     * @param sourceWim the extracted (whole) install.wim on local storage.
     * @param outputDir directory to receive the .swm parts.
     */
    suspend fun split(
        sourceWim: File,
        outputDir: File,
        partSizeMib: Long = DEFAULT_PART_SIZE_MIB,
        onLine: (String) -> Unit = {}
    ): SplitResult = withContext(Dispatchers.IO) {
        if (!isSupportedAbi()) {
            return@withContext SplitResult(
                success = false,
                partFiles = emptyList(),
                log = "No bundled wimlib-imagex for ABIs: ${Build.SUPPORTED_ABIS.joinToString()}"
            )
        }

        val binary = ensureBinary()
        outputDir.mkdirs()
        val firstPart = File(outputDir, "install.swm")

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
        val exit = process.waitFor()

        val parts = outputDir.listFiles { f ->
            f.name.matches(Regex("install\\d*\\.swm", RegexOption.IGNORE_CASE))
        }?.sortedBy { it.name }.orEmpty()

        SplitResult(
            success = exit == 0 && parts.isNotEmpty(),
            partFiles = parts,
            log = logBuilder.toString()
        )
    }
}
