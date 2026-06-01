package com.burnto.disk.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.burnto.disk.ui.Format
import com.burnto.disk.ui.components.InfoCard
import com.burnto.disk.ui.components.InfoRow
import com.burnto.disk.ui.theme.Amber
import com.burnto.disk.ui.theme.MonoText
import com.burnto.disk.ui.theme.NearBlack
import com.burnto.disk.ui.theme.SurfaceVariantDark
import com.burnto.disk.ui.theme.TextSecondary
import com.burnto.disk.viewmodel.HomeViewModel
import com.burnto.disk.viewmodel.IsoUiState

/**
 * Screen 3 — ISO info. Shows analysed metadata: name, size, detected OS, boot
 * type, architecture, SHA-256 (with copy), and the recommended write method.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IsoInfoScreen(
    onBack: () -> Unit,
    onProceed: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.isoState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    Scaffold(
        containerColor = NearBlack,
        topBar = {
            TopAppBar(
                title = { Text("ISO details") },
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            when (val s = state) {
                is IsoUiState.Importing -> ProgressBlock("Importing file...", s.progress)
                is IsoUiState.Analyzing -> ProgressBlock("Analyzing & checksumming...", s.progress)
                is IsoUiState.Error -> Text(s.message, color = MaterialTheme.colorScheme.error)
                is IsoUiState.Ready -> {
                    val info = s.info
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp)
                    ) {
                        Text(
                            text = info.fileName,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp,
                            color = Color.White
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = Format.bytes(info.sizeBytes),
                            style = MonoText.medium,
                            color = TextSecondary
                        )

                        Spacer(Modifier.height(20.dp))

                        InfoCard {
                            InfoRow("Detected OS", info.osType.label, valueMono = false)
                            InfoRow("Boot type", info.bootType.label, valueMono = false)
                            InfoRow("Architecture", info.architecture.label)
                            if (info.hasLargeWim) {
                                InfoRow("install.wim", "> 4 GB · will split to .swm")
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        // SHA-256 with copy button.
                        InfoCard {
                            Text("SHA-256", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = info.sha256 ?: "—",
                                    style = MonoText.small,
                                    color = Color.White,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = {
                                    info.sha256?.let { clipboard.setText(AnnotatedString(it)) }
                                }) {
                                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy checksum", tint = Amber)
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        // Recommended write method badge.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SurfaceVariantDark, RoundedCornerShape(12.dp))
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = info.recommendedMethod,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Amber
                            )
                        }

                        Spacer(Modifier.height(28.dp))

                        Button(
                            onClick = onProceed,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = NearBlack)
                        ) {
                            Text("PROCEED", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }

                        Spacer(Modifier.height(24.dp))
                    }
                }
                else -> ProgressBlock("Preparing...", 0)
            }
        }
    }
}

@Composable
private fun ProgressBlock(label: String, percent: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Amber)
        Spacer(Modifier.height(16.dp))
        Text(label, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        if (percent in 1..99) {
            Spacer(Modifier.height(4.dp))
            Text("$percent%", style = MonoText.medium, color = Amber)
        }
    }
}
