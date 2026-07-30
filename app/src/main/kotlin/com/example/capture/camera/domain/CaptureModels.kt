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
    data class Failure(val message: String, val cause: Throwable? = null) : CameraCaptureOutcome
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
)

/** Coordinator-level state machine exposed to the UI layer. */
sealed interface CaptureState {
    data object Idle : CaptureState
    data class Capturing(val trigger: CaptureTrigger) : CaptureState
    data class Completed(val result: CaptureResult) : CaptureState
}
