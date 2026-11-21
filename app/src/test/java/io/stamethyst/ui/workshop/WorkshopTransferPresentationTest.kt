package io.stamethyst.ui.workshop

import io.stamethyst.backend.workshop.WorkshopDownloadTaskStatus as Status
import io.stamethyst.backend.workshop.WorkshopItemDetails
import io.stamethyst.backend.workshop.WorkshopItemSummary
import org.junit.Assert.*
import org.junit.Test

class WorkshopTransferPresentationTest {
    private fun task(id: Int, status: Status = Status.Downloading): WorkshopDownloadTaskUi {
        val key = id.toULong()
        val title = "Mod $id"
        return WorkshopDownloadTaskUi(key, title, status, "", id.toLong(), WorkshopItemDetails(
            summary = WorkshopItemSummary(appId = 646570u, publishedFileId = key, title = title, previewUrl = "", description = ""),
        ))
    }

    @Test fun parallelWorkersKeepSeparateCardsAndDoNotSwapOnProgress() {
        val tasks = listOf(task(8), task(3), task(7, Status.Queued), task(6, Status.Resolving),
            task(2, Status.Failed), task(5, Status.Queued), task(4, Status.Completed), task(9, Status.Paused))
        val before = downloadSections(tasks)
        assertEquals(listOf(3uL, 6uL, 8uL, 9uL), before.transfers.map { it.publishedFileId })
        assertEquals(listOf(5uL, 7uL), before.queue.map { it.publishedFileId })
        assertEquals(listOf(2uL), before.attention.map { it.publishedFileId })
        assertEquals(listOf(4uL), before.history.map { it.publishedFileId })
        val after = downloadSections(tasks.reversed().map { it.copy(updatedAtMillis = 100 - it.updatedAtMillis) })
        assertEquals(before.transfers.map { it.title }, after.transfers.map { it.title })
    }

    @Test fun simultaneousDownloadsUseIndependentPayloadAndWriteCounters() {
        val sample = TransferSampler()
        val a = task(1).copy(downloadedBytes = 900, receivedBytes = 100, diskWriteBytes = 200)
        val b = task(2).copy(receivedBytes = 0, diskWriteBytes = 0)
        sample.sample(listOf(a, b), 0)
        // Reusing cached chunks increases logical progress, but not received or written bytes.
        val cached = a.copy(downloadedBytes = 1900)
        val writing = b.copy(receivedBytes = 1200, diskWriteBytes = 3200)
        val traces = sample.sample(listOf(writing, cached), 2000)
        assertEquals(TransferRates(0, 0), traces.getValue(1uL).samples.single())
        assertEquals(TransferRates(600, 1600), traces.getValue(2uL).samples.single())
        assertEquals(600L, traces.getValue(2uL).peak)
        val idle = sample.sample(listOf(writing, cached.copy(status = Status.Paused)), 3000)
        assertEquals(traces[1uL], idle[1uL]) // pause freezes this card only
        assertEquals(TransferRates(0, 0), idle.getValue(2uL).samples.last())
        assertEquals(600L, idle.getValue(2uL).peak)
    }

    @Test fun restartClearsOldSamplesAndUnknownCountersAreNotReportedAsZero() {
        val sample = TransferSampler()
        val old = task(1).copy(receivedBytes = 100, diskWriteBytes = 200)
        sample.sample(listOf(old), 0)
        sample.sample(listOf(old.copy(receivedBytes = 900)), 1000)
        val restarted = old.copy(receivedBytes = 0, diskWriteBytes = 0)
        assertEquals(TransferTrace(), sample.sample(listOf(restarted), 2000)[1uL])
        sample.sample(emptyList(), 3000)
        sample.sample(listOf(task(1)), 4000)
        assertEquals(TransferRates(null, null), sample.sample(listOf(task(1)), 5000)[1uL]?.samples?.single())
    }
}
