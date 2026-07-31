package com.example.capture.camera.domain

/**
 * Every way a user can ask for a photo to be taken. All three input paths described in
 * app-spec.md (touch, volume buttons, voice) resolve to one of these values and are then
 * routed through the single [CaptureCoordinator] - there is intentionally no separate
 * capture path per input source.
 */
sealed interface CaptureTrigger {
    data object ScreenTouch : CaptureTrigger
    data object ShutterButton : CaptureTrigger
    data object VolumeUp : CaptureTrigger
    data object VolumeDown : CaptureTrigger
    data class VoiceCommand(val phrase: String) : CaptureTrigger
}

/** Which category of input produced a capture attempt (see "Capture Request Processing" in app-spec.md). */
enum class CaptureTriggerSource { TOUCH, VOICE, VOLUME_BUTTON, SHUTTER_BUTTON, OTHER }

fun CaptureTrigger.toDiagnosticSource(): CaptureTriggerSource = when (this) {
    CaptureTrigger.ScreenTouch -> CaptureTriggerSource.TOUCH
    CaptureTrigger.ShutterButton -> CaptureTriggerSource.SHUTTER_BUTTON
    CaptureTrigger.VolumeUp, CaptureTrigger.VolumeDown -> CaptureTriggerSource.VOLUME_BUTTON
    is CaptureTrigger.VoiceCommand -> CaptureTriggerSource.VOICE
}
