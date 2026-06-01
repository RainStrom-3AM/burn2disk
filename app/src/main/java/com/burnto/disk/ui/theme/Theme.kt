package com.burnto.disk.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Dark-only color scheme. Light mode is intentionally not supported.
private val Burn2DiskColorScheme = darkColorScheme(
    primary = Amber,
    onPrimary = NearBlack,
    primaryContainer = AmberDark,
    onPrimaryContainer = TextPrimary,
    secondary = AmberDim,
    onSecondary = NearBlack,
    background = NearBlack,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextSecondary,
    error = DangerRed,
    onError = TextPrimary,
    outline = OutlineDark
)

@Composable
fun Burn2DiskTheme(
    // Parameter kept for API symmetry; the app is always dark.
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = NearBlack.toArgb()
            window.navigationBarColor = NearBlack.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = Burn2DiskColorScheme,
        typography = AppTypography,
        content = content
    )
}
