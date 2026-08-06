package com.example.capture.camera.domain

import app.cash.turbine.test
import com.example.capture.testing.FakeCameraCaptureController
import com.example.capture.testing.FakeCaptureAttemptIdGenerator
import com.example.capture.testing.FakeCaptureDiagnosticsLogger
import com.example.capture.testing.FakeCaptureErrorLogger
import com.example.capture.testing.FakeCaptureMetadataLogger
import com.example.capture.testing.FakeImageMetadataReader
import com.example.capture.testing.FakePhotoStorage
import com.example.capture.testing.FakeTimeProvider
import com.example.capture.testing.TestDispatcherProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureCoordinatorTest {

    private val timeProvider = FakeTimeProvider(startMillis = 1_000L)

    private fun buildCoordinator(
        camera: FakeCameraCaptureController = FakeCameraCaptureController(),
        storage: FakePhotoStorage = FakePhotoStorage(),
        errorLogger: FakeCaptureErrorLogger = FakeCaptureErrorLogger(),
        imageMetadataReader: FakeImageMetadataReader = FakeImageMetadataReader(),
        metadataLogger: FakeCaptureMetadataLogger = FakeCaptureMetadataLogger(),
        attemptIdGenerator: FakeCaptureAttemptIdGenerator = FakeCaptureAttemptIdGenerator(),
        diagnosticsLogger: FakeCaptureDiagnosticsLogger = FakeCaptureDiagnosticsLogger(),
        testScheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
    ): CaptureCoordinator {
        // Burst scheduling reads elapsed time across multiple delay() calls within one
        // requestCapture call (see CaptureCoordinator.performBurst) - attaching the scheduler keeps
        // timeProvider consistent with delay()'s own virtual-time advancement instead of staying
        // frozen while it happens (see FakeTimeProvider's kdoc).
        timeProvider.attachScheduler(testScheduler)
        val dispatcher = StandardTestDispatcher(testScheduler)
        return CaptureCoordinator(
            camera,
            storage,
            timeProvider,
            TestDispatcherProvider(dispatcher),
            errorLogger,
            imageMetadataReader,
            metadataLogger,
            attemptIdGenerator,
            diagnosticsLogger,
        )
    }

    @Test
    fun `idle is the initial state`() = runTest {
        val sut = buildCoordinator(testScheduler = testScheduler)
        assertThat(sut.state.value).isEqualTo(CaptureState.Idle)
    }

    @Test
    fun `successful capture transitions idle to capturing to completed with success`() = runTest {
        val camera = FakeCameraCaptureController()
        val storage = FakePhotoStorage()
        val sut = buildCoordinator(camera, storage, testScheduler = testScheduler)

        sut.state.test {
            assertThat(awaitItem()).isEqualTo(CaptureState.Idle)

            sut.requestCapture(CaptureTrigger.ScreenTouch)

            val capturing = awaitItem()
            assertThat(capturing).isInstanceOf(CaptureState.Capturing::class.java)
            assertThat((capturing as CaptureState.Capturing).trigger).isEqualTo(CaptureTrigger.ScreenTouch)

            val completed = awaitItem() as CaptureState.Completed
            val outcome = completed.result.outcome
            assertThat(outcome).isInstanceOf(CaptureOutcome.Success::class.java)
            assertThat((outcome as CaptureOutcome.Success).uriString).isEqualTo(storage.finalized.single().uriString)
        }
        assertThat(storage.discarded).isEmpty()
    }

    @Test
    fun `camera failure discards the pending entry and reports failure`() = runTest {
        val camera = FakeCameraCaptureController { CameraCaptureOutcome.Failure("no camera hardware") }
        val storage = FakePhotoStorage()
        val sut = buildCoordinator(camera, storage, testScheduler = testScheduler)

        sut.requestCapture(CaptureTrigger.VolumeUp)

        val completed = sut.state.value as CaptureState.Completed
        assertThat(completed.result.outcome).isEqualTo(CaptureOutcome.Failure("no camera hardware"))
        assertThat(storage.discarded).hasSize(1)
        assertThat(storage.finalized).isEmpty()
    }

    @Test
    fun `storage failure while finalizing also discards the entry`() = runTest {
        val camera = FakeCameraCaptureController()
        val storage = FakePhotoStorage(failFinalize = true)
        val sut = buildCoordinator(camera, storage, testScheduler = testScheduler)

        sut.requestCapture(CaptureTrigger.VolumeDown)

        val completed = sut.state.value as CaptureState.Completed
        assertThat(completed.result.outcome).isInstanceOf(CaptureOutcome.Failure::class.java)
        assertThat(storage.discarded).hasSize(1)
    }

    @Test
    fun `every completed capture records which trigger produced it`() = runTest {
        val sut = buildCoordinator(testScheduler = testScheduler)

        sut.requestCapture(CaptureTrigger.VoiceCommand("cheese"))

        val completed = sut.state.value as CaptureState.Completed
        assertThat(completed.result.trigger).isEqualTo(CaptureTrigger.VoiceCommand("cheese"))
    }

    @Test
    fun `a rapid duplicate trigger right after an accepted one is dropped`() = runTest {
        val camera = FakeCameraCaptureController()
        val sut = buildCoordinator(camera, testScheduler = testScheduler)

        sut.requestCapture(CaptureTrigger.VolumeUp)
        sut.requestCapture(CaptureTrigger.VolumeDown) // same fake clock tick: within the debounce window

        assertThat(camera.captureCount).isEqualTo(1)
    }

    @Test
    fun `a trigger after the debounce window has elapsed is accepted`() = runTest {
        val camera = FakeCameraCaptureController()
        val sut = buildCoordinator(camera, testScheduler = testScheduler)

        sut.requestCapture(CaptureTrigger.VolumeUp)
        timeProvider.currentMillis += 2_000
        sut.requestCapture(CaptureTrigger.VolumeDown)

        assertThat(camera.captureCount).isEqualTo(2)
    }

    @Test
    fun `simultaneous concurrent triggers are serialized and only the first is captured`() = runTest {
        val camera = FakeCameraCaptureController()
        val sut = buildCoordinator(camera, testScheduler = testScheduler)

        val jobs = List(5) {
            launch { sut.requestCapture(CaptureTrigger.ScreenTouch) }
        }
        advanceUntilIdle()
        jobs.forEach { it.join() }

        assertThat(camera.captureCount).isEqualTo(1)
        assertThat(sut.state.value).isInstanceOf(CaptureState.Completed::class.java)
    }

    @Test
    fun `burst mode issues four capture requests through the same central operation`() = runTest {
        val camera = FakeCameraCaptureController()
        val sut = buildCoordinator(camera, testScheduler = testScheduler)

        sut.requestCapture(CaptureTrigger.ScreenTouch, CaptureMode.BURST, burstIntervalMillis = 500L)

        assertThat(camera.memoryCaptureCount).isEqualTo(BURST_IMAGE_COUNT)
        val completed = sut.state.value as CaptureState.BurstCompleted
        assertThat(completed.results).hasSize(BURST_IMAGE_COUNT)
        assertThat(completed.results).isNotEmpty()
        completed.results.forEach { assertThat(it.trigger).isEqualTo(CaptureTrigger.ScreenTouch) }
    }

    @Test
    fun `burst mode reports progress once per image, in order, right after each is captured`() = runTest {
        val camera = FakeCameraCaptureController()
        val sut = buildCoordinator(camera, testScheduler = testScheduler)

        sut.state.test {
            assertThat(awaitItem()).isEqualTo(CaptureState.Idle)

            sut.requestCapture(CaptureTrigger.ScreenTouch, CaptureMode.BURST, burstIntervalMillis = 250L)

            assertThat(awaitItem()).isInstanceOf(CaptureState.BurstStarted::class.java)
            for (expectedCount in 1..BURST_IMAGE_COUNT) {
                val progress = awaitItem() as CaptureState.BurstProgress
                assertThat(progress.imagesCompleted).isEqualTo(expectedCount)
            }
            assertThat(awaitItem()).isInstanceOf(CaptureState.BurstCompleted::class.java)
        }
    }

    @Test
    fun `burst mode spaces captures by the configured interval when capture itself is instant`() = runTest {
        val camera = FakeCameraCaptureController()
        val sut = buildCoordinator(camera, testScheduler = testScheduler)

        val before = testScheduler.currentTime
        sut.requestCapture(CaptureTrigger.ScreenTouch, CaptureMode.BURST, burstIntervalMillis = 500L)
        val elapsed = testScheduler.currentTime - before

        assertThat(elapsed).isEqualTo((BURST_IMAGE_COUNT - 1) * 500L)
    }

    @Test
    fun `burst mode adds no delay once capture itself already exceeds the configured interval`() = runTest {
        // Clock-anchored scheduling: each shot targets a fixed burstStart + n*interval instant, so
        // a capture that alone takes longer than the interval leaves nothing left to wait for.
        val camera = FakeCameraCaptureController(memoryOutcome = { _ ->
            timeProvider.currentMillis += 800L
            CameraCaptureMemoryOutcome.Success(ByteArray(0))
        })
        val sut = buildCoordinator(camera, testScheduler = testScheduler)

        val before = testScheduler.currentTime
        sut.requestCapture(CaptureTrigger.ScreenTouch, CaptureMode.BURST, burstIntervalMillis = 250L)
        val elapsed = testScheduler.currentTime - before

        assertThat(elapsed).isEqualTo(0L)
    }

    @Test
    fun `burst mode only waits out whatever's left of the interval after a partially-slow capture`() = runTest {
        val camera = FakeCameraCaptureController(memoryOutcome = { _ ->
            timeProvider.currentMillis += 100L
            CameraCaptureMemoryOutcome.Success(ByteArray(0))
        })
        val sut = buildCoordinator(camera, testScheduler = testScheduler)

        val before = testScheduler.currentTime
        sut.requestCapture(CaptureTrigger.ScreenTouch, CaptureMode.BURST, burstIntervalMillis = 250L)
        val elapsed = testScheduler.currentTime - before

        assertThat(elapsed).isEqualTo((BURST_IMAGE_COUNT - 1) * 150L)
    }

    @Test
    fun `a capture command received while a burst is in progress is dropped`() = runTest {
        val camera = FakeCameraCaptureController()
        val sut = buildCoordinator(camera, testScheduler = testScheduler)

        val burstJob = launch {
            sut.requestCapture(CaptureTrigger.ScreenTouch, CaptureMode.BURST, burstIntervalMillis = 500L)
        }
        val overlappingJob = launch { sut.requestCapture(CaptureTrigger.VolumeUp) }
        advanceUntilIdle()
        burstJob.join()
        overlappingJob.join()

        // Only the burst's own four images - the overlapping single-shot request never starts.
        assertThat(camera.memoryCaptureCount).isEqualTo(BURST_IMAGE_COUNT)
    }

    @Test
    fun `an error on one burst image does not cancel the remaining images`() = runTest {
        var callCount = 0
        val camera = FakeCameraCaptureController(memoryOutcome = { _ ->
            callCount++
            if (callCount == 2) CameraCaptureMemoryOutcome.Failure("simulated failure") else CameraCaptureMemoryOutcome.Success(ByteArray(0))
        })
        val sut = buildCoordinator(camera, testScheduler = testScheduler)

        sut.requestCapture(CaptureTrigger.ScreenTouch, CaptureMode.BURST, burstIntervalMillis = 250L)

        assertThat(camera.memoryCaptureCount).isEqualTo(BURST_IMAGE_COUNT)
        val completed = sut.state.value as CaptureState.BurstCompleted
        assertThat(completed.results).hasSize(BURST_IMAGE_COUNT)
        assertThat(completed.results[0].outcome).isInstanceOf(CaptureOutcome.Success::class.java)
        assertThat(completed.results[1].outcome).isInstanceOf(CaptureOutcome.Failure::class.java)
        assertThat(completed.results[2].outcome).isInstanceOf(CaptureOutcome.Success::class.java)
        assertThat(completed.results[3].outcome).isInstanceOf(CaptureOutcome.Success::class.java)
    }

    @Test
    fun `a camera capture failure logs a structured error entry`() = runTest {
        val camera = FakeCameraCaptureController { CameraCaptureOutcome.Failure("no camera hardware") }
        val errorLogger = FakeCaptureErrorLogger()
        val sut = buildCoordinator(camera, errorLogger = errorLogger, testScheduler = testScheduler)

        sut.requestCapture(CaptureTrigger.VolumeUp)

        val entry = errorLogger.loggedEntries.single()
        assertThat(entry.captureMode).isEqualTo(CaptureMode.SINGLE_SHOT)
        assertThat(entry.errorMessage).isEqualTo("no camera hardware")
        assertThat(entry.burstImageNumber).isNull()
        assertThat(entry.burstIntervalMillis).isNull()
    }

    @Test
    fun `a burst image failure logs its burst image number and configured interval`() = runTest {
        var callCount = 0
        val camera = FakeCameraCaptureController(memoryOutcome = { _ ->
            callCount++
            if (callCount == 3) CameraCaptureMemoryOutcome.Failure("simulated failure") else CameraCaptureMemoryOutcome.Success(ByteArray(0))
        })
        val errorLogger = FakeCaptureErrorLogger()
        val sut = buildCoordinator(camera, errorLogger = errorLogger, testScheduler = testScheduler)

        sut.requestCapture(CaptureTrigger.ScreenTouch, CaptureMode.BURST, burstIntervalMillis = 250L)

        val entry = errorLogger.loggedEntries.single()
        assertThat(entry.captureMode).isEqualTo(CaptureMode.BURST)
        assertThat(entry.burstImageNumber).isEqualTo(3)
        assertThat(entry.burstIntervalMillis).isEqualTo(250L)
        assertThat(entry.errorMessage).isEqualTo("simulated failure")
    }

    @Test
    fun `a successful capture logs metadata matching the requested aspect ratio`() = runTest {
        val camera = FakeCameraCaptureController()
        val metadataLogger = FakeCaptureMetadataLogger()
        val imageMetadataReader = FakeImageMetadataReader(
            nextMetadata = ImageMetadata(widthPx = 4032, heightPx = 3024, exifOrientation = 1),
        )
        val sut = buildCoordinator(
            camera,
            imageMetadataReader = imageMetadataReader,
            metadataLogger = metadataLogger,
            testScheduler = testScheduler,
        )

        sut.requestCapture(CaptureTrigger.ScreenTouch, captureAspectRatio = CaptureAspectRatio.RATIO_4_3)

        val entry = metadataLogger.loggedEntries.single()
        assertThat(entry.widthPx).isEqualTo(4032)
        assertThat(entry.heightPx).isEqualTo(3024)
        assertThat(entry.requestedAspectRatio).isEqualTo(CaptureAspectRatio.RATIO_4_3)
        assertThat(entry.actualAspectRatio).isEqualTo(CaptureAspectRatio.RATIO_4_3)
        assertThat(entry.matchesTolerance).isTrue()
        assertThat(entry.exifOrientation).isEqualTo(1)
    }

    @Test
    fun `metadata is logged as a mismatch when the actual ratio differs from the requested one`() = runTest {
        val camera = FakeCameraCaptureController()
        val metadataLogger = FakeCaptureMetadataLogger()
        // 16:9 dimensions returned even though 4:3 was requested - simulates CameraX falling back
        // to the closest supported configuration (see "Preview and capture consistency").
        val imageMetadataReader = FakeImageMetadataReader(
            nextMetadata = ImageMetadata(widthPx = 4000, heightPx = 2250, exifOrientation = null),
        )
        val sut = buildCoordinator(
            camera,
            imageMetadataReader = imageMetadataReader,
            metadataLogger = metadataLogger,
            testScheduler = testScheduler,
        )

        sut.requestCapture(CaptureTrigger.ScreenTouch, captureAspectRatio = CaptureAspectRatio.RATIO_4_3)

        val entry = metadataLogger.loggedEntries.single()
        assertThat(entry.requestedAspectRatio).isEqualTo(CaptureAspectRatio.RATIO_4_3)
        assertThat(entry.actualAspectRatio).isEqualTo(CaptureAspectRatio.RATIO_16_9)
        assertThat(entry.matchesTolerance).isFalse()
    }

    @Test
    fun `no metadata is logged when the capture fails`() = runTest {
        val camera = FakeCameraCaptureController { CameraCaptureOutcome.Failure("no camera hardware") }
        val metadataLogger = FakeCaptureMetadataLogger()
        val sut = buildCoordinator(camera, metadataLogger = metadataLogger, testScheduler = testScheduler)

        sut.requestCapture(CaptureTrigger.ScreenTouch)

        assertThat(metadataLogger.loggedEntries).isEmpty()
    }

    @Test
    fun `no metadata is logged when the saved image can't be read back`() = runTest {
        val camera = FakeCameraCaptureController()
        val metadataLogger = FakeCaptureMetadataLogger()
        val imageMetadataReader = FakeImageMetadataReader(failNextRead = true)
        val sut = buildCoordinator(
            camera,
            imageMetadataReader = imageMetadataReader,
            metadataLogger = metadataLogger,
            testScheduler = testScheduler,
        )

        sut.requestCapture(CaptureTrigger.ScreenTouch)

        assertThat(metadataLogger.loggedEntries).isEmpty()
    }

    @Test
    fun `each burst image logs its own metadata entry`() = runTest {
        val camera = FakeCameraCaptureController()
        val metadataLogger = FakeCaptureMetadataLogger()
        val sut = buildCoordinator(camera, metadataLogger = metadataLogger, testScheduler = testScheduler)

        sut.requestCapture(CaptureTrigger.ScreenTouch, CaptureMode.BURST, burstIntervalMillis = 250L)

        assertThat(metadataLogger.loggedEntries).hasSize(BURST_IMAGE_COUNT)
    }

    @Test
    fun `every diagnostic event for a single-shot capture shares the same attempt id`() = runTest {
        val camera = FakeCameraCaptureController()
        val diagnosticsLogger = FakeCaptureDiagnosticsLogger()
        val sut = buildCoordinator(camera, diagnosticsLogger = diagnosticsLogger, testScheduler = testScheduler)

        sut.requestCapture(CaptureTrigger.ScreenTouch)

        val attemptIds = diagnosticsLogger.loggedEvents.map { it.attemptId }.distinct()
        assertThat(attemptIds).hasSize(1)
        assertThat(
            diagnosticsLogger.loggedEvents.map { it::class.simpleName },
        ).containsExactly("Requested", "Accepted", "CameraXRequestSubmitted", "ImageSaved", "Completed").inOrder()
    }

    @Test
    fun `a burst shares one attempt id across all of its images`() = runTest {
        val camera = FakeCameraCaptureController()
        val diagnosticsLogger = FakeCaptureDiagnosticsLogger()
        val sut = buildCoordinator(camera, diagnosticsLogger = diagnosticsLogger, testScheduler = testScheduler)

        sut.requestCapture(CaptureTrigger.ScreenTouch, CaptureMode.BURST, burstIntervalMillis = 250L)

        val attemptIds = diagnosticsLogger.loggedEvents.map { it.attemptId }.distinct()
        assertThat(attemptIds).hasSize(1)
        val submitted = diagnosticsLogger.loggedEvents.filterIsInstance<CaptureDiagnosticEvent.CameraXRequestSubmitted>()
        assertThat(submitted.map { it.burstImageNumber }).containsExactly(1, 2, 3, 4).inOrder()
    }

    // Note: there is no dedicated test for the CAPTURE_ALREADY_RUNNING reason (the sibling branch
    // to BURST_ALREADY_RUNNING below, in the same two-line `if` in requestCapture) - with these
    // fakes, a Single-Shot capture has no real suspension point, so two concurrent single-shot
    // requests never actually overlap in this test environment; only the Burst path's genuine
    // delay() gives the interleaving needed to prove mutex contention deterministically.

    @Test
    fun `a capture command received while a burst is in flight is rejected as burst already running`() = runTest {
        val camera = FakeCameraCaptureController()
        val diagnosticsLogger = FakeCaptureDiagnosticsLogger()
        val sut = buildCoordinator(camera, diagnosticsLogger = diagnosticsLogger, testScheduler = testScheduler)

        val burstJob = launch {
            sut.requestCapture(CaptureTrigger.ScreenTouch, CaptureMode.BURST, burstIntervalMillis = 500L)
        }
        val overlappingJob = launch { sut.requestCapture(CaptureTrigger.VolumeUp) }
        advanceUntilIdle()
        burstJob.join()
        overlappingJob.join()

        val rejected = diagnosticsLogger.loggedEvents.filterIsInstance<CaptureDiagnosticEvent.Rejected>().single()
        assertThat(rejected.reason).isEqualTo(CaptureRejectionReason.BURST_ALREADY_RUNNING)
    }

    @Test
    fun `every completed capture result carries the attempt id its diagnostic events share`() = runTest {
        val camera = FakeCameraCaptureController()
        val attemptIdGenerator = FakeCaptureAttemptIdGenerator()
        val diagnosticsLogger = FakeCaptureDiagnosticsLogger()
        val sut = buildCoordinator(
            camera,
            attemptIdGenerator = attemptIdGenerator,
            diagnosticsLogger = diagnosticsLogger,
            testScheduler = testScheduler,
        )

        sut.requestCapture(CaptureTrigger.ScreenTouch)

        val completed = sut.state.value as CaptureState.Completed
        assertThat(completed.result.attemptId).isEqualTo(diagnosticsLogger.loggedEvents.first().attemptId)
    }
}
