package com.example.capture.camera.ui

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.capture.permissions.CapturePermissions

/**
 * The one place that talks to `Activity`-level permission APIs. Everything below this
 * ([CameraViewModel], [CameraScreen]) only ever sees plain state and callbacks.
 *
 * [viewModel] defaults to the usual [hiltViewModel] resolution, but `MainActivity` passes its own
 * Activity-scoped instance explicitly instead: this composable now lives inside a Navigation
 * Compose destination, whose default `hiltViewModel()` scope is that destination's back-stack
 * entry, not the Activity - and `MainActivity` needs the exact same instance the UI observes to
 * route hardware volume-key events to it.
 */
@Composable
fun CameraRoute(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CameraViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val diagnosticsOverlayEnabled by viewModel.diagnosticsOverlayEnabled.collectAsStateWithLifecycle()
    val diagnosticsOverlayInfo by viewModel.diagnosticsOverlayInfo.collectAsStateWithLifecycle()

    var hasRequestedCameraPermission by rememberSaveable { mutableStateOf(false) }
    var hasRequestedMicrophonePermission by rememberSaveable { mutableStateOf(false) }

    fun refreshCameraPermission() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        val shouldShowRationale = activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        viewModel.onCameraPermissionResult(granted, shouldShowRationale, hasRequestedCameraPermission)
    }

    fun refreshMicrophonePermission() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        val shouldShowRationale = activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        viewModel.onMicrophonePermissionResult(granted, shouldShowRationale, hasRequestedMicrophonePermission)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        hasRequestedCameraPermission = true
        refreshCameraPermission()
    }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        hasRequestedMicrophonePermission = true
        refreshMicrophonePermission()
    }

    // Camera permission is requested immediately: the whole screen is useless without it.
    // Microphone permission is requested only when the user opts into voice triggering
    // (see onVoiceTriggerToggle below) - it is never requested up front.
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        refreshCameraPermission()
        if (!granted && !hasRequestedCameraPermission) {
            hasRequestedCameraPermission = true
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Re-checks both permissions whenever this screen comes back to the foreground, so a grant
    // (or revocation) made from system Settings while the app was backgrounded - not just one made
    // through this screen's own request flow - is reflected here too.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshCameraPermission()
                refreshMicrophonePermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    CameraScreen(
        uiState = uiState,
        onScreenTouch = viewModel::onScreenTouch,
        onShutterButtonClick = viewModel::onShutterButtonClick,
        onOverlayVisibilityChanged = viewModel::onOverlayVisibilityChanged,
        onVoiceTriggerToggle = { enabled ->
            viewModel.onVoiceTriggerToggled(enabled)
            val microphoneGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (enabled && !microphoneGranted) {
                hasRequestedMicrophonePermission = true
                microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                // Otherwise the ViewModel would never learn the real permission state here: it
                // only ever hears about microphone permission through this route, and the launch
                // above is the *only* other place that tells it. If RECORD_AUDIO was already
                // granted (e.g. from a previous session, or granted via system Settings), skipping
                // this would leave `microphonePermission` stuck at NOT_DETERMINED forever, and the
                // recognizer would never start no matter how many times the toggle is flipped.
                refreshMicrophonePermission()
            }
        },
        onRequestCameraPermission = {
            hasRequestedCameraPermission = true
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        },
        onOpenSystemSettings = { context.startActivity(appSettingsIntent(context)) },
        onOpenSettings = onOpenSettings,
        modifier = modifier,
        onGestureDiagnosticEvent = viewModel::onGestureDiagnosticEvent,
        diagnosticsOverlayEnabled = diagnosticsOverlayEnabled,
        diagnosticsOverlayInfo = diagnosticsOverlayInfo,
        onDiagnosticsOverlayToggled = viewModel::onDiagnosticsOverlayToggled,
        cameraPreview = { previewModifier ->
            CameraPreview(
                captureMode = uiState.captureMode,
                captureAspectRatio = uiState.captureAspectRatio,
                modifier = previewModifier,
                onImageCaptureReady = viewModel::attachImageCapture,
                onCameraReady = viewModel::attachCamera,
                onCameraDiagnostics = viewModel::onCameraDiagnostics,
            )
        },
    )
}

private fun appSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

private fun Context.findActivity(): ComponentActivity {
    var current = this
    while (current is ContextWrapper) {
        if (current is ComponentActivity) return current
        current = current.baseContext
    }
    error("CameraRoute must be hosted inside a ComponentActivity")
}
