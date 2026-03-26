package io.stamethyst.backend.update

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

object LauncherUpdateService {
    private const val LATEST_RELEASE_API_URL =
        "https://api.github.com/repos/ModinMobileSTS/SlayTheAmethystModded/releases/latest"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 12_000
    private const val USER_AGENT = "SlayTheAmethyst-LauncherUpdate"

    fun checkForUpdates(
        currentVersion: String,
        preferredUserSource: UpdateSource,
    ): UpdateCheckExecutionResult {
        val metadataSource = UpdateSource.normalizePreferredUserSource(preferredUserSource.id)
        val release = try {
            val responseText = requestText(metadataSource.buildUrl(LATEST_RELEASE_API_URL))
            parseLatestRelease(responseText)
                ?: throw IOException("Invalid release payload.")
        } catch (error: Throwable) {
            return UpdateCheckExecutionResult.Failure(
                errorSummary = "${metadataSource.displayName}: ${summarizeError(error)}",
                metadataSource = metadataSource
            )
        }

        val hasUpdate = LauncherUpdateVersioning.isRemoteNewer(
            currentVersion = currentVersion,
            remoteVersionTag = release.normalizedVersion
        )
        if (!hasUpdate) {
            return UpdateCheckExecutionResult.Success(
                currentVersion = currentVersion,
                release = release,
                metadataSource = metadataSource,
                downloadResolution = null,
                hasUpdate = false
            )
        }

        val downloadResolution = resolveDownloadResolution(
            release = release,
            source = metadataSource
        )
        if (downloadResolution == null) {
            return UpdateCheckExecutionResult.Failure(
                errorSummary = "${metadataSource.displayName}: Unable to resolve a reachable APK download link.",
                release = release,
                metadataSource = metadataSource
            )
        }

        return UpdateCheckExecutionResult.Success(
            currentVersion = currentVersion,
            release = release,
            metadataSource = metadataSource,
            downloadResolution = downloadResolution,
            hasUpdate = true
        )
    }

    internal fun parseLatestRelease(responseText: String): UpdateReleaseInfo? {
        val root = parseJsonObject(responseText) ?: return null
        val rawTagName = root.optString("tag_name").trim()
        if (rawTagName.isEmpty()) {
            return null
        }
        val assets = root.optJSONArray("assets") ?: JSONArray()
        val asset = findFirstApkAsset(assets) ?: return null
        val assetName = asset.optString("name").trim()
        val assetDownloadUrl = asset.optString("browser_download_url").trim()
        if (assetName.isEmpty() || assetDownloadUrl.isEmpty()) {
            return null
        }
        return UpdateReleaseInfo(
            rawTagName = rawTagName,
            normalizedVersion = LauncherUpdateVersioning.normalizeVersionTag(rawTagName),
            publishedAtRaw = root.optString("published_at").trim().ifEmpty { null },
            publishedAtDisplayText = LauncherUpdateVersioning.formatPublishedAt(
                root.optString("published_at").trim().ifEmpty { null }
            ),
            notesText = LauncherUpdateVersioning.normalizeReleaseNotesText(root.optString("body")),
            assetName = assetName,
            assetDownloadUrl = assetDownloadUrl
        )
    }

    internal fun resolveDownloadResolution(
        release: UpdateReleaseInfo,
        source: UpdateSource,
    ): UpdateDownloadResolution? {
        val candidateUrl = source.buildUrl(release.assetDownloadUrl)
        if (isDownloadCandidateReachable(candidateUrl)) {
            return UpdateDownloadResolution(
                source = source,
                resolvedUrl = candidateUrl
            )
        }
        return null
    }

    private fun requestText(requestUrl: String): String {
        val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("HTTP $responseCode")
            }
            BufferedInputStream(connection.inputStream).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    output.write(buffer, 0, read)
                }
                return output.toString(StandardCharsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun isDownloadCandidateReachable(requestUrl: String): Boolean {
        return requestProbe(requestUrl, "HEAD") || requestRangeProbe(requestUrl)
    }

    private fun requestProbe(requestUrl: String, method: String): Boolean {
        val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            val responseCode = connection.responseCode
            responseCode in 200..299
        } catch (_: Throwable) {
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun requestRangeProbe(requestUrl: String): Boolean {
        val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Range", "bytes=0-0")
        }
        return try {
            val responseCode = connection.responseCode
            responseCode in 200..299 || responseCode == HttpURLConnection.HTTP_PARTIAL
        } catch (_: Throwable) {
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun parseJsonObject(responseText: String): JSONObject? {
        return runCatching {
            val parsed = JSONTokener(responseText).nextValue()
            parsed as? JSONObject
        }.getOrNull()
    }

    private fun findFirstApkAsset(assets: JSONArray): JSONObject? {
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name").trim()
            if (name.endsWith(".apk", ignoreCase = true)) {
                return asset
            }
        }
        return null
    }

    private fun summarizeError(error: Throwable): String {
        val message = error.message?.trim().orEmpty()
        return if (message.isNotEmpty()) {
            message
        } else {
            error.javaClass.simpleName
        }
    }
}
