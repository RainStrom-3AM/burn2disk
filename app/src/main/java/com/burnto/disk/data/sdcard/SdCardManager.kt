package com.burnto.disk.data.sdcard

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
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

    data class SdCardRoot(
        val path: File,
        val label: String,
        val totalBytes: Long,
        val freeBytes: Long
    )

    companion object {
        private const val PREFS_NAME = "sd_card_prefs"
        private const val KEY_SD_URI = "sd_card_uri"
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Detects a physically inserted SD card.
     *
     * On API 24+ uses [StorageManager] which properly identifies removable
     * storage regardless of filesystem (FAT16, FAT32, exFAT, NTFS, ext4).
     * On older APIs falls back to [getExternalFilesDirs].
     *
     * The returned [SdCardInfo] has [uri] = [Uri.EMPTY] because the real
     * write URI always comes from the SAF picker and is persisted separately.
     */
    fun detectSdCard(): SdCardInfo? {
        // ---- API 24+: StorageManager gives proper removable detection ----
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            if (storageManager != null) {
                try {
                    for (volume in storageManager.storageVolumes) {
                        if (volume.isRemovable && !volume.isEmulated) {
                            val desc = volume.getDescription(context) ?: "SD Card"
                            val (total, free) = readVolumeStats(volume, storageManager)
                            return SdCardInfo(
                                uri = Uri.EMPTY,
                                displayName = desc,
                                freeBytes = free,
                                totalBytes = total,
                                filesystem = ""
                            )
                        }
                    }
                } catch (e: Exception) {
                    // StorageManager can throw on some devices — fall through.
                }
            }
        }

        // ---- Fallback 1: getExternalFilesDirs (API < 24, or StorageManager failed) ----
        val dirs = context.getExternalFilesDirs(null)
        for (i in 1 until dirs.size) {
            val dir = dirs.getOrNull(i) ?: continue
            if (!dir.exists()) continue
            val path = dir.absolutePath
            val rootPath = path.substringBefore("/Android/data")
            if (rootPath.isBlank()) continue
            val root = File(rootPath)
            if (!root.exists()) continue
            val stat = runCatching { StatFs(rootPath) }.getOrNull()
            return SdCardInfo(
                uri = Uri.EMPTY,
                displayName = "SD Card",
                freeBytes = stat?.availableBytes ?: 0L,
                totalBytes = stat?.totalBytes ?: 0L,
                filesystem = ""
            )
        }

        // ---- Fallback 2: check Environment.getExternalStorageState for removable ----
        if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            val extDir = Environment.getExternalStorageDirectory()
            if (extDir != null && extDir.exists()) {
                val stat = runCatching { StatFs(extDir.absolutePath) }.getOrNull()
                if (stat != null) {
                    return SdCardInfo(
                        uri = Uri.EMPTY,
                        displayName = "External Storage",
                        freeBytes = stat.availableBytes,
                        totalBytes = stat.totalBytes,
                        filesystem = ""
                    )
                }
            }
        }

        return null
    }

    /** Returns directly readable removable-storage roots for the smart browser. */
    fun getAvailableSdCardRoots(): List<SdCardRoot> {
        val roots = LinkedHashMap<String, SdCardRoot>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            storageManager?.storageVolumes?.forEach { volume ->
                if (!volume.isRemovable || volume.isEmulated) return@forEach
                val path = getVolumePath(volume) ?: return@forEach
                if (!path.exists() || !path.isDirectory) return@forEach
                roots[path.absolutePath] = SdCardRoot(
                    path = path,
                    label = volume.getDescription(context) ?: "SD Card",
                    totalBytes = path.totalSpace,
                    freeBytes = path.freeSpace
                )
            }
        }

        context.getExternalFilesDirs(null).drop(1).forEach { dir ->
            val rootPath = dir?.absolutePath?.substringBefore("/Android/data") ?: return@forEach
            if (rootPath.isBlank()) return@forEach
            val root = File(rootPath)
            if (!root.exists() || !root.isDirectory) return@forEach
            roots.putIfAbsent(
                root.absolutePath,
                SdCardRoot(
                    path = root,
                    label = "SD Card",
                    totalBytes = root.totalSpace,
                    freeBytes = root.freeSpace
                )
            )
        }

        return roots.values.toList()
    }

    fun hasDirectBrowseTarget(): Boolean = getAvailableSdCardRoots().isNotEmpty()

    private fun getVolumePath(volume: StorageVolume): File? {
        return try {
            val method = StorageVolume::class.java.getMethod("getPath")
            val path = method.invoke(volume) as? String ?: return null
            File(path)
        } catch (e: Exception) {
            null
        }
    }

    private fun readVolumeStats(volume: StorageVolume, storageManager: StorageManager): Pair<Long, Long> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                // StorageStatsManager for accurate per-volume stats.
                val statsManager = context.getSystemService(Context.STORAGE_STATS_SERVICE)
                    as? android.app.usage.StorageStatsManager
                if (statsManager != null) {
                    val uuid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        volume.storageUuid
                    } else {
                        val getUuid = StorageVolume::class.java.getMethod("getUuid")
                        getUuid.invoke(volume) as? java.util.UUID
                    }
                    if (uuid != null) {
                        val total = statsManager.getTotalBytes(uuid)
                        val free = statsManager.getFreeBytes(uuid)
                        return total to free
                    }
                }
            } catch (e: Exception) { /* fall through */ }
            0L to 0L
        } else {
            // Pre-O: try to get path via reflection and use StatFs.
            val path = try {
                val getPath = StorageVolume::class.java.getMethod("getPath")
                getPath.invoke(volume) as? String
            } catch (e: Exception) { null }
            if (path != null) {
                val stat = runCatching { StatFs(path) }.getOrNull()
                (stat?.totalBytes ?: 0L) to (stat?.availableBytes ?: 0L)
            } else {
                0L to 0L
            }
        }
    }

    /** Launches the SAF document-tree picker so the user can grant write access. */
    fun requestAccessIntent(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        }

    /**
     * Persists a granted SAF URI and returns an [SdCardInfo] backed by that URI.
     * The display name and capacity come from the tree document itself.
     */
    fun persistUri(uri: Uri): SdCardInfo {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        prefs.edit().putString(KEY_SD_URI, uri.toString()).apply()

        val doc = DocumentFile.fromTreeUri(context, uri)
        val displayName = doc?.name ?: "SD Card"
        val (free, total) = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && doc?.uri != null) {
                val path = doc.uri.path
                if (path != null) {
                    val stat = StatFs(path)
                    stat.availableBytes to stat.totalBytes
                } else 0L to 0L
            } else 0L to 0L
        }.getOrDefault(0L to 0L)

        return SdCardInfo(
            uri = uri,
            displayName = displayName,
            freeBytes = free,
            totalBytes = total,
            filesystem = ""
        )
    }

    /** Loads a previously persisted SD card URI, returning null if none. */
    fun loadPersistedUri(): Uri? {
        val str = prefs.getString(KEY_SD_URI, null) ?: return null
        return runCatching { Uri.parse(str) }.getOrNull()
    }

    /** Clears the persisted SD card URI (e.g. after user revokes access). */
    fun clearPersistedUri() {
        prefs.edit().remove(KEY_SD_URI).apply()
    }
}
