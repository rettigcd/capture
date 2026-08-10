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
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Writes a captured photo as a genuine `.kenc` file (see [PhotoEncryptor.encryptToKencFile]) into
 * the folder the user picked via Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE`, launched
 * from `SettingsRoute` - see "Encrypt saved photos" in app-spec.md). A plain user-visible folder,
 * not `MediaStore` or app-private storage, is what lets a separate encrypted-image-viewer app open
 * these files directly.
 *
 * The metadata block is deliberately minimal ([METADATA_CAPTURED_AT_MILLIS_KEY] and
 * [METADATA_LOGICAL_FILENAME_KEY], the latter set to what [MediaStorePhotoStorage] would have
 * named the file had it been saved unencrypted) rather than attempting to match keibler's own
 * `EncryptedMetadataDto` schema - that's specific to Keibler's own file-organization model
 * (tags/score/etc.), not part of this app. A `.kenc` reader that doesn't recognize this JSON shape
 * still decrypts the image itself just fine; only the metadata is affected (keibler's own
 * `EncryptedMapper.refreshMetadataFromFile` already tolerates any metadata-parse failure by
 * falling back to empty metadata, not a crash).
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
            val logicalFilename = "IMG_${FILENAME_FORMATTER.format(instant)}.jpg"
            val metadataJson = JSONObject()
                .put(METADATA_CAPTURED_AT_MILLIS_KEY, timestampMillis)
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
        const val METADATA_CAPTURED_AT_MILLIS_KEY = "capturedAtMillis"
        const val METADATA_LOGICAL_FILENAME_KEY = "logicalFilename"
        val FILENAME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmssSSS", Locale.US)
    }
}
