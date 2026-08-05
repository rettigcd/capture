package com.example.capture.camera.domain

/**
 * Abstraction over "make the camera hardware expose and encode one frame".
 *
 * The production implementation ([com.example.capture.camera.data.CameraXCaptureController])
 * is the only place CameraX's `ImageCapture` use case is touched, which is what lets
 * [CaptureCoordinator] and its tests run entirely without a physical camera - tests substitute a
 * fake implementation of this interface instead.
 */
interface CameraCaptureController {
    /** Encodes the frame into [entry]'s reserved MediaStore output stream. Used by Single-Shot Mode. */
    suspend fun captureTo(entry: PendingPhotoEntry, attemptId: CaptureAttemptId): CameraCaptureOutcome

    /**
     * Encodes the frame into memory instead of a [PendingPhotoEntry], so a caller can defer all
     * MediaStore work until later. Used by Burst Mode (see "Burst Mode" in app-spec.md) so the
     * shot-to-shot cadence isn't paced by MediaStore IPC/disk writes on top of the camera hardware's
     * own capture latency.
     */
    suspend fun captureToMemory(attemptId: CaptureAttemptId): CameraCaptureMemoryOutcome
}
