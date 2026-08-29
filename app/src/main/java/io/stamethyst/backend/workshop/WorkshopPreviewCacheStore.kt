package io.stamethyst.backend.workshop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import io.stamethyst.backend.steamcloud.SteamCloudAcceleratedHttp
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Rewrites a workshop preview URL into a CDN-resized variant for small covers.
 *
 * Steam's UGC image hosts resize server-side through the imw/ima/impolicy query params — the
 * browse pages themselves ship card URLs like `?imw=512&ima=fit&impolicy=Letterbox`. Requesting
 * that variant for list covers fetches tens of KB instead of a full-size screenshot. Unknown
 * hosts pass through untouched so legacy image CDNs keep working; a failed variant request also
 * degrades to nothing worse than the previous behavior because callers treat null as "show skeleton".
 */
internal fun workshopCoverVariantUrl(previewUrl: String, widthPx: Int): String {
    if (widthPx <= 0) return previewUrl
    val url = previewUrl.toHttpUrlOrNull() ?: return previewUrl
    val host = url.host.lowercase()
    val isResizableHost = host == STEAM_USER_IMAGES_HOST || host.endsWith(".steamusercontent.com")
    if (!isResizableHost) return previewUrl
    return url.newBuilder()
        .removeAllQueryParameters("imw")
        .removeAllQueryParameters("imh")
        .removeAllQueryParameters("ima")
        .setQueryParameter("imw", widthPx.toString())
        .setQueryParameter("ima", "fit")
        .setQueryParameter("impolicy", "Letterbox")
        .setQueryParameter("letterbox", "false")
        .build()
        .toString()
}

private const val STEAM_USER_IMAGES_HOST = "steamuserimages-a.akamaihd.net"

internal object WorkshopPreviewCacheStore {
    private const val DIRECTORY_NAME = "workshop-preview-cache"
    private const val TARGET_SIZE_PX = 320

    /** Matches the width Steam's own browse cards request; decode target stays [TARGET_SIZE_PX]. */
    private const val COVER_VARIANT_WIDTH_PX = 512
    private const val CACHE_SIZE_BYTES = 24 * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 8_000L
    private const val READ_TIMEOUT_MS = 20_000L

    private val memoryCache = object : LruCache<String, Bitmap>(CACHE_SIZE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    /**
     * Preview images live on Steam CDN hosts, so they must use the shared
     * accelerated client. A bare [OkHttpClient] here also meant no timeouts,
     * letting a stalled CDN hang the image load indefinitely.
     *
     * One client per acceleration state instead of one per download, so the
     * keep-alive connection pool is reused across the preview grid instead of
     * paying a fresh TCP+TLS handshake for every card. The [enabledProvider]
     * keeps per-request acceleration decisions live; the boolean key only
     * selects the builder shape (interceptors installed or not).
     */
    private val sharedClients = ConcurrentHashMap<Boolean, OkHttpClient>()

    private fun sharedClient(context: Context): OkHttpClient {
        val appContext = context.applicationContext
        val enabled = SteamCloudAcceleratedHttp.isEnabled(appContext)
        return sharedClients.getOrPut(enabled) {
            SteamCloudAcceleratedHttp.createClient(
                context = appContext,
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS,
                callTimeoutMs = CONNECT_TIMEOUT_MS + READ_TIMEOUT_MS,
                enabled = enabled,
                enabledProvider = { SteamCloudAcceleratedHttp.isEnabled(appContext) },
            )
        }
    }

    fun load(context: Context, publishedFileId: ULong, previewUrl: String): Bitmap? {
        val cacheKey = publishedFileId.toString()
        memoryCache.get(cacheKey)?.let { return it }
        decodeCached(context, publishedFileId)?.let { bitmap ->
            memoryCache.put(cacheKey, bitmap)
            return bitmap
        }
        if (previewUrl.isBlank()) return null
        return download(context, publishedFileId, previewUrl)?.also { bitmap ->
            memoryCache.put(cacheKey, bitmap)
        }
    }

    fun decodeCached(context: Context, publishedFileId: ULong): Bitmap? {
        val cacheKey = publishedFileId.toString()
        memoryCache.get(cacheKey)?.let { return it }
        val file = findCacheFile(context, publishedFileId) ?: return null
        return decodeFile(file)?.also { memoryCache.put(cacheKey, it) }
    }

    fun clear(context: Context): Int {
        memoryCache.evictAll()
        val directory = cacheDirectory(context)
        if (!directory.exists()) return 0
        val deletedCount = directory.walkBottomUp()
            .filter { it.isFile }
            .count { it.delete() }
        directory.listFiles()?.filter { it.isDirectory }?.forEach { it.delete() }
        return deletedCount
    }

    private fun download(context: Context, publishedFileId: ULong, previewUrl: String): Bitmap? {
        return runCatching {
            val directory = cacheDirectory(context).apply { mkdirs() }
            val outputFile = File(directory, "${publishedFileId}.${sanitizePreviewExtension(previewUrl)}")
            val tempFile = File(directory, "${publishedFileId}.tmp")
            val requestUrl = workshopCoverVariantUrl(previewUrl, COVER_VARIANT_WIDTH_PX)
            sharedClient(context).newCall(Request.Builder().url(requestUrl).build()).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                response.body.byteStream().use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            findCacheFile(context, publishedFileId)?.takeIf { it != outputFile }?.delete()
            if (outputFile.exists() && !outputFile.delete()) return@runCatching null
            if (!tempFile.renameTo(outputFile)) return@runCatching null
            decodeFile(outputFile)
        }.getOrNull()
    }

    private fun decodeFile(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(
                bounds.outWidth,
                bounds.outHeight,
                TARGET_SIZE_PX,
                TARGET_SIZE_PX,
            )
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun findCacheFile(context: Context, publishedFileId: ULong): File? {
        return cacheDirectory(context)
            .listFiles { file ->
                file.isFile &&
                    file.name.startsWith("${publishedFileId}.") &&
                    !file.name.endsWith(".tmp")
            }
            ?.firstOrNull()
    }

    private fun cacheDirectory(context: Context): File = File(context.filesDir, DIRECTORY_NAME)

    private fun calculateInSampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sampleSize = 1
        val halfWidth = width / 2
        val halfHeight = height / 2
        while (halfWidth / sampleSize >= targetWidth && halfHeight / sampleSize >= targetHeight) {
            sampleSize *= 2
        }
        return sampleSize.coerceAtLeast(1)
    }

    private fun sanitizePreviewExtension(previewUrl: String): String {
        val path = runCatching { Request.Builder().url(previewUrl).build().url.encodedPath }
            .getOrDefault("")
        return path.substringAfterLast('.', missingDelimiterValue = "jpg")
            .substringBefore('?')
            .lowercase()
            .filter { it in 'a'..'z' || it in '0'..'9' }
            .take(5)
            .ifBlank { "jpg" }
    }
}
