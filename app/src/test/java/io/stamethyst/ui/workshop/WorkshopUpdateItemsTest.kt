package io.stamethyst.ui.workshop

import io.stamethyst.backend.workshop.WorkshopInstalledModRecord
import io.stamethyst.backend.workshop.WorkshopModCardState
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkshopUpdateItemsTest {
    @Test
    fun updatesExcludeCurrentAndLauncherManagedItemsAndDeduplicateWorkshopIds() {
        val update = record(42UL, "Local mod", WorkshopModCardState.UpdateAvailable)
        assertEquals(
            listOf(update),
            availableWorkshopUpdates(listOf(
                record(43UL, "Current", WorkshopModCardState.ImportedPatched),
                update,
                update.copy(localJarPath = "/another.jar"),
                record(1605833019UL, "Managed component", WorkshopModCardState.UpdateAvailable),
                record(44UL, "BaseMod", WorkshopModCardState.UpdateAvailable),
            )),
        )
    }

    @Test
    fun summaryUsesStoredDescriptionAndMetadata() {
        val record = record(42UL, "Local mod", WorkshopModCardState.UpdateAvailable)
        val summary = record.toWorkshopItemSummary()
        assertEquals(record.title, summary.title)
        assertEquals("Local description", summary.description)
        assertEquals(record.previewUrl, summary.previewUrl)
        assertEquals(record.updatedAtMillis, summary.updatedAtMillis)
    }

    @Test
    fun retainedCardStopsOfferingUpdateAfterNewVersionIsInstalled() {
        val oldRecord = record(42UL, "Local mod", WorkshopModCardState.UpdateAvailable)
        val updatedRecords = listOf(oldRecord.copy(
            cardState = WorkshopModCardState.ImportedPatched,
            updatedAtMillis = 3_000L,
            localJarPath = "/updated.jar",
        ))
        assertEquals(emptyList<WorkshopInstalledModRecord>(), availableWorkshopUpdates(updatedRecords))
        assertEquals(
            WorkshopModDownloadState.Downloaded,
            resolveWorkshopModDownloadState(oldRecord.toWorkshopItemSummary(), updatedRecords, downloadTasks = emptyList()),
        )
    }

    private fun record(id: ULong, title: String, state: WorkshopModCardState) = WorkshopInstalledModRecord(
        appId = 646570u,
        publishedFileId = id,
        title = title,
        description = "Local description",
        previewUrl = "https://example.com/preview.png",
        versionText = "1.0",
        updatedAtMillis = 1_000L,
        installedAtMillis = 2_000L,
        localJarPath = "/local.jar",
        cardState = state,
    )
}
