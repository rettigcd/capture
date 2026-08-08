@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.capture.security.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.example.capture.R
import com.example.capture.security.domain.KeySessionState
import com.example.capture.security.domain.KeyStatus

/**
 * Stateless, like [com.example.capture.settings.ui.SettingsScreen]: every value comes from
 * [sessionState]/[dialog] and every action is a callback, so it can be tested without Hilt or a
 * real key file. [com.example.capture.security.ui.KeySessionRoute] is the `hiltViewModel()` entry
 * point that supplies both.
 */
@Composable
fun EncryptionKeyScreen(
    sessionState: KeySessionState,
    dialog: KeyDialog,
    onSignInOrLockClicked: () -> Unit,
    onCreateKeyRequested: () -> Unit,
    onImportKeyRequested: () -> Unit,
    onExportKeyRequested: () -> Unit,
    onChangePassphraseRequested: () -> Unit,
    onConfirmReplaceKey: (ReplaceFlow) -> Unit,
    onDialogCancelled: () -> Unit,
    onSignInPassphraseSubmitted: (String) -> Unit,
    onCreateKeyPassphraseEntered: (String) -> Unit,
    onCreateKeyPassphraseConfirmed: (String) -> Unit,
    onChangePassphraseCurrentEntered: (String) -> Unit,
    onChangePassphraseNewEntered: (String) -> Unit,
    onChangePassphraseConfirmEntered: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.encryption_key_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back_content_description),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            ListItem(
                headlineContent = {
                    Text(
                        stringResource(
                            if (sessionState.keyStatus == KeyStatus.PRIVATE) R.string.encryption_key_lock else R.string.encryption_key_sign_in,
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth().clickable(onClick = onSignInOrLockClicked),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.encryption_key_create_key)) },
                modifier = Modifier.fillMaxWidth().clickable(onClick = onCreateKeyRequested),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.encryption_key_import_key)) },
                modifier = Modifier.fillMaxWidth().clickable(onClick = onImportKeyRequested),
            )
            if (sessionState.hasKeyFile) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.encryption_key_export_key)) },
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onExportKeyRequested),
                )
            }
            if (sessionState.keyStatus == KeyStatus.PRIVATE) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.encryption_key_change_passphrase)) },
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onChangePassphraseRequested),
                )
            }
        }
    }

    EncryptionKeyDialogHost(
        dialog = dialog,
        onConfirmReplaceKey = onConfirmReplaceKey,
        onDialogCancelled = onDialogCancelled,
        onSignInPassphraseSubmitted = onSignInPassphraseSubmitted,
        onCreateKeyPassphraseEntered = onCreateKeyPassphraseEntered,
        onCreateKeyPassphraseConfirmed = onCreateKeyPassphraseConfirmed,
        onChangePassphraseCurrentEntered = onChangePassphraseCurrentEntered,
        onChangePassphraseNewEntered = onChangePassphraseNewEntered,
        onChangePassphraseConfirmEntered = onChangePassphraseConfirmEntered,
    )
}

@Composable
private fun EncryptionKeyDialogHost(
    dialog: KeyDialog,
    onConfirmReplaceKey: (ReplaceFlow) -> Unit,
    onDialogCancelled: () -> Unit,
    onSignInPassphraseSubmitted: (String) -> Unit,
    onCreateKeyPassphraseEntered: (String) -> Unit,
    onCreateKeyPassphraseConfirmed: (String) -> Unit,
    onChangePassphraseCurrentEntered: (String) -> Unit,
    onChangePassphraseNewEntered: (String) -> Unit,
    onChangePassphraseConfirmEntered: (String) -> Unit,
) {
    when (dialog) {
        is KeyDialog.None -> Unit

        is KeyDialog.ConfirmReplaceKey -> AlertDialog(
            onDismissRequest = onDialogCancelled,
            title = {
                Text(
                    stringResource(
                        if (dialog.flow == ReplaceFlow.CREATE) {
                            R.string.encryption_key_confirm_replace_title_create
                        } else {
                            R.string.encryption_key_confirm_replace_title_import
                        },
                    ),
                )
            },
            text = { Text(stringResource(R.string.encryption_key_confirm_replace_message)) },
            confirmButton = {
                TextButton(onClick = { onConfirmReplaceKey(dialog.flow) }) { Text(stringResource(R.string.encryption_key_continue_button)) }
            },
            dismissButton = { TextButton(onClick = onDialogCancelled) { Text(stringResource(R.string.encryption_key_cancel_button)) } },
        )

        is KeyDialog.SignInPrompt -> PassphraseDialog(
            title = stringResource(R.string.encryption_key_unlock_title),
            prompt = dialog.fingerprintHint?.let { stringResource(R.string.encryption_key_unlock_prompt_with_fingerprint, it) }
                ?: stringResource(R.string.encryption_key_unlock_prompt_no_fingerprint),
            error = dialog.error,
            confirmLabel = stringResource(R.string.encryption_key_unlock_button),
            onSubmit = onSignInPassphraseSubmitted,
            onCancel = onDialogCancelled,
        )

        is KeyDialog.CreateKeyEnterPassphrase -> PassphraseDialog(
            title = stringResource(R.string.encryption_key_create_title),
            prompt = stringResource(R.string.encryption_key_create_enter_prompt),
            error = dialog.error,
            confirmLabel = stringResource(R.string.encryption_key_next_button),
            onSubmit = onCreateKeyPassphraseEntered,
            onCancel = onDialogCancelled,
        )

        is KeyDialog.CreateKeyConfirmPassphrase -> PassphraseDialog(
            title = stringResource(R.string.encryption_key_create_title),
            prompt = stringResource(R.string.encryption_key_create_confirm_prompt),
            error = dialog.error,
            confirmLabel = stringResource(R.string.encryption_key_create_button),
            onSubmit = onCreateKeyPassphraseConfirmed,
            onCancel = onDialogCancelled,
        )

        is KeyDialog.ChangePassphraseCurrent -> PassphraseDialog(
            title = stringResource(R.string.encryption_key_change_passphrase_title),
            prompt = dialog.fingerprintHint?.let { stringResource(R.string.encryption_key_change_current_prompt_with_fingerprint, it) }
                ?: stringResource(R.string.encryption_key_change_current_prompt_no_fingerprint),
            error = dialog.error,
            confirmLabel = stringResource(R.string.encryption_key_next_button),
            onSubmit = onChangePassphraseCurrentEntered,
            onCancel = onDialogCancelled,
        )

        is KeyDialog.ChangePassphraseNew -> PassphraseDialog(
            title = stringResource(R.string.encryption_key_change_passphrase_title),
            prompt = stringResource(R.string.encryption_key_change_new_prompt),
            error = dialog.error,
            confirmLabel = stringResource(R.string.encryption_key_next_button),
            onSubmit = onChangePassphraseNewEntered,
            onCancel = onDialogCancelled,
        )

        is KeyDialog.ChangePassphraseConfirm -> PassphraseDialog(
            title = stringResource(R.string.encryption_key_change_passphrase_title),
            prompt = stringResource(R.string.encryption_key_change_confirm_prompt),
            error = dialog.error,
            confirmLabel = stringResource(R.string.encryption_key_change_button),
            onSubmit = onChangePassphraseConfirmEntered,
            onCancel = onDialogCancelled,
        )

        is KeyDialog.InfoMessage -> AlertDialog(
            onDismissRequest = onDialogCancelled,
            title = { Text(dialog.title) },
            text = { Text(dialog.message) },
            confirmButton = { TextButton(onClick = onDialogCancelled) { Text(stringResource(R.string.encryption_key_ok_button)) } },
        )
    }
}

@Composable
private fun PassphraseDialog(
    title: String,
    prompt: String,
    error: String?,
    confirmLabel: String,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember(title, prompt) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column {
                Text(prompt)
                // Deliberately not masked (no PasswordVisualTransformation) and not autocorrected -
                // so the passphrase can be checked before submit, and the keyboard never learns/suggests it.
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().testTag("passphrase_dialog_input"),
                )
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(text) }, enabled = text.isNotEmpty()) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.encryption_key_cancel_button)) } },
    )
}
