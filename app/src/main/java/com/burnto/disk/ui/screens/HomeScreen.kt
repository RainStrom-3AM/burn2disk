package com.burnto.disk.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.burnto.disk.ui.components.CircularBurnProgress
import com.burnto.disk.ui.components.SandstormUsb
import com.burnto.disk.ui.theme.Amber
import com.burnto.disk.ui.theme.NearBlack
import com.burnto.disk.ui.theme.SuccessGreen
import com.burnto.disk.ui.theme.TextSecondary
import com.burnto.disk.viewmodel.FormatUiState
import com.burnto.disk.viewmodel.HomeViewModel

/**
 * Screen 1 — Home. Branded title, animated sandstorm USB hero, the two primary
 * actions (select / download), a one-tap Format Disk recovery action, and a
 * Browse USB action to inspect the drive's contents after a burn.
 */
@Composable
fun HomeScreen(
    onSelectIso: () -> Unit,
    onDownloadIso: () -> Unit,
    onFormatDisk: () -> Unit,
    onBrowseUsb: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val usbConnected by viewModel.usbConnected.collectAsStateWithLifecycle()
    val formatState by viewModel.formatState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    // Re-scan whenever the screen is (re)composed into view.
    LaunchedEffect(Unit) { viewModel.refreshUsb() }

    // React to terminal format outcomes with a snackbar, then reset.
    LaunchedEffect(formatState) {
        when (formatState) {
            is FormatUiState.NoUsb -> {
                snackbarHostState.showSnackbar("Connect a USB drive via OTG first")
                viewModel.resetFormatState()
            }
            is FormatUiState.Success -> {
                snackbarHostState.showSnackbar("USB formatted and ready", duration = SnackbarDuration.Short)
                viewModel.resetFormatState()
            }
            is FormatUiState.Error -> {
                val msg = (formatState as FormatUiState.Error).message
                snackbarHostState.showSnackbar("Format failed: $msg")
                viewModel.resetFormatState()
            }
            else -> Unit
        }
    }

    Scaffold(
        containerColor = NearBlack,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))

            Text(
                text = "Burn2Disk",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp,
                color = Amber
            )
            Text(
                text = "ISO Burner",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )

            Spacer(Modifier.height(40.dp))

            SandstormUsb()

            Spacer(Modifier.height(40.dp))

            // Primary actions.
            Button(
                onClick = onSelectIso,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = NearBlack)
            ) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null)
                Spacer(Modifier.size(12.dp))
                Text("SELECT ISO", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onDownloadIso,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                border = BorderStroke(1.5.dp, Amber)
            ) {
                Icon(Icons.Filled.Download, contentDescription = null)
                Spacer(Modifier.size(12.dp))
                Text("DOWNLOAD ISO", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }

            Spacer(Modifier.height(16.dp))

            // Recovery action — one tap, no confirmation.
            OutlinedButton(
                onClick = onFormatDisk,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                border = BorderStroke(1.dp, Amber)
            ) {
                Icon(Icons.Outlined.Storage, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("FORMAT DISK", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }

            Spacer(Modifier.height(12.dp))

            // Browse the connected USB to verify burned contents.
            OutlinedButton(
                onClick = onBrowseUsb,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                border = BorderStroke(1.dp, Amber)
            ) {
                Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("BROWSE USB", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }

            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (usbConnected) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(SuccessGreen)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = "USB detected — ready to burn",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SuccessGreen
                    )
                } else {
                    Text(
                        text = "Connect a USB drive via OTG to begin",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }
        }
    }

    // Full-screen formatting overlay (no cancel — format is fast).
    val fmt = formatState
    if (fmt is FormatUiState.Formatting) {
        FormattingOverlay(progress = fmt.progress)
    }
}

@Composable
private fun FormattingOverlay(progress: Int) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF20D0D0D)) // ~95% opaque near-black scrim
            // Swallow taps so nothing behind the overlay is interactable.
            .pointerInput(Unit) { },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularBurnProgress(percent = progress, isSuccess = false, isIndeterminate = false)
            Spacer(Modifier.height(28.dp))
            Text(
                text = "Formatting USB...",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
