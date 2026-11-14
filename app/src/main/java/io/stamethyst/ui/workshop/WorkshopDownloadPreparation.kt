package io.stamethyst.ui.workshop

import io.stamethyst.backend.workshop.WorkshopItemDetails
import io.stamethyst.backend.workshop.WorkshopItemSummary

/** The shared detail pipeline may finish first; only completed, current snapshots are reusable. */
internal suspend fun prepareWorkshopDownloadDetails(
    item: WorkshopItemSummary,
    awaitLoadingDetails: suspend () -> WorkshopItemDetails?,
    loadDetails: suspend () -> WorkshopItemDetails,
): WorkshopItemDetails {
    val completed = awaitLoadingDetails()
    return completed?.takeIf {
        it.summary.appId == item.appId && it.summary.publishedFileId == item.publishedFileId &&
            it.summary.updatedAtMillis >= item.updatedAtMillis
    } ?: loadDetails()
}
