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

/**
 * Outcome of asking the camera hardware to expose and encode a single frame directly into memory
 * rather than a [PendingPhotoEntry]'s output stream - used by Burst Mode so MediaStore work never
 * sits between one image's capture and the next (see "Burst Mode" in app-spec.md).
 * [Success] is a plain `class`, not `data class`, since [ByteArray]'s structural `equals`/`hashCode`
 * would be misleading (array identity, not content, is what most callers actually want here).
 */
sealed interface CameraCaptureMemoryOutcome {
    class Success(val jpegBytes: ByteArray) : CameraCaptureMemoryOutcome
    data class Failure(
        val message: String,
        val cause: Throwable? = null,
        val reason: CaptureRejectionReason = CaptureRejectionReason.UNKNOWN,
    ) : CameraCaptureMemoryOutcome
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

    /**
     * Emitted once per image, right after its camera capture finishes (whether that succeeded or
     * failed - see "Error Handling" in app-spec.md: one image's failure doesn't stop the remaining
     * ones) - deliberately *not* after it's persisted to MediaStore, since [CaptureCoordinator]'s
     * capture phase is where nearly all of a burst's wall-clock time is actually spent, which is
     * what makes this a useful progress indicator (see "Capture Progress Indicator" in app-spec.md)
     * rather than four steps that all land within the same instant. [imagesCompleted] counts up from
     * 1 to [BURST_IMAGE_COUNT] inclusive.
     */
    data class BurstProgress(val imagesCompleted: Int, val trigger: CaptureTrigger, val attemptId: CaptureAttemptId) : CaptureState

    /** Emitted once all [BURST_IMAGE_COUNT] images have been attempted, in capture order. */
    data class BurstCompleted(val results: List<CaptureResult>, val trigger: CaptureTrigger) : CaptureState
}
