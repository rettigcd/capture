package com.example.capture.camera.ui

import com.example.capture.camera.domain.CaptureAspectRatio
import com.example.capture.camera.domain.CaptureMode
import com.example.capture.permissions.PermissionStatus

/**
 * Immutable snapshot of everything [com.example.capture.camera.ui.CameraScreen] needs to render.
 * Built once per state change by [CameraViewModel]; the screen itself never mutates it.
 */
data class CameraUiState(
    val cameraPermission: PermissionStatus = PermissionStatus.NOT_DETERMINED,
    val microphonePermission: PermissionStatus = PermissionStatus.NOT_DETERMINED,
    val voiceTriggerEnabled: Boolean = false,
    val voiceListening: Boolean = false,
    val voiceError: String? = null,
    /**
     * True only when the overlay was last left visible by a swipe gesture *and* at least one
     * cover photo has actually been configured; if none has ever been added, the live preview is
     * shown regardless so the screen never renders a blank placeholder.
     */
    val overlayVisible: Boolean = false,
    /** The cover photo at the current cover-photo index (see "Cover photo visibility" in app-spec.md), or null if none is configured. */
    val activeCoverPhotoUriString: String? = null,
    /** Total configured cover photos - used to decide whether an additional left swipe while Overlay View is shown should cycle (only when greater than 1). */
    val coverPhotoCount: Int = 0,
    /**
     * The mode the camera pipeline is *currently bound for* - not a single global setting (each
     * trigger now has its own Single-Shot/Burst choice, see "Capture Mode" in app-spec.md), but
     * whichever mode [com.example.capture.camera.ui.CameraViewModel] last rebound the pipeline to
     * for the most recently requested capture. `CameraPreview` reads this to configure
     * `ImageCapture`'s latency/resolution behavior.
     */
    val captureMode: CaptureMode = CaptureMode.SINGLE_SHOT,
    /**
     * The aspect ratio currently in effect for both the preview layout and the CameraX use
     * cases. This lags behind the persisted setting while a burst is in progress - see
     * [CameraViewModel]'s burst-deferred aspect-ratio handling and "Capture Aspect Ratio and
     * Preview Framing" in app-spec.md ("changing the setting while a burst is active must not
     * alter the active burst").
     */
    val captureAspectRatio: CaptureAspectRatio = CaptureAspectRatio.RATIO_4_3,
    /** Drives [CaptureProgressIndicator] - see its kdoc and "Capture Progress Indicator" in app-spec.md. */
    val captureProgress: CaptureProgressUi = CaptureProgressUi.Hidden,
)

/**
 * Drives the standalone progress control shown above the privacy overlay (see "Capture Progress
 * Indicator" in app-spec.md) - the only on-screen indication that a capture is in progress; no
 * textual status ("Capturing…"/"Photo saved") is shown, and capture/file-saving errors are logged
 * (see [com.example.capture.camera.domain.CaptureErrorLogger]), not displayed, per "Error Handling"
 * in app-spec.md.
 */
sealed interface CaptureProgressUi {
    /** No capture in flight - the indicator is not shown at all. */
    data object Hidden : CaptureProgressUi

    /** Single-Shot Mode: a spinner with no specific completion fraction. */
    data object Indeterminate : CaptureProgressUi

    /** Burst Mode: advances in [com.example.capture.camera.domain.BURST_IMAGE_COUNT] discrete steps as each image finishes. */
    data class Determinate(val completedSteps: Int, val totalSteps: Int) : CaptureProgressUi
}
