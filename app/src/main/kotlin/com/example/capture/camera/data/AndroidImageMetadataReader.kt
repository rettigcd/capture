package com.example.capture.camera.data

import android.content.Context
import android.graphics.BitmapFactory
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import com.example.capture.camera.domain.ImageMetadata
import com.example.capture.camera.domain.ImageMetadataReader
import com.example.capture.common.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Reads the actual saved dimensions back via [BitmapFactory] with `inJustDecodeBounds = true`
 * (decodes only the header, not the full bitmap) and the EXIF orientation via [ExifInterface] -
 * two separate streams, since a stream already consumed by [BitmapFactory] can't be re-read by
 * [ExifInterface].
 */
class AndroidImageMetadataReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
) : ImageMetadataReader {

    override suspend fun read(uriString: String): ImageMetadata? = withContext(dispatcherProvider.io) {
        val uri = uriString.toUri()
        val bounds = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.Options().apply { inJustDecodeBounds = true }
                .also { BitmapFactory.decodeStream(stream, null, it) }
        } ?: return@withContext null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

        val exifOrientation = context.contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        }

        ImageMetadata(
            widthPx = bounds.outWidth,
            heightPx = bounds.outHeight,
            exifOrientation = exifOrientation?.takeIf { it != ExifInterface.ORIENTATION_UNDEFINED },
        )
    }
}
