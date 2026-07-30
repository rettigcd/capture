package com.example.capture.camera.ui

import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.capture.camera.domain.CaptureMode

/**
 * The only file in the app that touches CameraX's `Preview`/`ImageCapture` use cases directly.
 * Binding is keyed on [lifecycleOwner] rather than `Unit`, but `MainActivity` declares
 * `android:configChanges` for orientation, so the owner instance - and this binding - survives
 * rotation instead of being torn down and rebuilt, which is what keeps the camera state safe
 * across orientation changes.
 *
 * [onImageCaptureReady] hands the bound `ImageCapture` use case to
 * [com.example.capture.camera.data.ImageCaptureUseCaseHolder], and [onCameraReady] hands the bound
 * `Camera` to [com.example.capture.camera.data.CameraControlHolder] (both via `CameraViewModel`),
 * so [com.example.capture.camera.domain.CaptureCoordinator] and
 * [com.example.capture.camera.domain.FlashTorchController] - which know nothing about Compose or
 * `LifecycleOwner`s - can act on them.
 */
@Composable
fun CameraPreview(
    captureMode: CaptureMode,
    modifier: Modifier = Modifier,
    onImageCaptureReady: (ImageCapture?) -> Unit,
    onCameraReady: (Camera?) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    val previewUseCase = remember {
        Preview.Builder().build().apply {
            setSurfaceProvider { request -> surfaceRequest = request }
        }
    }
    // Burst Mode explicitly asks for CameraX's lowest-latency capture pipeline (see "Capture
    // Performance" in app-spec.md); Single-Shot Mode leaves CameraX's own default alone rather
    // than assuming it already matches. A built ImageCapture's capture mode can't be changed
    // afterward, so a capture-mode change rebuilds (and, below, rebinds) it instead.
    val imageCaptureUseCase = remember(captureMode) {
        ImageCapture.Builder()
            .apply { if (captureMode == CaptureMode.BURST) setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY) }
            .build()
    }

    LaunchedEffect(lifecycleOwner, captureMode) {
        val cameraProvider = ProcessCameraProvider.awaitInstance(context)
        cameraProvider.unbindAll()
        val camera = cameraProvider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            previewUseCase,
            imageCaptureUseCase,
        )
        onImageCaptureReady(imageCaptureUseCase)
        onCameraReady(camera)
    }

    DisposableEffect(Unit) {
        onDispose {
            onImageCaptureReady(null)
            onCameraReady(null)
        }
    }

    surfaceRequest?.let { request ->
        CameraXViewfinder(surfaceRequest = request, modifier = modifier)
    }
}
