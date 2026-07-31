package com.example.capture.settings.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.capture.camera.domain.CaptureAspectRatio
import com.example.capture.camera.domain.CaptureMode
import com.example.capture.common.ApplicationScope
import com.example.capture.settings.domain.AppSettings
import com.example.capture.settings.domain.OverlayImageStore
import com.example.capture.settings.domain.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val overlayImageStore: OverlayImageStore,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = settingsRepository.settings
        .map { it.toUiState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SettingsUiState())

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
     * before being persisted. If the copy fails, the previously-selected image (if any) is left
     * in place rather than saving an unreadable reference.
     */
    fun onImageSelected(uriString: String?) {
        applicationScope.launch {
            if (uriString == null) {
                settingsRepository.setOverlayImageUri(null)
                return@launch
            }
            val persistedUriString = runCatching { overlayImageStore.persist(uriString) }
                .onFailure { Log.w(TAG, "Failed to durably persist the overlay image", it) }
                .getOrNull()
            if (persistedUriString != null) {
                settingsRepository.setOverlayImageUri(persistedUriString)
            }
        }
    }

    fun onCaptureModeChanged(mode: CaptureMode) {
        applicationScope.launch { settingsRepository.setCaptureMode(mode) }
    }

    fun onBurstIntervalChanged(intervalMillis: Long) {
        val clamped = intervalMillis.coerceIn(AppSettings.BURST_INTERVAL_RANGE_MILLIS)
        applicationScope.launch { settingsRepository.setBurstIntervalMillis(clamped) }
    }

    fun onCaptureAspectRatioChanged(ratio: CaptureAspectRatio) {
        applicationScope.launch { settingsRepository.setCaptureAspectRatio(ratio) }
    }

    fun onDiagnosticsFileLoggingChanged(enabled: Boolean) {
        applicationScope.launch { settingsRepository.setDiagnosticsFileLoggingEnabled(enabled) }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val TAG = "SettingsViewModel"
    }
}

private fun AppSettings.toUiState() = SettingsUiState(
    vibrationDurationMillis = vibrationDurationMillis,
    overlayImageUriString = overlayImageUriString,
    captureMode = captureMode,
    burstIntervalMillis = burstIntervalMillis,
    captureAspectRatio = captureAspectRatio,
    diagnosticsFileLoggingEnabled = diagnosticsFileLoggingEnabled,
)
