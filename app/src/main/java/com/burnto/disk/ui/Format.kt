package com.burnto.disk.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Human-readable formatting helpers shared across screens. */
object Format {

    fun bytes(bytes: Long): String {
        if (bytes < 0) return "—"
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }

    fun speed(bytesPerSec: Float): String {
        val mbps = bytesPerSec / (1024 * 1024)
        return String.format(Locale.US, "%.1f MB/s", mbps)
    }

    fun speedMBps(mbps: Float): String = String.format(Locale.US, "%.1f MB/s", mbps)

    fun duration(seconds: Int): String {
        if (seconds <= 0) return "0s"
        val m = seconds / 60
        val s = seconds % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }

    /** "About 3 min left" style ETA. */
    fun eta(seconds: Int): String {
        if (seconds <= 0) return "Almost done"
        return when {
            seconds < 60 -> "About ${seconds}s left"
            seconds < 3600 -> "About ${seconds / 60} min left"
            else -> "About ${seconds / 3600} hr left"
        }
    }

    fun date(epochMs: Long): String =
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(epochMs))
}
