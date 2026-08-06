package com.example.capture.camera.ui

import androidx.camera.core.Camera
import androidx.camera.core.ImageCapture
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.capture.camera.data.CameraControlHolder
import com.example.capture.camera.data.ImageCaptureUseCaseHolder
import com.example.capture.camera.domain.BURST_IMAGE_COUNT
import com.example.capture.camera.domain.CameraDiagnosticsSnapshot
import com.example.capture.camera.domain.CaptureAspectRatio
import com.example.capture.camera.domain.CaptureCoordinator
import com.example.capture.camera.domain.CaptureDiagnosticsLogger
import com.example.capture.camera.domain.CaptureMode
import com.example.capture.camera.domain.CaptureOutcome
import com.example.capture.camera.domain.CaptureState
import com.example.capture.camera.domain.CaptureTrigger
import com.example.capture.camera.domain.FlashTorchController
import com.example.capture.camera.domain.GestureDiagnosticEvent
import com.example.capture.camera.domain.GestureDiagnosticsLogger
import com.example.capture.camera.domain.HapticFeedback
import com.example.capture.camera.domain.OverlayVisibilityRepository
import com.example.capture.camera.domain.kind
import com.example.capture.camera.domain.toDiagnosticSource
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    private val gestureDiagnosticsLogger: GestureDiagnosticsLogger,
    private val captureDiagnosticsLogger: CaptureDiagnosticsLogger,
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

    /**
     * The `ImageCapture` use case's capture-mode/resolution optimization (see CameraPreview.kt) is
     * a bind-time decision shared by every trigger, but each [com.example.capture.camera.domain.CaptureTriggerKind]
     * now has its own independent Single-Shot/Burst setting (see "Capture Mode" in app-spec.md) -
     * so this tracks which mode the pipeline is *currently* bound for, updated by
     * [ensureCaptureModeBound] right before a capture whose trigger needs a different mode, rather
     * than mirroring a single global setting the way it used to.
     */
    private val _boundCaptureMode = MutableStateFlow(CaptureMode.SINGLE_SHOT)

    @Volatile
    private var burstInProgress = false

    // Debug-only state (see "Debug Overlay" in app-spec.md): kept separate from uiState/
    // CameraUiState so this diagnostics-only concern can't affect the screen's main render path or
    // its tests. The toggle button that flips diagnosticsOverlayEnabled is itself only rendered in
    // a debug build (see CameraScreen), so this never becomes true in Release.
    private val _diagnosticsOverlayEnabled = MutableStateFlow(false)
    val diagnosticsOverlayEnabled: StateFlow<Boolean> = _diagnosticsOverlayEnabled.asStateFlow()

    private val _diagnosticsOverlayInfo = MutableStateFlow(DiagnosticsOverlayInfo())
    val diagnosticsOverlayInfo: StateFlow<DiagnosticsOverlayInfo> = _diagnosticsOverlayInfo.asStateFlow()

    val uiState: StateFlow<CameraUiState> = combine(
        captureVoicePermissionState,
        settingsRepository.settings,
        overlayVisibilityRepository.overlayVisible,
        effectiveCaptureAspectRatio,
        _boundCaptureMode,
    ) { state, settings, overlayVisible, captureAspectRatio, boundCaptureMode ->
        CameraUiState(
            cameraPermission = state.cameraPermission,
            microphonePermission = state.microphonePermission,
            captureProgress = state.capture.toCaptureProgressUi(),
            voiceTriggerEnabled = state.voiceTriggerEnabled,
            voiceListening = state.voice is VoiceRecognitionState.Listening,
            voiceError = (state.voice as? VoiceRecognitionState.Error)?.error?.toUserMessageOrNull(),
            // Never show a blank placeholder: if the overlay was last left visible but no image
            // has been picked yet, fall back to the live preview.
            overlayVisible = overlayVisible && settings.overlayImageUriString != null,
            overlayImageUriString = settings.overlayImageUriString,
            captureMode = boundCaptureMode,
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
        // voice-state change) and would otherwise repeat the pulse for as long as the raw
        // CaptureState stays in a completed state.
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
        // condition becomes true. Keyed off the raw CaptureState (is a burst actually running
        // right now), not a settings value - with per-trigger capture modes there's no longer a
        // single global "is Burst Mode selected" the way CameraUiState.captureMode used to mean.
        viewModelScope.launch {
            combine(
                captureCoordinator.state.map { it is CaptureState.BurstStarted || it is CaptureState.BurstProgress },
                overlayVisibilityRepository.overlayVisible,
            ) { burstActive, overlayVisible -> burstActive || overlayVisible }
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
        // Keeps the debug overlay's capture-state/attempt-id/trigger-source fields current (see
        // "Debug Overlay" in app-spec.md) - a dedicated collector so this diagnostics-only concern
        // stays out of the uiState combine above.
        viewModelScope.launch {
            captureCoordinator.state.collect { state ->
                when (state) {
                    is CaptureState.Capturing -> _diagnosticsOverlayInfo.update {
                        it.copy(
                            captureState = "Capturing",
                            lastCaptureAttemptId = state.attemptId,
                            lastCaptureTriggerSource = state.trigger.toDiagnosticSource(),
                        )
                    }
                    is CaptureState.BurstStarted -> _diagnosticsOverlayInfo.update {
                        it.copy(
                            captureState = "BurstStarted",
                            lastCaptureAttemptId = state.attemptId,
                            lastCaptureTriggerSource = state.trigger.toDiagnosticSource(),
                        )
                    }
                    is CaptureState.BurstProgress -> _diagnosticsOverlayInfo.update {
                        it.copy(captureState = "BurstProgress:${state.imagesCompleted}/$BURST_IMAGE_COUNT")
                    }
                    is CaptureState.Completed -> _diagnosticsOverlayInfo.update {
                        it.copy(captureState = if (state.result.outcome is CaptureOutcome.Success) "Completed:Success" else "Completed:Failure")
                    }
                    is CaptureState.BurstCompleted -> _diagnosticsOverlayInfo.update { it.copy(captureState = "BurstCompleted") }
                    CaptureState.Idle -> {}
                }
            }
        }
    }

    /** [isTopHalf] selects which of the two independently-configurable screen-tap zones fired (see "Capture Mode" in app-spec.md). */
    fun onScreenTouch(isTopHalf: Boolean) =
        requestCapture(if (isTopHalf) CaptureTrigger.ScreenTouchTop else CaptureTrigger.ScreenTouchBottom)

    fun onShutterButtonClick() = requestCapture(CaptureTrigger.ShutterButton)

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

    /** Wired up as `CameraScreen`'s `onGestureDiagnosticEvent` callback (see "Gesture Events" in app-spec.md). */
    fun onGestureDiagnosticEvent(event: GestureDiagnosticEvent) {
        gestureDiagnosticsLogger.log(event)
        when (event) {
            is GestureDiagnosticEvent.Detected ->
                _diagnosticsOverlayInfo.update { it.copy(lastTouchLocation = event.downX to event.downY) }
            is GestureDiagnosticEvent.Classified ->
                _diagnosticsOverlayInfo.update { it.copy(lastGestureClassification = event.classification) }
            else -> {}
        }
    }

    /** Wired up as `CameraPreview`'s `onCameraDiagnostics` callback (see "Camera Diagnostics" in app-spec.md). */
    fun onCameraDiagnostics(snapshot: CameraDiagnosticsSnapshot) {
        captureDiagnosticsLogger.logCameraState(snapshot)
        _diagnosticsOverlayInfo.update { it.copy(cameraBound = true) }
    }

    /** Debug-only: flips whether the diagnostics overlay is drawn (see "Debug Overlay" in app-spec.md). */
    fun onDiagnosticsOverlayToggled() {
        _diagnosticsOverlayEnabled.update { !it }
    }

    private fun requestCapture(trigger: CaptureTrigger) {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            val requiredMode = settings.captureModeByTrigger[trigger.kind()] ?: CaptureMode.SINGLE_SHOT
            ensureCaptureModeBound(requiredMode)
            captureCoordinator.requestCapture(
                trigger,
                requiredMode,
                settings.burstIntervalMillis,
                settings.captureAspectRatio,
            )
        }
    }

    /**
     * Rebinds the camera pipeline to [requiredMode] first if it isn't already, so a trigger
     * configured for a different mode than whatever last fired still gets that mode's own
     * latency/resolution behavior (see "Capture Mode" in app-spec.md) rather than inheriting
     * whatever the pipeline happened to be bound as. Updating [_boundCaptureMode] causes
     * `CameraPreview` to rebuild and rebind its `ImageCapture` use case (the same mechanism that
     * already reacts to a capture-aspect-ratio change); this suspends until that completes -
     * bounded by [CAPTURE_MODE_REBIND_TIMEOUT_MILLIS] so a camera that's bound once but gets stuck
     * mid-rebind still falls through to `CameraXCaptureController`'s existing "not ready" failure
     * path instead of hanging this call forever. Switching between differently-configured triggers
     * therefore costs a one-time rebind delay; repeated use of the same trigger, or of triggers
     * sharing a mode, never rebinds.
     *
     * Only waits at all if the pipeline was *already* bound to something ([previousUseCase] is
     * non-null): if it was never bound in the first place (camera permission not yet granted, or
     * `CameraPreview` simply hasn't composed yet), there is no rebind to wait for - the normal
     * "camera not ready" handling in [captureCoordinator]/`CameraXCaptureController` already covers
     * that case without this method adding an unconditional multi-second wait to the very first
     * capture request the app ever makes.
     */
    private suspend fun ensureCaptureModeBound(requiredMode: CaptureMode) {
        if (_boundCaptureMode.value == requiredMode) return
        val previousUseCase = imageCaptureUseCaseHolder.imageCapture.value
        _boundCaptureMode.value = requiredMode
        if (previousUseCase == null) return
        withTimeoutOrNull(CAPTURE_MODE_REBIND_TIMEOUT_MILLIS) {
            imageCaptureUseCaseHolder.imageCapture.first { it != null && it !== previousUseCase }
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

        /**
         * Generous upper bound for a camera-pipeline rebind (see [ensureCaptureModeBound]) - long
         * enough to cover a real rebind on slow hardware, short enough that a camera that's never
         * going to bind (e.g. permission denied) doesn't leave a capture request hanging for long.
         */
        const val CAPTURE_MODE_REBIND_TIMEOUT_MILLIS = 3_000L
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
 * Drives [CaptureProgressIndicator]: an indeterminate spinner for the whole of Single-Shot Mode's
 * capture, a determinate one that starts at 0 of [BURST_IMAGE_COUNT] the instant a burst is
 * accepted and advances by one step per [CaptureState.BurstProgress] emission, and hidden the rest
 * of the time (see "Capture Progress Indicator" in app-spec.md).
 */
private fun CaptureState.toCaptureProgressUi(): CaptureProgressUi = when (this) {
    is CaptureState.Capturing -> CaptureProgressUi.Indeterminate
    is CaptureState.BurstStarted -> CaptureProgressUi.Determinate(0, BURST_IMAGE_COUNT)
    is CaptureState.BurstProgress -> CaptureProgressUi.Determinate(imagesCompleted, BURST_IMAGE_COUNT)
    CaptureState.Idle, is CaptureState.Completed, is CaptureState.BurstCompleted -> CaptureProgressUi.Hidden
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
