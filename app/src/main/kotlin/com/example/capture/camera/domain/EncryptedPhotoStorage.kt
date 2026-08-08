package com.example.capture.camera.domain

/**
 * Saves a captured photo as an encrypted `.kenc` file into the user-picked folder (see "Encrypt
 * saved photos" in app-spec.md), instead of through [PhotoStorage]'s `MediaStore` path. Unlike
 * [PhotoStorage], there is no reserve/write/finalize/discard lifecycle - a `.kenc` file is written
 * whole, in one call, since the destination is a plain Storage Access Framework folder rather than
 * a `MediaStore` row that needs to stay hidden from the gallery until it's complete.
 */
interface EncryptedPhotoStorage {
    /** Encrypts [jpegBytes] into a `.kenc` file and writes it into the picked folder. @return the saved file's `content://` Uri string. */
    suspend fun writeEncryptedPhoto(jpegBytes: ByteArray, timestampMillis: Long): String
}
