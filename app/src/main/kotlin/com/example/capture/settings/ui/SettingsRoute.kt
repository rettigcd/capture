package com.example.capture.settings.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Hosts the two Activity-level APIs this screen needs (the system Photo Picker, and the Storage
 * Access Framework folder picker for "Encrypt saved photos"); [SettingsScreen] itself never
 * touches `ActivityResultContracts`.
 */
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onNavigateToEncryptionKey: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        // The Photo Picker's returned Uri already grants persistent read access across app/device
        // restarts on its own, unlike a legacy document-picker Uri, so no
        // ContentResolver.takePersistableUriPermission call is needed here.
        if (uri != null) viewModel.onCoverPhotoSelected(uri.toString())
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        // Unlike the Photo Picker above, a SAF tree Uri's grant does not survive a restart on its
        // own - it must be explicitly persisted, once, right after the picker returns.
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        viewModel.onEncryptedPhotosFolderPicked(uri?.toString())
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                SettingsEvent.LaunchFolderPicker -> folderPickerLauncher.launch(null)
            }
        }
    }

    SettingsScreen(
        uiState = uiState,
        onVibrationDurationChanged = viewModel::onVibrationDurationChanged,
        onAddCoverPhotoClick = {
            pickImageLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        },
        onDeleteCoverPhotoClick = viewModel::onCoverPhotoDeleted,
        onCaptureModeChanged = viewModel::onCaptureModeChanged,
        onBurstIntervalChanged = viewModel::onBurstIntervalChanged,
        onDiagnosticsFileLoggingChanged = viewModel::onDiagnosticsFileLoggingChanged,
        onEncryptSavedPhotosChanged = viewModel::onEncryptSavedPhotosToggled,
        onChooseEncryptedPhotosFolderClick = viewModel::onChooseEncryptedPhotosFolderClicked,
        onNavigateToEncryptionKey = onNavigateToEncryptionKey,
        onBack = onBack,
        modifier = modifier,
    )
}
