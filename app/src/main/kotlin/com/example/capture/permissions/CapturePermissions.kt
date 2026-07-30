package com.example.capture.permissions

/**
 * Pure decision logic for turning raw Android permission signals into a [PermissionStatus].
 * Deliberately has zero Android dependencies (no `Activity`, no `ContextCompat`) so it is
 * trivially unit-testable; the Compose layer is responsible for supplying the three booleans
 * from `ActivityResultContracts` / `shouldShowRequestPermissionRationale`.
 */
object CapturePermissions {
    /**
     * @param granted true if `ContextCompat.checkSelfPermission` currently returns granted.
     * @param shouldShowRationale true if `shouldShowRequestPermissionRationale` currently returns
     *   true (Android's signal that the user denied once but can still be asked again).
     * @param hasRequestedBefore true once this process has asked for the permission at least
     *   once. Needed to distinguish "never asked yet" from "denied with 'don't ask again'", since
     *   both report `shouldShowRationale == false`.
     */
    fun permissionStatus(
        granted: Boolean,
        shouldShowRationale: Boolean,
        hasRequestedBefore: Boolean,
    ): PermissionStatus = when {
        granted -> PermissionStatus.GRANTED
        shouldShowRationale -> PermissionStatus.SHOULD_SHOW_RATIONALE
        hasRequestedBefore -> PermissionStatus.PERMANENTLY_DENIED
        else -> PermissionStatus.NOT_DETERMINED
    }

    /** Microphone access is only ever needed once the user opts into voice triggering. */
    fun needsMicrophonePermissionRequest(
        voiceTriggerRequested: Boolean,
        microphoneGranted: Boolean,
    ): Boolean = voiceTriggerRequested && !microphoneGranted
}

enum class PermissionStatus {
    NOT_DETERMINED,
    GRANTED,
    SHOULD_SHOW_RATIONALE,
    PERMANENTLY_DENIED,
}
