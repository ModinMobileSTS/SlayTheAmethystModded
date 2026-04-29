package io.stamethyst.backend.steamcloud

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class SteamCloudSnapshotStoreTest {
    @Test
    fun manifestStore_readSnapshot_recoversCorruptedManifestFromBackup() {
        val roots = TestRoots.create("steam-cloud-manifest-store")
        try {
            val original = manifestSnapshot(
                fetchedAtMs = 1L,
                entry = manifestEntry(
                    remotePath = "%GameInstall%preferences/STSPlayer",
                    localRelativePath = "preferences/STSPlayer",
                    sha1 = "original-sha1",
                )
            )
            val replacement = manifestSnapshot(
                fetchedAtMs = 2L,
                entry = manifestEntry(
                    remotePath = "%GameInstall%saves/WATCHER.autosave",
                    localRelativePath = "saves/WATCHER.autosave",
                    rootKind = SteamCloudRootKind.SAVES,
                    sha1 = "replacement-sha1",
                )
            )
            SteamCloudManifestStore.writeSnapshot(roots.context, original)
            SteamCloudManifestStore.writeSnapshot(roots.context, replacement)

            SteamCloudManifestStore.manifestFile(roots.context).writeText("{", StandardCharsets.UTF_8)

            assertEquals(original, SteamCloudManifestStore.readSnapshot(roots.context))
            assertEquals(original, SteamCloudManifestStore.readSnapshot(roots.context))
        } finally {
            roots.rootDir.deleteRecursively()
        }
    }

    @Test
    fun baselineStore_readSnapshot_recoversCorruptedBaselineFromBackup() {
        val roots = TestRoots.create("steam-cloud-baseline-store")
        try {
            val original = SteamCloudSyncBaseline(
                syncedAtMs = 1L,
                localEntries = listOf(
                    localEntry(
                        localRelativePath = "preferences/STSPlayer",
                        sha1 = "original-local-sha1",
                    )
                ),
                remoteEntries = listOf(
                    manifestEntry(
                        remotePath = "%GameInstall%preferences/STSPlayer",
                        localRelativePath = "preferences/STSPlayer",
                        sha1 = "original-remote-sha1",
                    )
                ),
            )
            val replacement = SteamCloudSyncBaseline(
                syncedAtMs = 2L,
                localEntries = listOf(
                    localEntry(
                        localRelativePath = "saves/WATCHER.autosave",
                        rootKind = SteamCloudRootKind.SAVES,
                        sha1 = "replacement-local-sha1",
                    )
                ),
                remoteEntries = listOf(
                    manifestEntry(
                        remotePath = "%GameInstall%saves/WATCHER.autosave",
                        localRelativePath = "saves/WATCHER.autosave",
                        rootKind = SteamCloudRootKind.SAVES,
                        sha1 = "replacement-remote-sha1",
                    )
                ),
            )
            SteamCloudBaselineStore.writeSnapshot(roots.context, original)
            SteamCloudBaselineStore.writeSnapshot(roots.context, replacement)

            SteamCloudBaselineStore.baselineFile(roots.context).writeText("{", StandardCharsets.UTF_8)

            assertEquals(original, SteamCloudBaselineStore.readSnapshot(roots.context))
            assertEquals(original, SteamCloudBaselineStore.readSnapshot(roots.context))
        } finally {
            roots.rootDir.deleteRecursively()
        }
    }

    private fun manifestSnapshot(
        fetchedAtMs: Long,
        entry: SteamCloudManifestEntry,
    ): SteamCloudManifestSnapshot {
        return SteamCloudManifestSnapshot(
            fetchedAtMs = fetchedAtMs,
            fileCount = 1,
            preferencesCount = if (entry.rootKind == SteamCloudRootKind.PREFERENCES) 1 else 0,
            savesCount = if (entry.rootKind == SteamCloudRootKind.SAVES) 1 else 0,
            entries = listOf(entry),
            warnings = emptyList(),
        )
    }

    private fun localEntry(
        localRelativePath: String,
        rootKind: SteamCloudRootKind = SteamCloudRootKind.PREFERENCES,
        sha1: String,
    ): SteamCloudLocalFileSnapshotEntry {
        return SteamCloudLocalFileSnapshotEntry(
            localRelativePath = localRelativePath,
            rootKind = rootKind,
            fileSize = 100L,
            lastModifiedMs = 1L,
            sha256 = "$sha1-sha256",
            sha1 = sha1,
        )
    }

    private fun manifestEntry(
        remotePath: String,
        localRelativePath: String,
        rootKind: SteamCloudRootKind = SteamCloudRootKind.PREFERENCES,
        sha1: String,
    ): SteamCloudManifestEntry {
        return SteamCloudManifestEntry(
            remotePath = remotePath,
            localRelativePath = localRelativePath,
            rootKind = rootKind,
            rawSize = 100L,
            timestamp = 1L,
            machineName = "",
            persistState = "Persisted",
            sha1 = sha1,
        )
    }

    private class TestRoots private constructor(
        val rootDir: File,
        val context: Context,
    ) {
        companion object {
            fun create(prefix: String): TestRoots {
                val rootDir = Files.createTempDirectory(prefix).toFile()
                val filesDir = File(rootDir, "internal-files").apply { mkdirs() }
                val externalFilesDir = File(rootDir, "external-files").apply { mkdirs() }
                return TestRoots(
                    rootDir = rootDir,
                    context = object : ContextWrapper(Application()) {
                        override fun getFilesDir(): File = filesDir

                        override fun getExternalFilesDir(type: String?): File = externalFilesDir

                        override fun getPackageName(): String = "io.stamethyst.test"
                    }
                )
            }
        }
    }
}
