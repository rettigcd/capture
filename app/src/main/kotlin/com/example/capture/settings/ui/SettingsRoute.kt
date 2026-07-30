package com.example.capture.settings.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Hosts the one Activity-level API this screen needs (the system Photo Picker); [SettingsScreen]
 * itself never touches `ActivityResultContracts`.
 */
@Composable
fun SettingsRoute(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: SettingsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        // The Photo Picker's returned Uri already grants persistent read access across app/device
        // restarts on its own, unlike a legacy document-picker Uri, so no
        // ContentResolver.takePersistableUriPermission call is needed here.
        if (uri != null) viewModel.onImageSelected(uri.toString())
    }

    SettingsScreen(
        uiState = uiState,
        onVibrationDurationChanged = viewModel::onVibrationDurationChanged,
        onPickImageClick = {
            pickImageLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        },
        onBack = onBack,
        modifier = modifier,
    )
}
