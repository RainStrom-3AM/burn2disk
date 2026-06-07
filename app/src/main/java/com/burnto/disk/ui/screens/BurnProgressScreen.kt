package com.burnto.disk.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.burnto.disk.data.model.BurnState
import com.burnto.disk.ui.Format
import com.burnto.disk.ui.components.BurnLog
import com.burnto.disk.ui.components.CircularBurnProgress
import com.burnto.disk.ui.theme.Amber
import com.burnto.disk.ui.theme.DangerRed
import com.burnto.disk.ui.theme.MonoText
import com.burnto.disk.ui.theme.NearBlack
import com.burnto.disk.ui.theme.TextSecondary
import com.burnto.disk.viewmodel.BurnViewModel

/**
 * Screen 5 — Burn progress. Full-screen circular progress, current-operation
 * label, throughput/ETA stats, collapsible auto-scrolling log, and a guarded
 * cancel button. The screen is kept awake by the host activity.
 */
@Composable
fun BurnProgressScreen(
    onComplete: () -> Unit,
    viewModel: BurnViewModel = hiltViewModel()
) {
    val state by viewModel.burnState.collectAsStateWithLifecycle()
    val iso by viewModel.iso.collectAsStateWithLifecycle()
    val target by viewModel.selectedTarget.collectAsStateWithLifecycle()
    val logLines by viewModel.logLines.collectAsStateWithLifecycle()

    var logExpanded by remember { mutableStateOf(false) }
    var showCancelDialog by remember { mutableStateOf(false) }
    // True once the copy phase has finished and we're in the flush/unmount window.
    var finishing by remember { mutableStateOf(false) }

    // Start the burn once when entering the screen.
    LaunchedEffect(Unit) { viewModel.startBurn() }

    // Navigate to the result screen on terminal states. On success, briefly hold
    // so the ring can turn green and show the checkmark before navigating.
    LaunchedEffect(state) {
        when (state) {
            is BurnState.Success -> {
                kotlinx.coroutines.delay(1000)
                onComplete()
            }
            is BurnState.Failed -> onComplete()
            else -> Unit
        }
    }

    // Safety net: once copying reaches 100% (or we otherwise enter the flush
    // window), force-navigate to the result screen if Success/Failed has not
    // arrived within 10 seconds. This guarantees the user is never stranded on
    // a "Finishing..." screen if the unmount stalls.
    val copyDone = (state as? BurnState.Copying)?.let {
        it.totalBytes > 0L && it.bytesWritten >= it.totalBytes
    } ?: false
    LaunchedEffect(copyDone) {
        if (copyDone) {
            finishing = true
            kotlinx.coroutines.delay(10_000)
            if (state !is BurnState.Success && state !is BurnState.Failed) {
                onComplete()
            }
        }
    }

    // Determine whether we are in the brief "parsing" sub-phase (a Copying state
    // emitted before any bytes are written) versus actively writing files.
    val isParsing = (state as? BurnState.Copying)?.let {
        it.currentFile == "Parsing ISO..." || it.totalBytes == 0L || it.bytesWritten == 0L
    } ?: false

    val (percent, label, showStats) = when (val s = state) {
        is BurnState.Idle -> Triple(0, "Preparing...", false)
        is BurnState.Formatting -> Triple(s.progress, "Formatting FAT32...", false)
        is BurnState.Copying ->
            when {
                copyDone || finishing -> Triple(100, "Finishing...", false)
                isParsing -> Triple(0, "Parsing ISO filesystem...", false)
                else -> Triple(
                    s.percent,
                    "Writing files... ${Format.bytes(s.bytesWritten)} of ${Format.bytes(s.totalBytes)}",
                    true
                )
            }
        is BurnState.Verifying -> Triple(s.progress, "Verifying...", false)
        is BurnState.Success -> Triple(100, "Complete", false)
        is BurnState.Failed -> Triple(0, "Failed", false)
    }
    // Indeterminate only during the very first preparing / parsing moments.
    val isIndeterminate = state is BurnState.Idle || isParsing
    // The ring shows green + checkmark on success, or while finishing (copy is
    // already 100% complete and we are only waiting on the unmount to settle).
    val showSuccessRing = state is BurnState.Success || (finishing && state !is BurnState.Failed)

    // Root-cause-1 visibility: if the engine logged a fallback to the slow path,
    // surface a persistent warning banner so the slowdown is never silent.
    val compatibilityMode = logLines.any { it.message.contains("compatibility mode") }

    Scaffold(containerColor = NearBlack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))

            // Compatibility-mode warning banner (visible whenever the fast writer
            // fell back to the slow libaums path).
            if (compatibilityMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .background(
                            com.burnto.disk.ui.theme.WarningYellow.copy(alpha = 0.15f),
                            RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⚠ Using compatibility mode — burn will be slower",
                        style = MaterialTheme.typography.bodyMedium,
                        color = com.burnto.disk.ui.theme.WarningYellow
                    )
                }
            }

            // ISO -> device header with arrow.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = iso?.fileName?.take(18) ?: "ISO",
                    style = MonoText.small,
                    color = Amber,
                    maxLines = 1
                )
                Spacer(Modifier.size(8.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = TextSecondary)
                Spacer(Modifier.size(8.dp))
                Text(
                    text = target?.displayName?.take(18) ?: "USB",
                    style = MonoText.small,
                    color = Color.White,
                    maxLines = 1
                )
            }

            Spacer(Modifier.height(40.dp))

            CircularBurnProgress(
                percent = percent,
                isSuccess = showSuccessRing,
                isIndeterminate = isIndeterminate
            )

            Spacer(Modifier.height(32.dp))

            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Medium
            )

            Spacer(Modifier.height(12.dp))

            // Speed / ETA row — only while actively writing files.
            if (showStats && state is BurnState.Copying) {
                val s = state as BurnState.Copying
                Text(
                    text = "${Format.speedMBps(s.speedMBps)}  ·  ${Format.etaShort(s.remainingSeconds)}",
                    style = MonoText.medium,
                    color = TextSecondary
                )
            }

            Spacer(Modifier.height(24.dp))

            BurnLog(
                lines = logLines,
                expanded = logExpanded,
                onToggle = { logExpanded = !logExpanded }
            )

            Spacer(Modifier.weight(1f))

            OutlinedButton(
                onClick = { showCancelDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(bottom = 0.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, DangerRed)
            ) {
                Text("CANCEL", fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            containerColor = com.burnto.disk.ui.theme.SurfaceDark,
            title = { Text("Cancel burn?", color = Color.White) },
            text = {
                Text(
                    if (target is com.burnto.disk.data.model.BurnTarget.SdCard)
                        "Cancel copy? Partial files will remain on the SD card."
                    else
                        "Cancel burn? USB may be left in an unusable state.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showCancelDialog = false
                    viewModel.cancelBurn()
                }) {
                    Text("CANCEL BURN", color = DangerRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) {
                    Text("CONTINUE", color = Amber)
                }
            }
        )
    }
}
