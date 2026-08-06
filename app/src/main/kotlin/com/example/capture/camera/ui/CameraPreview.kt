package com.example.capture.camera.ui

import android.util.Rational
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.capture.camera.domain.CameraDiagnosticsSnapshot
import com.example.capture.camera.domain.CaptureAspectRatio
import com.example.capture.camera.domain.CaptureMode

/**
 * The only file in the app that touches CameraX's `Preview`/`ImageCapture` use cases directly.
 * Binding is keyed on [lifecycleOwner] rather than `Unit`, but `MainActivity` declares
 * `android:configChanges` for orientation, so the owner instance - and this binding - survives
 * rotation instead of being torn down and rebuilt, which is what keeps the camera state safe
 * across orientation changes.
 *
 * The app is locked to portrait (`android:screenOrientation="portrait"` on `MainActivity` - see
 * "UI requirements" in app-spec.md), so the window itself never rotates and `Configuration`/
 * `Display.getRotation()` stay constant regardless of how the device is physically held. Correct
 * capture rotation/EXIF orientation for a physically-rotated device therefore can't be tracked via
 * either of those - an [OrientationEventListener] (raw accelerometer-based degrees, independent of
 * the locked window) is used instead; see the "Orientation changes" section this satisfies.
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
    captureAspectRatio: CaptureAspectRatio,
    modifier: Modifier = Modifier,
    onImageCaptureReady: (ImageCapture?) -> Unit,
    onCameraReady: (Camera?) -> Unit,
    onCameraDiagnostics: (CameraDiagnosticsSnapshot) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }

    // Applied to both use cases (see "Capture Aspect Ratio and Preview Framing" in app-spec.md:
    // "apply the same selected aspect-ratio preference to both the CameraX Preview and
    // ImageCapture use cases"), so the preview stream and the captured photo request the same
    // sensor crop instead of two independently-negotiated resolutions.
    val aspectRatioStrategy = remember(captureAspectRatio) {
        when (captureAspectRatio) {
            CaptureAspectRatio.RATIO_4_3 -> AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            CaptureAspectRatio.RATIO_16_9 -> AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
        }
    }

    val previewUseCase = remember(captureAspectRatio) {
        Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspectRatioStrategy).build())
            .build()
            .apply { setSurfaceProvider { request -> surfaceRequest = request } }
    }
    // Burst Mode explicitly asks for CameraX's lowest-latency capture pipeline and a reduced
    // capture resolution (see "Capture Performance" in app-spec.md: full sensor resolution is
    // Single-Shot-only) - on-device measurement (see README's "Build verification") showed capture
    // hardware latency, not MediaStore I/O, dominates burst shot-to-shot cadence, and that latency
    // scales with pixel count. Single-Shot Mode leaves CameraX's own defaults alone rather than
    // assuming they already match. A built ImageCapture's capture mode/resolution selector can't be
    // changed afterward, so a capture-mode or aspect-ratio change rebuilds (and, below, rebinds) it
    // instead.
    val imageCaptureUseCase = remember(captureMode, captureAspectRatio) {
        ImageCapture.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(aspectRatioStrategy)
                    .apply {
                        if (captureMode == CaptureMode.BURST) {
                            val resolution = burstCaptureResolution(captureAspectRatio)
                            setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(resolution.width, resolution.height),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                ),
                            )
                        }
                    }
                    .build(),
            )
            .apply { if (captureMode == CaptureMode.BURST) setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY) }
            .build()
    }

    LaunchedEffect(lifecycleOwner, captureMode, captureAspectRatio) {
        val cameraProvider = ProcessCameraProvider.awaitInstance(context)
        cameraProvider.unbindAll()
        val rotation = ContextCompat.getDisplayOrDefault(context).rotation
        previewUseCase.targetRotation = rotation
        imageCaptureUseCase.targetRotation = rotation
        // A shared viewport ties the preview and capture use cases to the same effective crop
        // (see "Use a shared CameraX viewport and use-case group..." in app-spec.md), built from
        // the selected ratio itself - already known exactly as a fraction - rather than derived
        // from measured pixel dimensions, which would just reconstruct the same ratio imprecisely.
        val viewPort = ViewPort.Builder(
            Rational(captureAspectRatio.widthRatio, captureAspectRatio.heightRatio),
            rotation,
        ).build()
        val useCaseGroup = UseCaseGroup.Builder()
            .setViewPort(viewPort)
            .addUseCase(previewUseCase)
            .addUseCase(imageCaptureUseCase)
            .build()
        val camera = cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, useCaseGroup)
        onImageCaptureReady(imageCaptureUseCase)
        onCameraReady(camera)
        onCameraDiagnostics(
            cameraDiagnosticsSnapshot(previewUseCase, imageCaptureUseCase, captureAspectRatio, rotation, rotation, captureMode),
        )
    }

    // Keeps the ImageCapture use case's target rotation (and thus the captured photo's EXIF
    // orientation) in sync with how the device is physically held, even though the app's own
    // locked-portrait window never rotates and so never reports a Configuration/
    // Display.getRotation() change on its own - see "Orientation changes" in app-spec.md.
    // OrientationEventListener reads the raw accelerometer instead, independent of the window's
    // (locked) rotation. previewUseCase.targetRotation is deliberately left untouched here: the
    // physical camera sensor's long axis, the locked-portrait layout, and the preview container
    // are always aligned with the phone body regardless of how it's held, so the on-screen preview
    // must stay fixed - only the captured photo's orientation metadata should change.
    val orientationEventListener = remember {
        object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientationDegrees: Int) {
                if (orientationDegrees == ORIENTATION_UNKNOWN) return
                val captureRotation = surfaceRotationFor(orientationDegrees)
                if (captureRotation == imageCaptureUseCase.targetRotation) return
                imageCaptureUseCase.targetRotation = captureRotation
                val displayRotation = ContextCompat.getDisplayOrDefault(context).rotation
                onCameraDiagnostics(
                    cameraDiagnosticsSnapshot(
                        previewUseCase,
                        imageCaptureUseCase,
                        captureAspectRatio,
                        displayRotation,
                        captureRotation,
                        captureMode,
                    ),
                )
            }
        }
    }
    DisposableEffect(orientationEventListener) {
        orientationEventListener.enable()
        onDispose { orientationEventListener.disable() }
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

/**
 * A plain (non-`android.util.Size`) pixel-dimension pair, kept separate from [android.util.Size]
 * so [burstCaptureResolution] stays a plain top-level function a JVM unit test can call directly -
 * `android.util.Size`'s methods are stubbed in that environment the same way any other
 * `android.*` class's are (see [surfaceRotationFor] for the same reasoning applied to `Surface`
 * constants, which are plain `Int`s and don't have this problem).
 */
internal data class PixelSize(val width: Int, val height: Int)

/**
 * The reduced capture resolution Burst Mode requests instead of the sensor's full resolution (see
 * "Capture Performance" in app-spec.md) - chosen as a standard, recognizable resolution per aspect
 * ratio that's still clearly usable at normal viewing/sharing/small-print sizes while being a small
 * fraction of the ~12MP full-resolution pixel count that dominates capture latency.
 */
internal fun burstCaptureResolution(captureAspectRatio: CaptureAspectRatio): PixelSize = when (captureAspectRatio) {
    CaptureAspectRatio.RATIO_4_3 -> PixelSize(2048, 1536)
    CaptureAspectRatio.RATIO_16_9 -> PixelSize(1920, 1080)
}

/**
 * Maps [OrientationEventListener]'s raw accelerometer-based degrees (0-359, clockwise from the
 * device's natural/upright orientation) to the `Surface.ROTATION_*` constant CameraX's
 * `targetRotation` expects. `targetRotation` means "how much to rotate the output to appear
 * upright," which is the *inverse* of how far the device itself physically rotated - hence 90/270
 * being swapped relative to the raw degrees below; this is the standard mapping used by
 * CameraX/Camera2 samples for this exact purpose. [orientationDegrees] must not be
 * `OrientationEventListener.ORIENTATION_UNKNOWN`.
 */
internal fun surfaceRotationFor(orientationDegrees: Int): Int = when (orientationDegrees) {
    in 45 until 135 -> Surface.ROTATION_270
    in 135 until 225 -> Surface.ROTATION_180
    in 225 until 315 -> Surface.ROTATION_90
    else -> Surface.ROTATION_0
}

/**
 * The app always binds the single back camera (see [CameraSelector.DEFAULT_BACK_CAMERA] above -
 * there is no camera-switching feature), so [CameraDiagnosticsSnapshot.selectedCamera] is a fixed
 * label rather than something read off the bound [androidx.camera.core.Camera]. Resolutions come
 * from each use case's own `resolutionInfo`, which CameraX populates as soon as binding completes.
 */
private fun cameraDiagnosticsSnapshot(
    previewUseCase: Preview,
    imageCaptureUseCase: ImageCapture,
    captureAspectRatio: CaptureAspectRatio,
    displayRotation: Int,
    captureRotation: Int,
    captureMode: CaptureMode,
): CameraDiagnosticsSnapshot = CameraDiagnosticsSnapshot(
    timestampMillis = System.currentTimeMillis(),
    selectedCamera = "DEFAULT_BACK_CAMERA",
    previewResolutionPx = previewUseCase.resolutionInfo?.resolution?.let { "${it.width}x${it.height}" },
    captureResolutionPx = imageCaptureUseCase.resolutionInfo?.resolution?.let { "${it.width}x${it.height}" },
    requestedAspectRatio = captureAspectRatio,
    displayRotation = displayRotation,
    captureRotation = captureRotation,
    captureMode = captureMode,
)
