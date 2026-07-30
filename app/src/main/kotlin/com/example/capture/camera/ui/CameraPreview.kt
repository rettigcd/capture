package com.example.capture.camera.ui

import androidx.camera.compose.CameraXViewfinder
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

/**
 * The only file in the app that touches CameraX's `Preview`/`ImageCapture` use cases directly.
 * Binding is keyed on [lifecycleOwner] rather than `Unit`, but `MainActivity` declares
 * `android:configChanges` for orientation, so the owner instance - and this binding - survives
 * rotation instead of being torn down and rebuilt, which is what keeps the camera state safe
 * across orientation changes.
 *
 * [onImageCaptureReady] hands the bound `ImageCapture` use case to
 * [com.example.capture.camera.data.ImageCaptureUseCaseHolder] (via `CameraViewModel`) so
 * [com.example.capture.camera.domain.CaptureCoordinator] - which knows nothing about Compose or
 * `LifecycleOwner`s - can trigger captures on it.
 */
@Composable
fun CameraPreview(modifier: Modifier = Modifier, onImageCaptureReady: (ImageCapture?) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    val previewUseCase = remember {
        Preview.Builder().build().apply {
            setSurfaceProvider { request -> surfaceRequest = request }
        }
    }
    val imageCaptureUseCase = remember { ImageCapture.Builder().build() }

    LaunchedEffect(lifecycleOwner) {
        val cameraProvider = ProcessCameraProvider.awaitInstance(context)
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            previewUseCase,
            imageCaptureUseCase,
        )
        onImageCaptureReady(imageCaptureUseCase)
    }

    DisposableEffect(Unit) {
        onDispose { onImageCaptureReady(null) }
    }

    surfaceRequest?.let { request ->
        CameraXViewfinder(surfaceRequest = request, modifier = modifier)
    }
}
