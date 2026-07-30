package com.example.capture.camera.ui

import com.example.capture.permissions.PermissionStatus

/**
 * Immutable snapshot of everything [com.example.capture.camera.ui.CameraScreen] needs to render.
 * Built once per state change by [CameraViewModel]; the screen itself never mutates it.
 */
data class CameraUiState(
    val cameraPermission: PermissionStatus = PermissionStatus.NOT_DETERMINED,
    val microphonePermission: PermissionStatus = PermissionStatus.NOT_DETERMINED,
    val captureStatus: CaptureStatusUi = CaptureStatusUi.Idle,
    val voiceTriggerEnabled: Boolean = false,
    val voiceListening: Boolean = false,
    val voiceError: String? = null,
    /**
     * True only when the overlay was last left visible by a swipe gesture *and* an image has
     * actually been selected; if no image has ever been picked, the live preview is shown
     * regardless so the screen never renders a blank placeholder.
     */
    val overlayVisible: Boolean = false,
    val overlayImageUriString: String? = null,
)

/** UI-facing projection of [com.example.capture.camera.domain.CaptureState]. */
sealed interface CaptureStatusUi {
    data object Idle : CaptureStatusUi
    data object Capturing : CaptureStatusUi
    data class Saved(val uriString: String) : CaptureStatusUi
    data class Failed(val message: String) : CaptureStatusUi
}
