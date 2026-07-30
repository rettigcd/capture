package com.example.capture.camera.domain

import app.cash.turbine.test
import com.example.capture.testing.FakeCameraCaptureController
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
        testScheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
    ): CaptureCoordinator {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return CaptureCoordinator(camera, storage, timeProvider, TestDispatcherProvider(dispatcher))
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
        val sut = buildCoordinator(camera, storage, testScheduler)

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
        val sut = buildCoordinator(camera, storage, testScheduler)

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
        val sut = buildCoordinator(camera, storage, testScheduler)

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
}
