package com.example.capture.camera.domain

/**
 * Abstraction over device haptics so [com.example.capture.camera.ui.CameraViewModel] can trigger
 * feedback without depending on `android.os.Vibrator` directly, and so tests can verify it fires
 * on a successful capture (and with what duration) without a real device.
 */
interface HapticFeedback {
    /**
     * A short pulse confirming a capture succeeded - meant to be felt, not looked at.
     * [durationMillis] comes from the user's vibration-duration setting.
     */
    fun performCaptureSuccess(durationMillis: Long)
}
