package com.example.capture.camera.domain

/**
 * Structured record of one capture or file-saving failure, meant for a log file rather than the
 * screen (see "Error Handling" in app-spec.md - these errors must not be shown on the main camera
 * screen). Fields that only make sense for a burst image are nullable so a Single-Shot Mode
 * failure simply omits them.
 */
data class CaptureErrorLogEntry(
    val timestampMillis: Long,
    val captureAttemptId: String,
    val captureMode: CaptureMode,
    val burstImageNumber: Int?,
    val burstIntervalMillis: Long?,
    val outputDestination: String?,
    val errorType: String,
    val errorMessage: String,
    val exceptionDetails: String?,
)

/**
 * Abstraction over where [CaptureErrorLogEntry] records go, so [CaptureCoordinator] can log every
 * capture/file-saving failure without depending on Android file APIs directly, and so tests can
 * assert on logged entries without touching disk.
 */
interface CaptureErrorLogger {
    suspend fun log(entry: CaptureErrorLogEntry)
}
