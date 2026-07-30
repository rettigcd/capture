package com.example.capture.camera.domain

import app.cash.turbine.test
import com.example.capture.testing.FakeCameraCaptureController
import com.example.capture.testing.FakeCaptureErrorLogger
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
        testScheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
    ): CaptureCoordinator {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return CaptureCoordinator(camera, storage, timeProvider, TestDispatcherProvider(dispatcher), errorLogger)
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

        assertThat(camera.captureCount).isEqualTo(BURST_IMAGE_COUNT)
        val completed = sut.state.value as CaptureState.BurstCompleted
        assertThat(completed.results).hasSize(BURST_IMAGE_COUNT)
        assertThat(completed.results).isNotEmpty()
        completed.results.forEach { assertThat(it.trigger).isEqualTo(CaptureTrigger.ScreenTouch) }
    }

    @Test
    fun `burst mode spaces captures by the configured interval`() = runTest {
        val camera = FakeCameraCaptureController()
        val sut = buildCoordinator(camera, testScheduler = testScheduler)

        val before = testScheduler.currentTime
        sut.requestCapture(CaptureTrigger.ScreenTouch, CaptureMode.BURST, burstIntervalMillis = 500L)
        val elapsed = testScheduler.currentTime - before

        assertThat(elapsed).isAtLeast((BURST_IMAGE_COUNT - 1) * 500L)
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
        assertThat(camera.captureCount).isEqualTo(BURST_IMAGE_COUNT)
    }

    @Test
    fun `an error on one burst image does not cancel the remaining images`() = runTest {
        var callCount = 0
        val camera = FakeCameraCaptureController { _ ->
            callCount++
            if (callCount == 2) CameraCaptureOutcome.Failure("simulated failure") else CameraCaptureOutcome.Success
        }
        val sut = buildCoordinator(camera, testScheduler = testScheduler)

        sut.requestCapture(CaptureTrigger.ScreenTouch, CaptureMode.BURST, burstIntervalMillis = 250L)

        assertThat(camera.captureCount).isEqualTo(BURST_IMAGE_COUNT)
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
        val camera = FakeCameraCaptureController { _ ->
            callCount++
            if (callCount == 3) CameraCaptureOutcome.Failure("simulated failure") else CameraCaptureOutcome.Success
        }
        val errorLogger = FakeCaptureErrorLogger()
        val sut = buildCoordinator(camera, errorLogger = errorLogger, testScheduler = testScheduler)

        sut.requestCapture(CaptureTrigger.ScreenTouch, CaptureMode.BURST, burstIntervalMillis = 250L)

        val entry = errorLogger.loggedEntries.single()
        assertThat(entry.captureMode).isEqualTo(CaptureMode.BURST)
        assertThat(entry.burstImageNumber).isEqualTo(3)
        assertThat(entry.burstIntervalMillis).isEqualTo(250L)
        assertThat(entry.errorMessage).isEqualTo("simulated failure")
    }
}
