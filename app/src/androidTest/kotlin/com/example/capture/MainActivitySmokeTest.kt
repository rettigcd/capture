package com.example.capture

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Requires a connected device or emulator (`./gradlew connectedDebugAndroidTest`) - it is not
 * part of `testDebugUnitTest` and is not expected to run in an environment without camera
 * hardware. Whether the preview actually shows live frames, whether every OEM's volume keys
 * behave identically, and other true hardware behavior cannot be reliably asserted here; see
 * README.md's manual device-test checklist for those. This test only confirms the Activity, the
 * Hilt dependency graph, and the Compose tree come up successfully once camera permission is
 * granted, so a regression that breaks app startup is still caught on CI hardware.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val grantCameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun mainActivity_launchesAndShowsTheShutterControl() {
        composeRule
            .onNodeWithContentDescription(
                composeRule.activity.getString(R.string.shutter_button_content_description),
            )
            .assertIsDisplayed()
    }
}
