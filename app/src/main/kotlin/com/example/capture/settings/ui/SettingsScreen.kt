package com.example.capture.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.capture.R
import com.example.capture.camera.domain.CaptureMode
import com.example.capture.settings.domain.AppSettings
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
    onPickImageClick: () -> Unit,
    onCaptureModeChanged: (CaptureMode) -> Unit,
    onBurstIntervalChanged: (Long) -> Unit,
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
            VibrationDurationSetting(
                durationMillis = uiState.vibrationDurationMillis,
                onDurationChanged = onVibrationDurationChanged,
            )
            HorizontalDivider()
            OverlayImageSetting(
                overlayImageUriString = uiState.overlayImageUriString,
                onPickImageClick = onPickImageClick,
            )
            HorizontalDivider()
            CaptureModeSetting(
                captureMode = uiState.captureMode,
                onCaptureModeChanged = onCaptureModeChanged,
            )
            HorizontalDivider()
            BurstIntervalSetting(
                intervalMillis = uiState.burstIntervalMillis,
                onIntervalChanged = onBurstIntervalChanged,
            )
        }
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

@Composable
private fun OverlayImageSetting(overlayImageUriString: String?, onPickImageClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_overlay_image_label))
        if (overlayImageUriString != null) {
            AsyncImage(
                model = overlayImageUriString,
                contentDescription = stringResource(R.string.settings_overlay_image_thumbnail_content_description),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
        } else {
            Text(
                text = stringResource(R.string.settings_no_image_selected),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = onPickImageClick) {
            Text(stringResource(R.string.settings_choose_image_button))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaptureModeSetting(captureMode: CaptureMode, onCaptureModeChanged: (CaptureMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_capture_mode_label))
        SingleChoiceSegmentedButtonRow {
            SegmentedButton(
                selected = captureMode == CaptureMode.SINGLE_SHOT,
                onClick = { onCaptureModeChanged(CaptureMode.SINGLE_SHOT) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) {
                Text(stringResource(R.string.settings_capture_mode_single_shot))
            }
            SegmentedButton(
                selected = captureMode == CaptureMode.BURST,
                onClick = { onCaptureModeChanged(CaptureMode.BURST) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) {
                Text(stringResource(R.string.settings_capture_mode_burst))
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
