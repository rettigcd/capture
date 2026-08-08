package com.example.capture.security.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.capture.R
import com.example.capture.security.domain.KeySessionState
import com.example.capture.security.domain.KeyStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runs on the JVM via Robolectric, same as `SettingsScreenTest`; no Hilt or real key file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EncryptionKeyScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun setScreen(
        sessionState: KeySessionState = KeySessionState(),
        dialog: KeyDialog = KeyDialog.None,
        onSignInOrLockClicked: () -> Unit = {},
        onCreateKeyRequested: () -> Unit = {},
        onImportKeyRequested: () -> Unit = {},
        onExportKeyRequested: () -> Unit = {},
        onChangePassphraseRequested: () -> Unit = {},
        onConfirmReplaceKey: (ReplaceFlow) -> Unit = {},
        onDialogCancelled: () -> Unit = {},
        onSignInPassphraseSubmitted: (String) -> Unit = {},
        onCreateKeyPassphraseEntered: (String) -> Unit = {},
        onCreateKeyPassphraseConfirmed: (String) -> Unit = {},
        onChangePassphraseCurrentEntered: (String) -> Unit = {},
        onChangePassphraseNewEntered: (String) -> Unit = {},
        onChangePassphraseConfirmEntered: (String) -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            EncryptionKeyScreen(
                sessionState = sessionState,
                dialog = dialog,
                onSignInOrLockClicked = onSignInOrLockClicked,
                onCreateKeyRequested = onCreateKeyRequested,
                onImportKeyRequested = onImportKeyRequested,
                onExportKeyRequested = onExportKeyRequested,
                onChangePassphraseRequested = onChangePassphraseRequested,
                onConfirmReplaceKey = onConfirmReplaceKey,
                onDialogCancelled = onDialogCancelled,
                onSignInPassphraseSubmitted = onSignInPassphraseSubmitted,
                onCreateKeyPassphraseEntered = onCreateKeyPassphraseEntered,
                onCreateKeyPassphraseConfirmed = onCreateKeyPassphraseConfirmed,
                onChangePassphraseCurrentEntered = onChangePassphraseCurrentEntered,
                onChangePassphraseNewEntered = onChangePassphraseNewEntered,
                onChangePassphraseConfirmEntered = onChangePassphraseConfirmEntered,
                onBack = onBack,
            )
        }
    }

    @Test
    fun `signed out with no key file shows Sign In, Create Key and Import Key but not Export or Change Passphrase`() {
        setScreen(sessionState = KeySessionState(keyStatus = KeyStatus.NONE, hasKeyFile = false))

        composeTestRule.onNodeWithText(context.getString(R.string.encryption_key_sign_in)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.encryption_key_create_key)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.encryption_key_import_key)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.encryption_key_export_key)).assertDoesNotExist()
        composeTestRule.onNodeWithText(context.getString(R.string.encryption_key_change_passphrase)).assertDoesNotExist()
    }

    @Test
    fun `signed in shows Lock and Change Passphrase, not Sign In`() {
        setScreen(sessionState = KeySessionState(keyStatus = KeyStatus.PRIVATE, hasKeyFile = true))

        composeTestRule.onNodeWithText(context.getString(R.string.encryption_key_lock)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.encryption_key_change_passphrase)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.encryption_key_sign_in)).assertDoesNotExist()
    }

    @Test
    fun `locked with a key file present shows Export Key`() {
        setScreen(sessionState = KeySessionState(keyStatus = KeyStatus.PUBLIC, hasKeyFile = true))

        composeTestRule.onNodeWithText(context.getString(R.string.encryption_key_export_key)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.encryption_key_change_passphrase)).assertDoesNotExist()
    }

    @Test
    fun `clicking Create Key invokes the callback`() {
        var createRequested = false
        setScreen(onCreateKeyRequested = { createRequested = true })

        composeTestRule.onNodeWithText(context.getString(R.string.encryption_key_create_key)).performClick()

        assertThat(createRequested).isTrue()
    }

    @Test
    fun `back button invokes onBack`() {
        var backCalled = false
        setScreen(onBack = { backCalled = true })

        composeTestRule.onNodeWithContentDescription(context.getString(R.string.back_content_description)).performClick()

        assertThat(backCalled).isTrue()
    }

    @Test
    fun `sign in prompt dialog submits the entered passphrase`() {
        var submittedPassphrase: String? = null
        setScreen(
            dialog = KeyDialog.SignInPrompt(fingerprintHint = null),
            onSignInPassphraseSubmitted = { submittedPassphrase = it },
        )

        // Dialog title and confirm-button label are both "Unlock" here - disambiguate the button
        // by its click action rather than matching on text alone.
        composeTestRule.onNodeWithTag("passphrase_dialog_input").performTextInput("s3cret!")
        composeTestRule.onNode(hasText(context.getString(R.string.encryption_key_unlock_button)) and hasClickAction()).performClick()

        assertThat(submittedPassphrase).isEqualTo("s3cret!")
    }

    @Test
    fun `sign in prompt shows a fingerprint hint when one is provided`() {
        setScreen(dialog = KeyDialog.SignInPrompt(fingerprintHint = "ABCD1234ABCD1234"))

        composeTestRule
            .onNodeWithText(context.getString(R.string.encryption_key_unlock_prompt_with_fingerprint, "ABCD1234ABCD1234"))
            .assertExists()
    }

    @Test
    fun `sign in prompt shows an error message when present`() {
        setScreen(dialog = KeyDialog.SignInPrompt(fingerprintHint = null, error = "Incorrect passphrase or corrupted key file."))

        composeTestRule.onNodeWithText("Incorrect passphrase or corrupted key file.").assertExists()
    }

    @Test
    fun `info message dialog OK button dismisses via onDialogCancelled`() {
        var cancelled = false
        setScreen(
            dialog = KeyDialog.InfoMessage(title = "Create Encryption Key", message = "Encryption key created and activated."),
            onDialogCancelled = { cancelled = true },
        )

        composeTestRule.onNodeWithText("Encryption key created and activated.").assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.encryption_key_ok_button)).performClick()

        assertThat(cancelled).isTrue()
    }

    @Test
    fun `confirm replace key dialog Continue invokes onConfirmReplaceKey with the pending flow`() {
        var confirmedFlow: ReplaceFlow? = null
        setScreen(
            dialog = KeyDialog.ConfirmReplaceKey(ReplaceFlow.IMPORT),
            onConfirmReplaceKey = { confirmedFlow = it },
        )

        composeTestRule.onNodeWithText(context.getString(R.string.encryption_key_continue_button)).performClick()

        assertThat(confirmedFlow).isEqualTo(ReplaceFlow.IMPORT)
    }
}
