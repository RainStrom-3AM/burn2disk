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
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.burnto.disk.ui.Format
import com.burnto.disk.ui.components.SandstormUsb
import com.burnto.disk.ui.theme.Amber
import com.burnto.disk.ui.theme.DangerRed
import com.burnto.disk.ui.theme.MonoText
import com.burnto.disk.ui.theme.NearBlack
import com.burnto.disk.ui.theme.OutlineDark
import com.burnto.disk.ui.theme.SuccessGreen
import com.burnto.disk.ui.theme.SurfaceDark
import com.burnto.disk.ui.theme.TextSecondary
import com.burnto.disk.viewmodel.FormatUiState
import com.burnto.disk.viewmodel.HomeViewModel

/**
 * Screen 1 — Home. Branded title, animated sandstorm USB hero, the two primary
 * actions (select / download), and a Format Disk recovery action. Shows live USB
 * connection status.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onSelectIso: () -> Unit,
    onDownloadIso: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val usbConnected by viewModel.usbConnected.collectAsStateWithLifecycle()
    val connectedDevice by viewModel.connectedDevice.collectAsStateWithLifecycle()
    val formatState by viewModel.formatState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var showFormatSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    // Re-scan whenever the screen is (re)composed into view.
    LaunchedEffect(Unit) { viewModel.refreshUsb() }

    // React to format outcomes with snackbars.
    LaunchedEffect(formatState) {
        when (val s = formatState) {
            is FormatUiState.Done -> {
                snackbarHostState.showSnackbar(s.message)
                viewModel.resetFormatState()
            }
            is FormatUiState.Error -> {
                snackbarHostState.showSnackbar(s.message)
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

            // Secondary recovery action — smaller, outlined.
            OutlinedButton(
                onClick = {
                    viewModel.refreshUsb()
                    if (viewModel.connectedDevice.value == null) {
                        // Will be re-checked async; rely on snackbar if none.
                    }
                    showFormatSheet = true
                },
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

    // --- Format Disk bottom sheet ---
    if (showFormatSheet) {
        val device = connectedDevice
        if (device == null) {
            // No USB — close the sheet and show a snackbar instead.
            LaunchedEffect(Unit) {
                showFormatSheet = false
                snackbarHostState.showSnackbar("Connect a USB drive via OTG first")
            }
        } else {
            FormatDiskSheet(
                deviceName = device.displayName,
                deviceSize = if (device.capacityBytes > 0) Format.bytes(device.capacityBytes) else "Unknown size",
                sheetState = sheetState,
                formatState = formatState,
                onDismiss = { showFormatSheet = false },
                onFormat = { label -> viewModel.formatDisk(label) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormatDiskSheet(
    deviceName: String,
    deviceSize: String,
    sheetState: androidx.compose.material3.SheetState,
    formatState: FormatUiState,
    onDismiss: () -> Unit,
    onFormat: (String) -> Unit
) {
    var volumeLabel by remember { mutableStateOf("USB DISK") }
    var showConfirm by remember { mutableStateOf(false) }
    val inProgress = formatState is FormatUiState.InProgress

    ModalBottomSheet(
        onDismissRequest = { if (!inProgress) onDismiss() },
        sheetState = sheetState,
        containerColor = SurfaceDark
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Text(
                "Format USB Drive",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "$deviceName · $deviceSize",
                style = MonoText.medium,
                color = Amber
            )

            Spacer(Modifier.height(20.dp))

            Text("Filesystem", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            Spacer(Modifier.height(8.dp))
            // FAT32 is the only supported target; shown as a selected chip.
            FilterChip(
                selected = true,
                onClick = { },
                label = { Text("FAT32  ·  recommended") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Amber,
                    selectedLabelColor = NearBlack
                )
            )

            Spacer(Modifier.height(20.dp))

            OutlinedTextField(
                value = volumeLabel,
                onValueChange = { volumeLabel = it.take(11) },
                label = { Text("Volume label (optional)") },
                singleLine = true,
                enabled = !inProgress,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MonoText.medium
            )

            Spacer(Modifier.height(16.dp))

            Text(
                "All data on the drive will be erased",
                style = MaterialTheme.typography.bodyMedium,
                color = DangerRed,
                fontWeight = FontWeight.Medium
            )

            Spacer(Modifier.height(20.dp))

            if (inProgress) {
                val pct = (formatState as FormatUiState.InProgress).progress
                Text("Formatting... $pct%", color = Color.White, style = MonoText.medium)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { pct / 100f },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = Amber,
                    trackColor = OutlineDark
                )
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                        border = BorderStroke(1.dp, OutlineDark)
                    ) { Text("CANCEL", fontWeight = FontWeight.Bold) }

                    Button(
                        onClick = { showConfirm = true },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = DangerRed, contentColor = Color.White)
                    ) { Text("FORMAT", fontWeight = FontWeight.Bold) }
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }

    if (showConfirm) {
        ConfirmFormatDialog(
            onDismiss = { showConfirm = false },
            onConfirmed = {
                showConfirm = false
                onFormat(volumeLabel)
            }
        )
    }
}

@Composable
private fun ConfirmFormatDialog(
    onDismiss: () -> Unit,
    onConfirmed: () -> Unit
) {
    var typed by remember { mutableStateOf("") }
    val confirmed = typed.trim() == "CONFIRM"

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = { Text("Type CONFIRM to format", color = Color.White) },
        text = {
            Column {
                Text(
                    "This permanently erases the drive.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    placeholder = { Text("CONFIRM", color = TextSecondary) },
                    textStyle = MonoText.medium,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirmed, enabled = confirmed) {
                Text("FORMAT", color = if (confirmed) DangerRed else TextSecondary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL", color = Amber) }
        }
    )
}
