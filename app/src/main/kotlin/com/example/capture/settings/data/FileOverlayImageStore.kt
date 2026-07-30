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
import javax.inject.Inject

/**
 * Copies the picked image into a single, fixed app-private file (each new selection overwrites
 * the previous one, so nothing accumulates) and hands back a `file://` Uri, which Coil loads
 * directly with no extra permission of any kind - unlike the picker's own `content://` Uri, whose
 * read grant does not reliably survive a process restart (see [OverlayImageStore]'s kdoc).
 */
class FileOverlayImageStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
) : OverlayImageStore {

    override suspend fun persist(sourceUriString: String): String = withContext(dispatcherProvider.io) {
        val destination = File(context.filesDir, OVERLAY_IMAGE_FILE_NAME)
        val input = context.contentResolver.openInputStream(sourceUriString.toUri())
            ?: throw IOException("Could not open the selected image for reading")
        input.use { stream ->
            destination.outputStream().use { output -> stream.copyTo(output) }
        }
        Uri.fromFile(destination).toString()
    }

    private companion object {
        const val OVERLAY_IMAGE_FILE_NAME = "overlay_image"
    }
}
