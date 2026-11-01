package io.stamethyst.backend.minigame

import io.stamethyst.backend.update.LauncherUpdateService
import io.stamethyst.backend.update.LauncherUpdateVersioning
import io.stamethyst.backend.update.UpdateReleaseInfo
import org.json.JSONArray

object SlingBreakReleases {
    const val ENGINE_URL = "https://github.com/Apricityx/slingbreak/releases/download/Resource/engine.apk"
    // Executable code is pinned by the launcher, never trusted solely on mirror metadata.
    const val ENGINE_SHA256 = "e51956f8f3b2be5d5fed6fd8e235ae6d4f93103ebab4bb23e018a36a2559e50d"
    const val RELEASES_API = "https://api.github.com/repos/Apricityx/slingbreak/releases?per_page=100"
    const val MAX_COMPONENT_BYTES = 256L * 1024 * 1024

    fun target(installed: UpdateReleaseInfo?) = LauncherUpdateService.ReleaseTarget(
        apiUrl = RELEASES_API,
        parse = ::parseLatest,
        hasUpdate = { _, remote -> isUpdate(installed, remote) },
        compare = ::compare,
    )

    fun compare(left: UpdateReleaseInfo, right: UpdateReleaseInfo): Int =
        compareValuesBy(left, right, { it.publishedAtRaw.orEmpty() }, { it.releaseId }, { it.assetId })

    fun isUpdate(installed: UpdateReleaseInfo?, remote: UpdateReleaseInfo): Boolean {
        if (installed == null) return true
        if (compare(remote, installed) < 0) return false // A stale mirror cannot downgrade an installed release.
        return remote.releaseId != installed.releaseId || remote.assetId != installed.assetId ||
            remote.assetSha256 != installed.assetSha256 || remote.rawTagName != installed.rawTagName
    }

    fun parseLatest(text: String): UpdateReleaseInfo? = runCatching {
        val releases = JSONArray(text)
        val ordered = (0 until releases.length()).mapNotNull { releases.optJSONObject(it) }
            .sortedWith(compareBy<org.json.JSONObject> { it.optString("published_at") }.thenBy { it.optLong("id") })
            .asReversed()
        for (release in ordered) {
                if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
                val tag = release.optString("tag_name").trim()
                if (tag.isBlank() || tag.equals("Resource", true)) continue
                val assets = release.optJSONArray("assets") ?: continue
                val pcks = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
                    .filter { it.optString("name").endsWith(".pck", true) }
                // Prefer the public contract name; do not guess between multiple platform packs.
                if (pcks.isEmpty()) continue
                val asset = pcks.firstOrNull { it.optString("name") == "game.pck" }
                    ?: pcks.singleOrNull() ?: return@runCatching null
                val sha = asset.optString("digest").removePrefix("sha256:").lowercase()
                val url = asset.optString("browser_download_url")
                val size = asset.optLong("size")
                if (!sha.matches(Regex("[a-f0-9]{64}")) || size !in 40..MAX_COMPONENT_BYTES ||
                    !url.startsWith("https://github.com/Apricityx/slingbreak/releases/download/") ||
                    release.optLong("id") <= 0 || asset.optLong("id") <= 0) return@runCatching null
                val published = release.optString("published_at")
                if (runCatching { java.time.Instant.parse(published) }.isFailure) return@runCatching null
                return@runCatching UpdateReleaseInfo(
                    rawTagName = tag,
                    normalizedVersion = LauncherUpdateVersioning.normalizeVersionTag(tag),
                    publishedAtRaw = published,
                    publishedAtDisplayText = LauncherUpdateVersioning.formatPublishedAt(published),
                    notesText = LauncherUpdateVersioning.normalizeReleaseNotesText(release.optString("body")),
                    releasePageUrl = release.optString("html_url"),
                    assetName = asset.optString("name"), assetDownloadUrl = url,
                    assetSha256 = sha, assetSize = size,
                    releaseId = release.optLong("id"), assetId = asset.optLong("id"),
                )
        }
        null
    }.getOrNull()
}
