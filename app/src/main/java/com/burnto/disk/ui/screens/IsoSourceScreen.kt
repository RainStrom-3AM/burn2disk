package com.burnto.disk.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.burnto.disk.data.model.DownloadState
import com.burnto.disk.ui.Format
import com.burnto.disk.ui.components.DownloadProgressBar
import com.burnto.disk.ui.theme.Amber
import com.burnto.disk.ui.theme.DangerRed
import com.burnto.disk.ui.theme.MonoText
import com.burnto.disk.ui.theme.NearBlack
import com.burnto.disk.ui.theme.SurfaceDark
import com.burnto.disk.ui.theme.TextSecondary
import com.burnto.disk.viewmodel.HomeViewModel

/**
 * Screen 2 — ISO source picker. Offers local file browse, URL download with
 * inline progress, and the recent-ISOs list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IsoSourceScreen(
    onBack: () -> Unit,
    onSourceReady: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf("") }

    LaunchedEffect(downloadState) {
        if (downloadState is DownloadState.Completed) {
            onSourceReady()
        }
    }

    // System file picker filtered to disk images.
    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.onIsoPicked(uri)
            onSourceReady()
        }
    }

    Scaffold(
        containerColor = NearBlack,
        topBar = {
            TopAppBar(
                title = { Text("Select ISO source") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. From device storage.
            item {
                SourceCard(
                    icon = Icons.Filled.FolderOpen,
                    title = "From device storage",
                    subtitle = "Browse local .iso, .img, .bin files"
                ) {
                    pickLauncher.launch(
                        arrayOf(
                            "application/x-iso9660-image",
                            "application/octet-stream",
                            "*/*"
                        )
                    )
                }
            }

            // 2. Download from URL.
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Download, contentDescription = null, tint = Amber)
                            Spacer(Modifier.size(12.dp))
                            Text(
                                "Download from URL",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("https://.../image.iso", color = TextSecondary) },
                            singleLine = true,
                            textStyle = MonoText.small
                        )
                        Spacer(Modifier.height(12.dp))

                        when (val s = downloadState) {
                            is DownloadState.Downloading -> {
                                DownloadProgressBar(s)
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = { viewModel.cancelDownload() },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
                                    border = BorderStroke(1.dp, DangerRed)
                                ) { Text("Cancel") }
                            }
                            is DownloadState.Failed -> {
                                Text(s.error, color = DangerRed, style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.height(8.dp))
                                StartDownloadButton(url) { viewModel.startDownload(url) }
                            }
                            is DownloadState.Completed -> {
                                Text(
                                    "Downloaded. Analyzing...",
                                    color = Amber,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            else -> StartDownloadButton(url) { viewModel.startDownload(url) }
                        }
                    }
                }
            }

            // 3. Recent ISOs.
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.History, contentDescription = null, tint = Amber)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "Recent ISOs",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            }

            if (recent.isEmpty()) {
                item {
                    Text(
                        "No recent ISOs yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            } else {
                items(recent) { r ->
                    Card(
                        onClick = {
                            viewModel.onRecentSelected(r)
                            onSourceReady()
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(r.name, color = Color.White, fontWeight = FontWeight.Medium)
                            Text(
                                "${Format.bytes(r.sizeBytes)} · ${Format.date(r.lastUsedEpochMs)}",
                                style = MonoText.small,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StartDownloadButton(url: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = url.isNotBlank(),
        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = NearBlack)
    ) { Text("Download", fontWeight = FontWeight.Bold) }
}

@Composable
private fun SourceCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = Amber, modifier = Modifier.size(28.dp))
            Spacer(Modifier.size(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
    }
}
