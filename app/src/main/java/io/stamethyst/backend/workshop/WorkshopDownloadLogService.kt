package io.stamethyst.backend.workshop

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.stamethyst.backend.file_interactive.FileShareCompat
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.nio.charset.StandardCharsets

object WorkshopDownloadLogService {
    fun buildLogText(task: WorkshopDownloadTaskRecord): String = buildString {
        if (task.downloadLog.isNotBlank()) {
            appendLine(task.downloadLog)
        } else {
            appendLine("创意工坊下载日志")
            appendLine("暂无详细下载日志。")
        }
        if (task.errorClass.isNotBlank() || task.errorMessage.isNotBlank() || task.errorStackTrace.isNotBlank()) {
            appendLine()
            appendLine("错误信息")
            task.errorClass.takeIf(String::isNotBlank)?.let { appendLine("Class: $it") }
            task.errorMessage.takeIf(String::isNotBlank)?.let { appendLine("Message: $it") }
            task.errorStackTrace.takeIf(String::isNotBlank)?.let { appendLine(it) }
        }
    }.trimEnd() + "\n"

    fun exportLog(context: Context, task: WorkshopDownloadTaskRecord, destination: Uri) {
        context.contentResolver.openOutputStream(destination)?.use { output ->
            output.write(buildLogText(task).toByteArray(StandardCharsets.UTF_8))
        } ?: error("无法打开导出位置")
    }

    fun prepareShareIntent(host: Activity, task: WorkshopDownloadTaskRecord): Intent {
        val file = File(RuntimePaths.transientFilesRoot(host), "share/${fileName(task)}")
        file.parentFile?.mkdirs()
        file.writeText(buildLogText(task), StandardCharsets.UTF_8)
        val uri = FileShareCompat.resolveShareUri(host, file)
        return FileShareCompat.buildShareIntent(
            host = host,
            uri = uri,
            fileName = file.name,
            mimeType = "text/plain",
        )
    }

    fun fileName(task: WorkshopDownloadTaskRecord): String {
        val safeTitle = task.title
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
            .take(48)
            .ifBlank { "workshop" }
        return "workshop-download-${task.publishedFileId}-$safeTitle.log.txt"
    }
}
