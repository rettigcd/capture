package com.example.capture.camera.domain

/**
 * Abstraction over "make the camera hardware start/stop recording video with audio".
 *
 * The production implementation ([com.example.capture.camera.data.CameraXVideoCaptureController])
 * is the only place CameraX's `VideoCapture`/`Recorder` use case is touched, which is what lets
 * [CaptureCoordinator] and its tests run entirely without a physical camera - tests substitute a
 * fake implementation of this interface instead. Unlike [CameraCaptureController], there is no
 * companion storage interface: CameraX's `Recorder` manages the MediaStore pending/finalize
 * lifecycle for video internally.
 */
interface VideoCaptureController {
    /** Starts recording to a new MediaStore video entry. */
    suspend fun startRecording(timestampMillis: Long, attemptId: CaptureAttemptId): VideoStartOutcome

    /** Stops the in-progress recording started by the most recent [startRecording] call. */
    suspend fun stopRecording(): VideoStopOutcome
}
