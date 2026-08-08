package com.example.capture.camera.data

import android.content.Context
import android.util.Log
import com.example.capture.BuildConfig
import com.example.capture.camera.domain.CameraDiagnosticsSnapshot
import com.example.capture.camera.domain.CaptureDiagnosticEvent
import com.example.capture.camera.domain.CaptureDiagnosticsLogger
import com.example.capture.camera.domain.GestureDiagnosticEvent
import com.example.capture.camera.domain.GestureDiagnosticsLogger
import com.example.capture.common.ApplicationScope
import com.example.capture.common.DispatcherProvider
import com.example.capture.settings.domain.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Always logs to Logcat (see "Diagnostic Persistence" in app-spec.md: "viewable through Logcat"),
 * and additionally appends JSON Lines to an app-private file when
 * [SettingsRepository]'s `diagnosticsFileLoggingEnabled` is on (off by default, per the same
 * section - "optional... disabled by default").
 *
 * [GestureDiagnosticsLogger.log] is a no-op unless this is a debug build (see "Gesture
 * Diagnostics" in app-spec.md: "shall be disabled in Release builds"); [CaptureDiagnosticsLogger]
 * has no such restriction - capture-processing events are "normal operational... logging", which
 * the same section says "may remain enabled" in Release.
 *
 * Neither interface is `suspend` (touch/capture events fire too often to await I/O on the caller's
 * thread), so file writes are fire-and-forget on [applicationScope] instead, and the
 * file-logging-enabled flag is mirrored into a plain `@Volatile` field rather than read from the
 * (suspend) [SettingsRepository] on every call.
 */
@Singleton
class AndroidDiagnosticsLogger @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
    settingsRepository: SettingsRepository,
    @ApplicationScope applicationScope: CoroutineScope,
) : GestureDiagnosticsLogger, CaptureDiagnosticsLogger {

    @Volatile
    private var fileLoggingEnabled = false

    init {
        settingsRepository.settings
            .map { it.diagnosticsFileLoggingEnabled }
            .distinctUntilChanged()
            .onEach { fileLoggingEnabled = it }
            .launchIn(applicationScope)
    }

    private val loggingScope = applicationScope

    override fun log(event: GestureDiagnosticEvent) {
        if (!BuildConfig.DEBUG) return
        Log.d(GESTURE_TAG, event.toString())
        if (fileLoggingEnabled) appendToFile(GESTURE_LOG_FILE_NAME, event.toJsonLine())
    }

    override fun logEvent(event: CaptureDiagnosticEvent) {
        Log.i(CAPTURE_TAG, event.toString())
        if (fileLoggingEnabled) appendToFile(CAPTURE_LOG_FILE_NAME, event.toJsonLine())
    }

    override fun logCameraState(snapshot: CameraDiagnosticsSnapshot) {
        Log.i(CAPTURE_TAG, snapshot.toString())
        if (fileLoggingEnabled) appendToFile(CAPTURE_LOG_FILE_NAME, snapshot.toJsonLine())
    }

    private fun appendToFile(fileName: String, jsonLine: String) {
        loggingScope.launch {
            withContext(dispatcherProvider.io) {
                try {
                    File(context.filesDir, fileName).appendText("$jsonLine\n")
                } catch (_: IOException) {
                    // Best-effort: see class kdoc on FileCaptureErrorLogger for the same reasoning.
                }
            }
        }
    }

    private fun GestureDiagnosticEvent.toJsonLine(): String = JSONObject().apply {
        put("timestampMillis", timestampMillis)
        put("type", this@toJsonLine::class.simpleName)
        when (val event = this@toJsonLine) {
            is GestureDiagnosticEvent.Detected -> {
                put("downX", event.downX)
                put("downY", event.downY)
            }
            is GestureDiagnosticEvent.Classified -> {
                put("downX", event.downX)
                put("downY", event.downY)
                put("upX", event.upX)
                put("upY", event.upY)
                put("durationMillis", event.durationMillis)
                put("totalDeltaX", event.totalDeltaX)
                put("totalDeltaY", event.totalDeltaY)
                put("totalDistance", event.totalDistance)
                put("touchSlopPx", event.touchSlopPx)
                put("swipeThresholdPx", event.swipeThresholdPx)
                put("classification", event.classification.name)
            }
            is GestureDiagnosticEvent.Accepted -> put("classification", event.classification.name)
            is GestureDiagnosticEvent.Cancelled -> {
                put("reason", event.reason.name)
                put("pointerEventConsumed", event.pointerEventConsumed)
                put("cancelledBeforeCompletion", event.cancelledBeforeCompletion)
                put("uiComponent", event.uiComponent)
                put("overlayVisible", event.overlayVisible)
                put("touchCaptureEnabled", event.touchCaptureEnabled)
                put("cameraAcceptingCaptureRequests", event.cameraAcceptingCaptureRequests)
            }
        }
    }.toString()

    private fun CaptureDiagnosticEvent.toJsonLine(): String = JSONObject().apply {
        put("timestampMillis", timestampMillis)
        put("captureAttemptId", attemptId.value)
        put("type", this@toJsonLine::class.simpleName)
        when (val event = this@toJsonLine) {
            is CaptureDiagnosticEvent.Requested -> {
                put("triggerSource", event.triggerSource.name)
                put("captureMode", event.captureMode.name)
                put("captureAspectRatio", event.captureAspectRatio.name)
            }
            is CaptureDiagnosticEvent.Accepted -> {}
            is CaptureDiagnosticEvent.Rejected -> put("reason", event.reason.name)
            is CaptureDiagnosticEvent.CameraXRequestSubmitted ->
                put("burstImageNumber", event.burstImageNumber ?: JSONObject.NULL)
            is CaptureDiagnosticEvent.CameraXCaptureStarted -> {}
            is CaptureDiagnosticEvent.ImageSaved -> put("outputDestination", event.outputDestination)
            is CaptureDiagnosticEvent.Completed -> put("success", event.success)
            is CaptureDiagnosticEvent.CameraXError -> {
                put("errorMessage", event.errorMessage)
                put("reason", event.reason.name)
            }
            is CaptureDiagnosticEvent.VideoStopRequested -> {}
        }
    }.toString()

    private fun CameraDiagnosticsSnapshot.toJsonLine(): String = JSONObject().apply {
        put("timestampMillis", timestampMillis)
        put("type", "CameraDiagnosticsSnapshot")
        put("selectedCamera", selectedCamera)
        put("previewResolutionPx", previewResolutionPx ?: JSONObject.NULL)
        put("captureResolutionPx", captureResolutionPx ?: JSONObject.NULL)
        put("requestedAspectRatio", requestedAspectRatio.name)
        put("displayRotation", displayRotation)
        put("captureRotation", captureRotation)
        put("captureMode", captureMode.name)
    }.toString()

    private companion object {
        const val GESTURE_TAG = "GestureDiagnostics"
        const val CAPTURE_TAG = "CaptureDiagnostics"
        const val GESTURE_LOG_FILE_NAME = "gesture_diagnostics.log"
        const val CAPTURE_LOG_FILE_NAME = "capture_diagnostics.log"
    }
}
