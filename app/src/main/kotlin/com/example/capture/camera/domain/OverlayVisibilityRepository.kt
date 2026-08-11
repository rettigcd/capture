package com.example.capture.camera.domain

import kotlinx.coroutines.flow.Flow

/**
 * Persists camera-screen UI state that must survive app restarts the same way a setting would,
 * without actually being a user-facing setting: whether the overlay was showing over the camera
 * preview, and which cover photo (see "Cover photo visibility" in app-spec.md) is active within
 * it. Both are controlled only by swipe gestures on the camera screen, never a settings-screen
 * control - the settings screen only manages which images exist in the cover-photo list (see
 * [com.example.capture.settings.domain.SettingsRepository]), not which one is active or whether
 * Overlay View is currently shown.
 */
interface OverlayVisibilityRepository {
    val overlayVisible: Flow<Boolean>

    suspend fun setOverlayVisible(visible: Boolean)

    /** Defaults to 0 (the first cover photo). Not clamped to the current cover-photo list's bounds here - callers do that (see `CameraViewModel`/`SettingsViewModel`). */
    val activeCoverPhotoIndex: Flow<Int>

    suspend fun setActiveCoverPhotoIndex(index: Int)
}
