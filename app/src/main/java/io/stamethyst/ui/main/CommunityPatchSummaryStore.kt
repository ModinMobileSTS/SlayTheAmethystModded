package io.stamethyst.ui.main

import io.stamethyst.backend.workshop.WorkshopItemSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/** List covers and detail cards share API metadata, including a request still running on navigation. */
internal class CommunityPatchSummaryStore(private val scope: CoroutineScope) {
    private val lock = Any()
    private val summaries = LinkedHashMap<Pair<UInt, ULong>, WorkshopItemSummary>(16, 0.75f, true)
    private val requests = mutableMapOf<Pair<UInt, ULong>, Deferred<WorkshopItemSummary>>()

    fun peek(appId: UInt, publishedFileId: ULong): WorkshopItemSummary? = synchronized(lock) {
        summaries[appId to publishedFileId]
    }

    suspend fun load(
        appId: UInt,
        publishedFileId: ULong,
        force: Boolean = false,
        fetch: suspend () -> WorkshopItemSummary,
    ): WorkshopItemSummary {
        val key = appId to publishedFileId
        val request = synchronized(lock) {
            requests[key] ?: run {
                if (!force) summaries[key]?.let { return it }
                scope.async(start = CoroutineStart.LAZY) {
                    try {
                        fetch().also { summary ->
                            check(summary.appId == appId && summary.publishedFileId == publishedFileId)
                            synchronized(lock) {
                                summaries[key] = summary
                                while (summaries.size > 128) summaries.remove(summaries.keys.first())
                            }
                        }
                    } finally {
                        synchronized(lock) { requests.remove(key) }
                    }
                }.also { requests[key] = it }
            }
        }
        request.start()
        return request.await()
    }

    companion object {
        val shared = CommunityPatchSummaryStore(CoroutineScope(SupervisorJob() + Dispatchers.IO))
    }
}
