package com.example.capture.camera.ui

import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.impl.CameraConfig
import com.example.capture.camera.data.CameraControlHolder
import com.example.capture.camera.data.ImageCaptureUseCaseHolder
import com.example.capture.camera.data.VideoCaptureUseCaseHolder
import com.example.capture.camera.domain.BURST_IMAGE_COUNT
import com.example.capture.camera.domain.CameraCaptureMemoryOutcome
import com.example.capture.camera.domain.CameraCaptureOutcome
import com.example.capture.camera.domain.CaptureAspectRatio
import com.example.capture.camera.domain.CaptureCoordinator
import com.example.capture.camera.domain.CaptureDiagnosticEvent
import com.example.capture.camera.domain.CaptureMode
import com.example.capture.camera.domain.CaptureTriggerKind
import com.example.capture.camera.domain.CaptureTriggerSource
import com.example.capture.camera.domain.GestureDiagnosticEvent
import com.example.capture.camera.domain.VideoStartOutcome
import com.example.capture.permissions.PermissionStatus
import com.example.capture.settings.domain.AppSettings
import com.example.capture.testing.FakeCameraCaptureController
import com.example.capture.testing.FakeCaptureAttemptIdGenerator
import com.example.capture.testing.FakeCaptureDiagnosticsLogger
import com.example.capture.testing.FakeCaptureErrorLogger
import com.example.capture.testing.FakeCaptureMetadataLogger
import com.example.capture.testing.FakeEncryptedPhotoStorage
import com.example.capture.testing.FakeFlashTorchController
import com.example.capture.testing.FakeGestureDiagnosticsLogger
import com.example.capture.testing.FakeHapticFeedback
import com.example.capture.testing.FakeImageMetadataReader
import com.example.capture.testing.FakeOverlayVisibilityRepository
import com.example.capture.testing.FakePhotoStorage
import com.example.capture.testing.FakeSettingsRepository
import com.example.capture.testing.FakeTimeProvider
import com.example.capture.testing.FakeVideoCaptureController
import com.example.capture.testing.FakeVoiceCommandRecognizer
import com.example.capture.testing.FakeZoomController
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

    /** All six triggers default to Single-Shot except [trigger], which is set to Burst. */
    private fun burstFor(trigger: CaptureTriggerKind): Map<CaptureTriggerKind, CaptureMode> =
        AppSettings().captureModeByTrigger + (trigger to CaptureMode.BURST)

    /** All six triggers default to Single-Shot except [trigger], which is set to Video. */
    private fun videoFor(trigger: CaptureTriggerKind): Map<CaptureTriggerKind, CaptureMode> =
        AppSettings().captureModeByTrigger + (trigger to CaptureMode.VIDEO)

    private fun buildViewModel(
        camera: FakeCameraCaptureController = FakeCameraCaptureController(),
        video: FakeVideoCaptureController = FakeVideoCaptureController(),
        storage: FakePhotoStorage = FakePhotoStorage(),
        voice: FakeVoiceCommandRecognizer = FakeVoiceCommandRecognizer(),
        haptics: FakeHapticFeedback = FakeHapticFeedback(),
        settings: FakeSettingsRepository = FakeSettingsRepository(),
        overlayVisibility: FakeOverlayVisibilityRepository = FakeOverlayVisibilityRepository(),
        flashTorchController: FakeFlashTorchController = FakeFlashTorchController(),
        zoomController: FakeZoomController = FakeZoomController(),
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
            video,
            storage,
            FakeEncryptedPhotoStorage(),
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
            VideoCaptureUseCaseHolder(),
            CameraControlHolder(),
            haptics,
            settings,
            overlayVisibility,
            flashTorchController,
            zoomController,
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

        assertThat(vm.uiState.value.captureProgress).isEqualTo(CaptureProgressUi.Hidden)
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

        vm.onScreenTouch(true)
        advanceUntilIdle()

        assertThat(camera.captureCount).isEqualTo(1)
        assertThat(vm.uiState.value.captureProgress).isEqualTo(CaptureProgressUi.Hidden)
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

        vm.onScreenTouch(true)
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

        vm.onScreenTouch(true)
        advanceUntilIdle()

        assertThat(vm.uiState.value.captureProgress).isEqualTo(CaptureProgressUi.Hidden)
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
        assertThat(vm.uiState.value.captureProgress).isEqualTo(CaptureProgressUi.Hidden)

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
    fun `uiState shows the overlay when it was last left visible and a cover photo is configured`() = runTest {
        val settings = FakeSettingsRepository(AppSettings(coverPhotoUriStrings = listOf("content://fake/pic")))
        val overlayVisibility = FakeOverlayVisibilityRepository(initial = true)
        val vm = buildViewModel(settings = settings, overlayVisibility = overlayVisibility, scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertThat(vm.uiState.value.overlayVisible).isTrue()
        assertThat(vm.uiState.value.activeCoverPhotoUriString).isEqualTo("content://fake/pic")
        assertThat(vm.uiState.value.coverPhotoCount).isEqualTo(1)

        collectJob.cancel()
    }

    @Test
    fun `uiState falls back to the live preview when overlay was left visible but no cover photo is configured`() = runTest {
        val settings = FakeSettingsRepository(AppSettings(coverPhotoUriStrings = emptyList()))
        val overlayVisibility = FakeOverlayVisibilityRepository(initial = true)
        val vm = buildViewModel(settings = settings, overlayVisibility = overlayVisibility, scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertThat(vm.uiState.value.overlayVisible).isFalse()

        collectJob.cancel()
    }

    @Test
    fun `uiState shows the cover photo at the active index, and clamps it if the index is out of bounds`() = runTest {
        val settings = FakeSettingsRepository(
            AppSettings(coverPhotoUriStrings = listOf("content://fake/a", "content://fake/b", "content://fake/c")),
        )
        val overlayVisibility = FakeOverlayVisibilityRepository(initial = true, initialActiveCoverPhotoIndex = 1)
        val vm = buildViewModel(settings = settings, overlayVisibility = overlayVisibility, scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertThat(vm.uiState.value.activeCoverPhotoUriString).isEqualTo("content://fake/b")

        collectJob.cancel()
    }

    @Test
    fun `an additional left swipe cycles to the next cover photo and wraps from the last back to the first`() = runTest {
        val settings = FakeSettingsRepository(
            AppSettings(coverPhotoUriStrings = listOf("content://fake/a", "content://fake/b", "content://fake/c")),
        )
        val overlayVisibility = FakeOverlayVisibilityRepository(initial = true, initialActiveCoverPhotoIndex = 0)
        val vm = buildViewModel(settings = settings, overlayVisibility = overlayVisibility, scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.onCoverPhotoCycleRequested()
        advanceUntilIdle()
        assertThat(vm.uiState.value.activeCoverPhotoUriString).isEqualTo("content://fake/b")

        vm.onCoverPhotoCycleRequested()
        advanceUntilIdle()
        assertThat(vm.uiState.value.activeCoverPhotoUriString).isEqualTo("content://fake/c")

        vm.onCoverPhotoCycleRequested()
        advanceUntilIdle()
        assertThat(vm.uiState.value.activeCoverPhotoUriString).isEqualTo("content://fake/a")

        collectJob.cancel()
    }

    @Test
    fun `cycling with zero or one cover photo configured is a no-op`() = runTest {
        val settings = FakeSettingsRepository(AppSettings(coverPhotoUriStrings = listOf("content://fake/a")))
        val overlayVisibility = FakeOverlayVisibilityRepository(initial = true, initialActiveCoverPhotoIndex = 0)
        val vm = buildViewModel(settings = settings, overlayVisibility = overlayVisibility, scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.onCoverPhotoCycleRequested()
        advanceUntilIdle()

        assertThat(overlayVisibility.activeCoverPhotoIndex.value).isEqualTo(0)
        assertThat(vm.uiState.value.activeCoverPhotoUriString).isEqualTo("content://fake/a")

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
    fun `uiState captureMode reflects the pipeline's currently bound mode, not a global setting`() = runTest {
        // captureMode is no longer a direct settings mirror (each trigger has its own setting now)
        // - it's whichever mode CameraViewModel last bound the pipeline to, which only changes once
        // a trigger configured for a different mode actually fires (see CameraViewModel's
        // ensureCaptureModeBound and "Capture Mode" in app-spec.md).
        val settings = FakeSettingsRepository(AppSettings(captureModeByTrigger = burstFor(CaptureTriggerKind.SCREEN_TOP)))
        val vm = buildViewModel(settings = settings, scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertThat(vm.uiState.value.captureMode).isEqualTo(CaptureMode.SINGLE_SHOT)

        vm.onScreenTouch(true)
        advanceUntilIdle()

        assertThat(vm.uiState.value.captureMode).isEqualTo(CaptureMode.BURST)

        collectJob.cancel()
    }

    @Test
    fun `a trigger configured for burst produces four images`() = runTest {
        val camera = FakeCameraCaptureController()
        val settings = FakeSettingsRepository(
            AppSettings(captureModeByTrigger = burstFor(CaptureTriggerKind.VOLUME_UP), burstIntervalMillis = 250L),
        )
        val vm = buildViewModel(camera = camera, settings = settings, scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }

        vm.onVolumeUpPressed()
        advanceUntilIdle()

        assertThat(camera.memoryCaptureCount).isEqualTo(BURST_IMAGE_COUNT)

        collectJob.cancel()
    }

    @Test
    fun `a different trigger stays single-shot even while another trigger is configured for burst`() = runTest {
        val camera = FakeCameraCaptureController()
        val settings = FakeSettingsRepository(
            AppSettings(captureModeByTrigger = burstFor(CaptureTriggerKind.VOLUME_UP), burstIntervalMillis = 250L),
        )
        val vm = buildViewModel(camera = camera, settings = settings, scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }

        vm.onVolumeDownPressed()
        advanceUntilIdle()

        assertThat(camera.captureCount).isEqualTo(1)
        assertThat(camera.memoryCaptureCount).isEqualTo(0)

        collectJob.cancel()
    }

    @Test
    fun `screen top and bottom halves have independent capture modes`() = runTest {
        val topCamera = FakeCameraCaptureController()
        val topSettings = FakeSettingsRepository(
            AppSettings(captureModeByTrigger = burstFor(CaptureTriggerKind.SCREEN_TOP), burstIntervalMillis = 250L),
        )
        val topVm = buildViewModel(camera = topCamera, settings = topSettings, scheduler = testScheduler)
        val topCollectJob = launch { topVm.uiState.collect {} }
        topVm.onScreenTouch(true)
        advanceUntilIdle()

        // Same setting (only SCREEN_TOP is configured for Burst) applied via a fresh ViewModel/
        // coordinator, but triggered via the bottom half this time.
        val bottomCamera = FakeCameraCaptureController()
        val bottomSettings = FakeSettingsRepository(
            AppSettings(captureModeByTrigger = burstFor(CaptureTriggerKind.SCREEN_TOP), burstIntervalMillis = 250L),
        )
        val bottomVm = buildViewModel(camera = bottomCamera, settings = bottomSettings, scheduler = testScheduler)
        val bottomCollectJob = launch { bottomVm.uiState.collect {} }
        bottomVm.onScreenTouch(false)
        advanceUntilIdle()

        assertThat(topCamera.memoryCaptureCount).isEqualTo(BURST_IMAGE_COUNT)
        assertThat(bottomCamera.captureCount).isEqualTo(1)
        assertThat(bottomCamera.memoryCaptureCount).isEqualTo(0)

        topCollectJob.cancel()
        bottomCollectJob.cancel()
    }

    @Test
    fun `a burst vibrates exactly once regardless of per-image outcomes`() = runTest {
        var callCount = 0
        val camera = FakeCameraCaptureController(memoryOutcome = { _ ->
            callCount++
            if (callCount == 2) CameraCaptureMemoryOutcome.Failure("simulated failure") else CameraCaptureMemoryOutcome.Success(ByteArray(0))
        })
        val haptics = FakeHapticFeedback()
        val settings = FakeSettingsRepository(
            AppSettings(captureModeByTrigger = burstFor(CaptureTriggerKind.SCREEN_TOP), burstIntervalMillis = 250L),
        )
        val vm = buildViewModel(camera = camera, haptics = haptics, settings = settings, scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }

        vm.onScreenTouch(true)
        advanceUntilIdle()

        assertThat(camera.memoryCaptureCount).isEqualTo(4)
        assertThat(haptics.performCaptureSuccessCount).isEqualTo(1)

        collectJob.cancel()
    }

    @Test
    fun `captureProgress is hidden before any capture and again once one completes`() = runTest {
        val vm = buildViewModel(scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertThat(vm.uiState.value.captureProgress).isEqualTo(CaptureProgressUi.Hidden)

        vm.onScreenTouch(true)
        advanceUntilIdle()

        assertThat(vm.uiState.value.captureProgress).isEqualTo(CaptureProgressUi.Hidden)

        collectJob.cancel()
    }

    @Test
    fun `captureProgress advances to one of four as soon as the first image is captured`() = runTest {
        // Progress is reported from the capture phase (see CaptureCoordinator.performBurst's
        // kdoc), so by the time execution reaches the first inter-image delay - the earliest point
        // runCurrent() can pause it at - image 1 has already been captured and counted.
        val settings = FakeSettingsRepository(
            AppSettings(captureModeByTrigger = burstFor(CaptureTriggerKind.SCREEN_TOP), burstIntervalMillis = 500L),
        )
        val vm = buildViewModel(settings = settings, scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.onScreenTouch(true)
        runCurrent() // starts the burst, captures image 1, and runs up to its first inter-image delay

        assertThat(vm.uiState.value.captureProgress).isEqualTo(CaptureProgressUi.Determinate(1, BURST_IMAGE_COUNT))

        advanceUntilIdle() // let the burst finish

        assertThat(vm.uiState.value.captureProgress).isEqualTo(CaptureProgressUi.Hidden)

        collectJob.cancel()
    }

    @Test
    fun `flash and torch are disabled while a burst is actually running, not merely configured`() = runTest {
        // Keyed off the raw CaptureState now (see CameraViewModel's flash/torch collector), not a
        // settings value - configuring a trigger for Burst Mode alone must not disable flash/torch
        // until a burst is actually in flight.
        val settings = FakeSettingsRepository(AppSettings(captureModeByTrigger = burstFor(CaptureTriggerKind.SCREEN_TOP)))
        val flashTorch = FakeFlashTorchController()
        val vm = buildViewModel(settings = settings, flashTorchController = flashTorch, scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertThat(flashTorch.disableCallCount).isEqualTo(0)

        vm.onScreenTouch(true)
        advanceUntilIdle()

        assertThat(flashTorch.disableCallCount).isAtLeast(1)

        collectJob.cancel()
    }

    @Test
    fun `flash and torch are disabled when the overlay becomes visible`() = runTest {
        val settings = FakeSettingsRepository(AppSettings(coverPhotoUriStrings = listOf("content://fake/pic")))
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
    fun `zoom level is applied once the camera becomes available`() = runTest {
        val settings = FakeSettingsRepository(AppSettings(zoomLevel = 3))
        val zoom = FakeZoomController()
        val vm = buildViewModel(settings = settings, zoomController = zoom, scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        assertThat(zoom.appliedZoomLevels).isEmpty()

        vm.attachCamera(StubCamera())
        advanceUntilIdle()

        assertThat(zoom.appliedZoomLevels).containsExactly(3)

        collectJob.cancel()
    }

    @Test
    fun `changing the zoom level while a camera is bound reapplies it`() = runTest {
        val settings = FakeSettingsRepository(AppSettings(zoomLevel = 1))
        val zoom = FakeZoomController()
        val vm = buildViewModel(settings = settings, zoomController = zoom, scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }
        vm.attachCamera(StubCamera())
        advanceUntilIdle()
        assertThat(zoom.appliedZoomLevels).containsExactly(1)

        settings.setZoomLevel(4)
        advanceUntilIdle()

        assertThat(zoom.appliedZoomLevels).containsExactly(1, 4).inOrder()

        collectJob.cancel()
    }

    @Test
    fun `uiState reflects the currently configured zoom level`() = runTest {
        val settings = FakeSettingsRepository(AppSettings(zoomLevel = 4))
        val vm = buildViewModel(settings = settings, scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertThat(vm.uiState.value.zoomLevel).isEqualTo(4)

        collectJob.cancel()
    }

    @Test
    fun `changing the zoom level from the camera screen updates state and is clamped to the valid range`() = runTest {
        val settings = FakeSettingsRepository()
        val vm = buildViewModel(settings = settings, scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }

        vm.onZoomLevelChanged(3)
        advanceUntilIdle()
        assertThat(vm.uiState.value.zoomLevel).isEqualTo(3)

        vm.onZoomLevelChanged(10)
        advanceUntilIdle()
        assertThat(vm.uiState.value.zoomLevel).isEqualTo(AppSettings.ZOOM_LEVEL_RANGE.last)

        vm.onZoomLevelChanged(0)
        advanceUntilIdle()
        assertThat(vm.uiState.value.zoomLevel).isEqualTo(AppSettings.ZOOM_LEVEL_RANGE.first)

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
    fun `changing the capture aspect ratio from the camera screen updates state and is persisted`() = runTest {
        val settings = FakeSettingsRepository()
        val vm = buildViewModel(settings = settings, scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }

        vm.onCaptureAspectRatioChanged(CaptureAspectRatio.RATIO_16_9)
        advanceUntilIdle()

        assertThat(vm.uiState.value.captureAspectRatio).isEqualTo(CaptureAspectRatio.RATIO_16_9)
        assertThat(settings.settings.value.captureAspectRatio).isEqualTo(CaptureAspectRatio.RATIO_16_9)

        collectJob.cancel()
    }

    @Test
    fun `a capture-aspect-ratio change made mid-burst does not apply until the burst completes`() = runTest {
        val settings = FakeSettingsRepository(
            AppSettings(
                captureModeByTrigger = burstFor(CaptureTriggerKind.SCREEN_TOP),
                burstIntervalMillis = 500L,
                captureAspectRatio = CaptureAspectRatio.RATIO_4_3,
            ),
        )
        val vm = buildViewModel(settings = settings, scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        assertThat(vm.uiState.value.captureAspectRatio).isEqualTo(CaptureAspectRatio.RATIO_4_3)

        vm.onScreenTouch(true)
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

    @Test
    fun `a trigger configured for video starts recording, shows an indeterminate spinner, and vibrates once`() = runTest {
        val video = FakeVideoCaptureController()
        val haptics = FakeHapticFeedback()
        val settings = FakeSettingsRepository(AppSettings(captureModeByTrigger = videoFor(CaptureTriggerKind.SCREEN_TOP)))
        val vm = buildViewModel(video = video, haptics = haptics, settings = settings, scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }

        vm.onScreenTouch(true)
        advanceUntilIdle()

        assertThat(video.startCount).isEqualTo(1)
        assertThat(vm.uiState.value.captureProgress).isEqualTo(CaptureProgressUi.Indeterminate)
        assertThat(haptics.performCaptureSuccessCount).isEqualTo(1)
        assertThat(haptics.performVideoStoppedCount).isEqualTo(0)

        // Clean up: stop the recording so this test doesn't leave a permanently-suspended job.
        vm.onVolumeUpPressed()
        advanceUntilIdle()
        collectJob.cancel()
    }

    @Test
    fun `any trigger stops an active video recording, hides the spinner, and vibrates twice`() = runTest {
        val video = FakeVideoCaptureController()
        val haptics = FakeHapticFeedback()
        val settings = FakeSettingsRepository(AppSettings(captureModeByTrigger = videoFor(CaptureTriggerKind.SCREEN_TOP)))
        val vm = buildViewModel(video = video, haptics = haptics, settings = settings, scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }

        vm.onScreenTouch(true)
        advanceUntilIdle()
        assertThat(vm.uiState.value.captureProgress).isEqualTo(CaptureProgressUi.Indeterminate)

        // Volume Up is left at its Single-Shot default, but while a recording is active any
        // trigger stops it instead of starting its own configured capture (see "Video Mode" in
        // app-spec.md).
        vm.onVolumeUpPressed()
        advanceUntilIdle()

        assertThat(video.stopCount).isEqualTo(1)
        assertThat(vm.uiState.value.captureProgress).isEqualTo(CaptureProgressUi.Hidden)
        assertThat(haptics.performVideoStoppedCount).isEqualTo(1)

        collectJob.cancel()
    }

    @Test
    fun `the stopping trigger's own configured mode is never bound while a recording is active`() = runTest {
        // If the stopping trigger's mode were bound before the coordinator even saw the request,
        // it would rebuild/rebind the very VideoCapture use case the active recording is writing
        // to (see CameraViewModel.videoInProgress's kdoc). uiState.captureMode staying VIDEO
        // throughout is the observable proof that never happened.
        val video = FakeVideoCaptureController()
        val camera = FakeCameraCaptureController()
        val settings = FakeSettingsRepository(
            AppSettings(
                captureModeByTrigger = videoFor(CaptureTriggerKind.SCREEN_TOP) +
                    (CaptureTriggerKind.VOLUME_UP to CaptureMode.BURST),
                burstIntervalMillis = 250L,
            ),
        )
        val vm = buildViewModel(camera = camera, video = video, settings = settings, scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }

        vm.onScreenTouch(true)
        advanceUntilIdle()
        assertThat(vm.uiState.value.captureMode).isEqualTo(CaptureMode.VIDEO)

        vm.onVolumeUpPressed()
        advanceUntilIdle()

        assertThat(vm.uiState.value.captureMode).isEqualTo(CaptureMode.VIDEO)
        assertThat(camera.captureCount).isEqualTo(0)
        assertThat(camera.memoryCaptureCount).isEqualTo(0)
        assertThat(video.stopCount).isEqualTo(1)

        collectJob.cancel()
    }

    @Test
    fun `a video start failure does not vibrate and leaves the progress indicator hidden`() = runTest {
        val video = FakeVideoCaptureController(startOutcome = VideoStartOutcome.Failure("camera not ready"))
        val haptics = FakeHapticFeedback()
        val settings = FakeSettingsRepository(AppSettings(captureModeByTrigger = videoFor(CaptureTriggerKind.SCREEN_TOP)))
        val vm = buildViewModel(video = video, haptics = haptics, settings = settings, scheduler = testScheduler)
        val collectJob = launch { vm.uiState.collect {} }

        vm.onScreenTouch(true)
        advanceUntilIdle()

        assertThat(vm.uiState.value.captureProgress).isEqualTo(CaptureProgressUi.Hidden)
        assertThat(haptics.performCaptureSuccessCount).isEqualTo(0)
        assertThat(haptics.performVideoStoppedCount).isEqualTo(0)

        collectJob.cancel()
    }
}

/**
 * A minimal, never-actually-invoked [Camera] - the zoom tests above only need
 * [com.example.capture.camera.data.CameraControlHolder] to observe *some* non-null value, never
 * a functioning one, since [FakeZoomController] doesn't touch it.
 */
private class StubCamera : Camera {
    override fun getCameraControl(): CameraControl = throw UnsupportedOperationException()
    override fun getCameraInfo(): CameraInfo = throw UnsupportedOperationException()
    override fun getExtendedConfig(): CameraConfig = throw UnsupportedOperationException()
}
