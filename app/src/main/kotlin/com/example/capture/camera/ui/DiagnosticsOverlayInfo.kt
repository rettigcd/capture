package com.example.capture.camera.ui

import com.example.capture.camera.domain.CaptureAttemptId
import com.example.capture.camera.domain.CaptureTriggerSource
import com.example.capture.camera.domain.GestureClassification

/**
 * Last-known state for the debug-only diagnostics overlay described in "Debug Overlay" in
 * app-spec.md. Populated by [CameraViewModel] from gesture callbacks and
 * [com.example.capture.camera.domain.CaptureCoordinator]'s state, kept separate from
 * [CameraUiState] so this debug-only concern never affects the screen's main render path or its
 * tests.
 */
data class DiagnosticsOverlayInfo(
    val lastTouchLocation: Pair<Float, Float>? = null,
    val lastGestureClassification: GestureClassification? = null,
    val captureState: String = "Idle",
    val cameraBound: Boolean = false,
    val lastCaptureAttemptId: CaptureAttemptId? = null,
    val lastCaptureTriggerSource: CaptureTriggerSource? = null,
)
