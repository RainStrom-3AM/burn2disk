package com.burnto.disk.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.burnto.disk.ui.Format
import com.burnto.disk.ui.components.SandstormUsb
import com.burnto.disk.ui.theme.Amber
import com.burnto.disk.ui.theme.MonoText
import com.burnto.disk.ui.theme.NearBlack
import com.burnto.disk.ui.theme.OutlineDark
import com.burnto.disk.ui.theme.RobotoMono
import com.burnto.disk.ui.theme.TextSecondary
import com.burnto.disk.ui.theme.TextTertiary
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
            // Top bar stays visible at all times — anchors the screen and shows
            // which drive is being read, even during loading.
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
                    is BrowseState.Loading -> BusyGroup("Reading USB drive...")
                    is BrowseState.RequestingPermission -> BusyGroup("Waiting for permission...")
                    is BrowseState.NoDevice -> ErrorContent(
                        message = "No drive connected",
                        helper = "Connect a USB drive or SD card and tap Retry",
                        onRetry = viewModel::open
                    )
                    is BrowseState.PickSource -> SourcePickerContent(
                        usbLabel = current.usbLabel,
                        sdLabel = current.sdLabel,
                        onUsb = viewModel::openUsbExplicit,
                        onSd = viewModel::openSdCardExplicit
                    )
                    is BrowseState.Error -> {
                        val msg = current.message
                        val helper = when {
                            msg.contains("permission", true) -> "Tap Retry and allow access when prompted"
                            msg.contains("filesystem", true) || msg.contains("format", true) ->
                                "Try formatting the drive first"
                            else -> "Reconnect the drive and tap Retry"
                        }
                        ErrorContent(message = msg, helper = helper, onRetry = viewModel::open)
                    }
                    is BrowseState.Listing -> {
                        if (current.entries.isEmpty()) {
                            EmptyContent()
                        } else {
                            FileListing(
                                entries = current.entries,
                                onOpen = { viewModel.navigateInto(it) }
                            )
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
private fun SourcePickerContent(
    usbLabel: String,
    sdLabel: String,
    onUsb: () -> Unit,
    onSd: () -> Unit
) {
    Column(
        modifier = Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Choose a drive to browse", style = MaterialTheme.typography.titleMedium, color = Color.White)
        OutlinedButton(
            onClick = onUsb,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
            border = BorderStroke(1.dp, Amber)
        ) {
            Icon(Icons.Filled.Usb, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(usbLabel)
        }
        OutlinedButton(
            onClick = onSd,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
            border = BorderStroke(1.dp, Amber)
        ) {
            Icon(Icons.Filled.SdCard, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(sdLabel)
        }
    }
}

/** Animated loading hero: breathing USB + amber progress bar + status text,
 *  centered in the full content area. */
@Composable
private fun BusyGroup(statusText: String) {
    // Gentle breathing scale 0.97 → 1.00 → 0.97 over 2s.
    val breath = rememberInfiniteTransition(label = "breath")
    val scale by breath.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.00f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    // Fill the whole content area and center both axes, so the group never
    // collapses into a corner regardless of the parent layout slot.
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.scale(scale)
        ) {
            SandstormUsb(boxSize = 80.dp, iconSize = 44.dp)

            // Amber indeterminate progress bar — same width as the icon.
            LinearProgressIndicator(
                modifier = Modifier
                    .width(80.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(50)),
                color = Amber,
                trackColor = OutlineDark
            )

            // Status text crossfades (300ms) when it changes.
            androidx.compose.animation.Crossfade(
                targetState = statusText,
                animationSpec = tween(300),
                label = "status"
            ) { text ->
                Text(
                    text = text,
                    fontFamily = RobotoMono,
                    fontSize = 13.sp,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun FileListing(
    entries: List<com.burnto.disk.viewmodel.UsbFileEntry>,
    onOpen: (com.burnto.disk.viewmodel.UsbFileEntry) -> Unit
) {
    // Fade + slide-up the whole list in; cascade rows top-to-bottom.
    val listAlpha = remember { Animatable(0f) }
    LaunchedEffect(entries) {
        listAlpha.snapTo(0f)
        listAlpha.animateTo(1f, tween(300))
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = listAlpha.value
                translationY = (1f - listAlpha.value) * 16.dp.toPx()
            }
    ) {
        itemsIndexed(entries) { index, entry ->
            StaggeredRow(index = index) {
                FileRow(
                    name = entry.name,
                    isDirectory = entry.isDirectory,
                    sizeText = if (entry.isDirectory) "" else Format.bytes(entry.sizeBytes),
                    onClick = { if (entry.isDirectory) onOpen(entry) }
                )
            }
            HorizontalDivider(color = OutlineDark.copy(alpha = 0.4f))
        }
    }
}

/** Cascades a row in: row N starts 30ms × N after the list appears. */
@Composable
private fun StaggeredRow(index: Int, content: @Composable () -> Unit) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        // Cap the stagger so very long lists don't take forever.
        kotlinx.coroutines.delay((index.coerceAtMost(20) * 30).toLong())
        anim.animateTo(1f, tween(220))
    }
    Box(
        modifier = Modifier.graphicsLayer {
            alpha = anim.value
            translationY = (1f - anim.value) * 16.dp.toPx()
        }
    ) { content() }
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
private fun ErrorContent(message: String, helper: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(32.dp)
    ) {
        // Static, desaturated (gray) USB icon — no animation.
        Icon(
            imageVector = Icons.Filled.Usb,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(20.dp))
        OutlinedButton(
            onClick = onRetry,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
            border = BorderStroke(1.dp, Amber)
        ) {
            Text("RETRY", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = helper,
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun EmptyContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(32.dp)
    ) {
        // Static amber USB icon (no particles).
        Icon(
            imageVector = Icons.Filled.Usb,
            contentDescription = null,
            tint = Amber,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "Drive is empty",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "No files found on this drive",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
    }
}
