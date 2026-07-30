package com.example.capture.settings.ui

import com.example.capture.settings.domain.AppSettings

/** Immutable snapshot the settings screen renders from. */
data class SettingsUiState(
    val vibrationDurationMillis: Long = AppSettings.DEFAULT_VIBRATION_DURATION_MILLIS,
    val overlayImageUriString: String? = null,
)
