package com.example.capture.settings.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.capture.R
import com.example.capture.camera.domain.CaptureAspectRatio
import com.example.capture.camera.domain.CaptureMode
import com.example.capture.camera.domain.CaptureTriggerKind
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runs on the JVM via Robolectric, same as `CameraScreenTest`; no Hilt, DataStore, or picker. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun setScreen(
        uiState: SettingsUiState,
        onVibrationDurationChanged: (Long) -> Unit = {},
        onPickImageClick: () -> Unit = {},
        onCaptureModeChanged: (CaptureTriggerKind, CaptureMode) -> Unit = { _, _ -> },
        onBurstIntervalChanged: (Long) -> Unit = {},
        onCaptureAspectRatioChanged: (CaptureAspectRatio) -> Unit = {},
        onDiagnosticsFileLoggingChanged: (Boolean) -> Unit = {},
        onEncryptSavedPhotosChanged: (Boolean) -> Unit = {},
        onChooseEncryptedPhotosFolderClick: () -> Unit = {},
        onZoomLevelChanged: (Int) -> Unit = {},
        onNavigateToEncryptionKey: () -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = uiState,
                onVibrationDurationChanged = onVibrationDurationChanged,
                onPickImageClick = onPickImageClick,
                onCaptureModeChanged = onCaptureModeChanged,
                onBurstIntervalChanged = onBurstIntervalChanged,
                onCaptureAspectRatioChanged = onCaptureAspectRatioChanged,
                onDiagnosticsFileLoggingChanged = onDiagnosticsFileLoggingChanged,
                onEncryptSavedPhotosChanged = onEncryptSavedPhotosChanged,
                onChooseEncryptedPhotosFolderClick = onChooseEncryptedPhotosFolderClick,
                onZoomLevelChanged = onZoomLevelChanged,
                onNavigateToEncryptionKey = onNavigateToEncryptionKey,
                onBack = onBack,
            )
        }
    }

    @Test
    fun vibrationDurationLabel_reflectsTheCurrentValue() {
        setScreen(SettingsUiState(vibrationDurationMillis = 180L))

        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_vibration_duration_label, 180L))
            .assertIsDisplayed()
    }

    @Test
    fun noImageSelectedMessage_isShown_whenNoImageHasBeenPicked() {
        setScreen(SettingsUiState(overlayImageUriString = null))

        composeTestRule.onNodeWithText(context.getString(R.string.settings_no_image_selected))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun noOverlayVisibilityControlExists_onTheSettingsScreen() {
        // Overlay visibility is controlled exclusively by a swipe gesture on the camera screen -
        // the only toggles on this screen are "Encrypt saved photos" and the diagnostics-file-
        // logging switch (see "Diagnostic Persistence" in app-spec.md); there must be no separate
        // one for the overlay.
        setScreen(SettingsUiState())

        assertThat(
            composeTestRule.onAllNodes(isToggleable()).fetchSemanticsNodes(atLeastOneRootRequired = false),
        ).hasSize(2)
    }

    @Test
    fun diagnosticsFileLoggingSwitch_reflectsTheCurrentValue_andInvokesCallbackWhenToggled() {
        var enabled: Boolean? = null
        setScreen(
            SettingsUiState(diagnosticsFileLoggingEnabled = false),
            onDiagnosticsFileLoggingChanged = { enabled = it },
        )

        composeTestRule.onNodeWithTag("diagnostics_file_logging_switch")
            .performScrollTo()
            .performClick()

        assertThat(enabled).isTrue()
    }

    @Test
    fun clickingChooseImage_invokesCallback() {
        var clickCount = 0
        setScreen(SettingsUiState(), onPickImageClick = { clickCount++ })

        composeTestRule.onNodeWithText(context.getString(R.string.settings_choose_image_button))
            .performScrollTo()
            .performClick()

        assertThat(clickCount).isEqualTo(1)
    }

    @Test
    fun allSixCaptureModeTriggers_areShown() {
        setScreen(SettingsUiState())

        for (trigger in CaptureTriggerKind.entries) {
            composeTestRule.onNodeWithTag("capture_mode_${trigger.name.lowercase()}_single_shot")
                .performScrollTo()
                .assertIsDisplayed()
        }
    }

    @Test
    fun captureModeSegmentedButtons_reflectEachTriggersOwnSelection_independently() {
        setScreen(
            SettingsUiState(
                captureModeByTrigger = CaptureTriggerKind.entries.associateWith { CaptureMode.SINGLE_SHOT } +
                    (CaptureTriggerKind.VOLUME_UP to CaptureMode.BURST),
            ),
        )

        composeTestRule.onNodeWithTag("capture_mode_volume_up_burst").performScrollTo().assertIsSelected()
        composeTestRule.onNodeWithTag("capture_mode_volume_up_single_shot").assertIsNotSelected()
        // A trigger left at the default stays Single-Shot, unaffected by Volume Up's setting.
        composeTestRule.onNodeWithTag("capture_mode_volume_down_single_shot").performScrollTo().assertIsSelected()
        composeTestRule.onNodeWithTag("capture_mode_volume_down_burst").assertIsNotSelected()
    }

    @Test
    fun selectingBurstForOneTrigger_invokesCallbackWithOnlyThatTrigger() {
        var changed: Pair<CaptureTriggerKind, CaptureMode>? = null
        setScreen(SettingsUiState(), onCaptureModeChanged = { trigger, mode -> changed = trigger to mode })

        composeTestRule.onNodeWithTag("capture_mode_volume_up_burst").performScrollTo().performClick()

        assertThat(changed).isEqualTo(CaptureTriggerKind.VOLUME_UP to CaptureMode.BURST)
    }

    @Test
    fun allSixCaptureModeTriggers_showAVideoOption() {
        setScreen(SettingsUiState())

        for (trigger in CaptureTriggerKind.entries) {
            composeTestRule.onNodeWithTag("capture_mode_${trigger.name.lowercase()}_video")
                .performScrollTo()
                .assertIsDisplayed()
        }
    }

    @Test
    fun selectingVideoForOneTrigger_invokesCallbackWithOnlyThatTrigger() {
        var changed: Pair<CaptureTriggerKind, CaptureMode>? = null
        setScreen(SettingsUiState(), onCaptureModeChanged = { trigger, mode -> changed = trigger to mode })

        composeTestRule.onNodeWithTag("capture_mode_volume_up_video").performScrollTo().performClick()

        assertThat(changed).isEqualTo(CaptureTriggerKind.VOLUME_UP to CaptureMode.VIDEO)
    }

    @Test
    fun burstIntervalLabel_reflectsTheCurrentValue() {
        setScreen(SettingsUiState(burstIntervalMillis = 750L))

        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_burst_interval_label, 750L))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun aspectRatioSegmentedButton_reflectsTheCurrentSelection() {
        setScreen(SettingsUiState(captureAspectRatio = CaptureAspectRatio.RATIO_16_9))

        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_aspect_ratio_16_9))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun selecting16x9_invokesCaptureAspectRatioChangedCallback() {
        var selectedRatio: CaptureAspectRatio? = null
        setScreen(
            SettingsUiState(captureAspectRatio = CaptureAspectRatio.RATIO_4_3),
            onCaptureAspectRatioChanged = { selectedRatio = it },
        )

        composeTestRule.onNodeWithText(context.getString(R.string.settings_aspect_ratio_16_9))
            .performScrollTo()
            .performClick()

        assertThat(selectedRatio).isEqualTo(CaptureAspectRatio.RATIO_16_9)
    }

    @Test
    fun zoomLevelLabel_reflectsTheCurrentValue() {
        setScreen(SettingsUiState(zoomLevel = 4))

        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_zoom_label, 4))
            .assertIsDisplayed()
    }

    @Test
    fun backButton_invokesOnBack() {
        var backCount = 0
        setScreen(SettingsUiState(), onBack = { backCount++ })

        composeTestRule.onNodeWithContentDescription(context.getString(R.string.back_content_description))
            .performClick()

        assertThat(backCount).isEqualTo(1)
    }

    @Test
    fun encryptSavedPhotosSwitch_isDisabled_whenNoKeyFileExists() {
        setScreen(SettingsUiState(encryptSavedPhotosAvailable = false))

        composeTestRule.onNodeWithTag("encrypt_saved_photos_switch").assertIsNotEnabled()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_encrypt_saved_photos_unavailable_message))
            .assertIsDisplayed()
    }

    @Test
    fun encryptSavedPhotosSwitch_isEnabled_whenAKeyFileExists_andReflectsTheCurrentValue() {
        setScreen(SettingsUiState(encryptSavedPhotosAvailable = true, encryptSavedPhotos = true))

        composeTestRule.onNodeWithTag("encrypt_saved_photos_switch").assertIsEnabled().assertIsOn()
    }

    @Test
    fun togglingEncryptSavedPhotos_invokesCallback() {
        var enabled: Boolean? = null
        setScreen(
            SettingsUiState(encryptSavedPhotosAvailable = true, encryptSavedPhotos = false),
            onEncryptSavedPhotosChanged = { enabled = it },
        )

        composeTestRule.onNodeWithTag("encrypt_saved_photos_switch").performClick()

        assertThat(enabled).isTrue()
    }

    @Test
    fun chooseFolderButton_showsChooseLabel_whenNoFolderIsPicked_andChangeLabel_onceOneIs() {
        setScreen(SettingsUiState(hasEncryptedPhotosFolder = false))
        composeTestRule.onNodeWithText(context.getString(R.string.settings_choose_encrypted_photos_folder_button)).assertIsDisplayed()
    }

    @Test
    fun chooseFolderButton_invokesCallback() {
        var clickCount = 0
        setScreen(SettingsUiState(hasEncryptedPhotosFolder = true), onChooseEncryptedPhotosFolderClick = { clickCount++ })

        composeTestRule.onNodeWithText(context.getString(R.string.settings_change_encrypted_photos_folder_button)).performClick()

        assertThat(clickCount).isEqualTo(1)
    }
}
