package com.example.capture.security.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Hosts the two Activity-level APIs this screen needs (the `.kkey` file picker, and launching the
 * export share sheet); [EncryptionKeyScreen] itself never touches `ActivityResultContracts` or
 * `startActivity` - matching [com.example.capture.settings.ui.SettingsRoute]'s role.
 */
@Composable
fun KeySessionRoute(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: KeySessionViewModel = hiltViewModel()) {
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
    val dialog by viewModel.dialog.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.onImportFilePicked(uri)
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                KeySessionEvent.LaunchImportPicker -> importLauncher.launch(arrayOf("*/*"))
                is KeySessionEvent.LaunchExportShare -> context.startActivity(event.intent)
            }
        }
    }

    EncryptionKeyScreen(
        sessionState = sessionState,
        dialog = dialog,
        onSignInOrLockClicked = viewModel::onSignInOrLockClicked,
        onCreateKeyRequested = viewModel::onCreateKeyRequested,
        onImportKeyRequested = viewModel::onImportKeyRequested,
        onExportKeyRequested = viewModel::onExportKeyRequested,
        onChangePassphraseRequested = viewModel::onChangePassphraseRequested,
        onConfirmReplaceKey = viewModel::onConfirmReplaceKey,
        onDialogCancelled = viewModel::onDialogCancelled,
        onSignInPassphraseSubmitted = viewModel::onSignInPassphraseSubmitted,
        onCreateKeyPassphraseEntered = viewModel::onCreateKeyPassphraseEntered,
        onCreateKeyPassphraseConfirmed = viewModel::onCreateKeyPassphraseConfirmed,
        onChangePassphraseCurrentEntered = viewModel::onChangePassphraseCurrentEntered,
        onChangePassphraseNewEntered = viewModel::onChangePassphraseNewEntered,
        onChangePassphraseConfirmEntered = viewModel::onChangePassphraseConfirmEntered,
        onBack = onBack,
        modifier = modifier,
    )
}
