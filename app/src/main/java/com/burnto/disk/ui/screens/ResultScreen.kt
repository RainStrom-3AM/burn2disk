package com.burnto.disk.ui.screens

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.burnto.disk.data.model.BurnState
import com.burnto.disk.ui.Format
import com.burnto.disk.ui.theme.Amber
import com.burnto.disk.ui.theme.DangerRed
import com.burnto.disk.ui.theme.MonoText
import com.burnto.disk.ui.theme.NearBlack
import com.burnto.disk.ui.theme.SuccessGreen
import com.burnto.disk.ui.theme.TextSecondary
import com.burnto.disk.viewmodel.BurnViewModel

/**
 * Screen 6 — Result. Success shows stats + Done/Verify; failure shows a plain
 * English error with a suggested fix and Retry/Report actions.
 */
@Composable
fun ResultScreen(
    onDone: () -> Unit,
    onRetry: () -> Unit,
    viewModel: BurnViewModel = hiltViewModel()
) {
    val state by viewModel.burnState.collectAsStateWithLifecycle()
    val logLines by viewModel.logLines.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "iconScale"
    )

    Scaffold(containerColor = NearBlack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (val s = state) {
                is BurnState.Success -> SuccessContent(s, scale, onDone, onVerify = { /* verify */ })
                is BurnState.Failed -> FailureContent(
                    error = s.error,
                    suggestion = s.suggestion,
                    scale = scale,
                    onRetry = onRetry,
                    onReport = {
                        val report = buildString {
                            appendLine("Burn2Disk error report")
                            appendLine("Error: ${s.error}")
                            appendLine("Suggestion: ${s.suggestion}")
                            appendLine("--- Log ---")
                            logLines.forEach { appendLine(it.message) }
                        }
                        clipboard.setText(AnnotatedString(report))
                    }
                )
                else -> {
                    Text("Finishing...", color = TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun SuccessContent(
    state: BurnState.Success,
    scale: Float,
    onDone: () -> Unit,
    onVerify: () -> Unit
) {
    val avgSpeed = if (state.durationSeconds > 0) {
        (state.totalBytes.toFloat() / state.durationSeconds) / (1024 * 1024)
    } else 0f

    Icon(
        imageVector = Icons.Filled.Check,
        contentDescription = "Success",
        tint = SuccessGreen,
        modifier = Modifier
            .size(120.dp)
            .scale(scale)
    )
    Spacer(Modifier.height(24.dp))
    Text("Burn complete", style = MaterialTheme.typography.headlineLarge, color = Color.White, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Text("Your USB drive is ready to boot", style = MaterialTheme.typography.bodyLarge, color = TextSecondary)

    Spacer(Modifier.height(32.dp))

    Text(
        text = "${Format.bytes(state.totalBytes)} · ${Format.duration(state.durationSeconds)} · ${Format.speedMBps(avgSpeed)} avg",
        style = MonoText.medium,
        color = Amber
    )

    Spacer(Modifier.height(40.dp))

    Button(
        onClick = onDone,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = NearBlack)
    ) { Text("DONE", fontWeight = FontWeight.Bold, fontSize = 16.sp) }

    Spacer(Modifier.height(12.dp))

    OutlinedButton(
        onClick = onVerify,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, Amber)
    ) { Text("VERIFY", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
}

@Composable
private fun FailureContent(
    error: String,
    suggestion: String,
    scale: Float,
    onRetry: () -> Unit,
    onReport: () -> Unit
) {
    Icon(
        imageVector = Icons.Filled.Close,
        contentDescription = "Failed",
        tint = DangerRed,
        modifier = Modifier
            .size(120.dp)
            .scale(scale)
    )
    Spacer(Modifier.height(24.dp))
    Text("Burn failed", style = MaterialTheme.typography.headlineLarge, color = Color.White, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
    Text(error, style = MaterialTheme.typography.bodyLarge, color = Color.White)
    Spacer(Modifier.height(8.dp))
    Text(suggestion, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)

    Spacer(Modifier.height(40.dp))

    Button(
        onClick = onRetry,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = NearBlack)
    ) { Text("RETRY", fontWeight = FontWeight.Bold, fontSize = 16.sp) }

    Spacer(Modifier.height(12.dp))

    OutlinedButton(
        onClick = onReport,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
        border = androidx.compose.foundation.BorderStroke(1.dp, TextSecondary)
    ) { Text("REPORT", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
}
