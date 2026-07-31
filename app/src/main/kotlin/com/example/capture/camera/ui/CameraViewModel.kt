package com.example.capture.camera.ui

import androidx.camera.core.Camera
import androidx.camera.core.ImageCapture
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.capture.camera.data.CameraControlHolder
import com.example.capture.camera.data.ImageCaptureUseCaseHolder
import com.example.capture.camera.domain.CaptureAspectRatio
import com.example.capture.camera.domain.CaptureCoordinator
import com.example.capture.camera.domain.CaptureMode
import com.example.capture.camera.domain.CaptureOutcome
import com.example.capture.camera.domain.CaptureState
import com.example.capture.camera.domain.CaptureTrigger
import com.example.capture.camera.domain.FlashTorchController
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    private val cameraControlHolder: CameraControlHolder,
    private val hapticFeedback: HapticFeedback,
    private val settingsRepository: SettingsRepository,
    private val overlayVisibilityRepository: OverlayVisibilityRepository,
    private val flashTorchController: FlashTorchController,
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

    // Mirrors settingsRepository.settings.captureAspectRatio immediately, *unless* a burst is
    // currently in progress - in which case it holds the previous value until that burst
    // completes (see the CaptureCoordinator.state collector in init and "Capture Aspect Ratio and
    // Preview Framing" in app-spec.md: "changing the setting while a burst is active must not
    // alter the active burst"). CameraPreview reads this (via CameraUiState), not the raw setting,
    // so the live camera use cases are never rebuilt/rebound mid-burst.
    private val effectiveCaptureAspectRatio = MutableStateFlow(CaptureAspectRatio.RATIO_4_3)

    @Volatile
    private var burstInProgress = false

    val uiState: StateFlow<CameraUiState> = combine(
        captureVoicePermissionState,
        settingsRepository.settings,
        overlayVisibilityRepository.overlayVisible,
        effectiveCaptureAspectRatio,
    ) { state, settings, overlayVisible, captureAspectRatio ->
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
            captureMode = settings.captureMode,
            captureAspectRatio = captureAspectRatio,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), CameraUiState())

    init {
        // The only place a recognized voice command turns into a capture request.
        viewModelScope.launch {
            voiceCommandRecognizer.state.collect { state ->
                if (state is VoiceRecognitionState.CommandRecognized) {
                    requestCapture(CaptureTrigger.VoiceCommand(state.phrase))
                }
            }
        }
        // Fires once per completed Single-Shot capture, and once per *triggered* burst (not once
        // per image in it - see CaptureState.BurstStarted's kdoc and "Burst Feedback" in
        // app-spec.md) - a dedicated collector rather than deriving it inside the `uiState`
        // combine above, since that combine re-runs on every unrelated upstream emission (e.g. a
        // voice-state change) and would otherwise repeat the pulse for as long as captureStatus
        // stays Saved.
        viewModelScope.launch {
            captureCoordinator.state.collect { state ->
                val shouldVibrate = when (state) {
                    is CaptureState.BurstStarted -> true
                    is CaptureState.Completed -> state.result.outcome is CaptureOutcome.Success
                    else -> false
                }
                if (shouldVibrate) {
                    val durationMillis = settingsRepository.settings.first().vibrationDurationMillis
                    hapticFeedback.performCaptureSuccess(durationMillis)
                }
            }
        }
        // Flash and torch must never be used while Burst Mode is active or while the overlay
        // image is visible (see "Flash and Torch Restrictions" in app-spec.md). There is
        // currently no user-facing control that turns either on, but this actively forces them
        // off - rather than merely relying on nothing else enabling them - every time either
        // condition becomes true.
        viewModelScope.launch {
            uiState
                .map { it.captureMode == CaptureMode.BURST || it.overlayVisible }
                .distinctUntilChanged()
                .collect { mustDisableFlashAndTorch -> if (mustDisableFlashAndTorch) flashTorchController.disableFlashAndTorch() }
        }
        // Mirrors the persisted capture-aspect-ratio setting into effectiveCaptureAspectRatio -
        // except while a burst is in progress, in which case the change is held back (see the
        // BurstStarted/BurstCompleted branches below) so CameraPreview never rebuilds/rebinds its
        // CameraX use cases mid-burst.
        viewModelScope.launch {
            settingsRepository.settings
                .map { it.captureAspectRatio }
                .distinctUntilChanged()
                .collect { ratio -> if (!burstInProgress) effectiveCaptureAspectRatio.value = ratio }
        }
        viewModelScope.launch {
            captureCoordinator.state.collect { state ->
                when (state) {
                    is CaptureState.BurstStarted -> burstInProgress = true
                    is CaptureState.BurstCompleted -> {
                        burstInProgress = false
                        effectiveCaptureAspectRatio.value = settingsRepository.settings.first().captureAspectRatio
                    }
                    else -> {}
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

    /**
     * Wired up as the `onCameraReady` callback for `CameraPreview`; keeps this being the only
     * place [CameraControlHolder] is touched from the UI layer.
     */
    fun attachCamera(camera: Camera?) = cameraControlHolder.attach(camera)

    private fun requestCapture(trigger: CaptureTrigger) {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            captureCoordinator.requestCapture(
                trigger,
                settings.captureMode,
                settings.burstIntervalMillis,
                settings.captureAspectRatio,
            )
        }
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

/**
 * Deliberately carries no per-outcome detail beyond idle/capturing/saved: capture and
 * file-saving errors (single-shot or within a burst) are logged, not shown on screen - see
 * "Error Handling" in app-spec.md and [CaptureStatusUi]'s kdoc.
 */
private fun CaptureState.toCaptureStatusUi(): CaptureStatusUi = when (this) {
    CaptureState.Idle -> CaptureStatusUi.Idle
    is CaptureState.Capturing, is CaptureState.BurstStarted -> CaptureStatusUi.Capturing
    is CaptureState.Completed -> when (result.outcome) {
        is CaptureOutcome.Success -> CaptureStatusUi.Saved
        is CaptureOutcome.Failure -> CaptureStatusUi.Idle
    }
    is CaptureState.BurstCompleted ->
        if (results.any { it.outcome is CaptureOutcome.Success }) CaptureStatusUi.Saved else CaptureStatusUi.Idle
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
