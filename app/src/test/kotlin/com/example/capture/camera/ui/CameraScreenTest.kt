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
    fun captureInProgress_isRepresentedInTheStatusIndicator() {
        setScreen(
            CameraUiState(cameraPermission = PermissionStatus.GRANTED, captureStatus = CaptureStatusUi.Capturing),
        )

        composeTestRule.onNodeWithText(context.getString(R.string.capture_status_capturing)).assertIsDisplayed()
    }

    @Test
    fun captureFailure_isPresentedAccessibly() {
        val message = "Couldn't save the photo. Please try again."
        setScreen(
            CameraUiState(cameraPermission = PermissionStatus.GRANTED, captureStatus = CaptureStatusUi.Failed(message)),
        )

        composeTestRule.onNodeWithText(message).assertIsDisplayed()
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
    fun statusIndicatorAndShutterButton_areHidden_whileTheOverlayIsShown() {
        setScreen(
            CameraUiState(
                cameraPermission = PermissionStatus.GRANTED,
                overlayVisible = true,
                overlayImageUriString = "content://fake/overlay",
                captureStatus = CaptureStatusUi.Saved("content://fake/photo"),
            ),
        )

        assertThat(
            composeTestRule.onAllNodesWithContentDescription(
                context.getString(R.string.shutter_button_content_description),
            ).fetchSemanticsNodes(atLeastOneRootRequired = false),
        ).isEmpty()
        assertThat(
            composeTestRule.onAllNodesWithText(context.getString(R.string.capture_status_saved))
                .fetchSemanticsNodes(atLeastOneRootRequired = false),
        ).isEmpty()
    }

    @Test
    fun statusIndicatorAndShutterButton_reappear_whenSwitchedBackToTheLivePreview() {
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
        composeTestRule.onNodeWithText(context.getString(R.string.capture_status_idle)).assertIsDisplayed()
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
}
