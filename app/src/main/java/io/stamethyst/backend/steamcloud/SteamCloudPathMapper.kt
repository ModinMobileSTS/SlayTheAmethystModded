package io.stamethyst.backend.steamcloud

internal object SteamCloudPathMapper {
    private const val PREFERENCES_PREFIX = "%GameInstall%preferences/"
    private const val SAVES_PREFIX = "%GameInstall%saves/"

    data class MappedPath(
        val rootKind: SteamCloudRootKind,
        val localRelativePath: String,
    )

    fun mapRemotePath(remotePath: String): MappedPath? {
        val normalized = remotePath.trim().replace('\\', '/')
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
        val normalized = localRelativePath.trim().replace('\\', '/')
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
    ): SteamCloudManifestSnapshot {
        val warnings = mutableListOf<String>()
        val entries = remoteEntries.mapNotNull { remoteEntry ->
            val mappedPath = mapRemotePath(remoteEntry.remotePath)
            if (mappedPath == null) {
                warnings += "Ignored unsupported Steam Cloud path: ${remoteEntry.remotePath}"
                return@mapNotNull null
            }

            SteamCloudManifestEntry(
                remotePath = remoteEntry.remotePath,
                localRelativePath = mappedPath.localRelativePath,
                rootKind = mappedPath.rootKind,
                rawSize = remoteEntry.rawFileSize,
                timestamp = remoteEntry.timestampMs,
                machineName = remoteEntry.machineName,
                persistState = remoteEntry.persistState,
            )
        }.sortedWith(
            compareBy<SteamCloudManifestEntry>({ it.localRelativePath.lowercase() }, { it.localRelativePath })
        )

        return SteamCloudManifestSnapshot(
            fetchedAtMs = fetchedAtMs,
            fileCount = entries.size,
            preferencesCount = entries.count { it.rootKind == SteamCloudRootKind.PREFERENCES },
            savesCount = entries.count { it.rootKind == SteamCloudRootKind.SAVES },
            entries = entries,
            warnings = warnings,
        )
    }

    private fun isSafeRelativePath(relativePath: String): Boolean {
        if (relativePath.isBlank()) {
            return false
        }
        val segments = relativePath.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) {
            return false
        }
        return true
    }
}
