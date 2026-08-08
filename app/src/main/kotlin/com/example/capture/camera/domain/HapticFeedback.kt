package com.example.capture.camera.domain

/**
 * Abstraction over device haptics so [com.example.capture.camera.ui.CameraViewModel] can trigger
 * feedback without depending on `android.os.Vibrator` directly, and so tests can verify it fires
 * on a successful capture (and with what duration) without a real device.
 */
interface HapticFeedback {
    /**
     * A short pulse confirming a capture succeeded - meant to be felt, not looked at.
     * [durationMillis] comes from the user's vibration-duration setting. Also used for Video
     * Mode's recording-started feedback (see "Video Mode" in app-spec.md: "same vibration as
     * Burst Mode").
     */
    fun performCaptureSuccess(durationMillis: Long)

    /**
     * Two pulses confirming a Video Mode recording stopped - distinct from
     * [performCaptureSuccess] so stopping a recording is distinguishable by feel from starting
     * one (see "Video Mode" in app-spec.md). [durationMillis] comes from the user's
     * vibration-duration setting, same as [performCaptureSuccess].
     */
    fun performVideoStopped(durationMillis: Long)
}
