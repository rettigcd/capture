package com.example.capture.camera.ui

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.capture.BuildConfig
import com.example.capture.R
import com.example.capture.camera.domain.CaptureAspectRatio
import com.example.capture.camera.domain.GestureCancellationReason
import com.example.capture.camera.domain.GestureClassification
import com.example.capture.camera.domain.GestureDiagnosticEvent
import com.example.capture.camera.domain.previewRatio
import com.example.capture.permissions.PermissionStatus
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Stateless: every value it renders comes from [uiState] and every user action is reported
 * through a callback, so this composable can be exercised in tests without a real camera,
 * microphone, or permission system. [cameraPreview] is a slot so this file never touches CameraX
 * directly (see `CameraPreview.kt`) and so tests can substitute an empty/fake preview.
 */
@Composable
fun CameraScreen(
    uiState: CameraUiState,
    onScreenTouch: () -> Unit,
    onShutterButtonClick: () -> Unit,
    onVoiceTriggerToggle: (Boolean) -> Unit,
    onOverlayVisibilityChanged: (Boolean) -> Unit,
    onRequestCameraPermission: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onGestureDiagnosticEvent: (GestureDiagnosticEvent) -> Unit = {},
    diagnosticsOverlayEnabled: Boolean = false,
    diagnosticsOverlayInfo: DiagnosticsOverlayInfo = DiagnosticsOverlayInfo(),
    onDiagnosticsOverlayToggled: () -> Unit = {},
    cameraPreview: @Composable (Modifier) -> Unit = {},
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (uiState.cameraPermission == PermissionStatus.GRANTED) {
            GrantedCameraContent(
                uiState = uiState,
                onScreenTouch = onScreenTouch,
                onShutterButtonClick = onShutterButtonClick,
                onVoiceTriggerToggle = onVoiceTriggerToggle,
                onOverlayVisibilityChanged = onOverlayVisibilityChanged,
                onGestureDiagnosticEvent = onGestureDiagnosticEvent,
                diagnosticsOverlayEnabled = diagnosticsOverlayEnabled,
                diagnosticsOverlayInfo = diagnosticsOverlayInfo,
                cameraPreview = cameraPreview,
            )
        } else {
            CameraPermissionDeniedContent(
                status = uiState.cameraPermission,
                onRequestCameraPermission = onRequestCameraPermission,
                onOpenSystemSettings = onOpenSystemSettings,
            )
        }

        val settingsDescription = stringResource(R.string.settings_button_content_description)
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .semantics { contentDescription = settingsDescription },
        ) {
            Icon(Icons.Filled.Settings, contentDescription = null, tint = Color.White)
        }

        // Debug-only: never shown in a Release build (see "Debug Overlay" in app-spec.md), even
        // though the toggle state itself is plain in-memory ViewModel state - BuildConfig.DEBUG is
        // a compile-time constant, so R8 dead-code-eliminates this branch entirely in Release.
        if (BuildConfig.DEBUG) {
            val diagnosticsToggleDescription = stringResource(R.string.diagnostics_overlay_toggle_content_description)
            IconButton(
                onClick = onDiagnosticsOverlayToggled,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .semantics { contentDescription = diagnosticsToggleDescription },
            ) {
                Icon(Icons.Filled.BugReport, contentDescription = null, tint = Color.White)
            }
        }
    }
}

@Composable
private fun GrantedCameraContent(
    uiState: CameraUiState,
    onScreenTouch: () -> Unit,
    onShutterButtonClick: () -> Unit,
    onVoiceTriggerToggle: (Boolean) -> Unit,
    onOverlayVisibilityChanged: (Boolean) -> Unit,
    onGestureDiagnosticEvent: (GestureDiagnosticEvent) -> Unit,
    diagnosticsOverlayEnabled: Boolean,
    diagnosticsOverlayInfo: DiagnosticsOverlayInfo,
    cameraPreview: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        // 0f = fully over the preview (visible); widthPx = fully off the right edge (hidden).
        val offsetX = remember(widthPx) { Animatable(if (uiState.overlayVisible) 0f else widthPx) }
        val coroutineScope = rememberCoroutineScope()

        // Syncs the on-screen position with the persisted value whenever it changes from
        // something other than a drag settling here - most notably the first emission after the
        // real persisted value loads shortly after launch (state starts at CameraUiState()'s
        // default before that).
        LaunchedEffect(uiState.overlayVisible, widthPx) {
            val target = if (uiState.overlayVisible) 0f else widthPx
            if (offsetX.value != target) {
                offsetX.animateTo(target, animationSpec = tween(durationMillis = OVERLAY_ANIMATION_DURATION_MILLIS))
            }
        }

        // Full-screen tap-to-capture and horizontal-swipe-to-toggle-the-overlay, handled by one
        // gesture detector so a real drag (which cancels the tap) and a quick tap can't both fire
        // for the same touch. This layer spans the whole screen - not just the aspect-ratio-
        // constrained preview below - so gestures and taps also work over the letterboxed/
        // pillarboxed background and wherever the overlay currently covers (see "Capture Aspect
        // Ratio and Preview Framing" / "Overlay sizing" in app-spec.md: gestures and capture must
        // work across the full screen, not only within the preview area). Its background color
        // is what shows through the unused letterbox/pillarbox space around the preview.
        val previewDescription = stringResource(R.string.camera_preview_content_description)
        val configuration = LocalConfiguration.current
        val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val previewAspectRatio = uiState.captureAspectRatio.previewRatio(isPortrait)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .semantics { contentDescription = previewDescription }
                .pointerInput(widthPx) {
                    detectTapOrHorizontalSwipe(
                        onTap = onScreenTouch,
                        onDrag = { deltaX ->
                            coroutineScope.launch {
                                offsetX.snapTo((offsetX.value + deltaX).coerceIn(0f, widthPx))
                            }
                        },
                        onDragEnd = {
                            val shouldShow = offsetX.value < widthPx / 2
                            coroutineScope.launch {
                                offsetX.animateTo(
                                    if (shouldShow) 0f else widthPx,
                                    animationSpec = tween(durationMillis = OVERLAY_ANIMATION_DURATION_MILLIS),
                                )
                            }
                            onOverlayVisibilityChanged(shouldShow)
                        },
                        overlayVisible = uiState.overlayVisible,
                        cameraAcceptingCaptureRequests = uiState.captureStatus != CaptureStatusUi.Capturing,
                        onGestureEvent = onGestureDiagnosticEvent,
                    )
                },
        ) {
            // Centered and constrained to the selected capture aspect ratio - letterboxed or
            // pillarboxed rather than stretched to fill the screen. Always composed, even while
            // the overlay image above covers it, so CameraX stays bound and capture keeps working
            // exactly as if the preview were visible - the swipe only changes what's drawn on
            // screen, never whether the camera is running.
            Box(modifier = Modifier.align(Alignment.Center).aspectRatio(previewAspectRatio)) {
                cameraPreview(Modifier.fillMaxSize())
            }
        }

        if (uiState.overlayImageUriString != null) {
            val overlayImageDescription = stringResource(R.string.overlay_image_content_description)
            AsyncImage(
                model = uiState.overlayImageUriString,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                    .semantics { contentDescription = overlayImageDescription },
            )
        }

        // Hidden (not just covered) while the overlay is shown: both sit conceptually underneath
        // it, so the image fully hides them rather than the controls poking out from behind it.
        // Capture still works by tapping the overlay itself either way.
        if (!uiState.overlayVisible) {
            CaptureStatusIndicator(
                status = uiState.captureStatus,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp),
            )
        }

        VoiceTriggerControl(
            enabled = uiState.voiceTriggerEnabled,
            listening = uiState.voiceListening,
            errorMessage = uiState.voiceError,
            onToggle = onVoiceTriggerToggle,
            modifier = Modifier
                .align(Alignment.TopEnd)
                // Clears the gear icon rendered above this content in the outer CameraScreen Box.
                .padding(top = 72.dp, end = 16.dp),
        )

        if (!uiState.overlayVisible) {
            val shutterDescription = stringResource(R.string.shutter_button_content_description)
            FloatingActionButton(
                onClick = onShutterButtonClick,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
                    .size(72.dp)
                    .semantics { contentDescription = shutterDescription },
            ) {
                Icon(Icons.Filled.Camera, contentDescription = null)
            }
        }

        // Debug-only: see the equivalent BuildConfig.DEBUG gate in CameraScreen's kdoc/toggle
        // button. diagnosticsOverlayEnabled is itself only ever true in a debug build (the toggle
        // that flips it is only rendered there), but the BuildConfig check here is what actually
        // guarantees this never renders in Release, independent of that ViewModel state.
        if (BuildConfig.DEBUG && diagnosticsOverlayEnabled) {
            DiagnosticsOverlay(
                info = diagnosticsOverlayInfo,
                aspectRatio = uiState.captureAspectRatio,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
            )
        }
    }
}

/**
 * Disambiguates a quick tap (fires [onTap]) from a horizontal swipe (fires [onDrag] as the finger
 * moves, then [onDragEnd] on release) for a single pointer, so the two can share one full-screen
 * gesture surface without a real drag also completing as a tap. Movement in any direction past
 * touch slop counts as "dragging" (cancelling the tap, matching plain tap-gesture semantics), but
 * only the horizontal component is reported to [onDrag].
 *
 * Independently of that existing tap/drag behavior, every touch interaction is also classified and
 * reported through [onGestureEvent] for diagnostics (see "Gesture Processing"/"Gesture Events" in
 * app-spec.md) - a drag that never exceeds [SWIPE_THRESHOLD_DP] is still handled exactly as before
 * (whichever side of the midpoint it settles on), it is just additionally labelled
 * [GestureClassification.MOVEMENT_BELOW_SWIPE_THRESHOLD] rather than [GestureClassification.SWIPE_LEFT]/
 * [GestureClassification.SWIPE_RIGHT] in the diagnostic log.
 */
private suspend fun PointerInputScope.detectTapOrHorizontalSwipe(
    onTap: () -> Unit,
    onDrag: (deltaX: Float) -> Unit,
    onDragEnd: () -> Unit,
    overlayVisible: Boolean,
    cameraAcceptingCaptureRequests: Boolean,
    onGestureEvent: (GestureDiagnosticEvent) -> Unit,
) {
    val swipeThresholdPx = with(this) { SWIPE_THRESHOLD_DP.dp.toPx() }
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val downTimeMillis = System.currentTimeMillis()
        var pointerEventConsumed = down.isConsumed
        onGestureEvent(GestureDiagnosticEvent.Detected(downTimeMillis, down.position.x, down.position.y))
        var isDragging = false
        var totalDeltaX = 0f
        var totalDeltaY = 0f
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null) {
                onGestureEvent(
                    GestureDiagnosticEvent.Cancelled(
                        timestampMillis = System.currentTimeMillis(),
                        reason = GestureCancellationReason.GESTURE_CANCELLED,
                        pointerEventConsumed = pointerEventConsumed,
                        cancelledBeforeCompletion = true,
                        uiComponent = GESTURE_SURFACE_COMPONENT_NAME,
                        overlayVisible = overlayVisible,
                        touchCaptureEnabled = true,
                        cameraAcceptingCaptureRequests = cameraAcceptingCaptureRequests,
                    ),
                )
                break
            }
            if (change.isConsumed) pointerEventConsumed = true
            if (change.changedToUpIgnoreConsumed()) {
                val nowMillis = System.currentTimeMillis()
                val classification = when {
                    !isDragging -> GestureClassification.TAP
                    abs(totalDeltaX) < swipeThresholdPx -> GestureClassification.MOVEMENT_BELOW_SWIPE_THRESHOLD
                    totalDeltaX < 0 -> GestureClassification.SWIPE_LEFT
                    else -> GestureClassification.SWIPE_RIGHT
                }
                onGestureEvent(
                    GestureDiagnosticEvent.Classified(
                        timestampMillis = nowMillis,
                        downX = down.position.x,
                        downY = down.position.y,
                        upX = change.position.x,
                        upY = change.position.y,
                        durationMillis = nowMillis - downTimeMillis,
                        totalDeltaX = totalDeltaX,
                        totalDeltaY = totalDeltaY,
                        totalDistance = sqrt(totalDeltaX * totalDeltaX + totalDeltaY * totalDeltaY),
                        touchSlopPx = viewConfiguration.touchSlop,
                        swipeThresholdPx = swipeThresholdPx,
                        classification = classification,
                    ),
                )
                onGestureEvent(GestureDiagnosticEvent.Accepted(nowMillis, classification))
                if (isDragging) onDragEnd() else onTap()
                break
            }
            val delta = change.positionChange()
            totalDeltaX += delta.x
            totalDeltaY += delta.y
            if (!isDragging) {
                val totalDistance = sqrt(totalDeltaX * totalDeltaX + totalDeltaY * totalDeltaY)
                if (totalDistance > viewConfiguration.touchSlop) isDragging = true
            }
            if (isDragging) {
                change.consume()
                onDrag(delta.x)
            }
        }
    }
}

/** Identifies which UI layer produced a [GestureDiagnosticEvent.Cancelled] (see "Gesture Cancellation Diagnostics" in app-spec.md). */
private const val GESTURE_SURFACE_COMPONENT_NAME = "CameraScreen.fullScreenGestureSurface"

/** Diagnostic-only threshold distinct from the system touch-slop: doesn't change swipe-to-toggle-overlay behavior, only its classification label. */
private const val SWIPE_THRESHOLD_DP = 32

private const val OVERLAY_ANIMATION_DURATION_MILLIS = 200

@Composable
private fun DiagnosticsOverlay(info: DiagnosticsOverlayInfo, aspectRatio: CaptureAspectRatio, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.7f)),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text("Aspect ratio: ${aspectRatio.name}", color = Color.White)
            Text("Capture state: ${info.captureState}", color = Color.White)
            Text("Camera bound: ${info.cameraBound}", color = Color.White)
            Text("Last gesture: ${info.lastGestureClassification ?: "-"}", color = Color.White)
            val touch = info.lastTouchLocation
            Text(
                text = "Touch: ${if (touch != null) "${touch.first.roundToInt()},${touch.second.roundToInt()}" else "-"}",
                color = Color.White,
            )
            Text("Attempt id: ${info.lastCaptureAttemptId?.value?.take(ATTEMPT_ID_DISPLAY_LENGTH) ?: "-"}", color = Color.White)
            Text("Trigger: ${info.lastCaptureTriggerSource ?: "-"}", color = Color.White)
        }
    }
}

private const val ATTEMPT_ID_DISPLAY_LENGTH = 8

@Composable
private fun CaptureStatusIndicator(status: CaptureStatusUi, modifier: Modifier = Modifier) {
    // No error branch here: capture and file-saving errors are logged, not shown on screen - see
    // "Error Handling" in app-spec.md and CaptureStatusUi's kdoc. A failure simply falls back to
    // the idle text below.
    val text = when (status) {
        CaptureStatusUi.Idle -> stringResource(R.string.capture_status_idle)
        CaptureStatusUi.Capturing -> stringResource(R.string.capture_status_capturing)
        CaptureStatusUi.Saved -> stringResource(R.string.capture_status_saved)
    }
    Card(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (status == CaptureStatusUi.Capturing) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                Box(modifier = Modifier.size(8.dp))
            }
            Text(text = text)
        }
    }
}

@Composable
private fun VoiceTriggerControl(
    enabled: Boolean,
    listening: Boolean,
    errorMessage: String?,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(horizontalAlignment = Alignment.End, modifier = modifier) {
        Card {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                val listeningDescription = if (listening) {
                    stringResource(R.string.voice_listening_indicator)
                } else {
                    stringResource(R.string.voice_not_listening_indicator)
                }
                Icon(
                    imageVector = if (listening) Icons.Filled.Mic else Icons.Filled.MicOff,
                    contentDescription = listeningDescription,
                )
                val toggleDescription = stringResource(R.string.voice_toggle_content_description)
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.semantics {
                        contentDescription = toggleDescription
                    },
                )
            }
        }
        if (errorMessage != null) {
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
    }
}

@Composable
private fun CameraPermissionDeniedContent(
    status: PermissionStatus,
    onRequestCameraPermission: () -> Unit,
    onOpenSystemSettings: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            val message = when (status) {
                PermissionStatus.PERMANENTLY_DENIED -> stringResource(R.string.permission_camera_denied)
                else -> stringResource(R.string.permission_camera_rationale)
            }
            Text(
                text = message,
                color = Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            Box(modifier = Modifier.size(16.dp))
            if (status == PermissionStatus.PERMANENTLY_DENIED) {
                Button(onClick = onOpenSystemSettings) { Text(stringResource(R.string.open_system_settings)) }
            } else {
                Button(onClick = onRequestCameraPermission) { Text(stringResource(R.string.permission_camera_request)) }
            }
        }
    }
}
