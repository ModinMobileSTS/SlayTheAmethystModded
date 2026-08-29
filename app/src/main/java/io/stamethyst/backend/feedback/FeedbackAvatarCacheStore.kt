package io.stamethyst.backend.feedback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import io.stamethyst.backend.github.WattToolkitAcceleratedHttp
import java.io.File
import java.security.MessageDigest
import okhttp3.Request

internal object FeedbackAvatarCacheStore {
    private const val DIRECTORY_NAME = "feedback-avatar-cache"
    private const val CACHE_SIZE_BYTES = 2 * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 15_000

    private val memoryCache = object : LruCache<String, Bitmap>(CACHE_SIZE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun load(context: Context, avatarUrl: String): Bitmap? {
        val normalizedUrl = avatarUrl.trim()
        if (normalizedUrl.isBlank()) {
            return null
        }
        val cacheKey = cacheKey(normalizedUrl)
        memoryCache.get(cacheKey)?.let { return it }
        decodeCached(context, cacheKey)?.let { bitmap ->
            memoryCache.put(cacheKey, bitmap)
            return bitmap
        }
        return download(context, normalizedUrl, cacheKey)?.also { bitmap ->
            memoryCache.put(cacheKey, bitmap)
        }
    }

    private fun decodeCached(context: Context, cacheKey: String): Bitmap? {
        val file = cacheFile(context, cacheKey)
        if (!file.isFile) {
            return null
        }
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    private fun download(context: Context, avatarUrl: String, cacheKey: String): Bitmap? {
        return runCatching {
            val directory = cacheDirectory(context).apply { mkdirs() }
            val outputFile = cacheFile(context, cacheKey)
            val tempFile = File(directory, "$cacheKey.tmp")
            val client = WattToolkitAcceleratedHttp.createClient(
                context = context,
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS,
                followRedirects = true
            )
            val request = Request.Builder()
                .url(avatarUrl)
                .header("User-Agent", "SlayTheAmethyst-FeedbackAvatar")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@runCatching null
                }
                response.body.byteStream().use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            if (outputFile.exists() && !outputFile.delete()) {
                return@runCatching null
            }
            if (!tempFile.renameTo(outputFile)) {
                return@runCatching null
            }
            BitmapFactory.decodeFile(outputFile.absolutePath)
        }.getOrNull()
    }

    private fun cacheFile(context: Context, cacheKey: String): File =
        File(cacheDirectory(context), "$cacheKey.img")

    private fun cacheDirectory(context: Context): File =
        File(context.applicationContext.filesDir, DIRECTORY_NAME)

    private fun cacheKey(avatarUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(avatarUrl.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
