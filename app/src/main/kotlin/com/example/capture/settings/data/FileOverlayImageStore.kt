package com.example.capture.settings.data

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.example.capture.common.DispatcherProvider
import com.example.capture.settings.domain.OverlayImageStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

/**
 * Copies each picked image into its own app-private file (a random filename per call, since up to
 * [com.example.capture.settings.domain.AppSettings.MAX_COVER_PHOTOS] copies can coexist - unlike
 * the single fixed file this used to overwrite) and hands back a `file://` Uri, which Coil loads
 * directly with no extra permission of any kind - unlike the picker's own `content://` Uri, whose
 * read grant does not reliably survive a process restart (see [OverlayImageStore]'s kdoc).
 */
class FileOverlayImageStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
) : OverlayImageStore {

    override suspend fun persist(sourceUriString: String): String = withContext(dispatcherProvider.io) {
        val destination = File(context.filesDir, "$COVER_PHOTO_FILE_PREFIX${UUID.randomUUID()}")
        val input = context.contentResolver.openInputStream(sourceUriString.toUri())
            ?: throw IOException("Could not open the selected image for reading")
        input.use { stream ->
            destination.outputStream().use { output -> stream.copyTo(output) }
        }
        Uri.fromFile(destination).toString()
    }

    override suspend fun delete(uriString: String) {
        withContext(dispatcherProvider.io) {
            runCatching { uriString.toUri().path?.let { File(it).delete() } }
        }
    }

    private companion object {
        const val COVER_PHOTO_FILE_PREFIX = "cover_photo_"
    }
}
