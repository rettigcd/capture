package com.example.capture.settings.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
    }

    override val settings: Flow<AppSettings> = context.settingsDataStore.data.map { preferences ->
        AppSettings(
            vibrationDurationMillis = preferences[Keys.VIBRATION_DURATION_MILLIS]
                ?: AppSettings.DEFAULT_VIBRATION_DURATION_MILLIS,
            overlayImageUriString = preferences[Keys.OVERLAY_IMAGE_URI],
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
}
