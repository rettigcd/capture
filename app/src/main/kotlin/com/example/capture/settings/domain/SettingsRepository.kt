package com.example.capture.settings.domain

import com.example.capture.camera.domain.CaptureAspectRatio
import com.example.capture.camera.domain.CaptureMode
import com.example.capture.camera.domain.CaptureTriggerKind
import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over settings persistence so [com.example.capture.settings.ui.SettingsViewModel]
 * and [com.example.capture.camera.ui.CameraViewModel] never depend on Jetpack DataStore directly,
 * and so tests can control settings deterministically without touching disk.
 */
interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun setVibrationDurationMillis(durationMillis: Long)

    /** Replaces the whole cover-photo list at once (see [AppSettings.coverPhotoUriStrings]); callers own append/remove ordering. */
    suspend fun setCoverPhotoUriStrings(uriStrings: List<String>)
    suspend fun setCaptureMode(trigger: CaptureTriggerKind, mode: CaptureMode)
    suspend fun setBurstIntervalMillis(intervalMillis: Long)
    suspend fun setCaptureAspectRatio(ratio: CaptureAspectRatio)
    suspend fun setDiagnosticsFileLoggingEnabled(enabled: Boolean)
    suspend fun setEncryptSavedPhotos(enabled: Boolean)
    suspend fun setEncryptedPhotosFolderUri(uriString: String?)
    suspend fun setZoomLevel(level: Int)
    suspend fun setFullScreenEnabled(enabled: Boolean)
}
