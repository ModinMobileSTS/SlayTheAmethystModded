package io.stamethyst.backend.workshop

import kotlinx.coroutines.CancellationException

internal data class WorkshopSubscriptionSyncReport(
    val queuedCount: Int,
    val failedCount: Int,
)

internal class WorkshopSubscriptionSyncEngine(private val appId: UInt) {
    suspend fun sync(
        loadPage: suspend (Int) -> WorkshopBrowseResult,
        shouldEnqueue: suspend (WorkshopItemSummary) -> Boolean,
        enqueue: suspend (WorkshopItemSummary) -> Unit,
    ): WorkshopSubscriptionSyncReport {
        val seen = HashSet<Pair<UInt, ULong>>()
        var page = 1
        var queuedCount = 0
        var failedCount = 0
        while (true) {
            val result = loadPage(page)
            var hasNewIds = false
            for (item in result.items) {
                if (!seen.add(item.appId to item.publishedFileId)) continue
                hasNewIds = true
                val report = enqueueItem(item, shouldEnqueue, enqueue)
                queuedCount += report.queuedCount
                failedCount += report.failedCount
            }
            if (!result.hasNextPage) break
            check(hasNewIds) { "Subscription page $page has no new item IDs but has a next page" }
            page++
        }
        return WorkshopSubscriptionSyncReport(queuedCount, failedCount)
    }

    suspend fun enqueueItem(
        item: WorkshopItemSummary,
        shouldEnqueue: suspend (WorkshopItemSummary) -> Boolean,
        enqueue: suspend (WorkshopItemSummary) -> Unit,
    ): WorkshopSubscriptionSyncReport {
        if (item.appId != appId) return WorkshopSubscriptionSyncReport(0, 0)
        return try {
            if (shouldEnqueue(item)) {
                enqueue(item)
                WorkshopSubscriptionSyncReport(1, 0)
            } else {
                WorkshopSubscriptionSyncReport(0, 0)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            WorkshopSubscriptionSyncReport(0, 1)
        }
    }
}
