package io.stamethyst.backend.feedback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import io.stamethyst.backend.github.WattToolkitAcceleratedHttp
import io.stamethyst.backend.github.GithubRequestClients
import io.stamethyst.backend.github.GithubResourceRequests
import io.stamethyst.backend.update.toGithubMirrorHttpException
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.security.MessageDigest
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Request

internal object FeedbackAvatarCacheStore {
    private const val DIRECTORY_NAME = "feedback-avatar-cache"
    private const val CACHE_SIZE_BYTES = 2 * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 15_000
    private val clients = ConcurrentHashMap<String, GithubRequestClients>()

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
            val app = context.applicationContext
            val pair = clients.getOrPut(RuntimePaths.transientFilesRoot(app).absolutePath) {
                WattToolkitAcceleratedHttp.createClientPair(
                    context = app,
                    connectTimeoutMs = CONNECT_TIMEOUT_MS,
                    readTimeoutMs = READ_TIMEOUT_MS,
                    followRedirects = true,
                )
            }
            val bitmap = GithubResourceRequests.run(context, pair, avatarUrl) { client, requestUrl ->
                val request = Request.Builder()
                    .url(requestUrl)
                    .header("User-Agent", "SlayTheAmethyst-FeedbackAvatar")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw response.toGithubMirrorHttpException("Avatar request failed.")
                    response.body.byteStream().use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                BitmapFactory.decodeFile(tempFile.absolutePath)
                    ?: throw IOException("Invalid avatar image response.")
            }
            if (outputFile.exists() && !outputFile.delete()) {
                return@runCatching null
            }
            if (!tempFile.renameTo(outputFile)) {
                return@runCatching null
            }
            bitmap
        }.getOrNull()
    }

    private fun cacheFile(context: Context, cacheKey: String): File =
        File(cacheDirectory(context), "$cacheKey.img")

    private fun cacheDirectory(context: Context): File =
        File(RuntimePaths.transientFilesRoot(context.applicationContext), DIRECTORY_NAME)

    private fun cacheKey(avatarUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(avatarUrl.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
