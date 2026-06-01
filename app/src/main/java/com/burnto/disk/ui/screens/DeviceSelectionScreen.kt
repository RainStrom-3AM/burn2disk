package com.burnto.disk.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UsbOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.burnto.disk.data.model.UsbDeviceInfo
import com.burnto.disk.ui.components.UsbDeviceCard
import com.burnto.disk.ui.theme.Amber
import com.burnto.disk.ui.theme.DangerRed
import com.burnto.disk.ui.theme.MonoText
import com.burnto.disk.ui.theme.NearBlack
import com.burnto.disk.ui.theme.SuccessGreen
import com.burnto.disk.ui.theme.TextSecondary
import com.burnto.disk.viewmodel.BurnViewModel

/**
 * Screen 4 — Device selection. Lists connected USB OTG devices, refresh, the
 * empty state, and a "type CONFIRM" safety bottom sheet before burning.
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
    val selected by viewModel.selectedDevice.collectAsStateWithLifecycle()

    var sheetDevice by remember { mutableStateOf<UsbDeviceInfo?>(null) }
    val sheetState = rememberModalBottomSheetState()

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
            when {
                scanning && devices.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(color = Amber)
                        Spacer(Modifier.height(16.dp))
                        Text("Scanning for USB drives...", color = TextSecondary)
                    }
                }
                devices.isEmpty() -> EmptyDeviceState()
                else -> {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(devices) { device ->
                            UsbDeviceCard(
                                device = device,
                                selected = selected?.deviceId == device.deviceId,
                                onClick = {
                                    viewModel.selectDevice(device)
                                    sheetDevice = device
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Safety confirmation bottom sheet.
    val target = sheetDevice
    if (target != null) {
        ConfirmBurnSheet(
            device = target,
            sheetState = sheetState,
            onDismiss = { sheetDevice = null },
            onConfirmed = {
                sheetDevice = null
                onConfirmBurn()
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfirmBurnSheet(
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
        containerColor = com.burnto.disk.ui.theme.SurfaceDark
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
                "You are about to erase all data on ${device.displayName} (${com.burnto.disk.ui.Format.bytes(device.capacityBytes)}). This cannot be undone.",
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
                    disabledContainerColor = com.burnto.disk.ui.theme.OutlineDark
                )
            ) {
                Text("BURN", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun EmptyDeviceState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.UsbOff,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(96.dp)
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "No USB drive detected",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Connect a USB drive using an OTG adapter",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
    }
}
