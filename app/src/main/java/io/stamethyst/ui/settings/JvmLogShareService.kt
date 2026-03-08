package io.stamethyst.ui.settings

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import io.stamethyst.backend.diag.CrashArchiveContext
import io.stamethyst.backend.diag.DiagnosticsProcessClient

data class JvmLogsSharePayload(
    val uri: Uri,
    val fileName: String
)

internal object JvmLogShareService {
    fun prepareSharePayload(host: Activity): JvmLogsSharePayload {
        val archiveResult = DiagnosticsProcessClient.buildJvmLogShareArchive(host)
        val fileName = archiveResult.archiveFile.name
        val shareUri = FileProvider.getUriForFile(
            host,
            "${host.packageName}.fileprovider",
            archiveResult.archiveFile
        )
        return JvmLogsSharePayload(
            uri = shareUri,
            fileName = fileName
        )
    }

    fun prepareCrashSharePayload(
        host: Activity,
        code: Int,
        isSignal: Boolean,
        detail: String?
    ): JvmLogsSharePayload {
        val archiveResult = DiagnosticsProcessClient.buildCrashShareArchive(
            host,
            CrashArchiveContext(
                code = code,
                isSignal = isSignal,
                detail = detail
            )
        )
        val fileName = archiveResult.archiveFile.name
        val shareUri = FileProvider.getUriForFile(
            host,
            "${host.packageName}.fileprovider",
            archiveResult.archiveFile
        )
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
