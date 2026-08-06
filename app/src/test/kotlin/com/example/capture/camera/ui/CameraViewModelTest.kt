package com.example.capture.camera.ui

import com.example.capture.camera.data.CameraControlHolder
import com.example.capture.camera.data.ImageCaptureUseCaseHolder
import com.example.capture.camera.domain.CameraCaptureMemoryOutcome
import com.example.capture.camera.domain.CameraCaptureOutcome
import com.example.capture.camera.domain.CaptureAspectRatio
import com.example.capture.camera.domain.CaptureCoordinator
import com.example.capture.camera.domain.CaptureDiagnosticEvent
import com.example.capture.camera.domain.CaptureMode
import com.example.capture.camera.domain.CaptureTriggerSource
import com.example.capture.camera.domain.GestureDiagnosticEvent
import com.example.capture.permissions.PermissionStatus
import com.example.capture.settings.domain.AppSettings
import com.example.capture.testing.FakeCameraCaptureController
import com.example.capture.testing.FakeCaptureAttemptIdGenerator
import com.example.capture.testing.FakeCaptureDiagnosticsLogger
import com.example.capture.testing.FakeCaptureErrorLogger
import com.example.capture.testing.FakeCaptureMetadataLogger
import com.example.capture.testing.FakeFlashTorchController
import com.example.capture.testing.FakeGestureDiagnosticsLogger
import com.example.capture.testing.FakeHapticFeedback
import com.example.capture.testing.FakeImageMetadataReader
import com.example.capture.testing.FakeOverlayVisibilityRepository
import com.example.capture.testing.FakePhotoStorage
import com.example.capture.testing.FakeSettingsRepository
import com.example.capture.testing.FakeTimeProvider
import com.example.capture.testing.FakeVoiceCommandRecognizer
import com.example.capture.testing.TestDispatcherProvider
import com.example.capture.voice.domain.VoiceRecognitionState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CameraViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(
        camera: FakeCameraCaptureController = FakeCameraCaptureController(),
        storage: FakePhotoStorage = FakePhotoStorage(),
        voice: FakeVoiceCommandRecognizer = FakeVoiceCommandRecognizer(),
        haptics: FakeHapticFeedback = FakeHapticFeedback(),
        settings: FakeSettingsRepository = FakeSettingsRepository(),
        overlayVisibility: FakeOverlayVisibilityRepository = FakeOverlayVisibilityRepository(),
        flashTorchController: FakeFlashTorchController = FakeFlashTorchController(),
        errorLogger: FakeCaptureErrorLogger = FakeCaptureErrorLogger(),
        imageMetadataReader: FakeImageMetadataReader = FakeImageMetadataReader(),
        metadataLogger: FakeCaptureMetadataLogger = FakeCaptureMetadataLogger(),
        attemptIdGenerator: FakeCaptureAttemptIdGenerator = FakeCaptureAttemptIdGenerator(),
        captureDiagnosticsLogger: FakeCaptureDiagnosticsLogger = FakeCaptureDiagnosticsLogger(),
        gestureDiagnosticsLogger: FakeGestureDiagnosticsLogger = FakeGestureDiagnosticsLogger(),
        scheduler: TestCoroutineScheduler,
    ): CameraViewModel {
        val dispatcher = StandardTestDispatcher(scheduler)
        val coordinator = CaptureCoordinator(
            camera,
            storage,
            FakeTimeProvider().apply { attachScheduler(scheduler) },
            TestDispatcherProvider(dispatcher),
            errorLogger,
            imageMetadataReader,
            metadataLogger,
            attemptIdGenerator,
            captureDiagnosticsLogger,
        )
        val applicationScope = CoroutineScope(dispatcher)
        return CameraViewModel(
            coordinator,
            voice,
            ImageCaptureUseCaseHolder(),
            CameraControlHolder(),
            haptics,
            settings,
            overlayVisibility,
            flashTorchController,
            gestureDiagnosticsLogger,
            captureDiagnosticsLogger,
            applicationScope,
        )
    }

    @Test
    fun `initial ui state is idle with permissions not yet determined`() = runTest {
        val vm = buildViewModel(scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertThat(vm.uiState.value.captureStatus).isEqualTo(CaptureStatusUi.Idle)
        assertThat(vm.uiState.value.cameraPermission).isEqualTo(PermissionStatus.NOT_DETERMINED)
        assertThat(vm.uiState.value.voiceTriggerEnabled).isFalse()

        collectJob.cancel()
    }

    @Test
    fun `screen touch produces a successful capture reflected in ui state`() = runTest {
        val camera = FakeCameraCaptureController()
        val haptics = FakeHapticFeedback()
        val vm = buildViewModel(camera = camera, haptics = haptics, scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }

        vm.onScreenTouch()
        advanceUntilIdle()

        assertThat(camera.captureCount).isEqualTo(1)
        assertThat(vm.uiState.value.captureStatus).isEqualTo(CaptureStatusUi.Saved)
        assertThat(haptics.performCaptureSuccessCount).isEqualTo(1)
        assertThat(haptics.recordedDurationsMillis).containsExactly(AppSettings.DEFAULT_VIBRATION_DURATION_MILLIS)

        collectJob.cancel()
    }

    @Test
    fun `haptic feedback uses the currently configured vibration duration`() = runTest {
        val camera = FakeCameraCaptureController()
        val haptics = FakeHapticFeedback()
        val settings = FakeSettingsRepository(AppSettings(vibrationDurationMillis = 240L))
        val vm = buildViewModel(camera = camera, haptics = haptics, settings = settings, scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }

        vm.onScreenTouch()
        advanceUntilIdle()

        assertThat(haptics.recordedDurationsMillis).containsExactly(240L)

        collectJob.cancel()
    }

    @Test
    fun `a failed capture does not trigger haptic feedback and is not surfaced on screen`() = runTest {
        val camera = FakeCameraCaptureController { CameraCaptureOutcome.Failure("no camera hardware") }
        val haptics = FakeHapticFeedback()
        val errorLogger = FakeCaptureErrorLogger()
        val vm = buildViewModel(camera = camera, haptics = haptics, errorLogger = errorLogger, scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }

        vm.onScreenTouch()
        advanceUntilIdle()

        assertThat(vm.uiState.value.captureStatus).isEqualTo(CaptureStatusUi.Idle)
        assertThat(haptics.performCaptureSuccessCount).isEqualTo(0)
        assertThat(errorLogger.loggedEntries).isNotEmpty()

        collectJob.cancel()
    }

    @Test
    fun `a voice-triggered capture also produces haptic feedback`() = runTest {
        val camera = FakeCameraCaptureController()
        val voice = FakeVoiceCommandRecognizer()
        val haptics = FakeHapticFeedback()
        val vm = buildViewModel(camera = camera, voice = voice, haptics = haptics, scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }

        voice.emit(VoiceRecognitionState.CommandRecognized("cheese"))
        advanceUntilIdle()

        assertThat(haptics.performCaptureSuccessCount).isEqualTo(1)

        collectJob.cancel()
    }

    @Test
    fun `shutter button click and volume presses all route through the same capture path`() = runTest {
        val camera = FakeCameraCaptureController()
        val vm = buildViewModel(camera = camera, scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }

        vm.onShutterButtonClick()
        advanceUntilIdle()
        assertThat(camera.captureCount).isEqualTo(1)

        collectJob.cancel()
    }

    @Test
    fun `a recognized voice command results in a capture`() = runTest {
        val camera = FakeCameraCaptureController()
        val voice = FakeVoiceCommandRecognizer()
        val vm = buildViewModel(camera = camera, voice = voice, scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }

        voice.emit(VoiceRecognitionState.CommandRecognized("cheese"))
        advanceUntilIdle()

        assertThat(camera.captureCount).isEqualTo(1)
        assertThat(vm.uiState.value.captureStatus).isEqualTo(CaptureStatusUi.Saved)

        collectJob.cancel()
    }

    @Test
    fun `camera permission result is mapped through CapturePermissions decision logic`() = runTest {
        val vm = buildViewModel(scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }

        vm.onCameraPermissionResult(granted = false, shouldShowRationale = true, hasRequestedBefore = true)
        advanceUntilIdle()

        assertThat(vm.uiState.value.cameraPermission).isEqualTo(PermissionStatus.SHOULD_SHOW_RATIONALE)

        collectJob.cancel()
    }

    @Test
    fun `enabling voice trigger with microphone already granted starts the recognizer`() = runTest {
        val voice = FakeVoiceCommandRecognizer()
        val vm = buildViewModel(voice = voice, scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }

        vm.onMicrophonePermissionResult(granted = true, shouldShowRationale = false, hasRequestedBefore = true)
        vm.onVoiceTriggerToggled(true)
        advanceUntilIdle()

        assertThat(voice.startCount).isEqualTo(1)
        assertThat(vm.uiState.value.voiceTriggerEnabled).isTrue()

        collectJob.cancel()
    }

    @Test
    fun `enabling voice trigger without microphone permission does not start the recognizer`() = runTest {
        val voice = FakeVoiceCommandRecognizer()
        val vm = buildViewModel(voice = voice, scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }

        vm.onVoiceTriggerToggled(true)
        advanceUntilIdle()

        assertThat(voice.startCount).isEqualTo(0)

        collectJob.cancel()
    }

    @Test
    fun `disabling voice trigger stops the recognizer`() = runTest {
        val voice = FakeVoiceCommandRecognizer()
        val vm = buildViewModel(voice = voice, scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }

        vm.onMicrophonePermissionResult(granted = true, shouldShowRationale = false, hasRequestedBefore = true)
        vm.onVoiceTriggerToggled(true)
        advanceUntilIdle()
        vm.onVoiceTriggerToggled(false)
        advanceUntilIdle()

        assertThat(voice.stopCount).isAtLeast(1)

        collectJob.cancel()
    }

    @Test
    fun `uiState shows the overlay when it was last left visible and an image is set`() = runTest {
        val settings = FakeSettingsRepository(AppSettings(overlayImageUriString = "content://fake/pic"))
        val overlayVisibility = FakeOverlayVisibilityRepository(initial = true)
        val vm = buildViewModel(settings = settings, overlayVisibility = overlayVisibility, scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertThat(vm.uiState.value.overlayVisible).isTrue()
        assertThat(vm.uiState.value.overlayImageUriString).isEqualTo("content://fake/pic")

        collectJob.cancel()
    }

    @Test
    fun `uiState falls back to the live preview when overlay was left visible but no image is selected`() = runTest {
        val settings = FakeSettingsRepository(AppSettings(overlayImageUriString = null))
        val overlayVisibility = FakeOverlayVisibilityRepository(initial = true)
        val vm = buildViewModel(settings = settings, overlayVisibility = overlayVisibility, scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertThat(vm.uiState.value.overlayVisible).isFalse()

        collectJob.cancel()
    }

    @Test
    fun `overlay visibility defaults to hidden`() = runTest {
        val vm = buildViewModel(scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertThat(vm.uiState.value.overlayVisible).isFalse()

        collectJob.cancel()
    }

    @Test
    fun `a swipe-committed overlay visibility change is durably persisted`() = runTest {
        val overlayVisibility = FakeOverlayVisibilityRepository()
        val vm = buildViewModel(overlayVisibility = overlayVisibility, scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }

        vm.onOverlayVisibilityChanged(true)
        advanceUntilIdle()

        assertThat(overlayVisibility.overlayVisible.value).isTrue()
        assertThat(vm.uiState.value.overlayVisible).isFalse() // no image selected yet, so still hidden

        collectJob.cancel()
    }

    @Test
    fun `uiState reflects the currently configured capture mode`() = runTest {
        val settings = FakeSettingsRepository(AppSettings(captureMode = CaptureMode.BURST))
        val vm = buildViewModel(settings = settings, scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertThat(vm.uiState.value.captureMode).isEqualTo(CaptureMode.BURST)

        collectJob.cancel()
    }

    @Test
    fun `a burst vibrates exactly once regardless of per-image outcomes`() = runTest {
        var callCount = 0
        val camera = FakeCameraCaptureController(memoryOutcome = { _ ->
            callCount++
            if (callCount == 2) CameraCaptureMemoryOutcome.Failure("simulated failure") else CameraCaptureMemoryOutcome.Success(ByteArray(0))
        })
        val haptics = FakeHapticFeedback()
        val settings = FakeSettingsRepository(AppSettings(captureMode = CaptureMode.BURST, burstIntervalMillis = 250L))
        val vm = buildViewModel(camera = camera, haptics = haptics, settings = settings, scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }

        vm.onScreenTouch()
        advanceUntilIdle()

        assertThat(camera.memoryCaptureCount).isEqualTo(4)
        assertThat(haptics.performCaptureSuccessCount).isEqualTo(1)

        collectJob.cancel()
    }

    @Test
    fun `flash and torch are disabled while burst mode is active`() = runTest {
        val settings = FakeSettingsRepository(AppSettings(captureMode = CaptureMode.BURST))
        val flashTorch = FakeFlashTorchController()
        val vm = buildViewModel(settings = settings, flashTorchController = flashTorch, scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertThat(flashTorch.disableCallCount).isAtLeast(1)

        collectJob.cancel()
    }

    @Test
    fun `flash and torch are disabled when the overlay becomes visible`() = runTest {
        val settings = FakeSettingsRepository(AppSettings(overlayImageUriString = "content://fake/pic"))
        val overlayVisibility = FakeOverlayVisibilityRepository(initial = false)
        val flashTorch = FakeFlashTorchController()
        val vm = buildViewModel(
            settings = settings,
            overlayVisibility = overlayVisibility,
            flashTorchController = flashTorch,
            scheduler = testScheduler,
        )
        val collectJob = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        assertThat(flashTorch.disableCallCount).isEqualTo(0)

        vm.onOverlayVisibilityChanged(true)
        advanceUntilIdle()

        assertThat(flashTorch.disableCallCount).isAtLeast(1)

        collectJob.cancel()
    }

    @Test
    fun `uiState reflects the currently configured capture aspect ratio`() = runTest {
        val settings = FakeSettingsRepository(AppSettings(captureAspectRatio = CaptureAspectRatio.RATIO_16_9))
        val vm = buildViewModel(settings = settings, scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertThat(vm.uiState.value.captureAspectRatio).isEqualTo(CaptureAspectRatio.RATIO_16_9)

        collectJob.cancel()
    }

    @Test
    fun `a capture-aspect-ratio change made mid-burst does not apply until the burst completes`() = runTest {
        val settings = FakeSettingsRepository(
            AppSettings(
                captureMode = CaptureMode.BURST,
                burstIntervalMillis = 500L,
                captureAspectRatio = CaptureAspectRatio.RATIO_4_3,
            ),
        )
        val vm = buildViewModel(settings = settings, scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        assertThat(vm.uiState.value.captureAspectRatio).isEqualTo(CaptureAspectRatio.RATIO_4_3)

        vm.onScreenTouch()
        runCurrent() // starts the burst and runs it up to its first inter-image delay

        settings.setCaptureAspectRatio(CaptureAspectRatio.RATIO_16_9)
        runCurrent()

        // Still 4:3: the burst is still in progress, so the new setting hasn't taken effect yet.
        assertThat(vm.uiState.value.captureAspectRatio).isEqualTo(CaptureAspectRatio.RATIO_4_3)

        advanceUntilIdle() // let the burst finish

        assertThat(vm.uiState.value.captureAspectRatio).isEqualTo(CaptureAspectRatio.RATIO_16_9)

        collectJob.cancel()
    }

    @Test
    fun `a capture-aspect-ratio change outside a burst applies immediately`() = runTest {
        val settings = FakeSettingsRepository(AppSettings(captureAspectRatio = CaptureAspectRatio.RATIO_4_3))
        val vm = buildViewModel(settings = settings, scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        settings.setCaptureAspectRatio(CaptureAspectRatio.RATIO_16_9)
        advanceUntilIdle()

        assertThat(vm.uiState.value.captureAspectRatio).isEqualTo(CaptureAspectRatio.RATIO_16_9)

        collectJob.cancel()
    }

    @Test
    fun `clearing the view model releases the voice recognizer on the application scope`() = runTest {
        val voice = FakeVoiceCommandRecognizer()
        val vm = buildViewModel(voice = voice, scheduler = testScheduler)

        vm.onCleared()
        advanceUntilIdle()

        assertThat(voice.stopCount).isEqualTo(1)
    }

    @Test
    fun `the shutter button and screen touch are distinguishable capture-diagnostic trigger sources`() = runTest {
        val camera = FakeCameraCaptureController()
        val diagnosticsLogger = FakeCaptureDiagnosticsLogger()
        val vm = buildViewModel(camera = camera, captureDiagnosticsLogger = diagnosticsLogger, scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }

        vm.onShutterButtonClick()
        advanceUntilIdle()

        val requested = diagnosticsLogger.loggedEvents.filterIsInstance<CaptureDiagnosticEvent.Requested>().single()
        assertThat(requested.triggerSource).isEqualTo(CaptureTriggerSource.SHUTTER_BUTTON)

        collectJob.cancel()
    }

    @Test
    fun `a gesture diagnostic event is forwarded to the gesture diagnostics logger`() = runTest {
        val gestureDiagnosticsLogger = FakeGestureDiagnosticsLogger()
        val vm = buildViewModel(gestureDiagnosticsLogger = gestureDiagnosticsLogger, scheduler = testScheduler)

        val event = GestureDiagnosticEvent.Detected(timestampMillis = 1_000L, downX = 10f, downY = 20f)
        vm.onGestureDiagnosticEvent(event)

        assertThat(gestureDiagnosticsLogger.loggedEvents).containsExactly(event)
        assertThat(vm.diagnosticsOverlayInfo.value.lastTouchLocation).isEqualTo(10f to 20f)
    }

    @Test
    fun `toggling the diagnostics overlay flips its enabled state`() = runTest {
        val vm = buildViewModel(scheduler = testScheduler)

        assertThat(vm.diagnosticsOverlayEnabled.value).isFalse()
        vm.onDiagnosticsOverlayToggled()
        assertThat(vm.diagnosticsOverlayEnabled.value).isTrue()
        vm.onDiagnosticsOverlayToggled()
        assertThat(vm.diagnosticsOverlayEnabled.value).isFalse()
    }
}
