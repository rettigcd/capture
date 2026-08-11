package com.example.capture.settings.ui

import com.example.capture.camera.domain.CaptureMode
import com.example.capture.camera.domain.CaptureTriggerKind
import com.example.capture.security.domain.KeySessionState
import com.example.capture.settings.domain.AppSettings
import com.example.capture.testing.FakeKeySessionRepository
import com.example.capture.testing.FakeOverlayImageStore
import com.example.capture.testing.FakeOverlayVisibilityRepository
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
    // actual dispatchers - this is what proves onCoverPhotoSelected et al. no longer depend on
    // viewModelScope surviving navigation away from the settings screen.
    private fun buildViewModel(
        repository: FakeSettingsRepository,
        overlayImageStore: FakeOverlayImageStore = FakeOverlayImageStore(),
        overlayVisibilityRepository: FakeOverlayVisibilityRepository = FakeOverlayVisibilityRepository(),
        keySessionRepository: FakeKeySessionRepository = FakeKeySessionRepository().apply { emit(KeySessionState(hasKeyFile = true)) },
    ): SettingsViewModel = SettingsViewModel(
        repository,
        overlayImageStore,
        overlayVisibilityRepository,
        keySessionRepository,
        CoroutineScope(mainDispatcher),
    )

    @Test
    fun `initial ui state reflects the repository's defaults`() = runTest {
        val vm = buildViewModel(FakeSettingsRepository())
        val collectJob = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertThat(vm.uiState.value.vibrationDurationMillis).isEqualTo(AppSettings.DEFAULT_VIBRATION_DURATION_MILLIS)
        assertThat(vm.uiState.value.coverPhotoUriStrings).isEmpty()

        collectJob.cancel()
    }

    @Test
    fun `initial ui state reflects a previously saved value`() = runTest {
        val repository = FakeSettingsRepository(
            AppSettings(
                vibrationDurationMillis = 180L,
                coverPhotoUriStrings = listOf("content://fake/pic"),
            ),
        )
        val vm = buildViewModel(repository)
        val collectJob = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertThat(vm.uiState.value.vibrationDurationMillis).isEqualTo(180L)
        assertThat(vm.uiState.value.coverPhotoUriStrings).containsExactly("content://fake/pic")

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
    fun `selecting a cover photo copies it into durable storage and appends it to the end of the list`() = runTest {
        // Regression test for a real bug, found via direct device inspection: the system Photo
        // Picker's read grant for its own content:// Uri does not reliably survive a process
        // restart, so loading a Uri that DataStore had correctly remembered still threw
        // SecurityException after relaunching. The fix copies the image into app-private storage
        // immediately (via OverlayImageStore) and persists *that* Uri instead of the picker's.
        val repository = FakeSettingsRepository(AppSettings(coverPhotoUriStrings = listOf("file://fake/persisted/existing")))
        val imageStore = FakeOverlayImageStore()
        val vm = buildViewModel(repository, imageStore)
        val collectJob = launch { vm.uiState.collect {} }

        vm.onCoverPhotoSelected("content://fake/new-pic")
        advanceUntilIdle()

        assertThat(imageStore.persistedSourceUris).containsExactly("content://fake/new-pic")
        assertThat(vm.uiState.value.coverPhotoUriStrings)
            .containsExactly("file://fake/persisted/existing", "file://fake/persisted/content://fake/new-pic")
            .inOrder()

        collectJob.cancel()
    }

    @Test
    fun `a failed copy leaves the cover photo list unchanged`() = runTest {
        val repository = FakeSettingsRepository(AppSettings(coverPhotoUriStrings = listOf("file://fake/persisted/old-pic")))
        val imageStore = FakeOverlayImageStore(failNextPersist = true)
        val vm = buildViewModel(repository, imageStore)
        val collectJob = launch { vm.uiState.collect {} }

        vm.onCoverPhotoSelected("content://fake/new-pic")
        advanceUntilIdle()

        assertThat(vm.uiState.value.coverPhotoUriStrings).containsExactly("file://fake/persisted/old-pic")

        collectJob.cancel()
    }

    @Test
    fun `selecting a cover photo once three are already configured does not add a fourth`() = runTest {
        val repository = FakeSettingsRepository(
            AppSettings(coverPhotoUriStrings = listOf("file://fake/persisted/a", "file://fake/persisted/b", "file://fake/persisted/c")),
        )
        val imageStore = FakeOverlayImageStore()
        val vm = buildViewModel(repository, imageStore)
        val collectJob = launch { vm.uiState.collect {} }

        vm.onCoverPhotoSelected("content://fake/new-pic")
        advanceUntilIdle()

        assertThat(imageStore.persistedSourceUris).isEmpty()
        assertThat(vm.uiState.value.coverPhotoUriStrings)
            .containsExactly("file://fake/persisted/a", "file://fake/persisted/b", "file://fake/persisted/c")
            .inOrder()

        collectJob.cancel()
    }

    @Test
    fun `deleting a cover photo shifts every later entry up and cleans up its file`() = runTest {
        val repository = FakeSettingsRepository(
            AppSettings(coverPhotoUriStrings = listOf("file://fake/persisted/a", "file://fake/persisted/b", "file://fake/persisted/c")),
        )
        val imageStore = FakeOverlayImageStore()
        val vm = buildViewModel(repository, imageStore)
        val collectJob = launch { vm.uiState.collect {} }

        vm.onCoverPhotoDeleted(0)
        advanceUntilIdle()

        assertThat(vm.uiState.value.coverPhotoUriStrings)
            .containsExactly("file://fake/persisted/b", "file://fake/persisted/c")
            .inOrder()
        assertThat(imageStore.deletedUris).containsExactly("file://fake/persisted/a")

        collectJob.cancel()
    }

    @Test
    fun `deleting a cover photo at or before the active index decrements it to stay on the same photo`() = runTest {
        val repository = FakeSettingsRepository(
            AppSettings(coverPhotoUriStrings = listOf("file://fake/persisted/a", "file://fake/persisted/b", "file://fake/persisted/c")),
        )
        val overlayVisibility = FakeOverlayVisibilityRepository(initialActiveCoverPhotoIndex = 2)
        val vm = buildViewModel(repository, overlayVisibilityRepository = overlayVisibility)
        val collectJob = launch { vm.uiState.collect {} }

        vm.onCoverPhotoDeleted(0)
        advanceUntilIdle()

        // b, c remain; the index that pointed at c (2) decrements to 1, still c.
        assertThat(overlayVisibility.activeCoverPhotoIndex.value).isEqualTo(1)

        collectJob.cancel()
    }

    @Test
    fun `deleting the last cover photo clamps the active index to 0 instead of going out of bounds`() = runTest {
        val repository = FakeSettingsRepository(AppSettings(coverPhotoUriStrings = listOf("file://fake/persisted/a")))
        val overlayVisibility = FakeOverlayVisibilityRepository(initialActiveCoverPhotoIndex = 0)
        val vm = buildViewModel(repository, overlayVisibilityRepository = overlayVisibility)
        val collectJob = launch { vm.uiState.collect {} }

        vm.onCoverPhotoDeleted(0)
        advanceUntilIdle()

        assertThat(vm.uiState.value.coverPhotoUriStrings).isEmpty()
        assertThat(overlayVisibility.activeCoverPhotoIndex.value).isEqualTo(0)

        collectJob.cancel()
    }

    @Test
    fun `deleting a cover photo after the active index leaves the index unchanged`() = runTest {
        val repository = FakeSettingsRepository(
            AppSettings(coverPhotoUriStrings = listOf("file://fake/persisted/a", "file://fake/persisted/b", "file://fake/persisted/c")),
        )
        val overlayVisibility = FakeOverlayVisibilityRepository(initialActiveCoverPhotoIndex = 0)
        val vm = buildViewModel(repository, overlayVisibilityRepository = overlayVisibility)
        val collectJob = launch { vm.uiState.collect {} }

        vm.onCoverPhotoDeleted(2)
        advanceUntilIdle()

        assertThat(overlayVisibility.activeCoverPhotoIndex.value).isEqualTo(0)

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

        vm.onCoverPhotoSelected("content://fake/still-persisted")
        val store = ViewModelStore()
        store.put("settings", vm)
        store.clear()

        advanceUntilIdle()

        assertThat(repository.settings.value.coverPhotoUriStrings)
            .containsExactly("file://fake/persisted/content://fake/still-persisted")
    }

    @Test
    fun `encryptSavedPhotosAvailable reflects whether a key file exists`() = runTest {
        val keySessionRepository = FakeKeySessionRepository().apply { emit(KeySessionState(hasKeyFile = false)) }
        val vm = buildViewModel(FakeSettingsRepository(), keySessionRepository = keySessionRepository)
        val collectJob = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertThat(vm.uiState.value.encryptSavedPhotosAvailable).isFalse()

        keySessionRepository.emit(KeySessionState(hasKeyFile = true))
        advanceUntilIdle()
        assertThat(vm.uiState.value.encryptSavedPhotosAvailable).isTrue()

        collectJob.cancel()
    }

    @Test
    fun `turning on encrypt saved photos with no folder yet picked launches the folder picker instead of persisting true`() = runTest {
        val repository = FakeSettingsRepository()
        val vm = buildViewModel(repository)
        val collectJob = launch { vm.uiState.collect {} }
        val events = mutableListOf<SettingsEvent>()
        val eventsJob = launch { vm.events.collect { events += it } }

        vm.onEncryptSavedPhotosToggled(true)
        advanceUntilIdle()

        assertThat(events).containsExactly(SettingsEvent.LaunchFolderPicker)
        assertThat(repository.settings.value.encryptSavedPhotos).isFalse()

        collectJob.cancel()
        eventsJob.cancel()
    }

    @Test
    fun `turning on encrypt saved photos with a folder already picked persists true without launching the picker`() = runTest {
        val repository = FakeSettingsRepository(AppSettings(encryptedPhotosFolderUriString = "content://fake/tree/existing"))
        val vm = buildViewModel(repository)
        val collectJob = launch { vm.uiState.collect {} }
        val events = mutableListOf<SettingsEvent>()
        val eventsJob = launch { vm.events.collect { events += it } }

        vm.onEncryptSavedPhotosToggled(true)
        advanceUntilIdle()

        assertThat(events).isEmpty()
        assertThat(repository.settings.value.encryptSavedPhotos).isTrue()

        collectJob.cancel()
        eventsJob.cancel()
    }

    @Test
    fun `turning off encrypt saved photos persists false and leaves the picked folder untouched`() = runTest {
        val repository = FakeSettingsRepository(
            AppSettings(encryptSavedPhotos = true, encryptedPhotosFolderUriString = "content://fake/tree/existing"),
        )
        val vm = buildViewModel(repository)
        val collectJob = launch { vm.uiState.collect {} }

        vm.onEncryptSavedPhotosToggled(false)
        advanceUntilIdle()

        assertThat(repository.settings.value.encryptSavedPhotos).isFalse()
        assertThat(repository.settings.value.encryptedPhotosFolderUriString).isEqualTo("content://fake/tree/existing")

        collectJob.cancel()
    }

    @Test
    fun `picking a folder persists its uri and turns encryption on`() = runTest {
        val repository = FakeSettingsRepository()
        val vm = buildViewModel(repository)
        val collectJob = launch { vm.uiState.collect {} }

        vm.onEncryptedPhotosFolderPicked("content://fake/tree/new")
        advanceUntilIdle()

        assertThat(repository.settings.value.encryptedPhotosFolderUriString).isEqualTo("content://fake/tree/new")
        assertThat(repository.settings.value.encryptSavedPhotos).isTrue()

        collectJob.cancel()
    }

    @Test
    fun `cancelling the folder picker does not change any setting`() = runTest {
        val repository = FakeSettingsRepository()
        val vm = buildViewModel(repository)
        val collectJob = launch { vm.uiState.collect {} }

        vm.onEncryptedPhotosFolderPicked(null)
        advanceUntilIdle()

        assertThat(repository.settings.value.encryptedPhotosFolderUriString).isNull()
        assertThat(repository.settings.value.encryptSavedPhotos).isFalse()

        collectJob.cancel()
    }

    @Test
    fun `the key file going missing reactively turns off a previously-enabled encrypt saved photos setting`() = runTest {
        val repository = FakeSettingsRepository(
            AppSettings(encryptSavedPhotos = true, encryptedPhotosFolderUriString = "content://fake/tree"),
        )
        val keySessionRepository = FakeKeySessionRepository().apply { emit(KeySessionState(hasKeyFile = true)) }
        val vm = buildViewModel(repository, keySessionRepository = keySessionRepository)
        val collectJob = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        assertThat(repository.settings.value.encryptSavedPhotos).isTrue()

        keySessionRepository.emit(KeySessionState(hasKeyFile = false))
        advanceUntilIdle()

        assertThat(repository.settings.value.encryptSavedPhotos).isFalse()
        // The folder itself is left alone - only the toggle is cleared.
        assertThat(repository.settings.value.encryptedPhotosFolderUriString).isEqualTo("content://fake/tree")

        collectJob.cancel()
    }
}
