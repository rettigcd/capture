package com.example.capture.camera.data

import android.content.Context
import com.example.capture.camera.domain.CaptureMetadataLogEntry
import com.example.capture.camera.domain.CaptureMetadataLogger
import com.example.capture.common.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * Appends one JSON object per line (JSON Lines) to a single app-private log file - the same
 * pattern as [FileCaptureErrorLogger], but for successful-capture diagnostics (see "Captured
 * image metadata and validation" in app-spec.md) rather than failures, so the two concerns stay in
 * separate files and separate interfaces.
 */
class FileCaptureMetadataLogger @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
) : CaptureMetadataLogger {

    override suspend fun log(entry: CaptureMetadataLogEntry) = withContext(dispatcherProvider.io) {
        try {
            File(context.filesDir, LOG_FILE_NAME).appendText(entry.toJsonLine() + "\n")
        } catch (_: IOException) {
            // Best-effort: a failure to write this diagnostic log must never crash or interrupt
            // capture - see FileCaptureErrorLogger's kdoc for the same reasoning.
        }
    }

    private fun CaptureMetadataLogEntry.toJsonLine(): String = JSONObject().apply {
        put("timestampMillis", timestampMillis)
        put("captureAttemptId", captureAttemptId)
        put("widthPx", widthPx)
        put("heightPx", heightPx)
        put("requestedAspectRatio", requestedAspectRatio.name)
        put("actualAspectRatio", actualAspectRatio?.name ?: JSONObject.NULL)
        put("matchesTolerance", matchesTolerance)
        put("outputDestination", outputDestination)
        put("exifOrientation", exifOrientation ?: JSONObject.NULL)
    }.toString()

    private companion object {
        const val LOG_FILE_NAME = "capture_metadata.log"
    }
}
