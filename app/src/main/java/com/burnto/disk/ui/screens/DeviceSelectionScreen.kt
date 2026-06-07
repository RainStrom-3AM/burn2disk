package com.burnto.disk.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.UsbOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.burnto.disk.data.model.BurnTarget
import com.burnto.disk.data.model.SdCardInfo
import com.burnto.disk.data.model.UsbDeviceInfo
import com.burnto.disk.ui.Format
import com.burnto.disk.ui.components.UsbDeviceCard
import com.burnto.disk.ui.theme.Amber
import com.burnto.disk.ui.theme.DangerRed
import com.burnto.disk.ui.theme.MonoText
import com.burnto.disk.ui.theme.NearBlack
import com.burnto.disk.ui.theme.OutlineDark
import com.burnto.disk.ui.theme.SurfaceDark
import com.burnto.disk.ui.theme.SuccessGreen
import com.burnto.disk.ui.theme.TextSecondary
import com.burnto.disk.viewmodel.BurnViewModel

/**
 * Screen 4 — Device selection. Lists connected USB OTG devices and, below that,
 * an optional SD card target. A confirmation bottom sheet (different for USB
 * vs SD) prevents accidental data loss.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSelectionScreen(
    onBack: () -> Unit,
    onConfirmBurn: () -> Unit,
    viewModel: BurnViewModel = hiltViewModel()
) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val selected by viewModel.selectedTarget.collectAsStateWithLifecycle()

    val context = androidx.compose.ui.platform.LocalContext.current
    val sdCardManager = remember { com.burnto.disk.data.sdcard.SdCardManager(context) }
    val sdCard by remember { mutableStateOf(sdCardManager.detectSdCard()) }

    var sheetTarget by remember { mutableStateOf<BurnTarget?>(null) }
    val sheetState = rememberModalBottomSheetState()

    val sdCardLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val info = sdCardManager.persistUri(uri)
                // Merge the persisted URI with the detected info (capacity, name).
                val merged = sdCard?.copy(
                    uri = info.uri,
                    displayName = info.displayName,
                    freeBytes = info.freeBytes,
                    totalBytes = info.totalBytes
                ) ?: info
                viewModel.selectSdCard(merged)
                sheetTarget = BurnTarget.SdCard(merged)
            }
        }
    }

    LaunchedEffect(Unit) { viewModel.refreshDevices() }

    Scaffold(
        containerColor = NearBlack,
        topBar = {
            TopAppBar(
                title = { Text("Select target drive") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Amber)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshDevices() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Rescan", tint = Amber)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NearBlack,
                    titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // --- USB Section ---
                if (scanning && devices.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Spacer(Modifier.height(48.dp))
                            CircularProgressIndicator(color = Amber)
                            Spacer(Modifier.height(16.dp))
                            Text("Scanning for USB drives...", color = TextSecondary)
                        }
                    }
                } else if (devices.isEmpty()) {
                    item {
                        EmptyUsbState()
                    }
                } else {
                    items(devices) { device ->
                        UsbDeviceCard(
                            device = device,
                            selected = selected is BurnTarget.UsbOtg &&
                                    (selected as BurnTarget.UsbOtg).info.deviceId == device.deviceId,
                            onClick = {
                                viewModel.selectUsbDevice(device)
                                sheetTarget = BurnTarget.UsbOtg(device)
                            }
                        )
                    }
                }

                // Divider.
                item { Spacer(Modifier.height(8.dp)) }

                // --- SD Card Section ---
                item {
                    Text("SD Card", style = MaterialTheme.typography.titleMedium, color = Amber)
                }

                if (sdCard != null) {
                    item {
                        SdCardCard(
                            info = sdCard!!,
                            selected = selected is BurnTarget.SdCard,
                            onClick = {
                                val persistedUri = sdCardManager.loadPersistedUri()
                                if (persistedUri != null) {
                                    // We already have SAF permission — use the persisted URI.
                                    val info = sdCard!!.copy(uri = persistedUri)
                                    viewModel.selectSdCard(info)
                                    sheetTarget = BurnTarget.SdCard(info)
                                } else {
                                    // No SAF permission yet — launch the picker.
                                    sdCardLauncher.launch(sdCardManager.requestAccessIntent())
                                }
                            }
                        )
                    }
                } else {
                    item {
                        NoSdCardState()
                    }
                }
            }
        }
    }

    // Confirmation bottom sheet.
    val target = sheetTarget
    if (target != null) {
        when (target) {
            is BurnTarget.UsbOtg -> ConfirmUsbSheet(
                device = target.info,
                sheetState = sheetState,
                onDismiss = { sheetTarget = null },
                onConfirmed = {
                    sheetTarget = null
                    onConfirmBurn()
                }
            )
            is BurnTarget.SdCard -> ConfirmSdSheet(
                info = target.info,
                isoSizeBytes = viewModel.iso.value?.sizeBytes ?: 0L,
                sheetState = sheetState,
                onDismiss = { sheetTarget = null },
                onConfirmed = {
                    sheetTarget = null
                    onConfirmBurn()
                }
            )
        }
    }
}

@Composable
private fun EmptyUsbState() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))
        Icon(
            Icons.Filled.UsbOff,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(72.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text("No USB drive detected", style = MaterialTheme.typography.titleLarge, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text(
            "Connect a USB drive using an OTG adapter",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
    }
}

@Composable
private fun SdCardCard(
    info: SdCardInfo,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) SurfaceDark else NearBlack,
            contentColor = Color.White
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) Amber else OutlineDark
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.SdCard,
                contentDescription = null,
                tint = Amber,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.size(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(info.displayName, fontWeight = FontWeight.SemiBold, color = Color.White)
                Text(
                    "${Format.bytes(info.freeBytes)} free of ${Format.bytes(info.totalBytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                if (info.filesystem.isNotBlank()) {
                    Text(
                        "Filesystem: ${info.filesystem}",
                        style = MonoText.small,
                        color = TextSecondary
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = Color(0xFFFFA000),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "FILE COPY ONLY — NOT RAW BURN",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFFA000)
                    )
                }
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(SuccessGreen, shape = RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        androidx.compose.material.icons.Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = NearBlack,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun NoSdCardState() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))
        Icon(
            Icons.Filled.SdCard,
            contentDescription = null,
            tint = OutlineDark,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text("No SD card detected", style = MaterialTheme.typography.titleMedium, color = TextSecondary)
        Text(
            "Insert a microSD card to use this option",
            style = MaterialTheme.typography.bodyMedium,
            color = OutlineDark
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfirmUsbSheet(
    device: UsbDeviceInfo,
    sheetState: androidx.compose.material3.SheetState,
    onDismiss: () -> Unit,
    onConfirmed: () -> Unit
) {
    var typed by remember { mutableStateOf("") }
    val confirmed = typed.trim() == "CONFIRM"

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDark
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Text(
                "Erase ${device.displayName}?",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "You are about to erase all data on ${device.displayName} (${Format.bytes(device.capacityBytes)}). This cannot be undone.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            Spacer(Modifier.height(16.dp))
            Text("Type CONFIRM to proceed", color = Color.White, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MonoText.medium,
                placeholder = { Text("CONFIRM", color = TextSecondary) }
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onConfirmed,
                enabled = confirmed,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DangerRed,
                    contentColor = Color.White,
                    disabledContainerColor = OutlineDark
                )
            ) {
                Text("BURN", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfirmSdSheet(
    info: SdCardInfo,
    isoSizeBytes: Long,
    sheetState: androidx.compose.material3.SheetState,
    onDismiss: () -> Unit,
    onConfirmed: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDark
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Text(
                "Copy ISO contents to SD card?",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Files will be copied to:\nSD Card / {folder}/",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "This will NOT create a bootable drive. It copies the ISO contents as regular files.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFFFA000)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Free space needed: ${Format.bytes(isoSizeBytes)}\nAvailable: ${Format.bytes(info.freeBytes)}",
                style = MonoText.medium,
                color = Amber
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onConfirmed,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFFA000),
                    contentColor = NearBlack
                )
            ) {
                Text("COPY", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
