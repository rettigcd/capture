package com.example.capture.security.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.capture.R
import java.io.File

/**
 * Builds a system share-sheet [Intent] for the encryption key backup file, via [FileProvider]
 * (a plain `file://` Uri can't be shared cross-app on modern Android - see the `<provider>` entry
 * in `AndroidManifest.xml` and `res/xml/file_paths.xml` for the grant this depends on).
 */
fun buildKeyFileShareIntent(context: Context, keyFile: File): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", keyFile)
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "application/octet-stream"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(sendIntent, context.getString(R.string.encryption_key_share_chooser_title))
}
