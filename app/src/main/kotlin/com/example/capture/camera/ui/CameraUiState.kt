package com.example.capture.camera.ui

import com.example.capture.camera.domain.CaptureMode
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
    val captureMode: CaptureMode = CaptureMode.SINGLE_SHOT,
)

/**
 * UI-facing projection of [com.example.capture.camera.domain.CaptureState]. Deliberately carries
 * no error detail: capture and file-saving errors are logged (see
 * [com.example.capture.camera.domain.CaptureErrorLogger]), not shown on the main camera screen -
 * see "Error Handling" in app-spec.md.
 */
sealed interface CaptureStatusUi {
    data object Idle : CaptureStatusUi
    data object Capturing : CaptureStatusUi
    data object Saved : CaptureStatusUi
}
