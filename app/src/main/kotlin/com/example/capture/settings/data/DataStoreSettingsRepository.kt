package com.example.capture.settings.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.capture.camera.domain.CaptureAspectRatio
import com.example.capture.camera.domain.CaptureMode
import com.example.capture.camera.domain.CaptureTriggerKind
import com.example.capture.settings.domain.AppSettings
import com.example.capture.settings.domain.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** The only class that touches Jetpack DataStore; everything else depends on [SettingsRepository]. */
class DataStoreSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettingsRepository {

    private object Keys {
        val VIBRATION_DURATION_MILLIS = longPreferencesKey("vibration_duration_millis")
        val OVERLAY_IMAGE_URI = stringPreferencesKey("overlay_image_uri")
        val BURST_INTERVAL_MILLIS = longPreferencesKey("burst_interval_millis")
        val CAPTURE_ASPECT_RATIO = stringPreferencesKey("capture_aspect_ratio")
        val DIAGNOSTICS_FILE_LOGGING_ENABLED = booleanPreferencesKey("diagnostics_file_logging_enabled")
        val ENCRYPT_SAVED_PHOTOS = booleanPreferencesKey("encrypt_saved_photos")
        val ENCRYPTED_PHOTOS_FOLDER_URI = stringPreferencesKey("encrypted_photos_folder_uri")
        val ZOOM_LEVEL = intPreferencesKey("zoom_level")

        // One key per CaptureTriggerKind (see "Capture Mode" in app-spec.md) rather than the single
        // "capture_mode" key this replaced - that old key is simply orphaned/never read again, not
        // migrated, matching this app's usual approach to settings changes.
        fun captureMode(trigger: CaptureTriggerKind) = stringPreferencesKey("capture_mode_${trigger.name.lowercase()}")
    }

    override val settings: Flow<AppSettings> = context.settingsDataStore.data.map { preferences ->
        AppSettings(
            vibrationDurationMillis = preferences[Keys.VIBRATION_DURATION_MILLIS]
                ?: AppSettings.DEFAULT_VIBRATION_DURATION_MILLIS,
            overlayImageUriString = preferences[Keys.OVERLAY_IMAGE_URI],
            captureModeByTrigger = CaptureTriggerKind.entries.associateWith { trigger ->
                preferences[Keys.captureMode(trigger)]?.toCaptureModeOrDefault() ?: CaptureMode.SINGLE_SHOT
            },
            burstIntervalMillis = preferences[Keys.BURST_INTERVAL_MILLIS]
                ?: AppSettings.DEFAULT_BURST_INTERVAL_MILLIS,
            captureAspectRatio = preferences[Keys.CAPTURE_ASPECT_RATIO]?.toCaptureAspectRatioOrDefault()
                ?: CaptureAspectRatio.RATIO_4_3,
            diagnosticsFileLoggingEnabled = preferences[Keys.DIAGNOSTICS_FILE_LOGGING_ENABLED] ?: false,
            encryptSavedPhotos = preferences[Keys.ENCRYPT_SAVED_PHOTOS] ?: false,
            encryptedPhotosFolderUriString = preferences[Keys.ENCRYPTED_PHOTOS_FOLDER_URI],
            zoomLevel = preferences[Keys.ZOOM_LEVEL] ?: AppSettings.DEFAULT_ZOOM_LEVEL,
        )
    }

    override suspend fun setVibrationDurationMillis(durationMillis: Long) {
        context.settingsDataStore.edit { it[Keys.VIBRATION_DURATION_MILLIS] = durationMillis }
    }

    override suspend fun setOverlayImageUri(uriString: String?) {
        context.settingsDataStore.edit { preferences ->
            if (uriString != null) {
                preferences[Keys.OVERLAY_IMAGE_URI] = uriString
            } else {
                preferences.remove(Keys.OVERLAY_IMAGE_URI)
            }
        }
    }

    override suspend fun setCaptureMode(trigger: CaptureTriggerKind, mode: CaptureMode) {
        context.settingsDataStore.edit { it[Keys.captureMode(trigger)] = mode.name }
    }

    override suspend fun setBurstIntervalMillis(intervalMillis: Long) {
        context.settingsDataStore.edit { it[Keys.BURST_INTERVAL_MILLIS] = intervalMillis }
    }

    override suspend fun setCaptureAspectRatio(ratio: CaptureAspectRatio) {
        context.settingsDataStore.edit { it[Keys.CAPTURE_ASPECT_RATIO] = ratio.name }
    }

    override suspend fun setDiagnosticsFileLoggingEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.DIAGNOSTICS_FILE_LOGGING_ENABLED] = enabled }
    }

    override suspend fun setEncryptSavedPhotos(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.ENCRYPT_SAVED_PHOTOS] = enabled }
    }

    override suspend fun setEncryptedPhotosFolderUri(uriString: String?) {
        context.settingsDataStore.edit { preferences ->
            if (uriString != null) {
                preferences[Keys.ENCRYPTED_PHOTOS_FOLDER_URI] = uriString
            } else {
                preferences.remove(Keys.ENCRYPTED_PHOTOS_FOLDER_URI)
            }
        }
    }

    override suspend fun setZoomLevel(level: Int) {
        context.settingsDataStore.edit { it[Keys.ZOOM_LEVEL] = level }
    }

    // Falls back to the default rather than throwing if a future release ever removes/renames an
    // enum constant and an old value is still on disk.
    private fun String.toCaptureModeOrDefault(): CaptureMode =
        CaptureMode.entries.firstOrNull { it.name == this } ?: CaptureMode.SINGLE_SHOT

    private fun String.toCaptureAspectRatioOrDefault(): CaptureAspectRatio =
        CaptureAspectRatio.entries.firstOrNull { it.name == this } ?: CaptureAspectRatio.RATIO_4_3
}
