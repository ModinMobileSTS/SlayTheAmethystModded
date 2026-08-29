package io.stamethyst.backend.steamcloud

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamCloudPullCoordinatorTest {
    @Test
    fun applyRemoteOnlyChanges_rollbackRestoresReplacedFile() {
        val root = Files.createTempDirectory("steam-cloud-pull-merge-rollback").toFile()
        try {
            val stsRoot = File(root, "sts")
            val stagingRoot = File(root, "staging")
            val rollbackRoot = File(root, "rollback")
            writeFile(stsRoot, "preferences/STSPlayer", "local")
            writeFile(stagingRoot, "preferences/STSPlayer", "remote")

            val transaction = SteamCloudPullCoordinator.applyRemoteOnlyChanges(
                stagingRoot = stagingRoot,
                stsRoot = stsRoot,
                downloadedEntries = listOf(manifestEntry("preferences/STSPlayer")),
                deletedEntries = emptyList(),
                rollbackRoot = rollbackRoot,
            )

            assertEquals("remote", readFile(stsRoot, "preferences/STSPlayer"))
            assertTrue(transaction.rollback().failures.isEmpty())
            assertEquals("local", readFile(stsRoot, "preferences/STSPlayer"))
            assertFalse(rollbackRoot.walkTopDown().any { it.isFile })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun applyStaging_rollbackRemovesRootsThatDidNotExistBeforeApply() {
        val root = Files.createTempDirectory("steam-cloud-pull-root-rollback").toFile()
        try {
            val stsRoot = File(root, "sts")
            val stagingRoot = File(root, "staging")
            val rollbackRoot = File(root, "rollback")
            writeFile(stagingRoot, "preferences/STSPlayer", "remote")

            val transaction = SteamCloudPullCoordinator.applyStaging(
                stagingRoot = stagingRoot,
                stsRoot = stsRoot,
                replaceRoots = setOf(SteamCloudRootKind.PREFERENCES),
                rollbackRoot = rollbackRoot,
                preserveLocalRelativePaths = emptySet(),
            )

            assertEquals("remote", readFile(stsRoot, "preferences/STSPlayer"))
            assertTrue(transaction.rollback().failures.isEmpty())
            assertFalse(File(stsRoot, "preferences").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun applyRemoteOnlyChanges_cancellationStopsBeforeMutation() {
        val root = Files.createTempDirectory("steam-cloud-pull-cancel").toFile()
        try {
            val stsRoot = File(root, "sts")
            val stagingRoot = File(root, "staging")
            val rollbackRoot = File(root, "rollback")
            writeFile(stsRoot, "preferences/STSPlayer", "local")
            writeFile(stagingRoot, "preferences/STSPlayer", "remote")

            var continueCalls = 0
            try {
                SteamCloudPullCoordinator.applyRemoteOnlyChanges(
                    stagingRoot = stagingRoot,
                    stsRoot = stsRoot,
                    downloadedEntries = listOf(manifestEntry("preferences/STSPlayer")),
                    deletedEntries = emptyList(),
                    rollbackRoot = rollbackRoot,
                    shouldContinue = {
                        continueCalls++
                        false
                    },
                )
                throw AssertionError("Expected cancellation")
            } catch (_: CancellationException) {
                // Expected: the live file must remain untouched.
            }

            assertTrue(continueCalls > 0)
            assertEquals("local", readFile(stsRoot, "preferences/STSPlayer"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun applyRemoteOnlyChanges_rollbackCanBeCalledMoreThanOnce() {
        val root = Files.createTempDirectory("steam-cloud-pull-rollback-idempotent").toFile()
        try {
            val stsRoot = File(root, "sts")
            val stagingRoot = File(root, "staging")
            val rollbackRoot = File(root, "rollback")
            writeFile(stsRoot, "preferences/STSPlayer", "local")
            writeFile(stagingRoot, "preferences/STSPlayer", "remote")

            val transaction = SteamCloudPullCoordinator.applyRemoteOnlyChanges(
                stagingRoot = stagingRoot,
                stsRoot = stsRoot,
                downloadedEntries = listOf(manifestEntry("preferences/STSPlayer")),
                deletedEntries = emptyList(),
                rollbackRoot = rollbackRoot,
            )

            val first = transaction.rollback()
            val second = transaction.rollback()
            assertTrue(first === second)
            assertEquals("local", readFile(stsRoot, "preferences/STSPlayer"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun applyStaging_cancellationAfterMutationRollsBackLiveRoot() {
        val root = Files.createTempDirectory("steam-cloud-pull-cancel-after-apply").toFile()
        try {
            val stsRoot = File(root, "sts")
            val stagingRoot = File(root, "staging")
            val rollbackRoot = File(root, "rollback")
            writeFile(stsRoot, "preferences/STSPlayer", "local")
            writeFile(stagingRoot, "preferences/STSPlayer", "remote")

            var continueCalls = 0
            try {
                SteamCloudPullCoordinator.applyStaging(
                    stagingRoot = stagingRoot,
                    stsRoot = stsRoot,
                    replaceRoots = linkedSetOf(SteamCloudRootKind.PREFERENCES, SteamCloudRootKind.SAVES),
                    rollbackRoot = rollbackRoot,
                    preserveLocalRelativePaths = emptySet(),
                    shouldContinue = {
                        continueCalls++
                        continueCalls < 7
                    },
                )
                throw AssertionError("Expected cancellation")
            } catch (_: CancellationException) {
                // Expected: the first root was applied, then restored before the failure escaped.
            }

            assertEquals("local", readFile(stsRoot, "preferences/STSPlayer"))
            assertTrue(continueCalls >= 7)
            assertFalse(SteamCloudPullCoordinator.hasRecoveryData(rollbackRoot))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun hasRecoveryData_ignoresEmptyRollbackDirectories() {
        val root = Files.createTempDirectory("steam-cloud-pull-empty-recovery").toFile()
        try {
            val emptyRollbackRoot = File(root, "empty").apply {
                File(this, "preferences/nested").mkdirs()
            }
            assertFalse(SteamCloudPullCoordinator.hasRecoveryData(emptyRollbackRoot))

            val populatedRollbackRoot = File(root, "populated").apply {
                mkdirs()
            }
            writeFile(populatedRollbackRoot, "preferences/STSPlayer", "recovery")
            assertTrue(SteamCloudPullCoordinator.hasRecoveryData(populatedRollbackRoot))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun validateRemoteMergePlan_acceptsUnchangedRemoteOnlyPlan() {
        val fixture = remoteMergeFixture(remoteSha1 = REMOTE_SHA1)

        SteamCloudPullCoordinator.validateRemoteMergePlan(
            expectedPlan = fixture.plan,
            currentPlan = fixture.plan,
            currentSnapshot = fixture.snapshot,
        )
    }

    @Test
    fun validateRemoteMergePlan_rejectsRemoteManifestMutation() {
        val original = remoteMergeFixture(remoteSha1 = REMOTE_SHA1)
        val changed = remoteMergeFixture(remoteSha1 = OTHER_SHA1)

        val error = org.junit.Assert.assertThrows(SteamCloudStalePlanException::class.java) {
            SteamCloudPullCoordinator.validateRemoteMergePlan(
                expectedPlan = original.plan,
                currentPlan = changed.plan,
                currentSnapshot = changed.snapshot,
            )
        }

        assertTrue(error.message.orEmpty().contains("remote manifest changed"))
    }

    @Test
    fun validateRemoteMergePlan_rejectsLocalEditOnRemoteOnlyPath() {
        val fixture = remoteMergeFixture(remoteSha1 = REMOTE_SHA1)
        val conflictedPlan = SteamCloudDiffPlanner.buildUploadPlan(
            plannedAtMs = 3L,
            currentLocalEntries = listOf(
                localEntry(sha256 = "locally-edited", sha1 = OTHER_SHA1)
            ),
            currentRemoteSnapshot = fixture.snapshot,
            baseline = fixture.baseline,
        )

        val error = org.junit.Assert.assertThrows(SteamCloudStalePlanException::class.java) {
            SteamCloudPullCoordinator.validateRemoteMergePlan(
                expectedPlan = fixture.plan,
                currentPlan = conflictedPlan,
                currentSnapshot = fixture.snapshot,
            )
        }

        assertTrue(error.message.orEmpty().contains("conflict"))
    }

    @Test
    fun validateRemoteMergePlan_rejectsDownloadWithoutSha1() {
        val fixture = remoteMergeFixture(remoteSha1 = "")

        val error = org.junit.Assert.assertThrows(SteamCloudStalePlanException::class.java) {
            SteamCloudPullCoordinator.validateRemoteMergePlan(
                expectedPlan = fixture.plan,
                currentPlan = fixture.plan,
                currentSnapshot = fixture.snapshot,
            )
        }

        assertTrue(error.message.orEmpty().contains("SHA-1"))
    }

    private fun remoteMergeFixture(remoteSha1: String): RemoteMergeFixture {
        val baselineRemote = manifestEntry(
            localRelativePath = "preferences/STSPlayer",
            rawSize = 5L,
            timestamp = 1L,
            sha1 = BASELINE_SHA1,
        )
        val baseline = SteamCloudSyncBaseline(
            syncedAtMs = 1L,
            localEntries = listOf(localEntry(sha256 = "baseline-local", sha1 = BASELINE_SHA1)),
            remoteEntries = listOf(baselineRemote),
        )
        val currentRemote = manifestEntry(
            localRelativePath = "preferences/STSPlayer",
            rawSize = 6L,
            timestamp = 2L,
            sha1 = remoteSha1,
        )
        val snapshot = SteamCloudManifestSnapshot(
            fetchedAtMs = 2L,
            fileCount = 1,
            preferencesCount = 1,
            savesCount = 0,
            entries = listOf(currentRemote),
            warnings = emptyList(),
        )
        return RemoteMergeFixture(
            baseline = baseline,
            snapshot = snapshot,
            plan = SteamCloudDiffPlanner.buildUploadPlan(
                plannedAtMs = 2L,
                currentLocalEntries = baseline.localEntries,
                currentRemoteSnapshot = snapshot,
                baseline = baseline,
            ),
        )
    }

    private fun manifestEntry(
        localRelativePath: String,
        rawSize: Long = 0L,
        timestamp: Long = 0L,
        sha1: String = "",
    ): SteamCloudManifestEntry =
        SteamCloudManifestEntry(
            remotePath = "%GameInstall%$localRelativePath",
            localRelativePath = localRelativePath,
            rootKind = if (localRelativePath.startsWith("saves/")) {
                SteamCloudRootKind.SAVES
            } else {
                SteamCloudRootKind.PREFERENCES
            },
            rawSize = rawSize,
            timestamp = timestamp,
            machineName = "test",
            persistState = "Persisted",
            sha1 = sha1,
        )

    private fun localEntry(
        sha256: String,
        sha1: String,
    ) = SteamCloudLocalFileSnapshotEntry(
        localRelativePath = "preferences/STSPlayer",
        rootKind = SteamCloudRootKind.PREFERENCES,
        fileSize = 5L,
        lastModifiedMs = 1L,
        sha256 = sha256,
        sha1 = sha1,
    )

    private fun writeFile(root: File, relativePath: String, text: String) {
        val file = File(root, relativePath.replace('/', File.separatorChar))
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    private fun readFile(root: File, relativePath: String): String =
        File(root, relativePath.replace('/', File.separatorChar)).readText()

    private data class RemoteMergeFixture(
        val baseline: SteamCloudSyncBaseline,
        val snapshot: SteamCloudManifestSnapshot,
        val plan: SteamCloudUploadPlan,
    )

    private companion object {
        const val BASELINE_SHA1 = "8B38A7B74E44B9601D1C6A20D90E868193390229"
        const val REMOTE_SHA1 = "A9993E364706816ABA3E25717850C26C9CD0D89D"
        const val OTHER_SHA1 = "0000000000000000000000000000000000000000"
    }
}
