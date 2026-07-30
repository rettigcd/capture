package com.example.capture.settings.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.capture.R
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
        onBack: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = uiState,
                onVibrationDurationChanged = onVibrationDurationChanged,
                onPickImageClick = onPickImageClick,
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

        composeTestRule.onNodeWithText(context.getString(R.string.settings_no_image_selected)).assertIsDisplayed()
    }

    @Test
    fun noOverlayVisibilityControlExists_onTheSettingsScreen() {
        // Overlay visibility is controlled exclusively by a swipe gesture on the camera screen -
        // there must be no settings-screen control for it (no toggle/switch of any kind here).
        setScreen(SettingsUiState())

        assertThat(
            composeTestRule.onAllNodes(isToggleable()).fetchSemanticsNodes(atLeastOneRootRequired = false),
        ).isEmpty()
    }

    @Test
    fun clickingChooseImage_invokesCallback() {
        var clickCount = 0
        setScreen(SettingsUiState(), onPickImageClick = { clickCount++ })

        composeTestRule.onNodeWithText(context.getString(R.string.settings_choose_image_button)).performClick()

        assertThat(clickCount).isEqualTo(1)
    }

    @Test
    fun backButton_invokesOnBack() {
        var backCount = 0
        setScreen(SettingsUiState(), onBack = { backCount++ })

        composeTestRule.onNodeWithContentDescription(context.getString(R.string.back_content_description))
            .performClick()

        assertThat(backCount).isEqualTo(1)
    }
}
