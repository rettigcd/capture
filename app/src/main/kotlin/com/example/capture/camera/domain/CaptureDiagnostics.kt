package com.example.capture.camera.domain

/** Why [CaptureCoordinator] declined to act on, or failed while acting on, a capture request (see "Capture Request Processing" in app-spec.md). */
enum class CaptureRejectionReason {
    CAMERA_NOT_READY,
    CAMERA_REBINDING,
    CAPTURE_ALREADY_RUNNING,
    BURST_ALREADY_RUNNING,
    IMAGE_CAPTURE_UNAVAILABLE,
    APPLICATION_INACTIVE,
    UNKNOWN,
}

/**
 * One entry in the capture-processing trace described in "Capture Events" / "Diagnostic
 * Correlation" in app-spec.md. Every event for the same capture attempt shares [attemptId], so a
 * complete request's path - accepted or rejected - can be reconstructed from the log alone.
 */
sealed interface CaptureDiagnosticEvent {
    val attemptId: CaptureAttemptId
    val timestampMillis: Long

    data class Requested(
        override val attemptId: CaptureAttemptId,
        override val timestampMillis: Long,
        val triggerSource: CaptureTriggerSource,
        val captureMode: CaptureMode,
        val captureAspectRatio: CaptureAspectRatio,
    ) : CaptureDiagnosticEvent

    data class Accepted(
        override val attemptId: CaptureAttemptId,
        override val timestampMillis: Long,
    ) : CaptureDiagnosticEvent

    data class Rejected(
        override val attemptId: CaptureAttemptId,
        override val timestampMillis: Long,
        val reason: CaptureRejectionReason,
    ) : CaptureDiagnosticEvent

    data class CameraXRequestSubmitted(
        override val attemptId: CaptureAttemptId,
        override val timestampMillis: Long,
        val burstImageNumber: Int?,
    ) : CaptureDiagnosticEvent

    data class CameraXCaptureStarted(
        override val attemptId: CaptureAttemptId,
        override val timestampMillis: Long,
    ) : CaptureDiagnosticEvent

    data class ImageSaved(
        override val attemptId: CaptureAttemptId,
        override val timestampMillis: Long,
        val outputDestination: String,
    ) : CaptureDiagnosticEvent

    data class Completed(
        override val attemptId: CaptureAttemptId,
        override val timestampMillis: Long,
        val success: Boolean,
    ) : CaptureDiagnosticEvent

    data class CameraXError(
        override val attemptId: CaptureAttemptId,
        override val timestampMillis: Long,
        val errorMessage: String,
        val reason: CaptureRejectionReason,
    ) : CaptureDiagnosticEvent
}

/**
 * Point-in-time snapshot of the bound camera, logged whenever it changes (initial bind, or a
 * capture-rotation bucket change) - see "Camera Diagnostics" in app-spec.md. Resolutions are
 * formatted "WxH" (or `null` if CameraX hasn't reported one yet). [displayRotation] is fixed (the
 * app is locked to portrait, so the preview and viewport never rotate); [captureRotation] tracks
 * the physical device's rotation and only affects the captured photo's EXIF orientation - see
 * "Orientation changes" in app-spec.md.
 */
data class CameraDiagnosticsSnapshot(
    val timestampMillis: Long,
    val selectedCamera: String,
    val previewResolutionPx: String?,
    val captureResolutionPx: String?,
    val requestedAspectRatio: CaptureAspectRatio,
    val displayRotation: Int,
    val captureRotation: Int,
    val captureMode: CaptureMode,
)

/**
 * Abstraction over where [CaptureDiagnosticEvent]s and [CameraDiagnosticsSnapshot]s go. Unlike
 * [CaptureErrorLogger]/[CaptureMetadataLogger] this is not `suspend`: it is called many times per
 * capture attempt (up to eight events per image, times [BURST_IMAGE_COUNT] in a burst), so
 * implementations must not add I/O latency to the capture path - see [GestureDiagnosticsLogger]'s
 * kdoc for the equivalent reasoning on the gesture side.
 */
interface CaptureDiagnosticsLogger {
    fun logEvent(event: CaptureDiagnosticEvent)
    fun logCameraState(snapshot: CameraDiagnosticsSnapshot)
}
