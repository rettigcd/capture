package com.example.capture.camera.domain

/**
 * Every way a user can ask for a photo to be taken. All input paths described in app-spec.md
 * (screen tap - split into top/bottom halves, volume buttons, shutter button, voice) resolve to
 * one of these values and are then routed through the single [CaptureCoordinator] - there is
 * intentionally no separate capture path per input source.
 */
sealed interface CaptureTrigger {
    data object ScreenTouchTop : CaptureTrigger
    data object ScreenTouchBottom : CaptureTrigger
    data object ShutterButton : CaptureTrigger
    data object VolumeUp : CaptureTrigger
    data object VolumeDown : CaptureTrigger
    data class VoiceCommand(val phrase: String) : CaptureTrigger
}

/** Which category of input produced a capture attempt (see "Capture Request Processing" in app-spec.md). */
enum class CaptureTriggerSource { TOUCH, VOICE, VOLUME_BUTTON, SHUTTER_BUTTON, OTHER }

fun CaptureTrigger.toDiagnosticSource(): CaptureTriggerSource = when (this) {
    CaptureTrigger.ScreenTouchTop, CaptureTrigger.ScreenTouchBottom -> CaptureTriggerSource.TOUCH
    CaptureTrigger.ShutterButton -> CaptureTriggerSource.SHUTTER_BUTTON
    CaptureTrigger.VolumeUp, CaptureTrigger.VolumeDown -> CaptureTriggerSource.VOLUME_BUTTON
    is CaptureTrigger.VoiceCommand -> CaptureTriggerSource.VOICE
}

/**
 * Which of the six independently-configurable capture-mode settings (see "Capture Mode" in
 * app-spec.md) a given [CaptureTrigger] instance is governed by - a plain, payload-free identity
 * for settings-lookup purposes, distinct from [CaptureTrigger] itself since e.g. two different
 * [CaptureTrigger.VoiceCommand] instances (different phrases) both belong to the same
 * [VOICE_COMMAND] setting.
 */
enum class CaptureTriggerKind { SCREEN_TOP, SCREEN_BOTTOM, SHUTTER_BUTTON, VOLUME_UP, VOLUME_DOWN, VOICE_COMMAND }

fun CaptureTrigger.kind(): CaptureTriggerKind = when (this) {
    CaptureTrigger.ScreenTouchTop -> CaptureTriggerKind.SCREEN_TOP
    CaptureTrigger.ScreenTouchBottom -> CaptureTriggerKind.SCREEN_BOTTOM
    CaptureTrigger.ShutterButton -> CaptureTriggerKind.SHUTTER_BUTTON
    CaptureTrigger.VolumeUp -> CaptureTriggerKind.VOLUME_UP
    CaptureTrigger.VolumeDown -> CaptureTriggerKind.VOLUME_DOWN
    is CaptureTrigger.VoiceCommand -> CaptureTriggerKind.VOICE_COMMAND
}
