package com.example.capture.camera.domain

/** Actual dimensions and orientation read back from a saved image file. */
data class ImageMetadata(
    val widthPx: Int,
    val heightPx: Int,
    /** An `androidx.exifinterface.media.ExifInterface.ORIENTATION_*` constant, when available. */
    val exifOrientation: Int?,
)

/**
 * Reads back a saved image's actual dimensions and EXIF orientation, so
 * [CaptureCoordinator] can log what was really saved (see "Captured image metadata and
 * validation" in app-spec.md) without depending on Android's bitmap/EXIF APIs directly.
 */
interface ImageMetadataReader {
    /** Returns `null` if [uriString] can't be read or decoded. */
    suspend fun read(uriString: String): ImageMetadata?
}
