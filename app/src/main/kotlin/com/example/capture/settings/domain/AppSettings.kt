package com.example.capture.settings.domain

import com.example.capture.camera.domain.CaptureAspectRatio
import com.example.capture.camera.domain.CaptureMode
import com.example.capture.camera.domain.CaptureTriggerKind

/** Immutable snapshot of every user-configurable setting, persisted across app restarts. */
data class AppSettings(
    val vibrationDurationMillis: Long = DEFAULT_VIBRATION_DURATION_MILLIS,
    /**
     * Up to [MAX_COVER_PHOTOS] cover photos (see "Cover photo visibility" in app-spec.md), in
     * display order - a newly added one is appended to the end; deleting one shifts every later
     * entry up so the list never has gaps. Empty by default (no cover photos configured).
     */
    val coverPhotoUriStrings: List<String> = emptyList(),
    /**
     * Each of the six trigger kinds (see "Capture Mode" in app-spec.md) has its own independent
     * Single-Shot/Burst choice - defaults to [CaptureMode.SINGLE_SHOT] for every kind, matching
     * this type's own default and the app's pre-per-trigger behavior. Always has exactly one entry
     * per [CaptureTriggerKind] value - [SettingsRepository.setCaptureMode] only ever overwrites an
     * existing entry, never adds/removes keys.
     */
    val captureModeByTrigger: Map<CaptureTriggerKind, CaptureMode> =
        CaptureTriggerKind.entries.associateWith { CaptureMode.SINGLE_SHOT },
    val burstIntervalMillis: Long = DEFAULT_BURST_INTERVAL_MILLIS,
    val captureAspectRatio: CaptureAspectRatio = CaptureAspectRatio.RATIO_4_3,
    /**
     * Off by default (see "Diagnostic Persistence" in app-spec.md: "disabled by default"). Logcat
     * output for capture-processing diagnostics is always available regardless of this setting;
     * this only controls whether the same events are additionally durably written to
     * `capture_diagnostics.log` for post-analysis.
     */
    val diagnosticsFileLoggingEnabled: Boolean = false,
    /**
     * Off by default. When on, Single-Shot and Burst captures are saved as encrypted `.kenc`
     * files into [encryptedPhotosFolderUriString] instead of plaintext `MediaStore` images - see
     * "Encrypt saved photos" in app-spec.md. Signing in is not required, only a key file (the
     * encryption itself only ever needs the public key). Cleared back to `false` if the key file
     * is ever found missing (see `SettingsViewModel`), so it never silently stays on pointing at
     * a key that no longer exists.
     */
    val encryptSavedPhotos: Boolean = false,
    /** The Storage Access Framework tree `Uri` (as a string) the user picked for [encryptSavedPhotos]'s output - null until a folder has ever been picked. */
    val encryptedPhotosFolderUriString: String? = null,
    /**
     * 1x (no zoom) by default. A single value, not per-trigger or per-mode - applies uniformly to
     * the live preview and to Single-Shot, Burst, and Video Mode capture alike (see "Camera zoom"
     * in app-spec.md).
     */
    val zoomLevel: Int = DEFAULT_ZOOM_LEVEL,
) {
    companion object {
        const val DEFAULT_VIBRATION_DURATION_MILLIS = 60L

        /** The cover-photo list (see "Cover Photos" in app-spec.md) never grows past this many entries. */
        const val MAX_COVER_PHOTOS = 3

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

        const val DEFAULT_ZOOM_LEVEL = 1

        /** The zoom slider on the settings screen snaps to these five integer positions. */
        val ZOOM_LEVEL_RANGE = 1..5
        const val ZOOM_LEVEL_STEP = 1
    }
}
