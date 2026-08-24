package com.example.capture.camera.ui

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.platform.testTag
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
import com.example.capture.settings.domain.AppSettings
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
    onScreenTouch: (isTopHalf: Boolean) -> Unit,
    onShutterButtonClick: () -> Unit,
    onVoiceTriggerToggle: (Boolean) -> Unit,
    onOverlayVisibilityChanged: (Boolean) -> Unit,
    onCoverPhotoCycleRequested: () -> Unit,
    onCaptureAspectRatioChanged: (CaptureAspectRatio) -> Unit,
    onZoomLevelChanged: (Int) -> Unit,
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
                onOverlayVisibilityChanged = onOverlayVisibilityChanged,
                onCoverPhotoCycleRequested = onCoverPhotoCycleRequested,
                onCaptureAspectRatioChanged = onCaptureAspectRatioChanged,
                onZoomLevelChanged = onZoomLevelChanged,
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
                // statusBarsPadding() keeps this clear of the status bar/notch on any device -
                // without it, Android 15+ (API 35+) enforces edge-to-edge by default for this
                // app's targetSdk (37) regardless of anything in MainActivity, so content draws
                // behind system bars unless it insets itself; the regular 8.dp is just the usual
                // breathing room from that inset.
                .statusBarsPadding()
                .padding(8.dp)
                .semantics { contentDescription = settingsDescription },
        ) {
            Icon(Icons.Filled.Settings, contentDescription = null, tint = Color.White.copy(alpha = 0.75f))
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
                    .statusBarsPadding()
                    .padding(8.dp)
                    .semantics { contentDescription = diagnosticsToggleDescription },
            ) {
                Icon(Icons.Filled.BugReport, contentDescription = null, tint = Color.White.copy(alpha = 0.75f))
            }
        }
    }
}

@Composable
private fun GrantedCameraContent(
    uiState: CameraUiState,
    onScreenTouch: (isTopHalf: Boolean) -> Unit,
    onShutterButtonClick: () -> Unit,
    onOverlayVisibilityChanged: (Boolean) -> Unit,
    onCoverPhotoCycleRequested: () -> Unit,
    onCaptureAspectRatioChanged: (CaptureAspectRatio) -> Unit,
    onZoomLevelChanged: (Int) -> Unit,
    onGestureDiagnosticEvent: (GestureDiagnosticEvent) -> Unit,
    diagnosticsOverlayEnabled: Boolean,
    diagnosticsOverlayInfo: DiagnosticsOverlayInfo,
    cameraPreview: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        // Gates only the "cycle to the next cover photo" swipe (see onDragEnd below) - unlike
        // showing/dismissing the overlay, that action has no offsetX-driven distance requirement
        // of its own (offsetX sits pinned at 0 the whole time), so a mostly-vertical swipe with a
        // little incidental horizontal drift could otherwise clear touch slop and misfire as a
        // leftward swipe. Reuses SWIPE_THRESHOLD_DP, the same distance already used to classify a
        // drag as Swipe Left/Right for diagnostics, so cycling now requires an actual Swipe Left.
        val coverPhotoCycleSwipeThresholdPx = with(density) { SWIPE_THRESHOLD_DP.dp.toPx() }
        // 0f = fully over the preview (visible); widthPx = fully off the right edge (hidden).
        val offsetX = remember(widthPx) { Animatable(if (uiState.overlayVisible) 0f else widthPx) }
        val coroutineScope = rememberCoroutineScope()
        // detectTapOrHorizontalSwipe below runs inside .pointerInput(widthPx) { ... }, which only
        // restarts when widthPx changes - effectively never, since the screen size is fixed. A
        // plain `uiState` reference captured by onDragEnd's closure would therefore stay frozen at
        // whatever it was when the gesture detector was first installed, not the live value at the
        // moment a swipe actually completes. rememberUpdatedState keeps latestUiState current
        // across recompositions regardless.
        val latestUiState by rememberUpdatedState(uiState)

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
                        onDragEnd = { totalDeltaX ->
                            // An additional left swipe while Overlay View is already fully shown
                            // (offsetX sat at 0 for the whole drag, so it's still 0 here) cycles to
                            // the next cover photo instead of re-committing visibility - see "Cover
                            // photo visibility" in app-spec.md. With zero or one cover photo
                            // configured, onCoverPhotoCycleRequested is a no-op and this falls
                            // through to the ordinary show/hide handling below (harmlessly
                            // re-committing the same visibility). Requires an actual leftward swipe
                            // past coverPhotoCycleSwipeThresholdPx, not just enough movement to
                            // clear touch slop, so a mostly-vertical swipe with a little incidental
                            // horizontal drift doesn't misfire as a cycle request.
                            val draggedLeft = totalDeltaX < 0
                            if (
                                latestUiState.overlayVisible &&
                                draggedLeft &&
                                latestUiState.coverPhotoCount > 1 &&
                                abs(totalDeltaX) >= coverPhotoCycleSwipeThresholdPx
                            ) {
                                onCoverPhotoCycleRequested()
                            } else {
                                val shouldShow = offsetX.value < widthPx / 2
                                coroutineScope.launch {
                                    offsetX.animateTo(
                                        if (shouldShow) 0f else widthPx,
                                        animationSpec = tween(durationMillis = OVERLAY_ANIMATION_DURATION_MILLIS),
                                    )
                                }
                                onOverlayVisibilityChanged(shouldShow)
                            }
                        },
                        overlayVisible = uiState.overlayVisible,
                        cameraAcceptingCaptureRequests = uiState.captureProgress == CaptureProgressUi.Hidden,
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

        if (uiState.activeCoverPhotoUriString != null) {
            val overlayImageDescription = stringResource(R.string.overlay_image_content_description)
            AsyncImage(
                model = uiState.activeCoverPhotoUriString,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                    .semantics { contentDescription = overlayImageDescription },
            )
        }

        // Composed after (so drawn on top of) the overlay image above - see "Capture Progress
        // Indicator" in app-spec.md: this must stay visible even while the privacy overlay is
        // covering the preview, not hide along with it.
        if (uiState.captureProgress != CaptureProgressUi.Hidden) {
            CaptureProgressIndicator(
                progress = uiState.captureProgress,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Compact capture-aspect-ratio and camera-zoom controls (see "Camera zoom" and "Capture
        // Aspect Ratio and Preview Framing" in app-spec.md) - moved off the settings screen since
        // both are adjusted often enough while framing a shot that a trip to settings would be
        // disruptive. Positioned just above the shutter button and hidden along with it while
        // Overlay View is shown, so neither sits on top of the cover photo.
        if (!uiState.overlayVisible) {
            CompactCameraControls(
                aspectRatio = uiState.captureAspectRatio,
                onAspectRatioChanged = onCaptureAspectRatioChanged,
                zoomLevel = uiState.zoomLevel,
                onZoomLevelChanged = onZoomLevelChanged,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = COMPACT_CONTROLS_BOTTOM_PADDING.dp),
            )
        }

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
 * Every touch interaction is also classified and reported through [onGestureEvent] for diagnostics
 * (see "Gesture Processing"/"Gesture Events" in app-spec.md); [onDragEnd] receives the drag's raw
 * total horizontal delta, rather than just its direction, so a caller can apply its own distance
 * threshold to a specific action - [SWIPE_THRESHOLD_DP] is the same distance used to separate
 * [GestureClassification.MOVEMENT_BELOW_SWIPE_THRESHOLD] from [GestureClassification.SWIPE_LEFT]/
 * [GestureClassification.SWIPE_RIGHT] in the diagnostic log.
 *
 * [onTap] receives whether the tap landed in the top or bottom half of this pointer input area's
 * full height (literal screen halves, independent of the letterboxed/pillarboxed preview area or
 * which capture aspect ratio is selected, and the same whether the live preview or the privacy
 * overlay image is currently shown - see "Capture Mode" in app-spec.md), since each half is now an
 * independently-configurable capture trigger.
 */
private suspend fun PointerInputScope.detectTapOrHorizontalSwipe(
    onTap: (isTopHalf: Boolean) -> Unit,
    onDrag: (deltaX: Float) -> Unit,
    onDragEnd: (totalDeltaX: Float) -> Unit,
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
                if (isDragging) onDragEnd(totalDeltaX) else onTap(down.position.y < size.height / 2f)
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

/**
 * Distance threshold distinct from the system touch-slop, used both to label a drag Swipe
 * Left/Right vs. Movement Below Swipe Threshold in diagnostics, and to gate the cover-photo-cycle
 * swipe in [GrantedCameraContent] (so a mostly-vertical swipe with a little incidental horizontal
 * drift doesn't misfire as a cycle request) - see "Overlay gestures" in app-spec.md.
 */
private const val SWIPE_THRESHOLD_DP = 64

private const val OVERLAY_ANIMATION_DURATION_MILLIS = 200

/** Clears the shutter FAB's own `bottom = 32.dp` padding plus its `72.dp` size, with an 8.dp gap. */
private const val COMPACT_CONTROLS_BOTTOM_PADDING = 112

/**
 * Compact capture-aspect-ratio and camera-zoom controls (see "Camera zoom" and "Capture Aspect
 * Ratio and Preview Framing" in app-spec.md), moved off the settings screen since both are
 * adjusted often enough while framing a shot. Sized to take up as little of the live preview as
 * practical - small segmented buttons with no separate label text above them, side by side in one
 * row rather than stacked - unlike their old settings-screen versions (a full-width slider with a
 * "Zoom: Nx" label, and a labelled segmented row).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactCameraControls(
    aspectRatio: CaptureAspectRatio,
    onAspectRatioChanged: (CaptureAspectRatio) -> Unit,
    zoomLevel: Int,
    onZoomLevelChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        SingleChoiceSegmentedButtonRow {
            SegmentedButton(
                selected = aspectRatio == CaptureAspectRatio.RATIO_4_3,
                onClick = { onAspectRatioChanged(CaptureAspectRatio.RATIO_4_3) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                modifier = Modifier.testTag("camera_aspect_ratio_4_3"),
            ) {
                Text(stringResource(R.string.settings_aspect_ratio_4_3), style = MaterialTheme.typography.labelSmall)
            }
            SegmentedButton(
                selected = aspectRatio == CaptureAspectRatio.RATIO_16_9,
                onClick = { onAspectRatioChanged(CaptureAspectRatio.RATIO_16_9) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                modifier = Modifier.testTag("camera_aspect_ratio_16_9"),
            ) {
                Text(stringResource(R.string.settings_aspect_ratio_16_9), style = MaterialTheme.typography.labelSmall)
            }
        }
        SingleChoiceSegmentedButtonRow {
            val zoomRange = AppSettings.ZOOM_LEVEL_RANGE
            val zoomCount = zoomRange.last - zoomRange.first + 1
            for (level in zoomRange) {
                SegmentedButton(
                    selected = zoomLevel == level,
                    onClick = { onZoomLevelChanged(level) },
                    shape = SegmentedButtonDefaults.itemShape(index = level - zoomRange.first, count = zoomCount),
                    modifier = Modifier.testTag("camera_zoom_${level}x"),
                ) {
                    Text(
                        stringResource(R.string.camera_zoom_level_label, level),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

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

/**
 * Standalone progress control shown above the privacy overlay (see "Capture Progress Indicator" in
 * app-spec.md) - an indeterminate spinner for the whole of Single-Shot Mode's capture, or a
 * determinate one that fills in [CaptureProgressUi.Determinate.totalSteps] discrete steps as each
 * Burst Mode image finishes. Unlike the small textual status indicator this replaced, it
 * deliberately stays visible while the privacy overlay is shown (see its call site in
 * [GrantedCameraContent]) rather than hiding along with it.
 */
@Composable
private fun CaptureProgressIndicator(progress: CaptureProgressUi, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.capture_progress_indicator_content_description)
    Card(
        modifier = modifier.semantics {
            contentDescription = description
            liveRegion = LiveRegionMode.Polite
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Box(modifier = Modifier.padding(24.dp), contentAlignment = Alignment.Center) {
            when (progress) {
                CaptureProgressUi.Indeterminate -> CircularProgressIndicator()
                is CaptureProgressUi.Determinate -> CircularProgressIndicator(
                    progress = { progress.completedSteps.toFloat() / progress.totalSteps },
                )
                CaptureProgressUi.Hidden -> Unit
            }
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
