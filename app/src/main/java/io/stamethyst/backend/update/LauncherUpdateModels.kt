package io.stamethyst.backend.update

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.net.URL
import java.util.LinkedHashSet
import java.util.Locale

enum class UpdateSource(
    val id: String,
    val displayName: String,
    private val proxyPrefix: String?,
    val supportsMetadataCheck: Boolean,
    val supportsDownloadProxy: Boolean,
    val userSelectable: Boolean,
    val usesGithubAcceleration: Boolean = false,
) {
    GH_PROXY_COM(
        id = "gh_proxy_com",
        displayName = "gh-proxy.com",
        proxyPrefix = "https://gh-proxy.com/",
        supportsMetadataCheck = false,
        supportsDownloadProxy = true,
        userSelectable = true
    ),
    GH_PROXY_VIP(
        id = "ghproxy_vip",
        displayName = "ghproxy.vip",
        proxyPrefix = "https://ghproxy.vip/",
        supportsMetadataCheck = true,
        supportsDownloadProxy = true,
        userSelectable = true
    ),
    GH_LLKK(
        id = "gh_llkk",
        displayName = "gh.llkk.cc",
        proxyPrefix = "https://gh.llkk.cc/",
        supportsMetadataCheck = true,
        supportsDownloadProxy = true,
        userSelectable = true
    ),
    GH_PROXY_NET(
        id = "ghproxy_net",
        displayName = "ghproxy.net",
        proxyPrefix = "https://ghproxy.net/",
        supportsMetadataCheck = false,
        supportsDownloadProxy = true,
        userSelectable = false
    ),
    ACCELERATED_DIRECT(
        id = "accelerated_direct",
        displayName = "加速直连",
        proxyPrefix = null,
        supportsMetadataCheck = true,
        supportsDownloadProxy = true,
        userSelectable = true,
        usesGithubAcceleration = true
    ),
    OFFICIAL(
        id = "official",
        displayName = "GitHub",
        proxyPrefix = null,
        supportsMetadataCheck = true,
        supportsDownloadProxy = true,
        userSelectable = true
    );

    fun buildUrl(targetUrl: String): String {
        val normalizedTarget = targetUrl.trim()
        if (normalizedTarget.isEmpty()) {
            return normalizedTarget
        }
        val prefix = proxyPrefix ?: return normalizedTarget
        if (normalizedTarget.startsWith(prefix, ignoreCase = true)) {
            return normalizedTarget
        }
        if (!isMirrorableGithubUrl(normalizedTarget)) {
            return normalizedTarget
        }
        return prefix + normalizedTarget
    }

    companion object {
        val DEFAULT_PREFERRED_USER_SOURCE: UpdateSource = ACCELERATED_DIRECT
        private val mirrorableGithubHosts = setOf(
            "github.com",
            "api.github.com",
            "raw.githubusercontent.com",
            "codeload.github.com",
            "objects.githubusercontent.com",
            "media.githubusercontent.com",
            "release-assets.githubusercontent.com"
        )

        fun fromPersistedValue(value: String?): UpdateSource? {
            return entries.firstOrNull { it.id == value }
        }

        fun userSelectableSources(): List<UpdateSource> {
            return entries.filter { it.userSelectable }
        }

        fun normalizePreferredUserSource(value: String?): UpdateSource {
            val stored = fromPersistedValue(value)
            return if (stored != null && stored.userSelectable) {
                stored
            } else {
                DEFAULT_PREFERRED_USER_SOURCE
            }
        }

        fun githubResourceFallbackCandidates(preferredUserSource: UpdateSource): List<UpdateSource> {
            val preferred = normalizePreferredGithubResourceSource(preferredUserSource)
            val ordered = LinkedHashSet<UpdateSource>()
            ordered += preferred
            entries.forEach { source ->
                if (
                    source != preferred &&
                    source != OFFICIAL &&
                    source != ACCELERATED_DIRECT
                ) {
                    ordered += source
                }
            }
            if (preferred != OFFICIAL) {
                ordered += OFFICIAL
            }
            return ordered.toList()
        }

        fun metadataCandidates(preferredUserSource: UpdateSource): List<UpdateSource> {
            return githubResourceFallbackCandidates(preferredUserSource)
        }

        fun downloadCandidates(
            preferredUserSource: UpdateSource,
            metadataSource: UpdateSource,
        ): List<UpdateSource> {
            return githubResourceFallbackCandidates(preferredUserSource)
        }

        fun oneShotDownloadSelectionSources(primarySource: UpdateSource): List<UpdateSource> {
            val ordered = LinkedHashSet<UpdateSource>()
            ordered += primarySource
            userSelectableSources().forEach { source ->
                ordered += source
            }
            return ordered.toList()
        }

        private fun isMirrorableGithubUrl(targetUrl: String): Boolean {
            val host = runCatching { URL(targetUrl).host.lowercase(Locale.ROOT) }.getOrNull()
                ?: return false
            return host in mirrorableGithubHosts || host.endsWith(".githubusercontent.com")
        }

        private fun normalizePreferredGithubResourceSource(
            preferredUserSource: UpdateSource
        ): UpdateSource {
            return when {
                preferredUserSource == OFFICIAL -> OFFICIAL
                preferredUserSource.userSelectable -> preferredUserSource
                else -> normalizePreferredUserSource(preferredUserSource.id)
            }
        }
    }
}

data class UpdateReleaseInfo(
    val rawTagName: String,
    val normalizedVersion: String,
    val publishedAtRaw: String?,
    val publishedAtDisplayText: String,
    val notesText: String,
    val releasePageUrl: String = "",
    val assetName: String,
    val assetDownloadUrl: String,
)

data class UpdateReleaseHistoryEntry(
    val rawTagName: String,
    val normalizedVersion: String,
    val publishedAtRaw: String?,
    val publishedAtDisplayText: String,
    val notesText: String,
    val releasePageUrl: String = "",
)

data class UpdateReleaseHistoryResult(
    val metadataSource: UpdateSource,
    val entries: List<UpdateReleaseHistoryEntry>,
)

data class UpdateDownloadResolution(
    val source: UpdateSource,
    val resolvedUrl: String,
)

sealed interface UpdateCheckExecutionResult {
    data class Success(
        val currentVersion: String,
        val release: UpdateReleaseInfo,
        val metadataSource: UpdateSource,
        val downloadResolution: UpdateDownloadResolution?,
        val hasUpdate: Boolean,
    ) : UpdateCheckExecutionResult

    data class Failure(
        val errorSummary: String,
        val release: UpdateReleaseInfo? = null,
        val metadataSource: UpdateSource? = null,
    ) : UpdateCheckExecutionResult
}

object LauncherUpdateVersioning {
    private val releaseVersionPattern =
        Regex("""^(\d+)\.(\d+)\.(\d+)(?:-hotfix(\d+))?$""")
    private val publishedAtFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

    fun normalizeVersionTag(value: String): String {
        return value.trim().removePrefix("v").removePrefix("V")
    }

    fun isRemoteNewer(currentVersion: String, remoteVersionTag: String): Boolean {
        val normalizedCurrent = normalizeVersionTag(currentVersion)
        val normalizedRemote = normalizeVersionTag(remoteVersionTag)
        val currentParsed = parseReleaseVersion(normalizedCurrent)
        val remoteParsed = parseReleaseVersion(normalizedRemote)
        if (currentParsed != null && remoteParsed != null) {
            return currentParsed < remoteParsed
        }
        return normalizedCurrent != normalizedRemote
    }

    fun formatPublishedAt(value: String?): String {
        val normalized = value?.trim().orEmpty()
        if (normalized.isEmpty()) {
            return ""
        }
        return runCatching {
            Instant.parse(normalized)
                .atZone(ZoneId.systemDefault())
                .format(publishedAtFormatter)
        }.getOrElse { normalized }
    }

    fun normalizeReleaseNotesText(value: String?): String {
        return value
            .orEmpty()
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
    }

    private data class ParsedReleaseVersion(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val hotfix: Int,
    ) : Comparable<ParsedReleaseVersion> {
        override fun compareTo(other: ParsedReleaseVersion): Int {
            return compareValuesBy(
                this,
                other,
                ParsedReleaseVersion::major,
                ParsedReleaseVersion::minor,
                ParsedReleaseVersion::patch,
                ParsedReleaseVersion::hotfix
            )
        }
    }

    private fun parseReleaseVersion(value: String): ParsedReleaseVersion? {
        val match = releaseVersionPattern.matchEntire(value) ?: return null
        return ParsedReleaseVersion(
            major = match.groupValues[1].toInt(),
            minor = match.groupValues[2].toInt(),
            patch = match.groupValues[3].toInt(),
            hotfix = match.groupValues[4].toIntOrNull() ?: 0
        )
    }
}
