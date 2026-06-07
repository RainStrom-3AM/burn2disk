package com.burnto.disk.data.sdcard

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import com.burnto.disk.data.model.SdCardInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages SD card detection, SAF permission requests, and metadata queries.
 *
 * Physical SD cards (not USB OTG) are accessed through the Storage Access
 * Framework because direct block-level writes to removable storage require
 * root on Android 10+. The trade-off is file-level copy only — the result
 * is a folder of ISO contents, not a bootable raw image.
 */
@Singleton
class SdCardManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val PREFS_NAME = "sd_card_prefs"
        private const val KEY_SD_URI = "sd_card_uri"
        private const val REQUEST_CODE = 9001
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Returns a [SdCardInfo] for the physically inserted SD card, or null. */
    fun detectSdCard(): SdCardInfo? {
        // getExternalFilesDirs returns [internal, external1, external2, ...]
        val dirs = context.getExternalFilesDirs(null)
        for (i in 1 until dirs.size) {
            val dir = dirs.getOrNull(i) ?: continue
            if (!dir.exists() || !dir.canWrite()) continue
            val path = dir.absolutePath
            val rootPath = path.substringBefore("/Android/data")
            if (rootPath.isBlank()) continue
            val root = File(rootPath)
            if (!root.exists() || !root.canRead()) continue
            val stat = StatFs(rootPath)
            val total = stat.totalBytes
            val free = stat.availableBytes
            // Try to read filesystem type from mount info (best-effort).
            val fs = runCatching {
                java.io.BufferedReader(java.io.FileReader("/proc/mounts")).use { reader ->
                    reader.lineSequence().find { it.contains(rootPath) }
                        ?.split(" ")?.getOrNull(2)
                        ?: "FAT32"
                }
            }.getOrDefault("FAT32")
            return SdCardInfo(
                uri = Uri.fromFile(root),
                displayName = "SD Card",
                freeBytes = free,
                totalBytes = total,
                filesystem = fs.uppercase()
            )
        }
        return null
    }

    /** Launches the SAF document-tree picker so the user can grant write access. */
    fun requestAccessIntent(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        }

    /** Persists a granted SAF URI and calls [onPersisted] with the [SdCardInfo]. */
    fun persistUri(uri: Uri, onPersisted: (SdCardInfo) -> Unit = {}) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        prefs.edit().putString(KEY_SD_URI, uri.toString()).apply()
        // Build info from the tree document.
        val doc = DocumentFile.fromTreeUri(context, uri) ?: return
        val stat = runCatching {
            val statFs = StatFs(doc.uri.path ?: return)
            statFs.availableBytes to statFs.totalBytes
        }.getOrDefault(0L to 0L)
        val info = SdCardInfo(
            uri = uri,
            displayName = doc.name ?: "SD Card",
            freeBytes = stat.first,
            totalBytes = stat.second,
            filesystem = "exFAT"
        )
        onPersisted(info)
    }

    /** Loads a previously persisted SD card URI, returning null if none. */
    fun loadPersistedUri(): Uri? {
        val str = prefs.getString(KEY_SD_URI, null) ?: return null
        return Uri.parse(str)
    }

    /** Clears the persisted SD card URI (e.g. after user revokes access). */
    fun clearPersistedUri() {
        prefs.edit().remove(KEY_SD_URI).apply()
    }
}
