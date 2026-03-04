package io.stamethyst.ui.settings

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri

data class JvmLogsSharePayload(
    val uri: Uri,
    val fileName: String
)

internal object JvmLogShareService {
    fun prepareSharePayload(host: Activity): JvmLogsSharePayload {
        val fileName = SettingsFileService.buildJvmLogExportFileName()
        val shareUri = SettingsFileService.resolveJvmLogsShareUri(host)
        return JvmLogsSharePayload(
            uri = shareUri,
            fileName = fileName
        )
    }

    fun buildShareIntent(payload: JvmLogsSharePayload): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, payload.uri)
            clipData = ClipData.newRawUri(payload.fileName, payload.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
