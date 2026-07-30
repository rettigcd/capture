package com.example.capture.voice.data

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.example.capture.common.DispatcherProvider
import com.example.capture.voice.domain.VoiceCommandMatcher
import com.example.capture.voice.domain.VoiceCommandRecognizer
import com.example.capture.voice.domain.VoiceRecognitionError
import com.example.capture.voice.domain.VoiceRecognitionState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps [SpeechRecognizer] behind [VoiceCommandRecognizer]. This is the only class in the app
 * that imports `android.speech.*`, so it can be replaced later with an offline/continuous
 * keyword-spotting engine without touching [com.example.capture.camera.domain.CaptureCoordinator]
 * or any UI code.
 *
 * ### Limitations (see also README "Voice-recognition limitations")
 * - **Not indefinite always-on listening.** A single [SpeechRecognizer] session ends after a
 *   short pause in speech or a system timeout. What looks like continuous listening here is this
 *   adapter starting a brand-new session after every result/error - there is always a small gap,
 *   and Android does not guarantee a session will start promptly under system pressure.
 * - **Not guaranteed to be fully offline.** On-device recognition is requested via
 *   [SpeechRecognizer.createOnDeviceSpeechRecognizer] when
 *   [SpeechRecognizer.isOnDeviceRecognitionAvailable] reports it, and `EXTRA_PREFER_OFFLINE` is
 *   set as a hint otherwise, but many devices still route through a network-backed recognizer.
 * - **Battery/privacy:** listening keeps the microphone and (on non-on-device recognizers) a
 *   network connection active, so it is only ever started while the user has explicitly enabled
 *   voice triggering (see `CameraViewModel.onVoiceTriggerToggled`) - never automatically.
 * - Recognized text is matched locally against a small fixed vocabulary
 *   ([VoiceCommandMatcher]) and is never logged, persisted, or transmitted by this app.
 * - After [MAX_CONSECUTIVE_ERRORS] consecutive errors this adapter stops restarting itself (to
 *   avoid a tight error loop) and surfaces [VoiceRecognitionState.Error]; the user must toggle
 *   voice triggering off and back on to retry.
 */
@Singleton
class AndroidSpeechRecognizerAdapter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val voiceCommandMatcher: VoiceCommandMatcher,
    private val dispatcherProvider: DispatcherProvider,
) : VoiceCommandRecognizer {

    private val _state = MutableStateFlow<VoiceRecognitionState>(VoiceRecognitionState.Idle)
    override val state: StateFlow<VoiceRecognitionState> = _state.asStateFlow()

    // SpeechRecognizer requires all calls on the same (main) thread that created it.
    private val mainHandler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var consecutiveErrorCount = 0
    private var userRequestedListening = false

    override suspend fun start(): Unit = withContext(dispatcherProvider.main) {
        userRequestedListening = true
        consecutiveErrorCount = 0
        beginListening()
    }

    override suspend fun stop(): Unit = withContext(dispatcherProvider.main) {
        userRequestedListening = false
        releaseRecognizer()
        _state.value = VoiceRecognitionState.Idle
    }

    private fun beginListening() {
        if (!userRequestedListening) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _state.value = VoiceRecognitionState.Unavailable
            return
        }
        releaseRecognizer()
        val newRecognizer = createRecognizer().apply { setRecognitionListener(listener) }
        recognizer = newRecognizer
        newRecognizer.startListening(buildRecognizerIntent())
        _state.value = VoiceRecognitionState.Listening
    }

    private fun createRecognizer(): SpeechRecognizer =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }

    private fun buildRecognizerIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
    }

    private fun releaseRecognizer() {
        recognizer?.apply {
            setRecognitionListener(null)
            destroy()
        }
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle) = handleResults(results)

        override fun onPartialResults(partialResults: Bundle) = handleResults(partialResults)

        override fun onError(error: Int) {
            _state.value = VoiceRecognitionState.Error(error.toVoiceRecognitionError())
            scheduleRestartAfterError()
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun handleResults(results: Bundle) {
        val candidates = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        val match = candidates.firstNotNullOfOrNull(voiceCommandMatcher::match) ?: return
        consecutiveErrorCount = 0
        _state.value = VoiceRecognitionState.CommandRecognized(match)
        // Deferred rather than called inline: this runs on the current session's own callback,
        // and starting a new session (which destroys this one) from directly inside it is unsafe.
        mainHandler.post { beginListening() }
    }

    private fun scheduleRestartAfterError() {
        if (!userRequestedListening) return
        consecutiveErrorCount++
        if (consecutiveErrorCount > MAX_CONSECUTIVE_ERRORS) {
            // Deliberately does not keep retrying forever: stop and require the user to explicitly
            // re-enable voice triggering, rather than looping on a persistent error.
            userRequestedListening = false
            releaseRecognizer()
            return
        }
        mainHandler.post { beginListening() }
    }

    private companion object {
        const val MAX_CONSECUTIVE_ERRORS = 3
    }
}

private fun Int.toVoiceRecognitionError(): VoiceRecognitionError = when (this) {
    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceRecognitionError.NO_SPEECH_MATCH
    SpeechRecognizer.ERROR_AUDIO -> VoiceRecognitionError.AUDIO_ERROR
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceRecognitionError.PERMISSION_DENIED
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> VoiceRecognitionError.RECOGNIZER_BUSY
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> VoiceRecognitionError.NETWORK_ERROR
    SpeechRecognizer.ERROR_CLIENT -> VoiceRecognitionError.CLIENT_ERROR
    else -> VoiceRecognitionError.UNKNOWN
}
