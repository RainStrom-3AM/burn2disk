package com.burnto.disk.data.model

/**
 * The lifecycle of a burn operation, emitted by [com.burnto.disk.data.usb.BurnEngine]
 * via a StateFlow and surfaced to the burn-progress screen.
 */
sealed class BurnState {
    data object Idle : BurnState()

    data class Formatting(val progress: Int) : BurnState()

    data class Copying(
        val currentFile: String,
        val bytesWritten: Long,
        val totalBytes: Long,
        val speedMBps: Float,
        val remainingSeconds: Int
    ) : BurnState() {
        val percent: Int
            get() = if (totalBytes > 0) ((bytesWritten * 100) / totalBytes).toInt() else 0
    }

    data class Verifying(val progress: Int) : BurnState()

    data class Success(val totalBytes: Long, val durationSeconds: Int) : BurnState()

    data class Failed(val error: String, val suggestion: String) : BurnState()
}

/** A single appended line in the live burn log. */
data class BurnLogLine(
    val message: String,
    val isFileName: Boolean = false
)
