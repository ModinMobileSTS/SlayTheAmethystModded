package io.stamethyst.ui.workshop

import io.stamethyst.backend.workshop.WorkshopDownloadTaskStatus
import io.stamethyst.backend.workshop.WorkshopItemDetails
import io.stamethyst.backend.workshop.WorkshopItemSummary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkshopDownloadStateRefreshTest {
    @Test fun completionRefreshesInstalledMetadataEvenWithAnotherActiveTask() {
        val before = listOf(task(1u, WorkshopDownloadTaskStatus.Downloading), task(2u, WorkshopDownloadTaskStatus.Queued))
        assertTrue(hasSettledWorkshopDownloads(before, listOf(
            task(1u, WorkshopDownloadTaskStatus.Completed), task(2u, WorkshopDownloadTaskStatus.Downloading))))
    }

    @Test fun lightweightReadDetectsFinishedTasksOmittedFromDiskResults() {
        val before = listOf(task(1u, WorkshopDownloadTaskStatus.Downloading), task(2u, WorkshopDownloadTaskStatus.Queued))
        assertTrue(hasSettledWorkshopDownloads(before, listOf(task(2u, WorkshopDownloadTaskStatus.Downloading))))
    }

    @Test fun cancellationOrRemovalAlsoRefreshesButProgressPauseAndRetryDoNot() {
        val before = listOf(task(1u, WorkshopDownloadTaskStatus.Downloading))
        assertTrue(hasSettledWorkshopDownloads(before, listOf(task(1u, WorkshopDownloadTaskStatus.Cancelled))))
        assertTrue(hasSettledWorkshopDownloads(before, emptyList()))
        for (status in listOf(WorkshopDownloadTaskStatus.Downloading, WorkshopDownloadTaskStatus.Paused,
            WorkshopDownloadTaskStatus.Failed, WorkshopDownloadTaskStatus.Queued)) {
            assertFalse(hasSettledWorkshopDownloads(before, listOf(task(1u, status))))
        }
    }

    @Test fun finishedHistoryDoesNotContinuouslyTriggerRefreshes() {
        val history = listOf(task(1u, WorkshopDownloadTaskStatus.Completed), task(2u, WorkshopDownloadTaskStatus.Cancelled))
        assertFalse(hasSettledWorkshopDownloads(history, history))
        assertFalse(hasSettledWorkshopDownloads(history, emptyList()))
        assertFalse(hasSettledWorkshopDownloads(emptyList(), listOf(task(3u, WorkshopDownloadTaskStatus.Queued))))
    }

    private fun task(id: ULong, status: WorkshopDownloadTaskStatus) = WorkshopDownloadTaskUi(
        publishedFileId = id, title = "Mod $id", status = status, message = "", updatedAtMillis = 1,
        details = WorkshopItemDetails(summary = WorkshopItemSummary(
            appId = 646570u, publishedFileId = id, title = "Mod $id", previewUrl = "", description = "")),
    )
}
