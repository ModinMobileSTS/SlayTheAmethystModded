package io.stamethyst.backend.workshop

import android.content.Context
import io.stamethyst.config.RuntimePaths
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.Request

internal class WorkshopPreviewImageStore(
    context: Context,
    private val client: OkHttpClient,
) {
    private val context = context.applicationContext

    fun download(appId: UInt, publishedFileId: ULong, previewUrl: String): String {
        if (previewUrl.isBlank()) return ""
        return runCatching {
            val relativePath = "preview.${sanitizePreviewExtension(previewUrl)}"
            val directory = RuntimePaths.workshopItemDir(context, appId, publishedFileId)
            val outputFile = File(directory, relativePath)
            val tempFile = File(directory, "$relativePath.tmp")
            directory.mkdirs()
            client.newCall(Request.Builder().url(previewUrl).build()).execute().use { response ->
                if (!response.isSuccessful) return@runCatching ""
                response.body.byteStream().use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            if (outputFile.exists() && !outputFile.delete()) return@runCatching ""
            if (!tempFile.renameTo(outputFile)) return@runCatching ""
            relativePath
        }.getOrDefault("")
    }

    private fun sanitizePreviewExtension(previewUrl: String): String {
        val path = runCatching { Request.Builder().url(previewUrl).build().url.encodedPath }.getOrDefault("")
        val extension = path.substringAfterLast('.', missingDelimiterValue = "jpg")
            .substringBefore('?')
            .lowercase()
            .filter { it in 'a'..'z' || it in '0'..'9' }
            .take(5)
            .ifBlank { "jpg" }
        return extension
    }
}
