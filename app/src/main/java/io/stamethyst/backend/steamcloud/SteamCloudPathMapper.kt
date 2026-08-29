package io.stamethyst.backend.steamcloud

internal object SteamCloudPathMapper {
    private const val PREFERENCES_PREFIX = "%GameInstall%preferences/"
    private const val SAVES_PREFIX = "%GameInstall%saves/"

    data class MappedPath(
        val rootKind: SteamCloudRootKind,
        val localRelativePath: String,
    )

    fun mapRemotePath(remotePath: String): MappedPath? {
        val normalized = remotePath.replace('\\', '/')
        if (!hasCanonicalPathSyntax(normalized)) {
            return null
        }
        val mapping = when {
            normalized.startsWith(PREFERENCES_PREFIX) -> {
                val relativePath = normalized.removePrefix(PREFERENCES_PREFIX)
                SteamCloudRootKind.PREFERENCES to relativePath
            }

            normalized.startsWith(SAVES_PREFIX) -> {
                val relativePath = normalized.removePrefix(SAVES_PREFIX)
                SteamCloudRootKind.SAVES to relativePath
            }

            else -> return null
        }

        if (!isSafeRelativePath(mapping.second)) {
            return null
        }

        return MappedPath(
            rootKind = mapping.first,
            localRelativePath = mapping.first.directoryName + "/" + mapping.second
        )
    }

    fun mapLocalRelativePath(localRelativePath: String): MappedPath? {
        if (!hasCanonicalPathSyntax(localRelativePath)) {
            return null
        }
        val normalized = localRelativePath
        val mapping = when {
            normalized.startsWith("preferences/") -> {
                SteamCloudRootKind.PREFERENCES to normalized.removePrefix("preferences/")
            }

            normalized.startsWith("saves/") -> {
                SteamCloudRootKind.SAVES to normalized.removePrefix("saves/")
            }

            else -> return null
        }

        if (!isSafeRelativePath(mapping.second)) {
            return null
        }

        return MappedPath(
            rootKind = mapping.first,
            localRelativePath = mapping.first.directoryName + "/" + mapping.second
        )
    }

    fun buildRemotePath(localRelativePath: String): String? {
        val mapped = mapLocalRelativePath(localRelativePath) ?: return null
        val relativePath = mapped.localRelativePath.removePrefix(mapped.rootKind.directoryName + "/")
        return when (mapped.rootKind) {
            SteamCloudRootKind.PREFERENCES -> PREFERENCES_PREFIX + relativePath
            SteamCloudRootKind.SAVES -> SAVES_PREFIX + relativePath
        }
    }

    fun buildManifestSnapshot(
        fetchedAtMs: Long,
        remoteEntries: List<SteamCloudClient.RemoteFileRecord>,
        steamId64: String = "",
    ): SteamCloudManifestSnapshot {
        val warnings = mutableListOf<String>()
        val mappedEntries = remoteEntries.mapNotNull { remoteEntry ->
            val mappedPath = mapRemotePath(remoteEntry.remotePath)
            if (mappedPath == null) {
                if (looksLikeManagedRemotePath(remoteEntry.remotePath)) {
                    throw SteamCloudIncompleteManifestException(
                        "Steam Cloud returned an unsafe or ambiguous managed path: ${remoteEntry.remotePath}"
                    )
                }
                warnings += SteamCloudUserWarning.UnsupportedRemotePath(remoteEntry.remotePath).rawMessage()
                return@mapNotNull null
            }
            val entry = SteamCloudManifestEntry(
                remotePath = requireNotNull(buildRemotePath(mappedPath.localRelativePath)),
                localRelativePath = mappedPath.localRelativePath,
                rootKind = mappedPath.rootKind,
                rawSize = remoteEntry.rawFileSize,
                timestamp = remoteEntry.timestampMs,
                machineName = remoteEntry.machineName,
                persistState = remoteEntry.persistState,
                sha1 = remoteEntry.sha1,
            )
            if (!entry.hasKnownPersistState) {
                throw SteamCloudIncompleteManifestException(
                    "Steam Cloud returned an unknown persistence state for ${entry.remotePath}: " +
                        remoteEntry.persistState
                )
            }

            entry
        }
        val duplicateLocalPaths = mappedEntries
            .groupBy { it.localRelativePath.lowercase() }
            .filterValues { it.size > 1 }
            .values
            .map { duplicates -> duplicates.first().localRelativePath }
        duplicateLocalPaths.forEach { localRelativePath ->
            throw SteamCloudIncompleteManifestException(
                SteamCloudUserWarning.DuplicateMappedLocalPath(localRelativePath).rawMessage()
            )
        }
        val entries = mappedEntries.asSequence()
            .sortedWith(
                compareBy<SteamCloudManifestEntry>({ it.localRelativePath.lowercase() }, { it.localRelativePath })
            )
            .toList()

        return SteamCloudManifestSnapshot(
            fetchedAtMs = fetchedAtMs,
            fileCount = entries.count { it.isLive },
            preferencesCount = entries.count { it.rootKind == SteamCloudRootKind.PREFERENCES },
            savesCount = entries.count { it.rootKind == SteamCloudRootKind.SAVES },
            entries = entries,
            warnings = warnings,
            steamId64 = steamId64.trim(),
        )
    }

    private fun isSafeRelativePath(relativePath: String): Boolean {
        if (!hasCanonicalPathSyntax(relativePath) || relativePath.startsWith('/')) {
            return false
        }
        val segments = relativePath.split('/')
        if (segments.any {
                it.isBlank() ||
                    it != it.trim() ||
                    it == "." ||
                    it == ".." ||
                    DRIVE_PATH_PREFIX.matches(it)
            }) {
            return false
        }
        return true
    }

    private fun hasCanonicalPathSyntax(path: String): Boolean {
        return path.isNotBlank() && path == path.trim() && '\\' !in path
    }

    private fun looksLikeManagedRemotePath(remotePath: String): Boolean {
        val normalized = remotePath.trim().replace('\\', '/')
        return normalized.startsWith("%GameInstall%preferences", ignoreCase = true) ||
            normalized.startsWith("%GameInstall%saves", ignoreCase = true)
    }

    private val DRIVE_PATH_PREFIX = Regex("^[A-Za-z]:")
}
