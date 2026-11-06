package io.stamethyst.backend.update

import android.content.Context
import io.stamethyst.backend.github.WattToolkitAcceleratedHttp
import io.stamethyst.backend.github.GithubRequestClients
import io.stamethyst.backend.github.HttpRequestControl
import io.stamethyst.backend.network.NetworkAccelerationPolicy
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

object LauncherUpdateService {
    /** Shared metadata transport/race, with product-specific release selection and identity rules. */
    class ReleaseTarget(
        val apiUrl: String,
        val parse: (String) -> UpdateReleaseInfo?,
        val hasUpdate: (String, UpdateReleaseInfo) -> Boolean = { current, release ->
            LauncherUpdateVersioning.isRemoteNewer(current, release.normalizedVersion)
        },
        val compare: (UpdateReleaseInfo, UpdateReleaseInfo) -> Int = { left, right ->
            LauncherUpdateVersioning.compareReleaseVersions(left.normalizedVersion, right.normalizedVersion) ?: 0
        },
    )

    private val launcherTarget get() = ReleaseTarget(LATEST_RELEASE_API_URL, ::parseLatestRelease)
    private const val LATEST_RELEASE_API_URL =
        "https://api.github.com/repos/ModinMobileSTS/SlayTheAmethystModded/releases/latest"
    private const val RELEASE_HISTORY_API_URL =
        "https://api.github.com/repos/ModinMobileSTS/SlayTheAmethystModded/releases"
    private const val DEFAULT_RELEASE_HISTORY_LIMIT = 10
    private const val MAX_RELEASE_HISTORY_LIMIT = 100
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 12_000
    private const val UPDATE_CHECK_TIMEOUT_MS = 20_000L
    private const val USER_AGENT = "SlayTheAmethyst-LauncherUpdate"

    fun checkForUpdates(
        context: Context,
        currentVersion: String,
        preferredUserSource: UpdateSource,
        target: ReleaseTarget = launcherTarget,
    ): UpdateCheckExecutionResult {
        val clients = createGithubClients(context)
        val normalizedPreferredSource = UpdateSource.normalizePreferredUserSource(preferredUserSource.id)
        val bypassAcceleratedLinks = NetworkAccelerationPolicy.shouldBypassAcceleratedLinks(context)
        return checkForUpdates(
            clients = clients,
            currentVersion = currentVersion,
            sources = UpdateSource.metadataCandidates(normalizedPreferredSource, bypassAcceleratedLinks),
            target = target,
        )
    }

    /** Race metadata only: a stale or slow mirror must not hide another source's update. */
    internal fun checkForUpdates(
        clients: GithubRequestClients,
        currentVersion: String,
        sources: List<UpdateSource>,
        timeoutMillis: Long = UPDATE_CHECK_TIMEOUT_MS,
        target: ReleaseTarget = launcherTarget,
    ): UpdateCheckExecutionResult {
        if (sources.isEmpty()) {
            return UpdateCheckExecutionResult.Failure("No update metadata sources configured.")
        }
        val candidates = GithubMirrorHealthStore.orderByHealth(sources.distinct())
        val executor = Executors.newFixedThreadPool(candidates.size)
        val completion = ExecutorCompletionService<MetadataAttempt>(executor)
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val controls = candidates.map { HttpRequestControl(timeoutMillis) }
        val tasks = candidates.mapIndexed { index, source ->
            completion.submit {
                try {
                    controls[index].checkActive()
                    val responseText = requestText(
                        clients.pick(source.usesGithubAcceleration),
                        source.buildUrl(target.apiUrl),
                        controls[index],
                    )
                    val release = target.parse(responseText)
                        ?: throw IOException("Invalid release payload.")
                    MetadataAttempt(source, release = release)
                } catch (error: Exception) {
                    MetadataAttempt(source, error = error)
                }
            }
        }
        val failures = ArrayList<GithubMirrorFallbackFailure>()
        var latestSuccessfulResult: GithubMirrorFallbackSuccess<UpdateReleaseInfo>? = null
        try {
            for (index in candidates.indices) {
                val remainingNanos = deadlineNanos - System.nanoTime()
                val completed = completion.poll(remainingNanos.coerceAtLeast(0), TimeUnit.NANOSECONDS)
                    ?: return UpdateCheckExecutionResult.Failure("Update metadata check timed out.")
                val attempt = completed.get()
                val release = attempt.release
                if (release != null) {
                    GithubMirrorHealthStore.recordSuccess(attempt.source)
                    if (target.hasUpdate(currentVersion, release)) {
                        return UpdateCheckExecutionResult.Success(
                            currentVersion = currentVersion,
                            release = release,
                            metadataSource = attempt.source,
                            hasUpdate = true
                        )
                    }
                    val previousRelease = latestSuccessfulResult?.value
                    if (previousRelease == null ||
                        target.compare(release, previousRelease) > 0
                    ) {
                        latestSuccessfulResult = GithubMirrorFallbackSuccess(attempt.source, release)
                    }
                } else {
                    val error = attempt.error ?: IOException("Invalid release payload.")
                    GithubMirrorHealthStore.recordFailure(attempt.source, error)
                    failures += GithubMirrorFallbackFailure(attempt.source, error)
                }
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            return UpdateCheckExecutionResult.Failure("Update metadata check cancelled.")
        } finally {
            controls.forEach(HttpRequestControl::cancel)
            tasks.forEach { it.cancel(true) }
            executor.shutdownNow()
        }
        val metadataResult = latestSuccessfulResult ?: run {
            return UpdateCheckExecutionResult.Failure(
                errorSummary = GithubMirrorFallback.summarize(GithubMirrorFallbackException(failures)),
                metadataSource = null
            )
        }
        val metadataSource = metadataResult.source
        val release = metadataResult.value
        return UpdateCheckExecutionResult.Success(
            currentVersion = currentVersion,
            release = release,
            metadataSource = metadataSource,
            hasUpdate = false
        )
    }

    private data class MetadataAttempt(
        val source: UpdateSource,
        val release: UpdateReleaseInfo? = null,
        val error: Exception? = null,
    )

    fun fetchReleaseHistory(
        context: Context,
        preferredUserSource: UpdateSource,
        limit: Int = DEFAULT_RELEASE_HISTORY_LIMIT,
    ): UpdateReleaseHistoryResult {
        val clients = createGithubClients(context)
        val normalizedPreferredSource = UpdateSource.normalizePreferredUserSource(preferredUserSource.id)
        val bypassAcceleratedLinks = NetworkAccelerationPolicy.shouldBypassAcceleratedLinks(context)
        val normalizedLimit = limit.coerceIn(1, MAX_RELEASE_HISTORY_LIMIT)
        val metadataResult = GithubMirrorFallback.run(
            normalizedPreferredSource,
            bypassAcceleratedLinks = bypassAcceleratedLinks,
        ) { source ->
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

    fun fetchLatestFullRelease(
        context: Context,
        preferredUserSource: UpdateSource,
    ): UpdateReleaseInfo {
        val clients = createGithubClients(context)
        val normalizedPreferredSource = UpdateSource.normalizePreferredUserSource(preferredUserSource.id)
        val bypassAcceleratedLinks = NetworkAccelerationPolicy.shouldBypassAcceleratedLinks(context)
        return GithubMirrorFallback.run(
            normalizedPreferredSource,
            bypassAcceleratedLinks = bypassAcceleratedLinks,
        ) { source ->
            val responseText = requestText(
                clients.pick(source.usesGithubAcceleration),
                source.buildUrl(LATEST_RELEASE_API_URL)
            )
            parseLatestFullRelease(responseText)
                ?: throw IOException("Latest full release APK not found.")
        }.value
    }

    internal fun parseLatestRelease(responseText: String): UpdateReleaseInfo? {
        return parseLatestRelease(
            responseText = responseText,
            preferredAsset = ReleaseAssetPreference.SLIM
        )
    }

    internal fun parseLatestFullRelease(responseText: String): UpdateReleaseInfo? {
        return parseLatestRelease(
            responseText = responseText,
            preferredAsset = ReleaseAssetPreference.FULL
        )
    }

    private fun parseLatestRelease(
        responseText: String,
        preferredAsset: ReleaseAssetPreference,
    ): UpdateReleaseInfo? {
        val root = parseJsonObject(responseText) ?: return null
        val summary = parseReleaseSummary(root) ?: return null
        val assets = root.optJSONArray("assets") ?: JSONArray()
        val asset = findPreferredApkAsset(assets, preferredAsset) ?: return null
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

    private fun createGithubClients(context: Context): GithubRequestClients {
        return WattToolkitAcceleratedHttp.createClientPair(
            context = context,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
            followRedirects = true,
        )
    }

    private fun requestText(
        client: Call.Factory,
        requestUrl: String,
        control: HttpRequestControl? = null,
    ): String {
        val request = Request.Builder()
            .url(requestUrl)
            .get()
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .tag(HttpRequestControl::class.java, control)
            .build()

        val call = client.newCall(request)
        control?.register(call)
        call.execute().use { response ->
            if (!response.isSuccessful) {
                throw response.toGithubMirrorHttpException()
            }
            return response.body.bytes().toString(StandardCharsets.UTF_8)
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

    private fun findPreferredApkAsset(
        assets: JSONArray,
        preferredAsset: ReleaseAssetPreference,
    ): JSONObject? {
        var firstApkAsset: JSONObject? = null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name").trim()
            if (!name.endsWith(".apk", ignoreCase = true)) {
                continue
            }
            if (firstApkAsset == null) {
                firstApkAsset = asset
            }
            val isFullApk = name.endsWith("-full.apk", ignoreCase = true)
            if (
                preferredAsset == ReleaseAssetPreference.FULL && isFullApk ||
                preferredAsset == ReleaseAssetPreference.SLIM && !isFullApk
            ) {
                return asset
            }
        }
        return if (preferredAsset == ReleaseAssetPreference.SLIM) firstApkAsset else null
    }

    private enum class ReleaseAssetPreference {
        SLIM,
        FULL
    }
}
