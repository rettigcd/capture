package com.example.capture.camera.domain

/**
 * A MediaStore row reserved for a photo before its pixel data has been written.
 *
 * [uriString] is the string form of the `content://` [android.net.Uri] MediaStore returned for
 * the reservation. It is kept as a plain `String` (rather than `android.net.Uri`) so this whole
 * capture/domain package stays a plain Kotlin module that plain JUnit tests can exercise without
 * an Android runtime or Robolectric; callers that need a real `Uri` (e.g. Compose UI code) parse
 * it at the point of use.
 */
data class PendingPhotoEntry(val uriString: String)

/** Outcome of asking the camera hardware to expose and encode a single frame. */
sealed interface CameraCaptureOutcome {
    data object Success : CameraCaptureOutcome
    data class Failure(
        val message: String,
        val cause: Throwable? = null,
        val reason: CaptureRejectionReason = CaptureRejectionReason.UNKNOWN,
    ) : CameraCaptureOutcome
}

/** Outcome of a full capture request, after camera capture and MediaStore finalization. */
sealed interface CaptureOutcome {
    data class Success(val uriString: String) : CaptureOutcome
    data class Failure(val userMessage: String) : CaptureOutcome
}

/** A structured, immutable record of one completed capture request. */
data class CaptureResult(
    val outcome: CaptureOutcome,
    val trigger: CaptureTrigger,
    val timestampMillis: Long,
    val attemptId: CaptureAttemptId,
)

/**
 * How many images a single capture command produces (see "Capture Mode" in app-spec.md).
 * Orthogonal to [CaptureTrigger]: the same touch/volume/voice trigger sources apply to both modes.
 */
enum class CaptureMode { SINGLE_SHOT, BURST }

/** Fixed number of images a Burst Mode capture command requests (see "Burst Mode" in app-spec.md). */
const val BURST_IMAGE_COUNT = 4

/** Coordinator-level state machine exposed to the UI layer. */
sealed interface CaptureState {
    data object Idle : CaptureState
    data class Capturing(val trigger: CaptureTrigger, val attemptId: CaptureAttemptId) : CaptureState
    data class Completed(val result: CaptureResult) : CaptureState

    /**
     * Emitted once, immediately when a burst is accepted - before any of its images are actually
     * captured - so the UI/haptics can react to "a burst was started" independent of whether any
     * individual image in it eventually succeeds (see "Burst Feedback" in app-spec.md).
     */
    data class BurstStarted(val trigger: CaptureTrigger, val attemptId: CaptureAttemptId) : CaptureState

    /** Emitted once all [BURST_IMAGE_COUNT] images have been attempted, in capture order. */
    data class BurstCompleted(val results: List<CaptureResult>, val trigger: CaptureTrigger) : CaptureState
}
