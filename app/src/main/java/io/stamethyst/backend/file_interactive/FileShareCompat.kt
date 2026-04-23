package io.stamethyst.backend.file_interactive

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

internal object FileShareCompat {
    fun resolveShareUri(host: Activity, file: File): Uri {
        return FileProvider.getUriForFile(
            host,
            "${host.packageName}.fileprovider",
            file
        )
    }

    fun buildShareIntent(
        host: Activity,
        uri: Uri,
        fileName: String,
        mimeType: String,
        subject: String? = fileName
    ): Intent {
        val label = fileName.trim().ifEmpty { "shared-file" }
        return Intent(Intent.ACTION_SEND).apply {
            setDataAndType(uri, mimeType)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(host.contentResolver, label, uri)
            putExtra(Intent.EXTRA_TITLE, label)
            subject
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
