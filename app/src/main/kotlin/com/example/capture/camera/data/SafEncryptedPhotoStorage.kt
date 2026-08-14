package com.example.capture.camera.data

import android.content.Context
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.example.capture.camera.domain.EncryptedPhotoStorage
import com.example.capture.common.DispatcherProvider
import com.example.capture.security.domain.PhotoEncryptor
import com.example.capture.settings.domain.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/*
   Writes a captured photo as a genuine `.kenc` file (see [PhotoEncryptor.encryptToKencFile]) into
   the folder the user picked via Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE`, launched
   from `SettingsRoute` - see "Encrypt saved photos" in app-spec.md). A plain user-visible folder,
   not `MediaStore` or app-private storage, is what lets a separate encrypted-image-viewer app open
   these files directly.

   The metadata block matches keibler's own `EncryptedMetadataDto` shape - [METADATA_PHOTO_DATE_KEY]
   (local date-time, no offset, truncated to seconds) plus a [METADATA_TAGS_KEY] map with a single
   `Capture` -> `["+"]` tag - so keibler's own viewer picks these captures up as tagged photos
   rather than falling back to empty metadata. [METADATA_LOGICAL_FILENAME_KEY] (set to what
   [MediaStorePhotoStorage] would have named the file had it been saved unencrypted) rides alongside
   as an extra field outside keibler's DTO; keibler's own `EncryptedMapper.refreshMetadataFromFile`
   already tolerates unrecognized metadata fields.
 */
class SafEncryptedPhotoStorage @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
    private val photoEncryptor: PhotoEncryptor,
    private val settingsRepository: SettingsRepository,
) : EncryptedPhotoStorage {

    override suspend fun writeEncryptedPhoto(jpegBytes: ByteArray, timestampMillis: Long): String =
        withContext(dispatcherProvider.io) {
            val folderUriString = settingsRepository.settings.first().encryptedPhotosFolderUriString
                ?: throw IOException("No folder has been selected for encrypted photos.")

            val instant = Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault())
            val photoDate = PHOTO_DATE_FORMATTER.format(instant.truncatedTo(ChronoUnit.SECONDS))
            val logicalFilename = "IMG_${FILENAME_FORMATTER.format(instant)}.jpg"
            val metadataJson = JSONObject()
                .put(METADATA_PHOTO_DATE_KEY, photoDate)
                .put(METADATA_TAGS_KEY, JSONObject().put(CAPTURE_TAG_KEY, JSONArray().put(CAPTURE_TAG_VALUE)))
                .put(METADATA_LOGICAL_FILENAME_KEY, logicalFilename)
                .toString()
            val kencBytes = photoEncryptor.encryptToKencFile(jpegBytes, metadataJson)

            val treeUri = folderUriString.toUri()
            val parentDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            val fileName = "${UUID.randomUUID()}.kenc"
            val fileUri = DocumentsContract.createDocument(context.contentResolver, parentDocumentUri, MIME_TYPE, fileName)
                ?: throw IOException("Could not create a file in the selected folder.")

            val stream = context.contentResolver.openOutputStream(fileUri)
                ?: throw IOException("Could not open the new file for writing.")
            stream.use { it.write(kencBytes) }

            fileUri.toString()
        }

    private companion object {
        const val MIME_TYPE = "application/octet-stream"
        const val METADATA_PHOTO_DATE_KEY = "photoDate"
        const val METADATA_TAGS_KEY = "tags"
        const val METADATA_LOGICAL_FILENAME_KEY = "logicalFilename"
        const val CAPTURE_TAG_KEY = "Capture"
        const val CAPTURE_TAG_VALUE = "+"
        val PHOTO_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
        val FILENAME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmssSSS", Locale.US)
    }
}
