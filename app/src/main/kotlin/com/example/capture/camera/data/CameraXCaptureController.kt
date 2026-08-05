package com.example.capture.camera.data

import android.content.Context
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.example.capture.camera.domain.CameraCaptureController
import com.example.capture.camera.domain.CameraCaptureMemoryOutcome
import com.example.capture.camera.domain.CameraCaptureOutcome
import com.example.capture.camera.domain.CaptureAttemptId
import com.example.capture.camera.domain.CaptureDiagnosticEvent
import com.example.capture.camera.domain.CaptureDiagnosticsLogger
import com.example.capture.camera.domain.CaptureRejectionReason
import com.example.capture.camera.domain.PendingPhotoEntry
import com.example.capture.common.TimeProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.FileNotFoundException
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * The only place `androidx.camera.core.ImageCapture` is invoked. [ImageCaptureUseCaseHolder]
 * supplies the currently-bound use case (owned and lifecycle-bound by `CameraPreview.kt`); this
 * class only adapts CameraX's callback-based `takePicture` API to a suspend function writing into
 * the `OutputStream` [com.example.capture.camera.domain.PhotoStorage] already reserved.
 */
class CameraXCaptureController @Inject constructor(
    private val imageCaptureUseCaseHolder: ImageCaptureUseCaseHolder,
    @ApplicationContext private val context: Context,
    private val diagnosticsLogger: CaptureDiagnosticsLogger,
    private val timeProvider: TimeProvider,
) : CameraCaptureController {

    override suspend fun captureTo(entry: PendingPhotoEntry, attemptId: CaptureAttemptId): CameraCaptureOutcome {
        val imageCapture = imageCaptureUseCaseHolder.imageCapture.value
            ?: return CameraCaptureOutcome.Failure(
                "Camera preview is not ready yet.",
                reason = CaptureRejectionReason.IMAGE_CAPTURE_UNAVAILABLE,
            )

        val outputStream = try {
            context.contentResolver.openOutputStream(entry.uriString.toUri())
        } catch (notFound: FileNotFoundException) {
            null
        } ?: return CameraCaptureOutcome.Failure("Could not open storage for the photo.")

        return outputStream.use { stream ->
            suspendCancellableCoroutine { continuation ->
                val outputOptions = ImageCapture.OutputFileOptions.Builder(stream).build()
                diagnosticsLogger.logEvent(
                    CaptureDiagnosticEvent.CameraXCaptureStarted(attemptId, timeProvider.currentTimeMillis()),
                )
                // A capture already in flight when the hardware shutter fires can't be cleanly
                // aborted mid-exposure, so cancellation here just stops waiting for the result;
                // it does not attempt to interrupt CameraX.
                imageCapture.takePicture(
                    outputOptions,
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            continuation.resume(CameraCaptureOutcome.Success)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            diagnosticsLogger.logEvent(
                                CaptureDiagnosticEvent.CameraXError(
                                    attemptId,
                                    timeProvider.currentTimeMillis(),
                                    exception.message ?: "Capture failed",
                                    CaptureRejectionReason.UNKNOWN,
                                ),
                            )
                            continuation.resume(
                                CameraCaptureOutcome.Failure(exception.message ?: "Capture failed", exception),
                            )
                        }
                    },
                )
            }
        }
    }

    override suspend fun captureToMemory(attemptId: CaptureAttemptId): CameraCaptureMemoryOutcome {
        val imageCapture = imageCaptureUseCaseHolder.imageCapture.value
            ?: return CameraCaptureMemoryOutcome.Failure(
                "Camera preview is not ready yet.",
                reason = CaptureRejectionReason.IMAGE_CAPTURE_UNAVAILABLE,
            )

        return suspendCancellableCoroutine { continuation ->
            diagnosticsLogger.logEvent(
                CaptureDiagnosticEvent.CameraXCaptureStarted(attemptId, timeProvider.currentTimeMillis()),
            )
            // A capture already in flight when the hardware shutter fires can't be cleanly
            // aborted mid-exposure, so cancellation here just stops waiting for the result;
            // it does not attempt to interrupt CameraX.
            imageCapture.takePicture(
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        // ImageProxy implements only java.lang.AutoCloseable, not
                        // java.io.Closeable, so Kotlin's `use` extension doesn't apply here.
                        val jpegBytes = try {
                            image.toJpegBytes()
                        } finally {
                            image.close()
                        }
                        continuation.resume(CameraCaptureMemoryOutcome.Success(jpegBytes))
                    }

                    override fun onError(exception: ImageCaptureException) {
                        diagnosticsLogger.logEvent(
                            CaptureDiagnosticEvent.CameraXError(
                                attemptId,
                                timeProvider.currentTimeMillis(),
                                exception.message ?: "Capture failed",
                                CaptureRejectionReason.UNKNOWN,
                            ),
                        )
                        continuation.resume(
                            CameraCaptureMemoryOutcome.Failure(exception.message ?: "Capture failed", exception),
                        )
                    }
                },
            )
        }
    }

    /** The single JPEG plane CameraX's default (compressed) capture format produces. */
    private fun ImageProxy.toJpegBytes(): ByteArray {
        val buffer = planes[0].buffer
        return ByteArray(buffer.remaining()).also(buffer::get)
    }
}
