package com.example.capture.camera.domain

/**
 * Abstraction over "make the camera hardware expose and encode one frame into [entry]".
 *
 * The production implementation ([com.example.capture.camera.data.CameraXCaptureController])
 * is the only place CameraX's `ImageCapture` use case is touched, which is what lets
 * [CaptureCoordinator] and its tests run entirely without a physical camera - tests substitute a
 * fake implementation of this interface instead.
 */
interface CameraCaptureController {
    suspend fun captureTo(entry: PendingPhotoEntry): CameraCaptureOutcome
}
