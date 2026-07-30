package com.example.capture.voice.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction over "listen for a small vocabulary of spoken commands".
 *
 * The initial implementation ([com.example.capture.voice.data.AndroidSpeechRecognizerAdapter])
 * wraps [android.speech.SpeechRecognizer]. Every Android-specific speech API is isolated behind
 * this interface so it can later be swapped for an offline/continuous keyword-spotting engine
 * without touching [com.example.capture.camera.domain.CaptureCoordinator] or the UI layer.
 *
 * ### Limitations of the built-in implementation
 * Android's `SpeechRecognizer` is **not** designed for reliable, indefinite always-on listening:
 * a single recognition session ends after a short pause in speech or a timeout, so the adapter
 * must restart it repeatedly to approximate continuous listening. See the adapter's kdoc and the
 * README's "Voice-recognition limitations" section for battery, privacy, network, and lifecycle
 * details.
 */
interface VoiceCommandRecognizer {
    val state: StateFlow<VoiceRecognitionState>

    /** Begins (or resumes) listening. Safe to call again while already listening. */
    suspend fun start()

    /** Stops listening and releases any held recognizer resources. */
    suspend fun stop()
}
