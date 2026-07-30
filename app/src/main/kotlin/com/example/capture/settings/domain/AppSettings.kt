package com.example.capture.settings.domain

import com.example.capture.camera.domain.CaptureMode

/** Immutable snapshot of every user-configurable setting, persisted across app restarts. */
data class AppSettings(
    val vibrationDurationMillis: Long = DEFAULT_VIBRATION_DURATION_MILLIS,
    val overlayImageUriString: String? = null,
    val captureMode: CaptureMode = CaptureMode.SINGLE_SHOT,
    val burstIntervalMillis: Long = DEFAULT_BURST_INTERVAL_MILLIS,
) {
    companion object {
        const val DEFAULT_VIBRATION_DURATION_MILLIS = 60L

        /** The slider on the settings screen snaps to these 60 ms increments. */
        val VIBRATION_DURATION_RANGE_MILLIS = 60L..300L
        const val VIBRATION_DURATION_STEP_MILLIS = 60L

        /**
         * 500 ms is fast enough to feel like a genuine burst, while being more likely to work
         * consistently across a wide range of Android device camera hardware than the 250 ms
         * minimum (see "Burst Mode" in app-spec.md).
         */
        const val DEFAULT_BURST_INTERVAL_MILLIS = 500L

        /** The burst-interval slider on the settings screen snaps to these 250 ms increments. */
        val BURST_INTERVAL_RANGE_MILLIS = 250L..2_000L
        const val BURST_INTERVAL_STEP_MILLIS = 250L
    }
}
