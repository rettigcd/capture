package com.example.capture.camera.data

import android.content.Context
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.example.capture.camera.domain.CameraCaptureController
import com.example.capture.camera.domain.CameraCaptureOutcome
import com.example.capture.camera.domain.PendingPhotoEntry
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
) : CameraCaptureController {

    override suspend fun captureTo(entry: PendingPhotoEntry): CameraCaptureOutcome {
        val imageCapture = imageCaptureUseCaseHolder.imageCapture.value
            ?: return CameraCaptureOutcome.Failure("Camera preview is not ready yet.")

        val outputStream = try {
            context.contentResolver.openOutputStream(entry.uriString.toUri())
        } catch (notFound: FileNotFoundException) {
            null
        } ?: return CameraCaptureOutcome.Failure("Could not open storage for the photo.")

        return outputStream.use { stream ->
            suspendCancellableCoroutine { continuation ->
                val outputOptions = ImageCapture.OutputFileOptions.Builder(stream).build()
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
                            continuation.resume(
                                CameraCaptureOutcome.Failure(exception.message ?: "Capture failed", exception),
                            )
                        }
                    },
                )
            }
        }
    }
}
