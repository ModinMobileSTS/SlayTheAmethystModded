package io.stamethyst.ui.main

import io.stamethyst.backend.workshop.WorkshopDownloadTaskStatus
import io.stamethyst.backend.workshop.WorkshopItemDetails
import io.stamethyst.backend.workshop.WorkshopItemSummary
import io.stamethyst.model.ModItemUi
import io.stamethyst.model.WorkshopModState
import io.stamethyst.model.WorkshopModUi
import io.stamethyst.ui.workshop.WorkshopDownloadTaskUi
import io.stamethyst.ui.workshop.WorkshopModDownloadState
import io.stamethyst.ui.workshop.resolveWorkshopModDownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkshopModCardUpdatesTest {
    @Test fun taskTransitionsMatchTheMarketAndCarryCurrentProgressAndMessage() {
        val states = mapOf(
            WorkshopDownloadTaskStatus.Queued to WorkshopModState.Queued,
            WorkshopDownloadTaskStatus.Resolving to WorkshopModState.Downloading,
            WorkshopDownloadTaskStatus.Downloading to WorkshopModState.Downloading,
            WorkshopDownloadTaskStatus.Pausing to WorkshopModState.Downloading,
            WorkshopDownloadTaskStatus.Paused to WorkshopModState.DownloadPaused,
            WorkshopDownloadTaskStatus.Cancelling to WorkshopModState.Cancelling,
            WorkshopDownloadTaskStatus.Failed to WorkshopModState.DownloadFailed,
        )
        var cards = listOf(mod(1u, WorkshopModState.ImportedUnpatched))
        states.forEach { (status, expectedState) ->
            val task = task(1u, status).copy(message = "Current $status", progressPercent = 42)
            val update = updateWorkshopModCards(cards, listOf(task))
            val workshop = update.mods.single().workshop!!
            assertEquals(expectedState, workshop.state)
            assertEquals("Current $status", workshop.statusText)
            assertEquals(42, workshop.downloadProgressPercent)
            val marketState = resolveWorkshopModDownloadState(task.details.summary, emptyList(), listOf(task))
            assertEquals(when (expectedState) {
                WorkshopModState.DownloadPaused -> WorkshopModDownloadState.Paused
                else -> WorkshopModDownloadState.valueOf(expectedState.name)
            }, marketState)
            assertEquals(cards.single().workshop!!.state in setOf(WorkshopModState.Queued, WorkshopModState.Downloading,
                WorkshopModState.Cancelling) && !update.hasActiveDownloads, update.needsInstalledModsRefresh)
            cards = update.mods
        }
    }

    @Test fun oneCompletedTransferRefreshesInstallationWhileOtherTransfersContinue() {
        val completedCard = mod(1u, WorkshopModState.Downloading)
        val update = updateWorkshopModCards(
            listOf(completedCard, mod(2u, WorkshopModState.Downloading)),
            // The lightweight store excludes the completed task, not just when the queue is empty.
            listOf(task(2u, WorkshopDownloadTaskStatus.Downloading).copy(progressPercent = 75)),
        )
        assertTrue(update.hasActiveDownloads)
        assertTrue(update.needsInstalledModsRefresh)
        assertSame(completedCard, update.mods.first())
        assertEquals(75, update.mods.last().workshop!!.downloadProgressPercent)
    }

    @Test fun terminalTasksDoNotRemoveCardsBeforeTheInstallationScan() {
        for (status in listOf(WorkshopDownloadTaskStatus.Completed, WorkshopDownloadTaskStatus.Cancelled)) {
            for (installed in listOf(false, true)) {
                val card = mod(1u, WorkshopModState.Downloading).copy(installed = installed)
                val update = updateWorkshopModCards(listOf(card), listOf(task(1u, status)))
                assertSame(card, update.mods.single())
                assertFalse(update.hasActiveDownloads)
                assertTrue(update.needsInstalledModsRefresh)
            }
        }
    }

    @Test fun refreshedUninstalledJarKeepsItsInstallStateWithoutRepeatedScans() {
        val downloaded = mod(1u, WorkshopModState.ImportedUnpatched)
        val installed = mod(2u, WorkshopModState.ImportedPatched).copy(installed = true)
        for (tasks in listOf(emptyList(), listOf(task(1u, WorkshopDownloadTaskStatus.Completed)))) {
            val update = updateWorkshopModCards(listOf(downloaded, installed), tasks)
            assertSame(downloaded, update.mods.first())
            assertSame(installed, update.mods.last())
            assertFalse(update.needsInstalledModsRefresh)
        }
    }

    @Test fun newTasksAreAddedOnceAndFinishedHistoryIsNotAddedAsAnUninstalledMod() {
        val tasks = listOf(task(1u, WorkshopDownloadTaskStatus.Queued),
            task(2u, WorkshopDownloadTaskStatus.Paused), task(3u, WorkshopDownloadTaskStatus.Completed))
        val first = updateWorkshopModCards(emptyList(), tasks)
        val second = updateWorkshopModCards(first.mods, tasks)
        assertEquals(listOf(1uL, 2uL), first.mods.map { it.workshop!!.publishedFileId })
        assertEquals(first.mods, second.mods)
        assertTrue(second.hasActiveDownloads)
        assertFalse(second.needsInstalledModsRefresh)
    }

    @Test fun progressUpdatesPreserveLocalModSelectionAndMetadata() {
        val local = mod(1u, WorkshopModState.ImportedPatched).copy(
            installed = true, enabled = true, favorite = true, alias = "My mod",
            storagePath = "/mods/local.jar", explicitPriority = 3,
        )
        val task = task(1u, WorkshopDownloadTaskStatus.Downloading).copy(progressPercent = 64, fileSizeBytes = 900)
        val updated = updateWorkshopModCards(listOf(local), listOf(task)).mods.single()
        assertEquals(local.copy(fileSizeBytes = 900, workshop = local.workshop!!.copy(
            state = WorkshopModState.Downloading, statusText = task.message, downloadProgressPercent = 64,
        )), updated)
    }

    @Test fun blankNewMessagesDoNotRetainAnEarlierFailure() {
        val card = mod(1u, WorkshopModState.DownloadFailed).let { it.copy(
            workshop = it.workshop!!.copy(statusText = "Old failure")) }
        val updated = updateWorkshopModCards(listOf(card), listOf(
            task(1u, WorkshopDownloadTaskStatus.Queued).copy(message = ""))).mods.single()
        assertEquals("等待下载", updated.workshop!!.statusText)
    }

    @Test fun pendingUpdateDoesNotRegressToDownloadFailureDuringLightweightPolling() {
        val pending = mod(1u, WorkshopModState.UpdatePendingInstall).let { it.copy(installed = true,
            workshop = it.workshop!!.copy(hasPendingInstall = true)) }
        for (status in listOf(WorkshopDownloadTaskStatus.Failed, WorkshopDownloadTaskStatus.Paused)) {
            val update = updateWorkshopModCards(listOf(pending), listOf(task(1u, status)))
            assertEquals(WorkshopModState.UpdatePendingInstall, update.mods.single().workshop!!.state)
            assertTrue(update.mods.single().showsInstallControl())
            assertFalse(update.mods.single().showsEnableControl())
        }
    }

    @Test fun oneFailedPatchRefreshesMetadataEvenWhileAnotherDownloadContinues() {
        val update = updateWorkshopModCards(listOf(mod(1u, WorkshopModState.Downloading)), listOf(
            task(1u, WorkshopDownloadTaskStatus.Failed), task(2u, WorkshopDownloadTaskStatus.Downloading)))
        assertTrue(update.hasActiveDownloads)
        assertTrue(update.needsInstalledModsRefresh)
    }

    private fun mod(id: ULong, state: WorkshopModState) = ModItemUi(
        modId = "workshop:$id", manifestModId = "workshop:$id", storagePath = "workshop:646570:$id",
        name = "Mod $id", version = "1", description = "Description", dependencies = emptyList(),
        required = false, installed = false, enabled = false, explicitPriority = null, effectivePriority = null,
        workshop = WorkshopModUi(646570u, id, state, localJarPath = "mod.jar"),
    )

    private fun task(id: ULong, status: WorkshopDownloadTaskStatus) = WorkshopDownloadTaskUi(
        publishedFileId = id, title = "Mod $id", status = status, message = "Task $status", updatedAtMillis = 1,
        details = WorkshopItemDetails(summary = WorkshopItemSummary(
            appId = 646570u, publishedFileId = id, title = "Mod $id", previewUrl = "", description = "")),
    )
}
