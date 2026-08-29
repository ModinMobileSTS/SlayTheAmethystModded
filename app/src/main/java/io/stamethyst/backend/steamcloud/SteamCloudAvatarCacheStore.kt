package io.stamethyst.backend.steamcloud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.security.MessageDigest
import okhttp3.Request

internal object SteamCloudAvatarCacheStore {
    private const val DIRECTORY_NAME = "steam-cloud-avatar-cache"
    private const val CACHE_SIZE_BYTES = 2 * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 8_000L
    private const val READ_TIMEOUT_MS = 15_000L
    private const val CALL_TIMEOUT_MS = 20_000L

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

    fun clear(context: Context): Int {
        memoryCache.evictAll()
        val directory = cacheDirectory(context)
        if (!directory.exists()) {
            return 0
        }
        val deletedCount = directory.walkBottomUp()
            .filter { it.isFile }
            .count { it.delete() }
        directory.listFiles()?.filter { it.isDirectory }?.forEach { it.delete() }
        return deletedCount
    }

    private fun decodeCached(context: Context, cacheKey: String): Bitmap? {
        val file = cacheFile(context, cacheKey)
        if (!file.isFile) {
            return null
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        if (bitmap == null) {
            // A file that no longer decodes is corrupt (truncated download or an
            // error page saved as an image); drop it so the next load re-downloads
            // instead of failing forever.
            file.delete()
        }
        return bitmap
    }

    private fun download(context: Context, avatarUrl: String, cacheKey: String): Bitmap? {
        return runCatching {
            val directory = cacheDirectory(context).apply { mkdirs() }
            val outputFile = cacheFile(context, cacheKey)
            val tempFile = File(directory, "$cacheKey.tmp")
            val client = SteamCloudAcceleratedHttp.createClient(
                context = context,
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS,
                callTimeoutMs = CALL_TIMEOUT_MS,
            )
            val request = Request.Builder()
                .url(avatarUrl)
                .header("User-Agent", "SlayTheAmethyst/${context.packageName}")
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
            val bitmap = BitmapFactory.decodeFile(outputFile.absolutePath)
            if (bitmap == null) {
                // Never keep a file we cannot render; otherwise every later load
                // retries the network against the same poisoned cache entry.
                outputFile.delete()
            }
            bitmap
        }.getOrNull()
    }

    private fun cacheFile(context: Context, cacheKey: String): File =
        File(cacheDirectory(context), "$cacheKey.img")

    private fun cacheDirectory(context: Context): File =
        File(context.applicationContext.filesDir, DIRECTORY_NAME)

    /**
     * Keys the cache by the URL path instead of the full URL: Steam serves the
     * same avatar image from rotating CDN hosts (akamai/cloudflare/steamstatic),
     * so host changes must not produce a second download for identical content.
     * The path itself carries the unique avatar hash (…/&lt;hash&gt;_full.jpg).
     */
    private fun cacheKey(avatarUrl: String): String {
        val path = runCatching { java.net.URI(avatarUrl).path }.getOrNull().orEmpty()
        val identity = path.ifBlank { avatarUrl }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
