package com.example.capture.camera.domain

/**
 * Every way a user can ask for a photo to be taken. All three input paths described in
 * app-spec.md (touch, volume buttons, voice) resolve to one of these values and are then
 * routed through the single [CaptureCoordinator] - there is intentionally no separate
 * capture path per input source.
 */
sealed interface CaptureTrigger {
    data object ScreenTouch : CaptureTrigger
    data object VolumeUp : CaptureTrigger
    data object VolumeDown : CaptureTrigger
    data class VoiceCommand(val phrase: String) : CaptureTrigger
}
