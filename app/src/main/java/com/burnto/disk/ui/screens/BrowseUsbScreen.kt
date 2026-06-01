package com.burnto.disk.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.burnto.disk.ui.Format
import com.burnto.disk.ui.components.SandstormUsb
import com.burnto.disk.ui.theme.Amber
import com.burnto.disk.ui.theme.MonoText
import com.burnto.disk.ui.theme.NearBlack
import com.burnto.disk.ui.theme.OutlineDark
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
                // Loading / permission group — fades out when listing arrives.
                val isBusy = current is BrowseState.Loading || current is BrowseState.RequestingPermission
                androidx.compose.animation.AnimatedVisibility(
                    visible = isBusy,
                    enter = fadeIn(tween(200)),
                    exit = fadeOut(tween(200))
                ) {
                    BusyGroup(
                        statusText = if (current is BrowseState.RequestingPermission)
                            "Waiting for permission..." else "Reading USB drive..."
                    )
                }

                when (current) {
                    is BrowseState.NoDevice -> ErrorContent(
                        message = "No USB drive connected",
                        helper = "Connect a USB drive via OTG and tap Retry",
                        onRetry = viewModel::open
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
                    else -> Unit // busy states handled by BusyGroup above
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

/** Animated loading hero: breathing USB + shimmer bar + fading status text. */
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

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.scale(scale)
    ) {
        SandstormUsb(boxSize = 160.dp, iconSize = 72.dp)
        Spacer(Modifier.height(20.dp))
        ShimmerBar(width = 160.dp)
        Spacer(Modifier.height(16.dp))
        // Status text fades in (300ms) and crossfades when it changes.
        androidx.compose.animation.Crossfade(
            targetState = statusText,
            animationSpec = tween(300),
            label = "status"
        ) { text ->
            Text(text = text, style = MonoText.small, color = TextSecondary)
        }
    }
}

/** Thin amber indeterminate progress bar with a sweeping shimmer highlight. */
@Composable
private fun ShimmerBar(width: androidx.compose.ui.unit.Dp) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val pos by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pos"
    )
    Box(
        modifier = Modifier
            .width(width)
            .height(3.dp)
            .background(OutlineDark, RoundedCornerShape(2.dp))
    ) {
        // Moving highlight ~30% of the bar width.
        Box(
            modifier = Modifier
                .fillMaxWidth(0.3f)
                .height(3.dp)
                .graphicsLayer {
                    // Sweep from left (-30%) to right (100%).
                    translationX = (pos * 1.3f - 0.3f) * size.width / 0.3f
                }
                .background(Amber, RoundedCornerShape(2.dp))
        )
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
