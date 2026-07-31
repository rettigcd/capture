package com.example.capture.camera.data

import android.content.Context
import com.example.capture.camera.domain.CaptureErrorLogEntry
import com.example.capture.camera.domain.CaptureErrorLogger
import com.example.capture.common.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * Appends one JSON object per line (JSON Lines) to a single app-private log file, using
 * `org.json` (bundled with Android, so this needs no extra dependency) rather than hand-rolled
 * string concatenation, which would risk malformed JSON if a message ever contains a quote or
 * newline.
 *
 * A failure to write the log itself must never crash or interrupt capture (see "Error Handling"
 * in app-spec.md, which already tolerates a burst finishing with fewer than four saved images) -
 * so [IOException]s here are swallowed after being written to a fallback destination is not
 * attempted; there is nowhere safer to report a logging failure than the log itself.
 */
class FileCaptureErrorLogger @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
) : CaptureErrorLogger {

    override suspend fun log(entry: CaptureErrorLogEntry) = withContext(dispatcherProvider.io) {
        try {
            File(context.filesDir, LOG_FILE_NAME).appendText(entry.toJsonLine() + "\n")
        } catch (_: IOException) {
            // Best-effort: see class kdoc.
        }
    }

    private fun CaptureErrorLogEntry.toJsonLine(): String = JSONObject().apply {
        put("timestampMillis", timestampMillis)
        put("captureAttemptId", captureAttemptId)
        put("captureMode", captureMode.name)
        put("burstImageNumber", burstImageNumber ?: JSONObject.NULL)
        put("burstIntervalMillis", burstIntervalMillis ?: JSONObject.NULL)
        put("outputDestination", outputDestination ?: JSONObject.NULL)
        put("errorType", errorType)
        put("errorMessage", errorMessage)
        put("exceptionDetails", exceptionDetails ?: JSONObject.NULL)
    }.toString()

    private companion object {
        const val LOG_FILE_NAME = "capture_errors.log"
    }
}
