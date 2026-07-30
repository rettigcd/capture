package com.example.capture.camera.domain

import kotlinx.coroutines.flow.Flow

/**
 * Persists whether the overlay image was showing over the camera preview, independent of
 * [com.example.capture.settings.domain.SettingsRepository]. Overlay visibility is controlled only
 * by a swipe gesture on the camera screen, never a settings-screen control, but it still needs to
 * survive app restarts the same way a setting would.
 */
interface OverlayVisibilityRepository {
    val overlayVisible: Flow<Boolean>

    suspend fun setOverlayVisible(visible: Boolean)
}
