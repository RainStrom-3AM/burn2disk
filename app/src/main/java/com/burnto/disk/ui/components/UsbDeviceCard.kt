package com.burnto.disk.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.burnto.disk.data.model.UsbDeviceInfo
import com.burnto.disk.ui.Format
import com.burnto.disk.ui.theme.Amber
import com.burnto.disk.ui.theme.DangerRed
import com.burnto.disk.ui.theme.MonoText
import com.burnto.disk.ui.theme.SurfaceDark
import com.burnto.disk.ui.theme.SurfaceVariantDark
import com.burnto.disk.ui.theme.TextSecondary

/** A selectable card representing one connected USB OTG device. */
@Composable
fun UsbDeviceCard(
    device: UsbDeviceInfo,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) SurfaceVariantDark else SurfaceDark
        ),
        border = if (selected) BorderStroke(2.dp, Amber) else null
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Usb,
                        contentDescription = null,
                        tint = Amber,
                        modifier = Modifier.size(36.dp)
                    )
                }
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = device.displayName,
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Text(
                        text = "${Format.bytes(device.capacityBytes)} · ${device.filesystem}",
                        style = MonoText.small,
                        color = TextSecondary
                    )
                    Text(
                        text = String.format("VID:%04X PID:%04X", device.vendorId, device.productId),
                        style = MonoText.small,
                        color = TextSecondary
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Destructive-action warning badge.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = DangerRed,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = "ALL DATA WILL BE ERASED",
                    style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                    color = DangerRed
                )
            }
        }
    }
}
