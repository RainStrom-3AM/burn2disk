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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.burnto.disk.ui.components.SandstormUsb
import com.burnto.disk.ui.theme.Amber
import com.burnto.disk.ui.theme.NearBlack
import com.burnto.disk.ui.theme.SuccessGreen
import com.burnto.disk.ui.theme.TextSecondary
import com.burnto.disk.viewmodel.HomeViewModel

/**
 * Screen 1 — Home. Branded title, animated sandstorm USB hero, and the two
 * primary actions (select / download). Shows live USB connection status.
 */
@Composable
fun HomeScreen(
    onSelectIso: () -> Unit,
    onDownloadIso: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val usbConnected by viewModel.usbConnected.collectAsStateWithLifecycle()

    Scaffold(containerColor = NearBlack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))

            // Title block.
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

            Spacer(Modifier.height(48.dp))

            // Animated hero.
            SandstormUsb()

            Spacer(Modifier.height(48.dp))

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

            Spacer(Modifier.weight(1f))

            // USB status footer.
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
}
