package io.stamethyst.backend.steamcloud

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamCloudBackgroundUploadSnapshotTest {
    @Test
    fun isBackgroundUploadEligible_requiresUploadOnlyPlan() {
        assertTrue(SteamCloudPushCoordinator.isBackgroundUploadEligible(uploadPlan()))
        assertFalse(
            SteamCloudPushCoordinator.isBackgroundUploadEligible(
                uploadPlan(remoteDeleteCandidates = listOf(deleteCandidate()))
            )
        )
        assertFalse(
            SteamCloudPushCoordinator.isBackgroundUploadEligible(
                uploadPlan(remoteOnlyChanges = listOf(remoteOnlyChange()))
            )
        )
        assertFalse(
            SteamCloudPushCoordinator.isBackgroundUploadEligible(
                uploadPlan(
                    conflicts = listOf(
                        SteamCloudConflict(
                            localRelativePath = "preferences/STSPlayer",
                            rootKind = SteamCloudRootKind.PREFERENCES,
                            kind = SteamCloudConflictKind.BOTH_CHANGED,
                            currentLocal = null,
                            currentRemote = null,
                            baselineLocal = null,
                            baselineRemote = null,
                        )
                    )
                )
            )
        )
    }

    @Test
    fun isBackgroundCheckSnapshotEligible_allowsFinishedRunAutosaveDeletion() {
        assertTrue(
            SteamCloudPushCoordinator.isBackgroundCheckSnapshotEligible(
                uploadPlan(remoteDeleteCandidates = listOf(deleteCandidate()))
            )
        )
        assertFalse(
            SteamCloudPushCoordinator.isBackgroundCheckSnapshotEligible(
                uploadPlan(remoteOnlyChanges = listOf(remoteOnlyChange()))
            )
        )
    }

    @Test
    fun prepareBackgroundUploadSnapshot_freezesSourceBeforeGameCanWrite() {
        val roots = TestRoots.create()
        try {
            val source = File(roots.stsRoot, "preferences/STSPlayer")
            source.parentFile?.mkdirs()
            source.writeText("before", Charsets.UTF_8)
            val entry = SteamCloudLocalSnapshotCollector.collect(roots.stsRoot).single()
            val plan = uploadPlan(
                candidate = SteamCloudUploadCandidate(
                    remotePath = "%GameInstall%preferences/STSPlayer",
                    localRelativePath = entry.localRelativePath,
                    rootKind = entry.rootKind,
                    fileSize = entry.fileSize,
                    lastModifiedMs = entry.lastModifiedMs,
                    sha256 = entry.sha256,
                    sha1 = entry.sha1,
                    kind = SteamCloudUploadCandidateKind.MODIFIED_FILE,
                )
            )

            val snapshot = SteamCloudPushCoordinator.prepareBackgroundUploadSnapshot(roots.context, plan)
            source.writeText("after", Charsets.UTF_8)

            val frozen = File(snapshot.root, "preferences/STSPlayer")
            assertEquals("before", frozen.readText(Charsets.UTF_8))
            assertEquals(listOf(entry), snapshot.localEntries)
            snapshot.delete()
            assertFalse(snapshot.root.exists())
        } finally {
            roots.root.deleteRecursively()
        }
    }

    @Test
    fun prepareBackgroundUploadSnapshot_removesInterruptedSnapshotBeforeCreatingNewOne() {
        val roots = TestRoots.create()
        try {
            val source = File(roots.stsRoot, "preferences/STSPlayer")
            source.parentFile?.mkdirs()
            source.writeText("save", Charsets.UTF_8)
            val entry = SteamCloudLocalSnapshotCollector.collect(roots.stsRoot).single()
            val plan = uploadPlan(
                candidate = SteamCloudUploadCandidate(
                    remotePath = "%GameInstall%preferences/STSPlayer",
                    localRelativePath = entry.localRelativePath,
                    rootKind = entry.rootKind,
                    fileSize = entry.fileSize,
                    lastModifiedMs = entry.lastModifiedMs,
                    sha256 = entry.sha256,
                    sha1 = entry.sha1,
                    kind = SteamCloudUploadCandidateKind.MODIFIED_FILE,
                )
            )
            val stale = File(roots.noBackupRoot, "steam-cloud-background-uploads/upload-interrupted")
            stale.mkdirs()
            File(stale, "partial").writeText("stale", Charsets.UTF_8)

            val snapshot = SteamCloudPushCoordinator.prepareBackgroundUploadSnapshot(roots.context, plan)

            assertFalse(stale.exists())
            snapshot.delete()
        } finally {
            roots.root.deleteRecursively()
        }
    }

    @Test
    fun prepareBackgroundCheckSnapshot_freezesAllManagedRoots() {
        val roots = TestRoots.create()
        try {
            writeFile(roots.stsRoot, "preferences/STSPlayer", "preferences-before")
            writeFile(roots.stsRoot, "saves/1_IRONCLAD.autosave", "save-before")

            val snapshot = SteamCloudPushCoordinator.prepareBackgroundCheckSnapshot(roots.context)
            writeFile(roots.stsRoot, "preferences/STSPlayer", "preferences-after")
            File(roots.stsRoot, "saves/1_IRONCLAD.autosave").delete()

            assertEquals(
                "preferences-before",
                File(snapshot.root, "preferences/STSPlayer").readText(Charsets.UTF_8),
            )
            assertEquals(
                "save-before",
                File(snapshot.root, "saves/1_IRONCLAD.autosave").readText(Charsets.UTF_8),
            )
            assertEquals(2, snapshot.localEntries.size)
            snapshot.delete()
        } finally {
            roots.root.deleteRecursively()
        }
    }

    private fun uploadPlan(
        candidate: SteamCloudUploadCandidate = defaultCandidate(),
        remoteDeleteCandidates: List<SteamCloudRemoteDeleteCandidate> = emptyList(),
        remoteOnlyChanges: List<SteamCloudRemoteOnlyChange> = emptyList(),
        conflicts: List<SteamCloudConflict> = emptyList(),
    ) = SteamCloudUploadPlan(
        plannedAtMs = 1L,
        baselineConfigured = true,
        uploadCandidates = listOf(candidate),
        conflicts = conflicts,
        remoteOnlyChanges = remoteOnlyChanges,
        remoteDeleteCandidates = remoteDeleteCandidates,
        warnings = emptyList(),
    )

    private fun defaultCandidate() = SteamCloudUploadCandidate(
        remotePath = "%GameInstall%preferences/STSPlayer",
        localRelativePath = "preferences/STSPlayer",
        rootKind = SteamCloudRootKind.PREFERENCES,
        fileSize = 1L,
        lastModifiedMs = 1L,
        sha256 = "sha256",
        sha1 = "sha1",
        kind = SteamCloudUploadCandidateKind.MODIFIED_FILE,
    )

    private fun deleteCandidate() = SteamCloudRemoteDeleteCandidate(
        remotePath = "%GameInstall%saves/IRONCLAD.autosave",
        localRelativePath = "saves/IRONCLAD.autosave",
        rootKind = SteamCloudRootKind.SAVES,
    )

    private fun remoteOnlyChange() = SteamCloudRemoteOnlyChange(
        localRelativePath = "preferences/STSPlayer",
        rootKind = SteamCloudRootKind.PREFERENCES,
        kind = SteamCloudRemoteOnlyChangeKind.MODIFIED_REMOTE_FILE,
        currentRemote = null,
        baselineRemote = null,
    )

    private fun writeFile(root: File, relativePath: String, content: String) {
        val target = File(root, relativePath.replace('/', File.separatorChar))
        target.parentFile?.mkdirs()
        target.writeText(content, Charsets.UTF_8)
    }

    private class TestRoots private constructor(
        val root: File,
        val context: Context,
        val stsRoot: File,
        val noBackupRoot: File,
    ) {
        companion object {
            fun create(): TestRoots {
                val root = Files.createTempDirectory("steam-cloud-background-snapshot").toFile()
                val files = File(root, "files").apply { mkdirs() }
                val external = File(root, "external").apply { mkdirs() }
                val noBackup = File(root, "no-backup").apply { mkdirs() }
                val context = object : ContextWrapper(Application()) {
                    override fun getApplicationContext(): Context = this

                    override fun getFilesDir(): File = files

                    override fun getNoBackupFilesDir(): File = noBackup

                    override fun getExternalFilesDir(type: String?): File = external

                    override fun getPackageName(): String = "io.stamethyst.test"
                }
                return TestRoots(root, context, File(external, "sts"), noBackup)
            }
        }
    }
}
