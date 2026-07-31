package com.example.capture.camera.domain

/** How a completed touch interaction on the camera screen was classified (see "Gesture Processing" in app-spec.md). */
enum class GestureClassification { TAP, SWIPE_LEFT, SWIPE_RIGHT, MOVEMENT_BELOW_SWIPE_THRESHOLD, CANCELLED }

/** Why a touch interaction did not run to a normal classification (see "Gesture Cancellation Diagnostics" in app-spec.md). */
enum class GestureCancellationReason {
    POINTER_EVENT_CONSUMED,
    GESTURE_CANCELLED,
    MOVEMENT_EXCEEDED_TAP_THRESHOLD,
    APPLICATION_STATE_CHANGED,
    CAMERA_TEMPORARILY_UNAVAILABLE,
    UNKNOWN,
}

/**
 * One entry in the gesture-processing trace described in "Gesture Events" in app-spec.md, reported
 * by `CameraScreen`'s full-screen tap/swipe detector for every touch interaction.
 */
sealed interface GestureDiagnosticEvent {
    val timestampMillis: Long

    /** The pointer went down; a new gesture is being tracked. */
    data class Detected(
        override val timestampMillis: Long,
        val downX: Float,
        val downY: Float,
    ) : GestureDiagnosticEvent

    /** The gesture ran to completion (pointer lifted) and was classified. */
    data class Classified(
        override val timestampMillis: Long,
        val downX: Float,
        val downY: Float,
        val upX: Float,
        val upY: Float,
        val durationMillis: Long,
        val totalDeltaX: Float,
        val totalDeltaY: Float,
        val totalDistance: Float,
        val touchSlopPx: Float,
        val swipeThresholdPx: Float,
        val classification: GestureClassification,
    ) : GestureDiagnosticEvent

    /** The classified gesture was acted upon (a tap requested a capture, a swipe toggled the overlay). */
    data class Accepted(
        override val timestampMillis: Long,
        val classification: GestureClassification,
    ) : GestureDiagnosticEvent

    /** The touch interaction ended without a normal classification (see "Gesture Cancellation Diagnostics" in app-spec.md). */
    data class Cancelled(
        override val timestampMillis: Long,
        val reason: GestureCancellationReason,
        val pointerEventConsumed: Boolean,
        val cancelledBeforeCompletion: Boolean,
        val uiComponent: String,
        val overlayVisible: Boolean,
        val touchCaptureEnabled: Boolean,
        val cameraAcceptingCaptureRequests: Boolean,
    ) : GestureDiagnosticEvent
}

/**
 * Abstraction over where [GestureDiagnosticEvent]s go. Implementations must be no-ops unless
 * running in a debug build (see "Gesture Diagnostics" in app-spec.md: "shall be disabled in
 * Release builds"), and must not block the caller - `CameraScreen`'s gesture detector calls this
 * on every touch interaction, and a slow logger would make the camera screen feel laggy.
 */
interface GestureDiagnosticsLogger {
    fun log(event: GestureDiagnosticEvent)
}
