package com.example.capture.camera.domain

/**
 * Abstraction over saving photographs through `MediaStore`.
 *
 * Callers are expected to:
 *  1. [createPendingEntry] to reserve a row (and get somewhere to write pixel data),
 *  2. hand the entry to a [CameraCaptureController] to write the actual image, then either
 *  3. [finalizeEntry] on success, or
 *  4. [discardEntry] on failure, so a failed capture never leaves a broken/partial entry
 *     visible in the user's gallery.
 */
interface PhotoStorage {
    suspend fun createPendingEntry(timestampMillis: Long): PendingPhotoEntry
    suspend fun finalizeEntry(entry: PendingPhotoEntry): String
    suspend fun discardEntry(entry: PendingPhotoEntry)
}
