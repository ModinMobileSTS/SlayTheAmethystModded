package io.stamethyst.backend.update

import android.content.Context
import io.stamethyst.backend.github.GithubAcceleratedHttp
import io.stamethyst.backend.github.GithubRequestClients
import java.io.IOException
import java.nio.charset.StandardCharsets
import okhttp3.OkHttpClient
import okhttp3.Request
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
        context: Context,
        currentVersion: String,
        preferredUserSource: UpdateSource,
    ): UpdateCheckExecutionResult {
        val clients = createGithubClients(context)
        val normalizedPreferredSource = UpdateSource.normalizePreferredUserSource(preferredUserSource.id)
        val metadataResult = try {
            GithubMirrorFallback.run(normalizedPreferredSource) { source ->
                val responseText = requestText(
                    clients.pick(source.usesGithubAcceleration),
                    source.buildUrl(LATEST_RELEASE_API_URL)
                )
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
                clients = clients,
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
        context: Context,
        preferredUserSource: UpdateSource,
        limit: Int = DEFAULT_RELEASE_HISTORY_LIMIT,
    ): UpdateReleaseHistoryResult {
        val clients = createGithubClients(context)
        val normalizedPreferredSource = UpdateSource.normalizePreferredUserSource(preferredUserSource.id)
        val normalizedLimit = limit.coerceIn(1, 20)
        val metadataResult = GithubMirrorFallback.run(normalizedPreferredSource) { source ->
            val requestUrl = source.buildUrl("$RELEASE_HISTORY_API_URL?per_page=$normalizedLimit")
            val responseText = requestText(
                clients.pick(source.usesGithubAcceleration),
                requestUrl
            )
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
        clients: GithubRequestClients,
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
            if (!isDownloadCandidateReachable(clients.pick(source.usesGithubAcceleration), candidateUrl)) {
                throw IOException("Unreachable APK download link.")
            }
            UpdateDownloadResolution(
                source = source,
                resolvedUrl = candidateUrl
            )
        }.value
    }

    private fun createGithubClients(context: Context): GithubRequestClients {
        return GithubAcceleratedHttp.createClientPair(
            context = context,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
            followRedirects = true,
        )
    }

    private fun requestText(client: OkHttpClient, requestUrl: String): String {
        val request = Request.Builder()
            .url(requestUrl)
            .get()
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            return response.body.bytes().toString(StandardCharsets.UTF_8)
        }
    }

    private fun isDownloadCandidateReachable(client: OkHttpClient, requestUrl: String): Boolean {
        return requestProbe(client, requestUrl, "HEAD") || requestRangeProbe(client, requestUrl)
    }

    private fun requestProbe(client: OkHttpClient, requestUrl: String, method: String): Boolean {
        val requestBuilder = Request.Builder()
            .url(requestUrl)
            .header("User-Agent", USER_AGENT)
        val request = if (method.equals("HEAD", ignoreCase = true)) {
            requestBuilder.head().build()
        } else {
            requestBuilder.method(method, null).build()
        }
        return try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun requestRangeProbe(client: OkHttpClient, requestUrl: String): Boolean {
        val request = Request.Builder()
            .url(requestUrl)
            .get()
            .header("User-Agent", USER_AGENT)
            .header("Range", "bytes=0-0")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful || response.code == 206
            }
        } catch (_: Throwable) {
            false
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
