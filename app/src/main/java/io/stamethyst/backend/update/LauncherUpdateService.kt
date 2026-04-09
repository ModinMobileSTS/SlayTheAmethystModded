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
    private const val RELEASE_HISTORY_API_URL =
        "https://api.github.com/repos/ModinMobileSTS/SlayTheAmethystModded/releases"
    private const val DEFAULT_RELEASE_HISTORY_LIMIT = 10
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 12_000
    private const val USER_AGENT = "SlayTheAmethyst-LauncherUpdate"

    fun checkForUpdates(
        currentVersion: String,
        preferredUserSource: UpdateSource,
    ): UpdateCheckExecutionResult {
        val normalizedPreferredSource = UpdateSource.normalizePreferredUserSource(preferredUserSource.id)
        val metadataResult = try {
            GithubMirrorFallback.run(normalizedPreferredSource) { source ->
                val responseText = requestText(source.buildUrl(LATEST_RELEASE_API_URL))
                parseLatestRelease(responseText)
                    ?: throw IOException("Invalid release payload.")
            }
        } catch (error: Throwable) {
            return UpdateCheckExecutionResult.Failure(
                errorSummary = GithubMirrorFallback.summarize(error),
                metadataSource = null
            )
        }
        val metadataSource = metadataResult.source
        val release = metadataResult.value

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

        val downloadResolution = try {
            resolveDownloadResolution(
                release = release,
                preferredUserSource = normalizedPreferredSource,
                metadataSource = metadataSource
            )
        } catch (error: Throwable) {
            return UpdateCheckExecutionResult.Failure(
                errorSummary = "Unable to resolve a reachable APK download link. ${GithubMirrorFallback.summarize(error)}"
                    .trim(),
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

    fun fetchReleaseHistory(
        preferredUserSource: UpdateSource,
        limit: Int = DEFAULT_RELEASE_HISTORY_LIMIT,
    ): UpdateReleaseHistoryResult {
        val normalizedPreferredSource = UpdateSource.normalizePreferredUserSource(preferredUserSource.id)
        val normalizedLimit = limit.coerceIn(1, 20)
        val metadataResult = GithubMirrorFallback.run(normalizedPreferredSource) { source ->
            val requestUrl = source.buildUrl("$RELEASE_HISTORY_API_URL?per_page=$normalizedLimit")
            val responseText = requestText(requestUrl)
            parseReleaseHistory(responseText, normalizedLimit)
                .takeIf { it.isNotEmpty() }
                ?: throw IOException("Release history is empty.")
        }
        return UpdateReleaseHistoryResult(
            metadataSource = metadataResult.source,
            entries = metadataResult.value
        )
    }

    internal fun parseLatestRelease(responseText: String): UpdateReleaseInfo? {
        val root = parseJsonObject(responseText) ?: return null
        val summary = parseReleaseSummary(root) ?: return null
        val assets = root.optJSONArray("assets") ?: JSONArray()
        val asset = findFirstApkAsset(assets) ?: return null
        val assetName = asset.optString("name").trim()
        val assetDownloadUrl = asset.optString("browser_download_url").trim()
        if (assetName.isEmpty() || assetDownloadUrl.isEmpty()) {
            return null
        }
        return UpdateReleaseInfo(
            rawTagName = summary.rawTagName,
            normalizedVersion = summary.normalizedVersion,
            publishedAtRaw = summary.publishedAtRaw,
            publishedAtDisplayText = summary.publishedAtDisplayText,
            notesText = summary.notesText,
            releasePageUrl = summary.releasePageUrl,
            assetName = assetName,
            assetDownloadUrl = assetDownloadUrl
        )
    }

    internal fun parseReleaseHistory(
        responseText: String,
        limit: Int = DEFAULT_RELEASE_HISTORY_LIMIT,
    ): List<UpdateReleaseHistoryEntry> {
        val releases = parseJsonArray(responseText) ?: return emptyList()
        val entries = ArrayList<UpdateReleaseHistoryEntry>()
        val maxEntries = limit.coerceAtLeast(1)
        for (index in 0 until releases.length()) {
            if (entries.size >= maxEntries) {
                break
            }
            val release = releases.optJSONObject(index) ?: continue
            val summary = parseReleaseSummary(release) ?: continue
            entries += UpdateReleaseHistoryEntry(
                rawTagName = summary.rawTagName,
                normalizedVersion = summary.normalizedVersion,
                publishedAtRaw = summary.publishedAtRaw,
                publishedAtDisplayText = summary.publishedAtDisplayText,
                notesText = summary.notesText,
                releasePageUrl = summary.releasePageUrl
            )
        }
        return entries
    }

    internal fun resolveDownloadResolution(
        release: UpdateReleaseInfo,
        preferredUserSource: UpdateSource,
        metadataSource: UpdateSource,
    ): UpdateDownloadResolution {
        return GithubMirrorFallback.run(
            UpdateSource.downloadCandidates(
                preferredUserSource = preferredUserSource,
                metadataSource = metadataSource
            )
        ) { source ->
            val candidateUrl = source.buildUrl(release.assetDownloadUrl)
            if (!isDownloadCandidateReachable(candidateUrl)) {
                throw IOException("Unreachable APK download link.")
            }
            UpdateDownloadResolution(
                source = source,
                resolvedUrl = candidateUrl
            )
        }.value
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

    private fun parseJsonArray(responseText: String): JSONArray? {
        return runCatching {
            val parsed = JSONTokener(responseText).nextValue()
            parsed as? JSONArray
        }.getOrNull()
    }

    private data class ParsedReleaseSummary(
        val rawTagName: String,
        val normalizedVersion: String,
        val publishedAtRaw: String?,
        val publishedAtDisplayText: String,
        val notesText: String,
        val releasePageUrl: String,
    )

    private fun parseReleaseSummary(root: JSONObject): ParsedReleaseSummary? {
        if (root.optBoolean("draft") || root.optBoolean("prerelease")) {
            return null
        }
        val rawTagName = root.optString("tag_name").trim()
        if (rawTagName.isEmpty()) {
            return null
        }
        val publishedAtRaw = root.optString("published_at").trim().ifEmpty { null }
        return ParsedReleaseSummary(
            rawTagName = rawTagName,
            normalizedVersion = LauncherUpdateVersioning.normalizeVersionTag(rawTagName),
            publishedAtRaw = publishedAtRaw,
            publishedAtDisplayText = LauncherUpdateVersioning.formatPublishedAt(publishedAtRaw),
            notesText = LauncherUpdateVersioning.normalizeReleaseNotesText(root.optString("body")),
            releasePageUrl = root.optString("html_url").trim()
        )
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
}
