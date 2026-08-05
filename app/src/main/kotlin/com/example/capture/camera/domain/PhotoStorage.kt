package com.example.capture.camera.domain

/**
 * Abstraction over saving photographs through `MediaStore`.
 *
 * Callers are expected to either:
 *  1. [createPendingEntry] to reserve a row (and get somewhere to write pixel data),
 *  2. hand the entry to a [CameraCaptureController] to write the actual image, then either
 *  3. [finalizeEntry] on success, or
 *  4. [discardEntry] on failure, so a failed capture never leaves a broken/partial entry
 *     visible in the user's gallery,
 *
 * or, when the image was captured into memory instead (see [CameraCaptureMemoryOutcome], used by
 * Burst Mode), [createPendingEntry] then [writeBytes] in place of step 2 before finalizing/discarding.
 */
interface PhotoStorage {
    suspend fun createPendingEntry(timestampMillis: Long): PendingPhotoEntry
    suspend fun writeBytes(entry: PendingPhotoEntry, bytes: ByteArray)
    suspend fun finalizeEntry(entry: PendingPhotoEntry): String
    suspend fun discardEntry(entry: PendingPhotoEntry)
}
