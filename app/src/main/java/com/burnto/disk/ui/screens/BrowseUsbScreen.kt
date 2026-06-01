package com.burnto.disk.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.burnto.disk.ui.Format
import com.burnto.disk.ui.theme.Amber
import com.burnto.disk.ui.theme.MonoText
import com.burnto.disk.ui.theme.NearBlack
import com.burnto.disk.ui.theme.OutlineDark
import com.burnto.disk.ui.theme.TextSecondary
import com.burnto.disk.viewmodel.BrowseState
import com.burnto.disk.viewmodel.UsbBrowserViewModel

/**
 * Read-only USB file browser. After a burn, the user opens this to confirm the
 * drive actually has boot/, isolinux/, etc. — no PC required.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseUsbScreen(
    onClose: () -> Unit,
    viewModel: UsbBrowserViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val deviceName by viewModel.deviceName.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.open() }

    // Hardware back: go up a directory, or close at root.
    BackHandler { if (!viewModel.navigateUp()) onClose() }

    Scaffold(
        containerColor = NearBlack,
        topBar = {
            TopAppBar(
                title = { Text(deviceName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { if (!viewModel.navigateUp()) onClose() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Amber)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NearBlack,
                    titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val current = state
            if (current is BrowseState.Listing) {
                Breadcrumbs(
                    path = current.path,
                    onCrumb = { viewModel.navigateToPath(it) }
                )
                HorizontalDivider(color = OutlineDark)
            }

            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                when (current) {
                    is BrowseState.Loading -> CircularProgressIndicator(color = Amber)
                    is BrowseState.RequestingPermission -> PermissionPrompt()
                    is BrowseState.NoDevice -> CenterText(
                        "No USB drive connected. Connect a drive via OTG."
                    )
                    is BrowseState.Error -> CenterText(current.message)
                    is BrowseState.Listing -> {
                        if (current.entries.isEmpty()) {
                            CenterText("No files found on this drive")
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(current.entries) { entry ->
                                    FileRow(
                                        name = entry.name,
                                        isDirectory = entry.isDirectory,
                                        sizeText = if (entry.isDirectory) "" else Format.bytes(entry.sizeBytes),
                                        onClick = { if (entry.isDirectory) viewModel.navigateInto(entry) }
                                    )
                                    HorizontalDivider(color = OutlineDark.copy(alpha = 0.4f))
                                }
                            }
                        }
                    }
                }
            }

            if (current is BrowseState.Listing) {
                HorizontalDivider(color = OutlineDark)
                Text(
                    text = "${current.totalFiles} files · ${Format.bytes(current.totalBytes)} used",
                    style = MonoText.small,
                    color = TextSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                )
            }
        }
    }
}

@Composable
private fun Breadcrumbs(path: String, onCrumb: (String) -> Unit) {
    val parts = if (path.isEmpty()) emptyList() else path.split('/')
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "/",
            style = MonoText.medium,
            color = Amber,
            modifier = Modifier.clickable { onCrumb("") }
        )
        var built = ""
        for (part in parts) {
            built = if (built.isEmpty()) part else "$built/$part"
            val target = built
            Text("  >  ", style = MonoText.medium, color = TextSecondary)
            Text(
                text = part,
                style = MonoText.medium,
                color = Amber,
                modifier = Modifier.clickable { onCrumb(target) }
            )
        }
    }
}

@Composable
private fun FileRow(
    name: String,
    isDirectory: Boolean,
    sizeText: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isDirectory, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isDirectory) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            tint = if (isDirectory) Amber else TextSecondary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.size(14.dp))
        Text(
            text = name,
            style = MonoText.medium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (sizeText.isNotEmpty()) {
            Spacer(Modifier.size(12.dp))
            Text(text = sizeText, style = MonoText.small, color = TextSecondary)
        }
    }
}

@Composable
private fun PermissionPrompt() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(32.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Usb,
            contentDescription = null,
            tint = Amber,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "Allow USB access",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Tap Allow when Android asks for permission",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Spacer(Modifier.height(24.dp))
        CircularProgressIndicator(color = Amber, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun CenterText(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyLarge,
        color = TextSecondary,
        modifier = Modifier.padding(32.dp)
    )
}
