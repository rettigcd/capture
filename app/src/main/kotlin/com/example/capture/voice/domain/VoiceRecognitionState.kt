package com.example.capture.voice.domain

/** Lifecycle/result state of a [VoiceCommandRecognizer]. */
sealed interface VoiceRecognitionState {
    /** Not currently listening and nothing to report. */
    data object Idle : VoiceRecognitionState

    /** Actively listening for one of the configured commands. */
    data object Listening : VoiceRecognitionState

    /** A configured command phrase was recognized. */
    data class CommandRecognized(val phrase: String) : VoiceRecognitionState

    /** The recognizer failed; [error] describes why. Recoverable - callers may retry. */
    data class Error(val error: VoiceRecognitionError) : VoiceRecognitionState

    /** No speech recognizer is available on this device at all. */
    data object Unavailable : VoiceRecognitionState
}

/** Reasons a [VoiceCommandRecognizer] can fail, kept Android-API-agnostic. */
enum class VoiceRecognitionError {
    NO_SPEECH_MATCH,
    AUDIO_ERROR,
    PERMISSION_DENIED,
    RECOGNIZER_BUSY,
    NETWORK_ERROR,
    CLIENT_ERROR,
    UNKNOWN,
}
