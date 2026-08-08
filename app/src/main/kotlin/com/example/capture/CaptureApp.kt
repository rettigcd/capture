package com.example.capture

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.capture.camera.ui.CameraRoute
import com.example.capture.camera.ui.CameraViewModel
import com.example.capture.security.ui.KeySessionRoute
import com.example.capture.settings.ui.SettingsRoute

const val CAMERA_ROUTE = "camera"
private const val SETTINGS_ROUTE = "settings"
private const val ENCRYPTION_ROUTE = "encryption"

/**
 * The whole app is these two destinations. [cameraViewModel] is threaded in from `MainActivity`
 * (see [CameraRoute]'s kdoc) rather than resolved with the default `hiltViewModel()` inside the
 * "camera" destination, so hardware volume-key events reach the same instance the UI observes.
 */
@Composable
fun CaptureApp(cameraViewModel: CameraViewModel, navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = CAMERA_ROUTE) {
        composable(CAMERA_ROUTE) {
            CameraRoute(
                viewModel = cameraViewModel,
                onOpenSettings = { navController.navigate(SETTINGS_ROUTE) },
            )
        }
        composable(SETTINGS_ROUTE) {
            SettingsRoute(
                onBack = { navController.popBackStack() },
                onNavigateToEncryptionKey = { navController.navigate(ENCRYPTION_ROUTE) },
            )
        }
        composable(ENCRYPTION_ROUTE) {
            KeySessionRoute(onBack = { navController.popBackStack() })
        }
    }
}
