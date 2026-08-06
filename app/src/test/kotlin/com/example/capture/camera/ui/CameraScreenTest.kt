package com.example.capture.camera.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import com.example.capture.R
import com.example.capture.camera.domain.GestureClassification
import com.example.capture.camera.domain.GestureDiagnosticEvent
import com.example.capture.permissions.PermissionStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Runs on the JVM via Robolectric (no emulator/device) so it can be part of `testDebugUnitTest`.
 * `CameraScreen` is exercised directly with hand-built [CameraUiState] values and no-op/counting
 * callbacks - it never touches CameraX, permissions, or Hilt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CameraScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun setScreen(
        uiState: CameraUiState,
        onScreenTouch: () -> Unit = {},
        onShutterButtonClick: () -> Unit = {},
        onVoiceTriggerToggle: (Boolean) -> Unit = {},
        onOverlayVisibilityChanged: (Boolean) -> Unit = {},
        onRequestCameraPermission: () -> Unit = {},
        onOpenSystemSettings: () -> Unit = {},
        onOpenSettings: () -> Unit = {},
        onGestureDiagnosticEvent: (GestureDiagnosticEvent) -> Unit = {},
        diagnosticsOverlayEnabled: Boolean = false,
        onDiagnosticsOverlayToggled: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            CameraScreen(
                uiState = uiState,
                onScreenTouch = onScreenTouch,
                onShutterButtonClick = onShutterButtonClick,
                onVoiceTriggerToggle = onVoiceTriggerToggle,
                onOverlayVisibilityChanged = onOverlayVisibilityChanged,
                onRequestCameraPermission = onRequestCameraPermission,
                onOpenSystemSettings = onOpenSystemSettings,
                onOpenSettings = onOpenSettings,
                onGestureDiagnosticEvent = onGestureDiagnosticEvent,
                diagnosticsOverlayEnabled = diagnosticsOverlayEnabled,
                onDiagnosticsOverlayToggled = onDiagnosticsOverlayToggled,
            )
        }
    }

    @Test
    fun permissionRationale_isDisplayed_whenCameraPermissionNotYetGranted() {
        setScreen(CameraUiState(cameraPermission = PermissionStatus.SHOULD_SHOW_RATIONALE))

        composeTestRule.onNodeWithText(context.getString(R.string.permission_camera_rationale)).assertIsDisplayed()
    }

    @Test
    fun permanentlyDeniedMessage_offersOpenSettings_insteadOfRationale() {
        setScreen(CameraUiState(cameraPermission = PermissionStatus.PERMANENTLY_DENIED))

        composeTestRule.onNodeWithText(context.getString(R.string.permission_camera_denied)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.open_system_settings)).assertIsDisplayed()
    }

    @Test
    fun cameraUi_isDisplayed_whenPermissionGranted() {
        setScreen(CameraUiState(cameraPermission = PermissionStatus.GRANTED))

        composeTestRule.onNodeWithContentDescription(
            context.getString(R.string.shutter_button_content_description),
        ).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(
            context.getString(R.string.camera_preview_content_description),
        ).assertIsDisplayed()
    }

    @Test
    fun touchingThePreview_dispatchesAScreenTouchTrigger() {
        var touchCount = 0
        setScreen(
            CameraUiState(cameraPermission = PermissionStatus.GRANTED),
            onScreenTouch = { touchCount++ },
        )

        composeTestRule.onNodeWithContentDescription(
            context.getString(R.string.camera_preview_content_description),
        ).performTouchInput { click() }

        assertThat(touchCount).isEqualTo(1)
    }

    @Test
    fun pressingTheShutterButton_dispatchesACaptureTrigger() {
        var clickCount = 0
        setScreen(
            CameraUiState(cameraPermission = PermissionStatus.GRANTED),
            onShutterButtonClick = { clickCount++ },
        )

        composeTestRule.onNodeWithContentDescription(
            context.getString(R.string.shutter_button_content_description),
        ).performClick()

        assertThat(clickCount).isEqualTo(1)
    }

    @Test
    fun voiceListeningIndicator_reflectsWhetherTheRecognizerIsActive() {
        setScreen(
            CameraUiState(cameraPermission = PermissionStatus.GRANTED, voiceListening = true),
        )

        composeTestRule.onNodeWithContentDescription(
            context.getString(R.string.voice_listening_indicator),
        ).assertIsDisplayed()
    }

    @Test
    fun captureProgressIndicator_isHidden_whenNoCaptureIsInFlight() {
        setScreen(
            CameraUiState(cameraPermission = PermissionStatus.GRANTED, captureProgress = CaptureProgressUi.Hidden),
        )

        assertThat(
            composeTestRule.onAllNodesWithContentDescription(
                context.getString(R.string.capture_progress_indicator_content_description),
            ).fetchSemanticsNodes(atLeastOneRootRequired = false),
        ).isEmpty()
    }

    @Test
    fun captureProgressIndicator_isShown_forAnIndeterminateSingleShotCapture() {
        setScreen(
            CameraUiState(cameraPermission = PermissionStatus.GRANTED, captureProgress = CaptureProgressUi.Indeterminate),
        )

        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.capture_progress_indicator_content_description))
            .assertIsDisplayed()
    }

    @Test
    fun captureProgressIndicator_isShown_forADeterminateBurstStep() {
        setScreen(
            CameraUiState(
                cameraPermission = PermissionStatus.GRANTED,
                captureProgress = CaptureProgressUi.Determinate(completedSteps = 2, totalSteps = 4),
            ),
        )

        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.capture_progress_indicator_content_description))
            .assertIsDisplayed()
    }

    @Test
    fun captureProgressIndicator_staysVisible_whileThePrivacyOverlayIsShown() {
        // Unlike the shutter button (which hides underneath the overlay image), the capture
        // progress indicator sits above it - see "Capture Progress Indicator" in app-spec.md.
        setScreen(
            CameraUiState(
                cameraPermission = PermissionStatus.GRANTED,
                overlayVisible = true,
                overlayImageUriString = "content://fake/overlay",
                captureProgress = CaptureProgressUi.Determinate(completedSteps = 1, totalSteps = 4),
            ),
        )

        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.capture_progress_indicator_content_description))
            .assertIsDisplayed()
    }

    @Test
    fun gearIcon_isAlwaysShown_andOpensSettings() {
        var openSettingsCount = 0
        setScreen(
            CameraUiState(cameraPermission = PermissionStatus.SHOULD_SHOW_RATIONALE),
            onOpenSettings = { openSettingsCount++ },
        )

        composeTestRule.onNodeWithContentDescription(
            context.getString(R.string.settings_button_content_description),
        ).performClick()

        assertThat(openSettingsCount).isEqualTo(1)
    }

    @Test
    fun overlayImage_isShownWhenVisible_andStillDispatchesCaptureOnTap() {
        var touchCount = 0
        setScreen(
            CameraUiState(
                cameraPermission = PermissionStatus.GRANTED,
                overlayVisible = true,
                overlayImageUriString = "content://fake/overlay",
            ),
            onScreenTouch = { touchCount++ },
        )

        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.overlay_image_content_description))
            .apply { assertIsDisplayed() }
            .performTouchInput { click() }

        assertThat(touchCount).isEqualTo(1)
    }

    @Test
    fun shutterButton_isHidden_whileTheOverlayIsShown() {
        setScreen(
            CameraUiState(
                cameraPermission = PermissionStatus.GRANTED,
                overlayVisible = true,
                overlayImageUriString = "content://fake/overlay",
            ),
        )

        assertThat(
            composeTestRule.onAllNodesWithContentDescription(
                context.getString(R.string.shutter_button_content_description),
            ).fetchSemanticsNodes(atLeastOneRootRequired = false),
        ).isEmpty()
    }

    @Test
    fun shutterButton_reappears_whenSwitchedBackToTheLivePreview() {
        setScreen(
            CameraUiState(
                cameraPermission = PermissionStatus.GRANTED,
                overlayVisible = false,
                overlayImageUriString = "content://fake/overlay",
            ),
        )

        composeTestRule.onNodeWithContentDescription(
            context.getString(R.string.shutter_button_content_description),
        ).assertIsDisplayed()
    }

    @Test
    fun livePreview_isShown_whenOverlayWasLeftVisibleButNoImageIsSelected() {
        setScreen(
            CameraUiState(
                cameraPermission = PermissionStatus.GRANTED,
                overlayVisible = false,
                overlayImageUriString = null,
            ),
        )

        composeTestRule.onNodeWithContentDescription(
            context.getString(R.string.camera_preview_content_description),
        ).assertIsDisplayed()
    }

    @Test
    fun leftSwipeOnThePreview_commitsOverlayVisible_andDoesNotDispatchACapture() {
        var touchCount = 0
        var committedVisible: Boolean? = null
        setScreen(
            CameraUiState(
                cameraPermission = PermissionStatus.GRANTED,
                overlayVisible = false,
                overlayImageUriString = "content://fake/overlay",
            ),
            onScreenTouch = { touchCount++ },
            onOverlayVisibilityChanged = { committedVisible = it },
        )

        composeTestRule.onNodeWithContentDescription(
            context.getString(R.string.camera_preview_content_description),
        ).performTouchInput { swipeLeft() }

        assertThat(committedVisible).isTrue()
        assertThat(touchCount).isEqualTo(0)
    }

    @Test
    fun rightSwipeOnTheOverlay_commitsOverlayHidden_andDoesNotDispatchACapture() {
        var touchCount = 0
        var committedVisible: Boolean? = null
        setScreen(
            CameraUiState(
                cameraPermission = PermissionStatus.GRANTED,
                overlayVisible = true,
                overlayImageUriString = "content://fake/overlay",
            ),
            onScreenTouch = { touchCount++ },
            onOverlayVisibilityChanged = { committedVisible = it },
        )

        composeTestRule.onNodeWithContentDescription(
            context.getString(R.string.overlay_image_content_description),
        ).performTouchInput { swipeRight() }

        assertThat(committedVisible).isFalse()
        assertThat(touchCount).isEqualTo(0)
    }

    @Test
    fun tappingThePreview_reportsATapGestureDiagnosticEvent() {
        val events = mutableListOf<GestureDiagnosticEvent>()
        setScreen(
            CameraUiState(cameraPermission = PermissionStatus.GRANTED),
            onGestureDiagnosticEvent = { events += it },
        )

        composeTestRule.onNodeWithContentDescription(
            context.getString(R.string.camera_preview_content_description),
        ).performTouchInput { click() }

        assertThat(events.filterIsInstance<GestureDiagnosticEvent.Detected>()).hasSize(1)
        val classified = events.filterIsInstance<GestureDiagnosticEvent.Classified>().single()
        assertThat(classified.classification).isEqualTo(GestureClassification.TAP)
        assertThat(events.filterIsInstance<GestureDiagnosticEvent.Accepted>().single().classification)
            .isEqualTo(GestureClassification.TAP)
    }

    @Test
    fun leftSwipeOnThePreview_reportsASwipeLeftGestureDiagnosticEvent() {
        val events = mutableListOf<GestureDiagnosticEvent>()
        setScreen(
            CameraUiState(
                cameraPermission = PermissionStatus.GRANTED,
                overlayVisible = false,
                overlayImageUriString = "content://fake/overlay",
            ),
            onGestureDiagnosticEvent = { events += it },
        )

        composeTestRule.onNodeWithContentDescription(
            context.getString(R.string.camera_preview_content_description),
        ).performTouchInput { swipeLeft() }

        val classified = events.filterIsInstance<GestureDiagnosticEvent.Classified>().single()
        assertThat(classified.classification).isEqualTo(GestureClassification.SWIPE_LEFT)
    }

    @Test
    fun diagnosticsToggleButton_isShown_andInvokesCallback() {
        // Only exercised under testDebugUnitTest, where BuildConfig.DEBUG is true - matching
        // "Debug Overlay" in app-spec.md, this button (and the overlay it controls) must never
        // render in a Release build, which is verified separately via the assembleRelease build.
        var toggleCount = 0
        setScreen(
            CameraUiState(cameraPermission = PermissionStatus.GRANTED),
            onDiagnosticsOverlayToggled = { toggleCount++ },
        )

        composeTestRule.onNodeWithContentDescription(
            context.getString(R.string.diagnostics_overlay_toggle_content_description),
        ).performClick()

        assertThat(toggleCount).isEqualTo(1)
    }
}
