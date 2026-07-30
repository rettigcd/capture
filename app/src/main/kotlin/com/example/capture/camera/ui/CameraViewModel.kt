package com.example.capture.camera.ui

import androidx.camera.core.ImageCapture
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.capture.camera.data.ImageCaptureUseCaseHolder
import com.example.capture.camera.domain.CaptureCoordinator
import com.example.capture.camera.domain.CaptureOutcome
import com.example.capture.camera.domain.CaptureState
import com.example.capture.camera.domain.CaptureTrigger
import com.example.capture.camera.domain.HapticFeedback
import com.example.capture.camera.domain.OverlayVisibilityRepository
import com.example.capture.common.ApplicationScope
import com.example.capture.permissions.CapturePermissions
import com.example.capture.permissions.PermissionStatus
import com.example.capture.settings.domain.SettingsRepository
import com.example.capture.voice.domain.VoiceCommandRecognizer
import com.example.capture.voice.domain.VoiceRecognitionError
import com.example.capture.voice.domain.VoiceRecognitionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Holds no `Activity` or Compose reference - it only exposes state and callbacks. Screen touch,
 * volume buttons, and recognized voice commands are all funneled through the same
 * [CaptureCoordinator] method, [CaptureCoordinator.requestCapture], so behavior is identical
 * regardless of which one fired.
 */
@HiltViewModel
class CameraViewModel @Inject constructor(
    private val captureCoordinator: CaptureCoordinator,
    private val voiceCommandRecognizer: VoiceCommandRecognizer,
    private val imageCaptureUseCaseHolder: ImageCaptureUseCaseHolder,
    private val hapticFeedback: HapticFeedback,
    private val settingsRepository: SettingsRepository,
    private val overlayVisibilityRepository: OverlayVisibilityRepository,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {

    private val cameraPermission = MutableStateFlow(PermissionStatus.NOT_DETERMINED)
    private val microphonePermission = MutableStateFlow(PermissionStatus.NOT_DETERMINED)
    private val voiceTriggerEnabled = MutableStateFlow(false)

    // kotlinx.coroutines.flow.combine has typed overloads only up to 5 flows; nesting this
    // 5-flow combine inside a 2-flow one (rather than the untyped vararg overload) keeps every
    // branch type-checked.
    private val captureVoicePermissionState = combine(
        captureCoordinator.state,
        voiceCommandRecognizer.state,
        cameraPermission,
        microphonePermission,
        voiceTriggerEnabled,
        ::CaptureVoicePermissionState,
    )

    val uiState: StateFlow<CameraUiState> = combine(
        captureVoicePermissionState,
        settingsRepository.settings,
        overlayVisibilityRepository.overlayVisible,
    ) { state, settings, overlayVisible ->
        CameraUiState(
            cameraPermission = state.cameraPermission,
            microphonePermission = state.microphonePermission,
            captureStatus = state.capture.toCaptureStatusUi(),
            voiceTriggerEnabled = state.voiceTriggerEnabled,
            voiceListening = state.voice is VoiceRecognitionState.Listening,
            voiceError = (state.voice as? VoiceRecognitionState.Error)?.error?.toUserMessageOrNull(),
            // Never show a blank placeholder: if the overlay was last left visible but no image
            // has been picked yet, fall back to the live preview.
            overlayVisible = overlayVisible && settings.overlayImageUriString != null,
            overlayImageUriString = settings.overlayImageUriString,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), CameraUiState())

    init {
        // The only place a recognized voice command turns into a capture request.
        viewModelScope.launch {
            voiceCommandRecognizer.state.collect { state ->
                if (state is VoiceRecognitionState.CommandRecognized) {
                    captureCoordinator.requestCapture(CaptureTrigger.VoiceCommand(state.phrase))
                }
            }
        }
        // Fires once per successful capture, regardless of which trigger produced it (and
        // regardless of whether the live preview or the selected image is currently shown) - a
        // dedicated collector rather than deriving it inside the `uiState` combine above, since
        // that combine re-runs on every unrelated upstream emission (e.g. a voice-state change)
        // and would otherwise repeat the pulse for as long as captureStatus stays Saved.
        viewModelScope.launch {
            captureCoordinator.state.collect { state ->
                if (state is CaptureState.Completed && state.result.outcome is CaptureOutcome.Success) {
                    val durationMillis = settingsRepository.settings.first().vibrationDurationMillis
                    hapticFeedback.performCaptureSuccess(durationMillis)
                }
            }
        }
    }

    fun onScreenTouch() = requestCapture(CaptureTrigger.ScreenTouch)

    fun onShutterButtonClick() = requestCapture(CaptureTrigger.ScreenTouch)

    fun onVolumeUpPressed() = requestCapture(CaptureTrigger.VolumeUp)

    fun onVolumeDownPressed() = requestCapture(CaptureTrigger.VolumeDown)

    /**
     * Wired up as the `onImageCaptureReady` callback for `CameraPreview`; keeps this being the
     * only place [ImageCaptureUseCaseHolder] is touched from the UI layer.
     */
    fun attachImageCapture(useCase: ImageCapture?) = imageCaptureUseCaseHolder.attach(useCase)

    private fun requestCapture(trigger: CaptureTrigger) {
        viewModelScope.launch { captureCoordinator.requestCapture(trigger) }
    }

    fun onCameraPermissionResult(granted: Boolean, shouldShowRationale: Boolean, hasRequestedBefore: Boolean) {
        cameraPermission.value = CapturePermissions.permissionStatus(granted, shouldShowRationale, hasRequestedBefore)
    }

    fun onMicrophonePermissionResult(granted: Boolean, shouldShowRationale: Boolean, hasRequestedBefore: Boolean) {
        microphonePermission.value =
            CapturePermissions.permissionStatus(granted, shouldShowRationale, hasRequestedBefore)
        syncVoiceRecognition()
    }

    /** User-controllable: voice triggering never starts listening silently on its own. */
    fun onVoiceTriggerToggled(enabled: Boolean) {
        voiceTriggerEnabled.value = enabled
        syncVoiceRecognition()
    }

    /**
     * Called once a swipe gesture on the camera screen settles on a new overlay visibility.
     * Written on [applicationScope], not [viewModelScope], for the same durability reason
     * described on [com.example.capture.settings.ui.SettingsViewModel]'s setters: this state must
     * survive even if the screen that triggered the write is torn down immediately afterward.
     */
    fun onOverlayVisibilityChanged(visible: Boolean) {
        applicationScope.launch { overlayVisibilityRepository.setOverlayVisible(visible) }
    }

    private fun syncVoiceRecognition() {
        val shouldListen = voiceTriggerEnabled.value && microphonePermission.value == PermissionStatus.GRANTED
        viewModelScope.launch {
            if (shouldListen) voiceCommandRecognizer.start() else voiceCommandRecognizer.stop()
        }
    }

    // Left `public` (Kotlin allows widening an override's visibility) so tests can call it
    // directly without a full ViewModelStore teardown.
    public override fun onCleared() {
        // viewModelScope is already cancelled by the time onCleared() runs, so releasing the
        // recognizer's resources has to happen on a scope that outlives it.
        applicationScope.launch { voiceCommandRecognizer.stop() }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

private data class CaptureVoicePermissionState(
    val capture: CaptureState,
    val voice: VoiceRecognitionState,
    val cameraPermission: PermissionStatus,
    val microphonePermission: PermissionStatus,
    val voiceTriggerEnabled: Boolean,
)

private fun CaptureState.toCaptureStatusUi(): CaptureStatusUi = when (this) {
    CaptureState.Idle -> CaptureStatusUi.Idle
    is CaptureState.Capturing -> CaptureStatusUi.Capturing
    is CaptureState.Completed -> when (val outcome = result.outcome) {
        is CaptureOutcome.Success -> CaptureStatusUi.Saved(outcome.uriString)
        is CaptureOutcome.Failure -> CaptureStatusUi.Failed(outcome.userMessage)
    }
}

/**
 * [VoiceRecognitionError.NO_SPEECH_MATCH] and [VoiceRecognitionError.RECOGNIZER_BUSY] are expected,
 * routine occurrences while continuously re-listening and are handled internally by restarting;
 * surfacing them as user-facing errors would just be noise.
 */
private fun VoiceRecognitionError.toUserMessageOrNull(): String? = when (this) {
    VoiceRecognitionError.NO_SPEECH_MATCH, VoiceRecognitionError.RECOGNIZER_BUSY -> null
    VoiceRecognitionError.AUDIO_ERROR -> "Microphone error - voice capture paused."
    VoiceRecognitionError.PERMISSION_DENIED -> "Microphone permission is required for voice capture."
    VoiceRecognitionError.NETWORK_ERROR -> "Voice recognition needs a network connection on this device."
    VoiceRecognitionError.CLIENT_ERROR, VoiceRecognitionError.UNKNOWN -> "Voice capture is temporarily unavailable."
}
