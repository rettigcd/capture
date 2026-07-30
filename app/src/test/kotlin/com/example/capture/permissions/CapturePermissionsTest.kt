package com.example.capture.permissions

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CapturePermissionsTest {

    @Test
    fun `granted permission is reported as granted regardless of the other signals`() {
        val status = CapturePermissions.permissionStatus(
            granted = true,
            shouldShowRationale = true,
            hasRequestedBefore = true,
        )
        assertThat(status).isEqualTo(PermissionStatus.GRANTED)
    }

    @Test
    fun `never requested and not granted is not determined`() {
        val status = CapturePermissions.permissionStatus(
            granted = false,
            shouldShowRationale = false,
            hasRequestedBefore = false,
        )
        assertThat(status).isEqualTo(PermissionStatus.NOT_DETERMINED)
    }

    @Test
    fun `denied once with rationale allowed should show rationale`() {
        val status = CapturePermissions.permissionStatus(
            granted = false,
            shouldShowRationale = true,
            hasRequestedBefore = true,
        )
        assertThat(status).isEqualTo(PermissionStatus.SHOULD_SHOW_RATIONALE)
    }

    @Test
    fun `denied with rationale suppressed after a prior request is permanently denied`() {
        val status = CapturePermissions.permissionStatus(
            granted = false,
            shouldShowRationale = false,
            hasRequestedBefore = true,
        )
        assertThat(status).isEqualTo(PermissionStatus.PERMANENTLY_DENIED)
    }

    @Test
    fun `microphone permission is only needed once voice triggering is requested`() {
        assertThat(
            CapturePermissions.needsMicrophonePermissionRequest(voiceTriggerRequested = false, microphoneGranted = false),
        ).isFalse()
        assertThat(
            CapturePermissions.needsMicrophonePermissionRequest(voiceTriggerRequested = true, microphoneGranted = false),
        ).isTrue()
        assertThat(
            CapturePermissions.needsMicrophonePermissionRequest(voiceTriggerRequested = true, microphoneGranted = true),
        ).isFalse()
    }
}
