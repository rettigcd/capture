package com.example.capture.settings.ui

import com.example.capture.camera.domain.CaptureAspectRatio
import com.example.capture.camera.domain.CaptureMode
import com.example.capture.camera.domain.CaptureTriggerKind
import com.example.capture.settings.domain.AppSettings
import com.example.capture.testing.FakeOverlayImageStore
import com.example.capture.testing.FakeSettingsRepository
import com.google.common.truth.Truth.assertThat
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // Shares mainDispatcher with viewModelScope (set as the Main dispatcher above), matching how
    // CameraViewModel's real @ApplicationScope and viewModelScope are both driven by the app's
    // actual dispatchers - this is what proves onImageSelected et al. no longer depend on
    // viewModelScope surviving navigation away from the settings screen.
    private fun buildViewModel(
        repository: FakeSettingsRepository,
        overlayImageStore: FakeOverlayImageStore = FakeOverlayImageStore(),
    ): SettingsViewModel = SettingsViewModel(repository, overlayImageStore, CoroutineScope(mainDispatcher))

    @Test
    fun `initial ui state reflects the repository's defaults`() = runTest {
        val vm = buildViewModel(FakeSettingsRepository())
        val collectJob = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertThat(vm.uiState.value.vibrationDurationMillis).isEqualTo(AppSettings.DEFAULT_VIBRATION_DURATION_MILLIS)
        assertThat(vm.uiState.value.overlayImageUriString).isNull()

        collectJob.cancel()
    }

    @Test
    fun `initial ui state reflects a previously saved value`() = runTest {
        val repository = FakeSettingsRepository(
            AppSettings(
                vibrationDurationMillis = 180L,
                overlayImageUriString = "content://fake/pic",
            ),
        )
        val vm = buildViewModel(repository)
        val collectJob = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertThat(vm.uiState.value.vibrationDurationMillis).isEqualTo(180L)
        assertThat(vm.uiState.value.overlayImageUriString).isEqualTo("content://fake/pic")

        collectJob.cancel()
    }

    @Test
    fun `changing the vibration duration updates state and is clamped to the valid range`() = runTest {
        val repository = FakeSettingsRepository()
        val vm = buildViewModel(repository)
        val collectJob = launch { vm.uiState.collect {} }

        vm.onVibrationDurationChanged(180L)
        advanceUntilIdle()
        assertThat(vm.uiState.value.vibrationDurationMillis).isEqualTo(180L)

        vm.onVibrationDurationChanged(10_000L)
        advanceUntilIdle()
        assertThat(vm.uiState.value.vibrationDurationMillis)
            .isEqualTo(AppSettings.VIBRATION_DURATION_RANGE_MILLIS.last)

        collectJob.cancel()
    }

    @Test
    fun `selecting an image copies it into durable storage before persisting the result`() = runTest {
        // Regression test for a real bug, found via direct device inspection: the system Photo
        // Picker's read grant for its own content:// Uri does not reliably survive a process
        // restart, so loading a Uri that DataStore had correctly remembered still threw
        // SecurityException after relaunching. The fix copies the image into app-private storage
        // immediately (via OverlayImageStore) and persists *that* Uri instead of the picker's.
        val repository = FakeSettingsRepository()
        val imageStore = FakeOverlayImageStore()
        val vm = buildViewModel(repository, imageStore)
        val collectJob = launch { vm.uiState.collect {} }

        vm.onImageSelected("content://fake/new-pic")
        advanceUntilIdle()

        assertThat(imageStore.persistedSourceUris).containsExactly("content://fake/new-pic")
        assertThat(vm.uiState.value.overlayImageUriString).isEqualTo("file://fake/persisted/content://fake/new-pic")

        collectJob.cancel()
    }

    @Test
    fun `a failed copy leaves the previously-selected image in place`() = runTest {
        val repository = FakeSettingsRepository(AppSettings(overlayImageUriString = "file://fake/persisted/old-pic"))
        val imageStore = FakeOverlayImageStore(failNextPersist = true)
        val vm = buildViewModel(repository, imageStore)
        val collectJob = launch { vm.uiState.collect {} }

        vm.onImageSelected("content://fake/new-pic")
        advanceUntilIdle()

        assertThat(vm.uiState.value.overlayImageUriString).isEqualTo("file://fake/persisted/old-pic")

        collectJob.cancel()
    }

    @Test
    fun `clearing the selected image does not attempt to copy anything`() = runTest {
        val repository = FakeSettingsRepository(AppSettings(overlayImageUriString = "file://fake/persisted/old-pic"))
        val imageStore = FakeOverlayImageStore()
        val vm = buildViewModel(repository, imageStore)
        val collectJob = launch { vm.uiState.collect {} }

        vm.onImageSelected(null)
        advanceUntilIdle()

        assertThat(imageStore.persistedSourceUris).isEmpty()
        assertThat(vm.uiState.value.overlayImageUriString).isNull()

        collectJob.cancel()
    }

    @Test
    fun `changing one trigger's capture mode updates state and is persisted, independent of the others`() = runTest {
        val repository = FakeSettingsRepository()
        val vm = buildViewModel(repository)
        val collectJob = launch { vm.uiState.collect {} }

        vm.onCaptureModeChanged(CaptureTriggerKind.VOLUME_UP, CaptureMode.BURST)
        advanceUntilIdle()

        assertThat(vm.uiState.value.captureModeByTrigger[CaptureTriggerKind.VOLUME_UP]).isEqualTo(CaptureMode.BURST)
        assertThat(repository.settings.value.captureModeByTrigger[CaptureTriggerKind.VOLUME_UP]).isEqualTo(CaptureMode.BURST)
        // Every other trigger is untouched.
        for (trigger in CaptureTriggerKind.entries - CaptureTriggerKind.VOLUME_UP) {
            assertThat(vm.uiState.value.captureModeByTrigger[trigger]).isEqualTo(CaptureMode.SINGLE_SHOT)
        }

        collectJob.cancel()
    }

    @Test
    fun `changing the burst interval updates state and is clamped to the valid range`() = runTest {
        val repository = FakeSettingsRepository()
        val vm = buildViewModel(repository)
        val collectJob = launch { vm.uiState.collect {} }

        vm.onBurstIntervalChanged(750L)
        advanceUntilIdle()
        assertThat(vm.uiState.value.burstIntervalMillis).isEqualTo(750L)

        vm.onBurstIntervalChanged(10_000L)
        advanceUntilIdle()
        assertThat(vm.uiState.value.burstIntervalMillis).isEqualTo(AppSettings.BURST_INTERVAL_RANGE_MILLIS.last)

        vm.onBurstIntervalChanged(0L)
        advanceUntilIdle()
        assertThat(vm.uiState.value.burstIntervalMillis).isEqualTo(AppSettings.BURST_INTERVAL_RANGE_MILLIS.first)

        collectJob.cancel()
    }

    @Test
    fun `changing the capture aspect ratio updates state and is persisted`() = runTest {
        val repository = FakeSettingsRepository()
        val vm = buildViewModel(repository)
        val collectJob = launch { vm.uiState.collect {} }

        vm.onCaptureAspectRatioChanged(CaptureAspectRatio.RATIO_16_9)
        advanceUntilIdle()

        assertThat(vm.uiState.value.captureAspectRatio).isEqualTo(CaptureAspectRatio.RATIO_16_9)
        assertThat(repository.settings.value.captureAspectRatio).isEqualTo(CaptureAspectRatio.RATIO_16_9)

        collectJob.cancel()
    }

    @Test
    fun `changing diagnostics file logging updates state and is persisted`() = runTest {
        val repository = FakeSettingsRepository()
        val vm = buildViewModel(repository)
        val collectJob = launch { vm.uiState.collect {} }

        vm.onDiagnosticsFileLoggingChanged(true)
        advanceUntilIdle()

        assertThat(vm.uiState.value.diagnosticsFileLoggingEnabled).isTrue()
        assertThat(repository.settings.value.diagnosticsFileLoggingEnabled).isTrue()

        collectJob.cancel()
    }

    @Test
    fun `a selection write survives the view model being cleared right afterward`() = runTest {
        // Regression test for a real bug: SettingsViewModel is scoped to the "settings"
        // NavBackStackEntry, which is popped (clearing the ViewModel and cancelling
        // viewModelScope) as soon as the user navigates back - a very common flow immediately
        // after picking an image. A write still queued on viewModelScope at that moment would be
        // cancelled before it durably reached DataStore, so a previously-selected image could
        // silently fail to survive an app restart. A real ViewModelStore is used here (rather
        // than calling a test-only "clear" hook) so this exercises the exact same clearing path
        // Navigation Compose triggers.
        val repository = FakeSettingsRepository()
        val vm = buildViewModel(repository)

        vm.onImageSelected("content://fake/still-persisted")
        val store = ViewModelStore()
        store.put("settings", vm)
        store.clear()

        advanceUntilIdle()

        assertThat(repository.settings.value.overlayImageUriString)
            .isEqualTo("file://fake/persisted/content://fake/still-persisted")
    }
}
