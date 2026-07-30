package com.example.capture.settings.domain

/** Immutable snapshot of every user-configurable setting, persisted across app restarts. */
data class AppSettings(
    val vibrationDurationMillis: Long = DEFAULT_VIBRATION_DURATION_MILLIS,
    val overlayImageUriString: String? = null,
) {
    companion object {
        const val DEFAULT_VIBRATION_DURATION_MILLIS = 60L

        /** The slider on the settings screen snaps to these 60 ms increments. */
        val VIBRATION_DURATION_RANGE_MILLIS = 60L..300L
        const val VIBRATION_DURATION_STEP_MILLIS = 60L
    }
}
