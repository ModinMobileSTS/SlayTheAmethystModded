package io.stamethyst.backend.steamcloud

import java.io.File
import java.util.Locale

internal object SteamCloudSyncBlacklist {
    const val DEFAULT_LOCAL_RELATIVE_PATH: String = "preferences/STSGameplaySettings"

    fun defaultLocalRelativePaths(): Set<String> = linkedSetOf(DEFAULT_LOCAL_RELATIVE_PATH)

    fun normalizeLocalRelativePath(localRelativePath: String?): String? {
        val normalized = localRelativePath?.trim().orEmpty()
        if (normalized.isBlank()) {
            return null
        }
        return SteamCloudPathMapper.mapLocalRelativePath(normalized)?.localRelativePath
    }

    fun normalizeLocalRelativePaths(localRelativePaths: Iterable<String>): Set<String> {
        return localRelativePaths.mapNotNullTo(linkedSetOf(), ::normalizeLocalRelativePath)
    }

    fun listSelectableLocalRelativePaths(
        stsRoot: File,
        configuredBlacklist: Set<String>,
    ): List<String> {
        val paths = linkedSetOf<String>()
        paths += defaultLocalRelativePaths()
        paths += normalizeLocalRelativePaths(configuredBlacklist)
        SteamCloudRootKind.entries.forEach { rootKind ->
            val rootDir = File(stsRoot, rootKind.directoryName)
            if (!rootDir.isDirectory) {
                return@forEach
            }
            rootDir.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val relativeSuffix = file.relativeTo(rootDir).invariantSeparatorsPath
                    if (relativeSuffix.isNotBlank()) {
                        paths += rootKind.directoryName + "/" + relativeSuffix
                    }
                }
        }
        return paths.sortedWith(compareBy<String>({ it.lowercase(Locale.ROOT) }, { it }))
    }

    fun filterLocalEntries(
        entries: List<SteamCloudLocalFileSnapshotEntry>,
        configuredBlacklist: Set<String>,
    ): List<SteamCloudLocalFileSnapshotEntry> {
        val normalizedBlacklist = normalizeLocalRelativePaths(configuredBlacklist)
        if (normalizedBlacklist.isEmpty()) {
            return entries
        }
        return entries.filterNot { it.localRelativePath in normalizedBlacklist }
    }

    fun filterManifestSnapshot(
        snapshot: SteamCloudManifestSnapshot,
        configuredBlacklist: Set<String>,
    ): SteamCloudManifestSnapshot {
        val normalizedBlacklist = normalizeLocalRelativePaths(configuredBlacklist)
        if (normalizedBlacklist.isEmpty()) {
            return snapshot
        }
        val filteredEntries = snapshot.entries.filterNot { it.localRelativePath in normalizedBlacklist }
        return snapshot.copy(
            fileCount = filteredEntries.size,
            preferencesCount = filteredEntries.count { it.rootKind == SteamCloudRootKind.PREFERENCES },
            savesCount = filteredEntries.count { it.rootKind == SteamCloudRootKind.SAVES },
            entries = filteredEntries,
        )
    }

    fun filterBaseline(
        baseline: SteamCloudSyncBaseline?,
        configuredBlacklist: Set<String>,
    ): SteamCloudSyncBaseline? {
        baseline ?: return null
        val normalizedBlacklist = normalizeLocalRelativePaths(configuredBlacklist)
        if (normalizedBlacklist.isEmpty()) {
            return baseline
        }
        return baseline.copy(
            localEntries = baseline.localEntries.filterNot { it.localRelativePath in normalizedBlacklist },
            remoteEntries = baseline.remoteEntries.filterNot { it.localRelativePath in normalizedBlacklist },
        )
    }

    fun relativeSuffixesForRoot(
        rootKind: SteamCloudRootKind,
        configuredBlacklist: Set<String>,
    ): Set<String> {
        val prefix = rootKind.directoryName + "/"
        return normalizeLocalRelativePaths(configuredBlacklist)
            .asSequence()
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .filter { it.isNotBlank() }
            .toCollection(linkedSetOf())
    }
}
