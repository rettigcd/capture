package com.example.capture.camera.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.provider.MediaStore
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.example.capture.camera.domain.CaptureAttemptId
import com.example.capture.camera.domain.VideoCaptureController
import com.example.capture.camera.domain.VideoStartOutcome
import com.example.capture.camera.domain.VideoStopOutcome
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/**
 * The only place `androidx.camera.video.Recorder`/`VideoCapture` is invoked.
 * [VideoCaptureUseCaseHolder] supplies the currently-bound use case (owned and lifecycle-bound by
 * `CameraPreview.kt`); this class adapts CameraX's event-callback-based recording API to
 * [VideoCaptureController]'s start/stop suspend functions. Unlike photo capture, there is no
 * separate storage interface here - `Recorder.prepareRecording`'s `MediaStoreOutputOptions`
 * handles the pending/finalize MediaStore row lifecycle internally.
 *
 * Audio is included only if `RECORD_AUDIO` is currently granted; recording proceeds silently
 * (rather than failing, or triggering a new permission-request flow) if it is not - the
 * permission is already requested elsewhere for voice triggering, but Video Mode does not require
 * it (see "Video Mode" in app-spec.md).
 */
class CameraXVideoCaptureController @Inject constructor(
    private val videoCaptureUseCaseHolder: VideoCaptureUseCaseHolder,
    @ApplicationContext private val context: Context,
) : VideoCaptureController {

    @Volatile
    private var activeRecording: Recording? = null

    @Volatile
    private var finalizeDeferred: CompletableDeferred<VideoRecordEvent.Finalize>? = null

    override suspend fun startRecording(timestampMillis: Long, attemptId: CaptureAttemptId): VideoStartOutcome {
        val videoCapture = videoCaptureUseCaseHolder.videoCapture.value
            ?: return VideoStartOutcome.Failure("Camera preview is not ready yet.")

        val instant = Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault())
        val displayName = "VID_${FILENAME_FORMATTER.format(instant)}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$SUBFOLDER")
        }
        val outputOptions = MediaStoreOutputOptions.Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()

        val audioGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val pendingRecording = videoCapture.output.prepareRecording(context, outputOptions)
            .apply { if (audioGranted) withAudioEnabled() }

        val deferred = CompletableDeferred<VideoRecordEvent.Finalize>()
        return try {
            activeRecording = pendingRecording.start(ContextCompat.getMainExecutor(context)) { event ->
                if (event is VideoRecordEvent.Finalize) deferred.complete(event)
            }
            finalizeDeferred = deferred
            VideoStartOutcome.Started
        } catch (error: RuntimeException) {
            VideoStartOutcome.Failure(error.message ?: "Could not start recording", error)
        }
    }

    override suspend fun stopRecording(): VideoStopOutcome {
        val recording = activeRecording ?: return VideoStopOutcome.Failure("No recording in progress.")
        val deferred = finalizeDeferred ?: return VideoStopOutcome.Failure("No recording in progress.")
        recording.stop()
        val finalizeEvent = deferred.await()
        activeRecording = null
        finalizeDeferred = null
        return if (finalizeEvent.hasError()) {
            VideoStopOutcome.Failure(finalizeEvent.cause?.message ?: "Recording failed", finalizeEvent.cause)
        } else {
            VideoStopOutcome.Success(finalizeEvent.outputResults.outputUri.toString())
        }
    }

    private companion object {
        const val SUBFOLDER = "Capture"
        val FILENAME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmssSSS", Locale.US)
    }
}
