package io.stamethyst.ui.settings.files

import io.stamethyst.ui.settings.baidu.*
import io.stamethyst.ui.settings.common.*
import io.stamethyst.ui.settings.core.*
import io.stamethyst.ui.settings.first_run.*
import io.stamethyst.ui.settings.mobileglues.*
import io.stamethyst.ui.settings.native_library.*
import io.stamethyst.ui.settings.sections.*
import io.stamethyst.ui.settings.services.*
import io.stamethyst.ui.settings.steamcloud.*

import android.app.Activity
import android.content.Intent
import android.net.Uri
import io.stamethyst.backend.diag.CrashArchiveContext
import io.stamethyst.backend.diag.DiagnosticsProcessClient
import io.stamethyst.backend.file_interactive.FileShareCompat

data class JvmLogsSharePayload(
    val uri: Uri,
    val fileName: String
)

internal object JvmLogShareService {
    fun prepareSharePayload(host: Activity): JvmLogsSharePayload {
        val archiveResult = DiagnosticsProcessClient.buildJvmLogShareArchive(host)
        val fileName = archiveResult.archiveFile.name
        val shareUri = FileShareCompat.resolveShareUri(host, archiveResult.archiveFile)
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
        val shareUri = FileShareCompat.resolveShareUri(host, archiveResult.archiveFile)
        return JvmLogsSharePayload(
            uri = shareUri,
            fileName = fileName
        )
    }

    fun preparePerformanceSharePayload(host: Activity): JvmLogsSharePayload {
        val archiveResult = DiagnosticsProcessClient.buildPerformanceLogShareArchive(host)
        val fileName = archiveResult.archiveFile.name
        return JvmLogsSharePayload(
            uri = FileShareCompat.resolveShareUri(host, archiveResult.archiveFile),
            fileName = fileName
        )
    }

    fun buildShareIntent(host: Activity, payload: JvmLogsSharePayload): Intent {
        return FileShareCompat.buildShareIntent(
            host = host,
            uri = payload.uri,
            fileName = payload.fileName,
            mimeType = "application/zip"
        )
    }
}

