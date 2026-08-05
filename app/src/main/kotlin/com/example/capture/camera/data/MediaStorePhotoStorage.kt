package com.example.capture.camera.data

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.core.net.toUri
import com.example.capture.camera.domain.PendingPhotoEntry
import com.example.capture.camera.domain.PhotoStorage
import com.example.capture.common.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/**
 * Saves photographs through `MediaStore` into `Pictures/Capture`, never touching a raw filesystem
 * path and never requesting a broad storage permission (both are only necessary pre-scoped-storage,
 * and this app's `minSdk` of 29 is scoped-storage-only - see README "Why minSdk 29").
 *
 * Reserves a row with `IS_PENDING = 1` up front so partially-written files never show up as
 * finished photos in the user's gallery while a capture is still in progress; [finalizeEntry]
 * clears that flag on success and [discardEntry] deletes the row entirely on failure, so a failed
 * capture never leaves a broken entry behind.
 */
class MediaStorePhotoStorage @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
) : PhotoStorage {

    override suspend fun createPendingEntry(timestampMillis: Long): PendingPhotoEntry =
        withContext(dispatcherProvider.io) {
            val instant = Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault())
            val displayName = "IMG_${FILENAME_FORMATTER.format(instant)}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_PICTURES}/$SUBFOLDER")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = context.contentResolver.insert(collection, values)
                ?: throw IOException("MediaStore returned no Uri for the new photo entry")
            PendingPhotoEntry(uri.toString())
        }

    override suspend fun writeBytes(entry: PendingPhotoEntry, bytes: ByteArray) {
        withContext(dispatcherProvider.io) {
            val stream = context.contentResolver.openOutputStream(entry.uriString.toUri())
                ?: throw IOException("Could not open storage for the photo.")
            stream.use { it.write(bytes) }
        }
    }

    override suspend fun finalizeEntry(entry: PendingPhotoEntry): String =
        withContext(dispatcherProvider.io) {
            val values = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            context.contentResolver.update(entry.uriString.toUri(), values, null, null)
            entry.uriString
        }

    override suspend fun discardEntry(entry: PendingPhotoEntry) {
        withContext(dispatcherProvider.io) {
            context.contentResolver.delete(entry.uriString.toUri(), null, null)
            Unit
        }
    }

    private companion object {
        const val SUBFOLDER = "Capture"
        val FILENAME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmssSSS", Locale.US)
    }
}
