package io.stamethyst.backend.workshop

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import io.stamethyst.ui.workshop.WorkshopModDownloadState
import io.stamethyst.ui.workshop.resolveWorkshopModDownloadState

class WorkshopPendingInstallTest {
    @Test fun completeUpdatePersistsAlongsideOldInstallationAndIsNeverCleanedAsASuccess() = fixture { f ->
        val candidate = f.old.withPendingInstall(f.details, listOf(f.artifact))
        f.metadata.upsert(candidate)
        val stored = WorkshopMetadataCodec.fromJson(WorkshopMetadataCodec.toJson(f.metadata.list().single()))
        assertEquals(f.old.localJarPath, stored.localJarPath)
        assertEquals(100L, stored.updatedAtMillis)
        assertEquals(listOf("new.jar"), stored.pendingInstall!!.localJarPaths)
        assertEquals(200L, stored.pendingInstall.updatedAtMillis)
        assertEquals(WorkshopResolvedModStateKind.UpdatePendingInstall, WorkshopModStateResolver.resolve(stored).kind)
        assertFalse(stored.canCleanDownloadedJars())
        assertEquals(WorkshopModDownloadState.UpdatePendingInstall,
            resolveWorkshopModDownloadState(f.details.summary, listOf(stored), emptyList()))
        assertEquals("old-installed", f.oldFile.readText())
        assertTrue(f.newFile.isFile)
    }

    @Test fun onlyActiveTasksHidePendingInstallation() = fixture { f ->
        val record = f.old.withPendingInstall(f.details, listOf(f.artifact))
        WorkshopDownloadTaskStatus.entries.forEach { status ->
            val resolved = WorkshopModStateResolver.resolve(record, status, "Task $status")
            assertEquals(status.name,
                if (status.isActiveDownload()) WorkshopModStateResolver.resolveTaskKind(status)
                else WorkshopResolvedModStateKind.UpdatePendingInstall, resolved.kind)
        }
    }

    @Test fun failedRetryKeepsCandidateAndSuccessfulRetryPromotesItsVersionAndDependencies() = fixture { f ->
        f.metadata.upsert(f.old.withPendingInstall(f.details, listOf(f.artifact)))
        f.metadata.updateState(646570u, 42u, WorkshopModCardState.Downloading, "patching")
        f.metadata.markImportFailed(646570u, 42u, "patch failed")
        val failed = f.metadata.list().single()
        assertEquals(f.old.localJarPath, failed.localJarPath)
        assertEquals("patch failed", failed.statusText)
        assertNotNull(failed.pendingInstall)
        assertFalse(failed.canCleanDownloadedJars())
        assertEquals(listOf("new.jar"), failed.installationJarPaths())
        assertEquals(200L, failed.installationDetails().summary.updatedAtMillis)
        assertEquals(f.details.dependencies, failed.installationDetails().dependencies)
        val installedNew = File(f.root, "library/new.jar").apply { parentFile.mkdirs(); writeText("installed-new") }
        f.metadata.markPatched(646570u, 42u, installedNew.absolutePath, "installed")
        val success = f.metadata.list().single()
        assertNull(success.pendingInstall)
        assertEquals(200L, success.updatedAtMillis)
        assertEquals("200", success.versionText)
        assertEquals(f.details.dependencies, success.dependencies)
        assertEquals(installedNew.absolutePath, success.localJarPath)
        assertTrue(success.canCleanDownloadedJars())
    }

    @Test fun coldRecoveryRetainsCandidateEvenAfterPatchProgressHasResetBelowOneHundred() = fixture { f ->
        f.metadata.upsert(f.old.withPendingInstall(f.details, listOf(f.artifact)).copy(
            cardState = WorkshopModCardState.Downloading, statusText = "patching 31%"))
        val task = f.task.copy(progressPercent = 31, completedFiles = null, totalFiles = null)
        f.tasks.upsert(task)
        assertTrue(WorkshopInterruptedDownloadRecovery.recoverFinishedTransferIfPossible(f.context, f.metadata, f.tasks, task))
        val recovered = WorkshopMetadataStore(f.context).list().single()
        assertEquals(f.old.localJarPath, recovered.localJarPath)
        assertNotNull(recovered.pendingInstall)
        assertEquals(WorkshopResolvedModStateKind.UpdatePendingInstall, WorkshopModStateResolver.resolve(recovered).kind)
        assertEquals(WorkshopDownloadTaskStatus.Completed, f.tasks.find(42u)!!.status)
        assertEquals("old-installed", f.oldFile.readText())
        assertTrue(f.newFile.isFile)
    }

    @Test fun partialTransferDoesNotBecomeAnInstallableUpdateJustBecauseAJarExists() = fixture { f ->
        f.metadata.upsert(f.old.copy(cardState = WorkshopModCardState.Downloading))
        val task = f.task.copy(progressPercent = 31, downloadedBytes = 3, totalBytes = 10)
        f.tasks.upsert(task)
        assertTrue(WorkshopInterruptedDownloadRecovery.recoverFinishedTransferIfPossible(f.context, f.metadata, f.tasks, task))
        val restored = f.metadata.list().single()
        assertNull(restored.pendingInstall)
        assertEquals(f.old.localJarPath, restored.localJarPath)
        assertNotEquals(WorkshopResolvedModStateKind.UpdatePendingInstall, WorkshopModStateResolver.resolve(restored).kind)
    }

    @Test fun pendingUpdateCanStillRecoverIfTheOldInstalledFileHasDisappeared() = fixture { f ->
        f.metadata.upsert(f.old.withPendingInstall(f.details, listOf(f.artifact)))
        assertTrue(f.oldFile.delete())
        val task = f.task.copy(progressPercent = 31)
        f.tasks.upsert(task)
        assertTrue(recoverStoppedPendingInstall(f.context, f.metadata, f.tasks, task))
        assertNotNull(f.metadata.list().single().pendingInstall)
        assertEquals(WorkshopResolvedModStateKind.UpdatePendingInstall,
            WorkshopModStateResolver.resolve(f.metadata.list().single()).kind)
        assertTrue(f.newFile.isFile)
    }

    @Test fun liveManualImportIsNotRecoveredButAStoppedWorkerCanRecoverImmediately() = fixture { f ->
        f.metadata.upsert(f.old.withPendingInstall(f.details, listOf(f.artifact)))
        f.tasks.upsert(f.task)
        WorkshopPendingInstallOperations.started(42u)
        try {
            assertFalse(recoverStoppedPendingInstall(f.context, f.metadata, f.tasks, f.task))
            assertEquals(WorkshopDownloadTaskStatus.Downloading, f.tasks.find(42u)!!.status)
        } finally { WorkshopPendingInstallOperations.finished(42u) }
        assertTrue(recoverStoppedPendingInstall(f.context, f.metadata, f.tasks, f.task))
        assertEquals(WorkshopDownloadTaskStatus.Completed, f.tasks.find(42u)!!.status)
        assertNotNull(f.metadata.list().single().pendingInstall)
    }

    @Test fun missingCandidateFallsBackToUpdateWithoutRemovingOldInstallation() = fixture { f ->
        f.metadata.upsert(f.old.withPendingInstall(f.details, listOf(f.artifact)))
        assertTrue(f.newFile.delete())
        f.metadata.markMissingFiles()
        val record = f.metadata.list().single()
        assertNull(record.pendingInstall)
        assertEquals(WorkshopModCardState.UpdateAvailable, record.cardState)
        assertEquals(f.old.localJarPath, record.localJarPath)
        assertEquals("old-installed", f.oldFile.readText())
    }

    @Test fun missingCandidateSettlesAnInterruptedTaskInsteadOfLeavingItDownloadingForever() = fixture { f ->
        f.metadata.upsert(f.old.withPendingInstall(f.details, listOf(f.artifact)))
        f.tasks.upsert(f.task)
        assertTrue(f.newFile.delete())
        assertTrue(recoverStoppedPendingInstall(f.context, f.metadata, f.tasks, f.task))
        assertNull(f.metadata.list().single().pendingInstall)
        assertEquals(f.old.localJarPath, f.metadata.list().single().localJarPath)
        assertEquals(WorkshopDownloadTaskStatus.Failed, f.tasks.find(42u)!!.status)
        assertTrue(f.oldFile.isFile)
    }

    @Test fun committedInstallIsNotOfferedAgainWhenProcessStopsBeforeDownloadCleanup() = fixture { f ->
        f.metadata.upsert(f.old.withPendingInstall(f.details, listOf(f.artifact)))
        f.metadata.markPatched(646570u, 42u, f.oldFile.absolutePath, "installed new version")
        val task = f.task.copy(progressPercent = 100, completedFiles = 1, totalFiles = 1)
        f.tasks.upsert(task)
        assertTrue(WorkshopInterruptedDownloadRecovery.recoverFinishedTransferIfPossible(f.context, f.metadata, f.tasks, task))
        assertNull(f.metadata.list().single().pendingInstall)
        assertEquals(200L, f.metadata.list().single().updatedAtMillis)
        assertEquals(WorkshopDownloadTaskStatus.Completed, f.tasks.find(42u)!!.status)
    }

    @Test fun updateCheckDoesNotOverwritePendingCandidateOrItsFailureExplanation() = fixture { f ->
        val candidate = f.old.withPendingInstall(f.details, listOf(f.artifact), "patch failed")
        f.metadata.upsert(candidate)
        for (hasUpdate in listOf(true, false)) {
            f.metadata.applyUpdateCheckResults(listOf(WorkshopUpdateCheckResult(646570u, 42u, hasUpdate, 300, 100, "300")))
            assertEquals(candidate, f.metadata.list().single())
        }
    }

    @Test fun oldMetadataWithoutPendingFieldStillLoadsAndEmptyPendingPathsAreRejected() = fixture { f ->
        val json = WorkshopMetadataCodec.toJson(f.old)
        json.remove("pendingInstall")
        assertEquals(f.old, WorkshopMetadataCodec.fromJson(json))
        json.put("pendingInstall", JSONObject().put("updatedAtMillis", 200))
        assertNull(WorkshopMetadataCodec.fromJson(json).pendingInstall)
    }

    @Test fun patchOnlyUpgradeWithSameRemoteTimestampStillRequiresInstallation() = fixture { f ->
        val candidate = f.old.withPendingInstall(f.details.copy(summary = f.details.summary.copy(updatedAtMillis = 100)), listOf(f.artifact))
        assertEquals(WorkshopResolvedModStateKind.UpdatePendingInstall, WorkshopModStateResolver.resolve(candidate).kind)
        assertFalse(candidate.canCleanDownloadedJars())
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val cwd = File(requireNotNull(System.getProperty("user.dir")))
        val repo = if (File(cwd, "AGENTS.md").isFile) cwd else cwd.parentFile
        val base = File(repo, "agent-tmp/workshop-pending-install-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(base.toPath(), "fixture-").toFile()
        try { block(Fixture(root)) } finally { root.deleteRecursively() }
    }

    private class Fixture(val root: File) {
        val context: Context = object : ContextWrapper(Application()) {
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getApplicationContext(): Context = this
            override fun getPackageName(): String = "io.stamethyst.test.pending"
        }
        val metadata = WorkshopMetadataStore(context)
        val tasks = WorkshopDownloadTaskStore(context)
        val oldFile = File(root, "library/old.jar").apply { parentFile.mkdirs(); writeText("old-installed") }
        val newFile = File(context.filesDir, "workshop/646570/42/new.jar").apply { parentFile.mkdirs(); writeText("downloaded-new") }
        val old = WorkshopInstalledModRecord(646570u, 42u, "Mod", "", "", "100", 100, 110,
            oldFile.absolutePath, cardState = WorkshopModCardState.ImportedPatched)
        val details = WorkshopItemDetails(WorkshopItemSummary(42u, 646570u, "Mod", "", "", updatedAtMillis = 200),
            dependencies = listOf(WorkshopItemSummary(99u, 646570u, "New dependency", "", "")))
        val artifact = WorkshopDownloadedArtifact("new.jar", newFile.length(), newFile.lastModified())
        val task = WorkshopDownloadTaskRecord(42u, "Mod", WorkshopDownloadTaskStatus.Downloading, "patching", details = details)
    }
}
