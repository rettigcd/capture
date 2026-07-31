package com.example.capture.camera.domain

/**
 * Structured record of one successfully saved image's actual dimensions and aspect ratio, logged
 * for diagnostics after every successful capture (see "Captured image metadata and validation" in
 * app-spec.md) rather than shown on screen.
 */
data class CaptureMetadataLogEntry(
    val timestampMillis: Long,
    val captureAttemptId: String,
    val widthPx: Int,
    val heightPx: Int,
    val requestedAspectRatio: CaptureAspectRatio,
    /** `null` if the actual dimensions don't classify as any supported ratio within tolerance. */
    val actualAspectRatio: CaptureAspectRatio?,
    val matchesTolerance: Boolean,
    val outputDestination: String,
    /** An `androidx.exifinterface.media.ExifInterface.ORIENTATION_*` constant, when available. */
    val exifOrientation: Int?,
)

/**
 * Abstraction over where [CaptureMetadataLogEntry] records go, so [CaptureCoordinator] can log
 * every successful capture's metadata without depending on Android file APIs directly, and so
 * tests can assert on logged entries without touching disk.
 */
interface CaptureMetadataLogger {
    suspend fun log(entry: CaptureMetadataLogEntry)
}
