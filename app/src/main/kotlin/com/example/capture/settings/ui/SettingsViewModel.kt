package com.example.capture.settings.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.capture.camera.domain.CaptureMode
import com.example.capture.camera.domain.CaptureTriggerKind
import com.example.capture.camera.domain.OverlayVisibilityRepository
import com.example.capture.common.ApplicationScope
import com.example.capture.security.domain.KeySessionRepository
import com.example.capture.settings.domain.AppSettings
import com.example.capture.settings.domain.OverlayImageStore
import com.example.capture.settings.domain.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val overlayImageStore: OverlayImageStore,
    private val overlayVisibilityRepository: OverlayVisibilityRepository,
    private val keySessionRepository: KeySessionRepository,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        keySessionRepository.state,
    ) { settings, sessionState -> settings.toUiState(hasKeyFile = sessionState.hasKeyFile) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SettingsUiState())

    private val _events = MutableSharedFlow<SettingsEvent>()
    val events: SharedFlow<SettingsEvent> = _events.asSharedFlow()

    init {
        // If the key file the "Encrypt saved photos" preference depends on ever goes missing
        // (deleted, replaced), that preference is cleared rather than left silently on pointing
        // at a key that no longer exists (see AppSettings.encryptSavedPhotos's kdoc).
        viewModelScope.launch {
            combine(settingsRepository.settings, keySessionRepository.state) { settings, sessionState -> settings to sessionState }
                .collect { (settings, sessionState) ->
                    if (settings.encryptSavedPhotos && !sessionState.hasKeyFile) {
                        applicationScope.launch { settingsRepository.setEncryptSavedPhotos(false) }
                    }
                }
        }
    }

    // These writes use applicationScope, not viewModelScope: this screen is popped off the
    // Navigation Compose back stack (which cancels viewModelScope) as soon as the user navigates
    // back - a very common flow right after picking an image - and a DataStore write cancelled
    // mid-flight may never durably land on disk, which is exactly why a previously-selected image
    // could silently fail to survive an app restart.
    fun onVibrationDurationChanged(durationMillis: Long) {
        val clamped = durationMillis.coerceIn(AppSettings.VIBRATION_DURATION_RANGE_MILLIS)
        applicationScope.launch { settingsRepository.setVibrationDurationMillis(clamped) }
    }

    /**
     * [uriString] is the picker's own `content://` Uri, whose read grant does not reliably
     * survive a restart - see [OverlayImageStore]'s kdoc - so it is copied into durable storage
     * before being persisted. If the copy fails, the list is left unchanged rather than saving an
     * unreadable reference. A no-op once [AppSettings.MAX_COVER_PHOTOS] cover photos are already
     * configured (the settings screen's Add control is disabled/hidden by then, but this guards
     * against a stray call reaching here anyway).
     */
    fun onCoverPhotoSelected(uriString: String) {
        applicationScope.launch {
            val current = settingsRepository.settings.first().coverPhotoUriStrings
            if (current.size >= AppSettings.MAX_COVER_PHOTOS) return@launch
            val persistedUriString = runCatching { overlayImageStore.persist(uriString) }
                .onFailure { Log.w(TAG, "Failed to durably persist the cover photo", it) }
                .getOrNull()
            if (persistedUriString != null) {
                settingsRepository.setCoverPhotoUriStrings(current + persistedUriString)
            }
        }
    }

    /**
     * Removes the cover photo at [index], shifting every later entry up to fill the gap (see
     * "Cover Photos" in app-spec.md), then adjusts the persisted active cover-photo index so it
     * keeps pointing at the same photo it pointed at before the deletion - or clamps to the new
     * last valid position (0 if the list becomes empty) if that photo was the one removed.
     * Best-effort deletes the now-unreferenced app-private copy.
     */
    fun onCoverPhotoDeleted(index: Int) {
        applicationScope.launch {
            val current = settingsRepository.settings.first().coverPhotoUriStrings
            if (index !in current.indices) return@launch
            val removedUriString = current[index]
            val updated = current.toMutableList().apply { removeAt(index) }
            settingsRepository.setCoverPhotoUriStrings(updated)

            val currentIndex = overlayVisibilityRepository.activeCoverPhotoIndex.first()
            val newIndex = if (index <= currentIndex) {
                (currentIndex - 1).coerceIn(0, (updated.size - 1).coerceAtLeast(0))
            } else {
                currentIndex
            }
            overlayVisibilityRepository.setActiveCoverPhotoIndex(newIndex)

            overlayImageStore.delete(removedUriString)
        }
    }

    fun onCaptureModeChanged(trigger: CaptureTriggerKind, mode: CaptureMode) {
        applicationScope.launch { settingsRepository.setCaptureMode(trigger, mode) }
    }

    fun onBurstIntervalChanged(intervalMillis: Long) {
        val clamped = intervalMillis.coerceIn(AppSettings.BURST_INTERVAL_RANGE_MILLIS)
        applicationScope.launch { settingsRepository.setBurstIntervalMillis(clamped) }
    }

    fun onDiagnosticsFileLoggingChanged(enabled: Boolean) {
        applicationScope.launch { settingsRepository.setDiagnosticsFileLoggingEnabled(enabled) }
    }

    /**
     * Turning on with no folder yet picked launches the folder picker instead of persisting
     * `true` directly - [onEncryptedPhotosFolderPicked] is what actually turns encryption on,
     * once a folder is confirmed. Turning on with a folder already picked (re-enabling) does not
     * re-prompt. Turning off just persists `false`, leaving the remembered folder alone so
     * turning back on later doesn't require re-picking.
     */
    fun onEncryptSavedPhotosToggled(enabled: Boolean) {
        applicationScope.launch {
            if (!enabled) {
                settingsRepository.setEncryptSavedPhotos(false)
                return@launch
            }
            val hasFolder = settingsRepository.settings.first().encryptedPhotosFolderUriString != null
            if (hasFolder) {
                settingsRepository.setEncryptSavedPhotos(true)
            } else {
                _events.emit(SettingsEvent.LaunchFolderPicker)
            }
        }
    }

    fun onChooseEncryptedPhotosFolderClicked() {
        viewModelScope.launch { _events.emit(SettingsEvent.LaunchFolderPicker) }
    }

    /** [uriString] is null if the picker was cancelled, in which case encryption is not turned on. */
    fun onEncryptedPhotosFolderPicked(uriString: String?) {
        if (uriString == null) return
        applicationScope.launch {
            settingsRepository.setEncryptedPhotosFolderUri(uriString)
            settingsRepository.setEncryptSavedPhotos(true)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val TAG = "SettingsViewModel"
    }
}

private fun AppSettings.toUiState(hasKeyFile: Boolean) = SettingsUiState(
    vibrationDurationMillis = vibrationDurationMillis,
    coverPhotoUriStrings = coverPhotoUriStrings,
    captureModeByTrigger = captureModeByTrigger,
    burstIntervalMillis = burstIntervalMillis,
    diagnosticsFileLoggingEnabled = diagnosticsFileLoggingEnabled,
    encryptSavedPhotos = encryptSavedPhotos,
    encryptSavedPhotosAvailable = hasKeyFile,
    hasEncryptedPhotosFolder = encryptedPhotosFolderUriString != null,
)
