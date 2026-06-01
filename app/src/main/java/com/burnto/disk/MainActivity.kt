package com.burnto.disk

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.navigation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.activity.result.ActivityResultLauncher
import com.burnto.disk.ui.navigation.Routes
import com.burnto.disk.ui.screens.BurnProgressScreen
import com.burnto.disk.ui.screens.BrowseUsbScreen
import com.burnto.disk.ui.screens.DeviceSelectionScreen
import com.burnto.disk.ui.screens.HomeScreen
import com.burnto.disk.ui.screens.IsoInfoScreen
import com.burnto.disk.ui.screens.IsoSourceScreen
import com.burnto.disk.ui.screens.ResultScreen
import com.burnto.disk.ui.theme.Burn2DiskTheme
import com.burnto.disk.viewmodel.HomeViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity host. Owns the Compose navigation graph and toggles
 * FLAG_KEEP_SCREEN_ON while on the burn screen so the display stays awake during
 * a burn (paired with the service's partial wake lock).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        notificationPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            Burn2DiskTheme {
                AppNavHost(
                    onKeepScreenOn = { keep ->
                        if (keep) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun AppNavHost(
    onKeepScreenOn: (Boolean) -> Unit,
    navController: NavHostController = rememberNavController()
) {
    NavHost(navController = navController, startDestination = Routes.SETUP_GRAPH) {
        // The Home -> Source -> Info flow shares one HomeViewModel scoped to the
        // nested graph back-stack entry so the analysed ISO/progress is consistent.
        navigation(startDestination = Routes.HOME, route = Routes.SETUP_GRAPH) {
            composable(Routes.HOME) { entry ->
                val vm = sharedSetupViewModel(navController, entry)
                HomeScreen(
                    onSelectIso = { navController.navigate(Routes.ISO_SOURCE) },
                    onDownloadIso = { navController.navigate(Routes.ISO_SOURCE) },
                    onFormatDisk = { vm.formatDisk() },
                    onBrowseUsb = { navController.navigate(Routes.BROWSE_USB) },
                    viewModel = vm
                )
            }
            composable(Routes.ISO_SOURCE) { entry ->
                val vm = sharedSetupViewModel(navController, entry)
                IsoSourceScreen(
                    onBack = { navController.popBackStack() },
                    onSourceReady = {
                        navController.navigate(Routes.ISO_INFO) { launchSingleTop = true }
                    },
                    viewModel = vm
                )
            }
            composable(Routes.ISO_INFO) { entry ->
                val vm = sharedSetupViewModel(navController, entry)
                IsoInfoScreen(
                    onBack = { navController.popBackStack() },
                    onProceed = { navController.navigate(Routes.DEVICE_SELECTION) },
                    viewModel = vm
                )
            }
        }

        composable(Routes.DEVICE_SELECTION) {
            DeviceSelectionScreen(
                onBack = { navController.popBackStack() },
                onConfirmBurn = { navController.navigate(Routes.BURN_PROGRESS) }
            )
        }
        composable(Routes.BURN_PROGRESS) {
            // Keep the screen on only while this destination is active.
            DisposableEffect(Unit) {
                onKeepScreenOn(true)
                onDispose { onKeepScreenOn(false) }
            }
            BurnProgressScreen(
                onComplete = {
                    navController.navigate(Routes.RESULT) {
                        popUpTo(Routes.BURN_PROGRESS) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.RESULT) {
            ResultScreen(
                onDone = {
                    navController.navigate(Routes.SETUP_GRAPH) {
                        popUpTo(Routes.SETUP_GRAPH) { inclusive = true }
                    }
                },
                onRetry = {
                    navController.navigate(Routes.DEVICE_SELECTION) {
                        popUpTo(Routes.DEVICE_SELECTION) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.BROWSE_USB) {
            BrowseUsbScreen(onClose = { navController.popBackStack() })
        }
    }
}

/** Returns the HomeViewModel scoped to the setup nested-graph back-stack entry. */
@Composable
private fun sharedSetupViewModel(
    navController: NavHostController,
    entry: NavBackStackEntry
): HomeViewModel {
    val parentEntry = remember(entry) {
        navController.getBackStackEntry(Routes.SETUP_GRAPH)
    }
    return hiltViewModel(parentEntry)
}
