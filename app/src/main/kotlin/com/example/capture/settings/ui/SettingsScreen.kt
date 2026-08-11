package com.example.capture.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.capture.R
import com.example.capture.camera.domain.CaptureAspectRatio
import com.example.capture.camera.domain.CaptureMode
import com.example.capture.camera.domain.CaptureTriggerKind
import com.example.capture.settings.domain.AppSettings
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Stateless, like [com.example.capture.camera.ui.CameraScreen]: every value comes from [uiState]
 * and every action is a callback, so it can be tested without Hilt, DataStore, or a real photo
 * picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onVibrationDurationChanged: (Long) -> Unit,
    onAddCoverPhotoClick: () -> Unit,
    onDeleteCoverPhotoClick: (Int) -> Unit,
    onCaptureModeChanged: (CaptureTriggerKind, CaptureMode) -> Unit,
    onBurstIntervalChanged: (Long) -> Unit,
    onCaptureAspectRatioChanged: (CaptureAspectRatio) -> Unit,
    onDiagnosticsFileLoggingChanged: (Boolean) -> Unit,
    onEncryptSavedPhotosChanged: (Boolean) -> Unit,
    onChooseEncryptedPhotosFolderClick: () -> Unit,
    onZoomLevelChanged: (Int) -> Unit,
    onNavigateToEncryptionKey: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .padding(contentPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            EncryptSavedPhotosSetting(
                enabled = uiState.encryptSavedPhotos,
                available = uiState.encryptSavedPhotosAvailable,
                hasFolder = uiState.hasEncryptedPhotosFolder,
                onEnabledChanged = onEncryptSavedPhotosChanged,
                onChooseFolderClick = onChooseEncryptedPhotosFolderClick,
            )
            HorizontalDivider()
            ZoomLevelSetting(
                level = uiState.zoomLevel,
                onLevelChanged = onZoomLevelChanged,
            )
            HorizontalDivider()
            VibrationDurationSetting(
                durationMillis = uiState.vibrationDurationMillis,
                onDurationChanged = onVibrationDurationChanged,
            )
            HorizontalDivider()
            CoverPhotosSetting(
                coverPhotoUriStrings = uiState.coverPhotoUriStrings,
                onAddClick = onAddCoverPhotoClick,
                onDeleteClick = onDeleteCoverPhotoClick,
            )
            HorizontalDivider()
            CaptureModeSection(
                captureModeByTrigger = uiState.captureModeByTrigger,
                onCaptureModeChanged = onCaptureModeChanged,
            )
            HorizontalDivider()
            BurstIntervalSetting(
                intervalMillis = uiState.burstIntervalMillis,
                onIntervalChanged = onBurstIntervalChanged,
            )
            HorizontalDivider()
            CaptureAspectRatioSetting(
                aspectRatio = uiState.captureAspectRatio,
                onAspectRatioChanged = onCaptureAspectRatioChanged,
            )
            HorizontalDivider()
            DiagnosticsFileLoggingSetting(
                enabled = uiState.diagnosticsFileLoggingEnabled,
                onEnabledChanged = onDiagnosticsFileLoggingChanged,
            )
            HorizontalDivider()
            Button(onClick = onNavigateToEncryptionKey) {
                Text(stringResource(R.string.settings_encryption_key_button))
            }
        }
    }
}

/**
 * Off by default (see "Encrypt saved photos" in app-spec.md). Disabled entirely while no
 * encryption key file exists ([available] false) - signing in is not required, only a key file,
 * since encryption only ever needs the public key. The "Choose folder"/"Change folder" button is
 * always available (independent of [enabled]) so a folder can be picked or changed at any time;
 * picking one turns the toggle on.
 */
@Composable
private fun EncryptSavedPhotosSetting(
    enabled: Boolean,
    available: Boolean,
    hasFolder: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
    onChooseFolderClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_encrypt_saved_photos_label))
        if (!available) {
            Text(
                text = stringResource(R.string.settings_encrypt_saved_photos_unavailable_message),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChanged,
            enabled = available,
            modifier = Modifier.testTag("encrypt_saved_photos_switch"),
        )
        Button(onClick = onChooseFolderClick) {
            Text(
                stringResource(
                    if (hasFolder) R.string.settings_change_encrypted_photos_folder_button else R.string.settings_choose_encrypted_photos_folder_button,
                ),
            )
        }
    }
}

/**
 * Five discrete positions, 1x through 5x (see "Camera zoom" in app-spec.md) - a single value, not
 * per-trigger or per-mode, applied identically to the live preview and to Single-Shot, Burst, and
 * Video Mode capture alike.
 */
@Composable
private fun ZoomLevelSetting(level: Int, onLevelChanged: (Int) -> Unit) {
    Column {
        Text(stringResource(R.string.settings_zoom_label, level))
        val range = AppSettings.ZOOM_LEVEL_RANGE
        val stepCount = (range.last - range.first) / AppSettings.ZOOM_LEVEL_STEP
        Slider(
            value = level.toFloat(),
            onValueChange = { onLevelChanged(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (stepCount - 1).coerceAtLeast(0),
        )
    }
}

@Composable
private fun VibrationDurationSetting(durationMillis: Long, onDurationChanged: (Long) -> Unit) {
    Column {
        Text(stringResource(R.string.settings_vibration_duration_label, durationMillis))
        val range = AppSettings.VIBRATION_DURATION_RANGE_MILLIS
        val stepCount = ((range.last - range.first) / AppSettings.VIBRATION_DURATION_STEP_MILLIS).toInt()
        Slider(
            value = durationMillis.toFloat(),
            onValueChange = { onDurationChanged(it.roundToLong()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (stepCount - 1).coerceAtLeast(0),
        )
    }
}

/**
 * Up to [AppSettings.MAX_COVER_PHOTOS] cover photos (see "Cover Photos" in app-spec.md), shown as
 * thumbnails in list order with a per-photo delete control. The Add button is hidden once the cap
 * is reached, rather than shown disabled, since there's nothing more to explain to the user at
 * that point.
 */
@Composable
private fun CoverPhotosSetting(
    coverPhotoUriStrings: List<String>,
    onAddClick: () -> Unit,
    onDeleteClick: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_cover_photos_label))
        if (coverPhotoUriStrings.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_no_image_selected),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                coverPhotoUriStrings.forEachIndexed { index, uriString ->
                    CoverPhotoThumbnail(
                        uriString = uriString,
                        onDeleteClick = { onDeleteClick(index) },
                    )
                }
            }
        }
        if (coverPhotoUriStrings.size < AppSettings.MAX_COVER_PHOTOS) {
            Button(onClick = onAddClick) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(stringResource(R.string.settings_add_cover_photo_button))
            }
        }
    }
}

@Composable
private fun CoverPhotoThumbnail(uriString: String, onDeleteClick: () -> Unit) {
    Box(modifier = Modifier.size(120.dp)) {
        AsyncImage(
            model = uriString,
            contentDescription = stringResource(R.string.settings_cover_photo_thumbnail_content_description),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp)),
        )
        val deleteDescription = stringResource(R.string.settings_delete_cover_photo_content_description)
        OutlinedIconButton(
            onClick = onDeleteClick,
            colors = IconButtonDefaults.outlinedIconButtonColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(28.dp)
                .testTag("delete_cover_photo_$uriString"),
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = deleteDescription,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * Six independent Single-Shot/Burst choices, one per [CaptureTriggerKind] (see "Capture Mode" in
 * app-spec.md) - each trigger's [CaptureModeRow] is otherwise identical to what used to be the
 * single, global control here.
 */
@Composable
private fun CaptureModeSection(
    captureModeByTrigger: Map<CaptureTriggerKind, CaptureMode>,
    onCaptureModeChanged: (CaptureTriggerKind, CaptureMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.settings_capture_mode_label), style = MaterialTheme.typography.titleMedium)
        for (trigger in CaptureTriggerKind.entries) {
            CaptureModeRow(
                trigger = trigger,
                captureMode = captureModeByTrigger.getValue(trigger),
                onCaptureModeChanged = { mode -> onCaptureModeChanged(trigger, mode) },
            )
        }
    }
}

@Composable
private fun CaptureTriggerKind.label(): String = stringResource(
    when (this) {
        CaptureTriggerKind.SCREEN_TOP -> R.string.settings_capture_mode_trigger_screen_top
        CaptureTriggerKind.SCREEN_BOTTOM -> R.string.settings_capture_mode_trigger_screen_bottom
        CaptureTriggerKind.SHUTTER_BUTTON -> R.string.settings_capture_mode_trigger_shutter_button
        CaptureTriggerKind.VOLUME_UP -> R.string.settings_capture_mode_trigger_volume_up
        CaptureTriggerKind.VOLUME_DOWN -> R.string.settings_capture_mode_trigger_volume_down
        CaptureTriggerKind.VOICE_COMMAND -> R.string.settings_capture_mode_trigger_voice_command
    },
)

// Distinguishes the six otherwise-identically-labelled Single-Shot/Burst button pairs for tests
// (see CaptureModeRow's testTag usage below) - not shown to the user.
private fun CaptureTriggerKind.testTagPrefix(): String = "capture_mode_${name.lowercase()}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaptureModeRow(trigger: CaptureTriggerKind, captureMode: CaptureMode, onCaptureModeChanged: (CaptureMode) -> Unit) {
    val testTagPrefix = trigger.testTagPrefix()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(trigger.label())
        SingleChoiceSegmentedButtonRow {
            SegmentedButton(
                selected = captureMode == CaptureMode.SINGLE_SHOT,
                onClick = { onCaptureModeChanged(CaptureMode.SINGLE_SHOT) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                modifier = Modifier.testTag("${testTagPrefix}_single_shot"),
            ) {
                Text(stringResource(R.string.settings_capture_mode_single_shot))
            }
            SegmentedButton(
                selected = captureMode == CaptureMode.BURST,
                onClick = { onCaptureModeChanged(CaptureMode.BURST) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                modifier = Modifier.testTag("${testTagPrefix}_burst"),
            ) {
                Text(stringResource(R.string.settings_capture_mode_burst))
            }
            SegmentedButton(
                selected = captureMode == CaptureMode.VIDEO,
                onClick = { onCaptureModeChanged(CaptureMode.VIDEO) },
                shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                modifier = Modifier.testTag("${testTagPrefix}_video"),
            ) {
                Text(stringResource(R.string.settings_capture_mode_video))
            }
        }
    }
}

@Composable
private fun BurstIntervalSetting(intervalMillis: Long, onIntervalChanged: (Long) -> Unit) {
    Column {
        Text(stringResource(R.string.settings_burst_interval_label, intervalMillis))
        val range = AppSettings.BURST_INTERVAL_RANGE_MILLIS
        val stepCount = ((range.last - range.first) / AppSettings.BURST_INTERVAL_STEP_MILLIS).toInt()
        Slider(
            value = intervalMillis.toFloat(),
            onValueChange = { onIntervalChanged(it.roundToLong()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (stepCount - 1).coerceAtLeast(0),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaptureAspectRatioSetting(
    aspectRatio: CaptureAspectRatio,
    onAspectRatioChanged: (CaptureAspectRatio) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_aspect_ratio_label))
        SingleChoiceSegmentedButtonRow {
            SegmentedButton(
                selected = aspectRatio == CaptureAspectRatio.RATIO_4_3,
                onClick = { onAspectRatioChanged(CaptureAspectRatio.RATIO_4_3) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) {
                Text(stringResource(R.string.settings_aspect_ratio_4_3))
            }
            SegmentedButton(
                selected = aspectRatio == CaptureAspectRatio.RATIO_16_9,
                onClick = { onAspectRatioChanged(CaptureAspectRatio.RATIO_16_9) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) {
                Text(stringResource(R.string.settings_aspect_ratio_16_9))
            }
        }
    }
}

/**
 * Off by default (see "Diagnostic Persistence" in app-spec.md). Logcat output for capture
 * diagnostics is unaffected by this toggle - it only controls the additional
 * `capture_diagnostics.log` file used for post-analysis.
 */
@Composable
private fun DiagnosticsFileLoggingSetting(enabled: Boolean, onEnabledChanged: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_diagnostics_file_logging_label))
        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChanged,
            modifier = Modifier.testTag("diagnostics_file_logging_switch"),
        )
    }
}
