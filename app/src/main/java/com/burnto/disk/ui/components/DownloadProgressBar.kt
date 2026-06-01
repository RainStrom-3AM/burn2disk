package com.burnto.disk.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.burnto.disk.data.model.DownloadState
import com.burnto.disk.ui.Format
import com.burnto.disk.ui.theme.Amber
import com.burnto.disk.ui.theme.MonoText
import com.burnto.disk.ui.theme.OutlineDark
import com.burnto.disk.ui.theme.TextSecondary

/**
 * Inline download progress, e.g.
 * "Downloading ubuntu-24.04.iso — 45% · 2.1 GB of 4.7 GB · 12.4 MB/s".
 */
@Composable
fun DownloadProgressBar(
    state: DownloadState.Downloading,
    modifier: Modifier = Modifier
) {
    val animated by animateFloatAsState(
        targetValue = state.percent / 100f,
        animationSpec = tween(300),
        label = "dl"
    )
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Downloading ${state.fileName}",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = androidx.compose.ui.graphics.Color.White
        )
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { animated },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
            color = Amber,
            trackColor = OutlineDark
        )
        Spacer(Modifier.height(8.dp))
        val totalText = if (state.totalBytes > 0) Format.bytes(state.totalBytes) else "?"
        Text(
            text = "${state.percent}% · ${Format.bytes(state.bytesReceived)} of $totalText · ${Format.speed(state.speedBytesPerSec)}",
            style = MonoText.small,
            color = TextSecondary
        )
    }
}
