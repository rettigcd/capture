package com.example.capture.camera.domain

/**
 * Applies the user-configured zoom level (see "Camera zoom" in app-spec.md) to the live camera.
 * A single level, not per-trigger or per-mode - it affects the live preview and Single-Shot,
 * Burst, and Video Mode capture alike, since they all share the same bound camera.
 */
interface ZoomController {
    suspend fun setZoomLevel(level: Int)
}
