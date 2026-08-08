package com.example.capture.settings.ui

import com.example.capture.camera.domain.CaptureAspectRatio
import com.example.capture.camera.domain.CaptureMode
import com.example.capture.camera.domain.CaptureTriggerKind
import com.example.capture.settings.domain.AppSettings

/** Immutable snapshot the settings screen renders from. */
data class SettingsUiState(
    val vibrationDurationMillis: Long = AppSettings.DEFAULT_VIBRATION_DURATION_MILLIS,
    val overlayImageUriString: String? = null,
    val captureModeByTrigger: Map<CaptureTriggerKind, CaptureMode> =
        CaptureTriggerKind.entries.associateWith { CaptureMode.SINGLE_SHOT },
    val burstIntervalMillis: Long = AppSettings.DEFAULT_BURST_INTERVAL_MILLIS,
    val captureAspectRatio: CaptureAspectRatio = CaptureAspectRatio.RATIO_4_3,
    val diagnosticsFileLoggingEnabled: Boolean = false,
    val encryptSavedPhotos: Boolean = false,
    /** True once a `.kkey` file exists (see `KeySessionRepository.state.hasKeyFile`) - the toggle is disabled while this is false. */
    val encryptSavedPhotosAvailable: Boolean = false,
    val hasEncryptedPhotosFolder: Boolean = false,
)
