package com.example.capture.camera.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.example.capture.camera.domain.OverlayVisibilityRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

private val Context.cameraUiDataStore: DataStore<Preferences> by preferencesDataStore(name = "camera_ui_state")

/**
 * Uses its own DataStore file, separate from
 * [com.example.capture.settings.data.DataStoreSettingsRepository], so overlay visibility stays a
 * piece of camera-screen UI state rather than living alongside user-facing settings.
 */
class DataStoreOverlayVisibilityRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : OverlayVisibilityRepository {

    private object Keys {
        val OVERLAY_VISIBLE = booleanPreferencesKey("overlay_visible")
    }

    override val overlayVisible: Flow<Boolean> = context.cameraUiDataStore.data.map { preferences ->
        preferences[Keys.OVERLAY_VISIBLE] ?: false
    }

    override suspend fun setOverlayVisible(visible: Boolean) {
        context.cameraUiDataStore.edit { it[Keys.OVERLAY_VISIBLE] = visible }
    }
}
