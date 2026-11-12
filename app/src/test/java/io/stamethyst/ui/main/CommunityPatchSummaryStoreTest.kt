package io.stamethyst.ui.main

import io.stamethyst.backend.workshop.WorkshopItemSummary
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.Assert.*
import org.junit.Test

class CommunityPatchSummaryStoreTest {
    private val summary = WorkshopItemSummary(1234uL, 646570u, "Mod", "https://example.com/cover.jpg", "", downloadCount = 123)

    @Test fun completedListSummaryIsReusedWithoutAnotherFetch() = runBlocking {
        val store = CommunityPatchSummaryStore(this)
        store.load(summary.appId, summary.publishedFileId) { summary }
        assertEquals(summary, store.peek(summary.appId, summary.publishedFileId))
        assertEquals(summary, store.load(summary.appId, summary.publishedFileId) { error("Duplicate API request") })
    }

    @Test fun navigatingDuringListRequestJoinsItInsteadOfRepeatingIt() = runBlocking {
        val store = CommunityPatchSummaryStore(this)
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<WorkshopItemSummary>()
        val list = async {
            store.load(summary.appId, summary.publishedFileId) { entered.complete(Unit); response.await() }
        }
        entered.await()
        val detail = async(start = CoroutineStart.UNDISPATCHED) {
            store.load(summary.appId, summary.publishedFileId) { error("Duplicate API request") }
        }
        response.complete(summary)
        assertEquals(summary, list.await())
        assertEquals(summary, detail.await())
    }

    @Test fun leavingListDoesNotCancelMetadataNeededByDetail() = runBlocking {
        val store = CommunityPatchSummaryStore(this)
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<WorkshopItemSummary>()
        val list = async {
            store.load(summary.appId, summary.publishedFileId) { entered.complete(Unit); response.await() }
        }
        entered.await()
        list.cancelAndJoin()
        val detail = async(start = CoroutineStart.UNDISPATCHED) {
            store.load(summary.appId, summary.publishedFileId) { error("Cancelled shared request") }
        }
        response.complete(summary)
        assertEquals(summary, detail.await())
    }

    @Test fun explicitRefreshReplacesCachedSummary() = runBlocking {
        val store = CommunityPatchSummaryStore(this)
        store.load(summary.appId, summary.publishedFileId) { summary }
        val refreshed = summary.copy(downloadCount = 456)
        assertEquals(refreshed, store.load(summary.appId, summary.publishedFileId, force = true) { refreshed })
        assertEquals(refreshed, store.peek(summary.appId, summary.publishedFileId))
    }

    @Test fun cacheIsScopedToBothAppAndWorkshopId() = runBlocking {
        val store = CommunityPatchSummaryStore(this)
        store.load(summary.appId, summary.publishedFileId) { summary }
        assertNull(store.peek(1u, summary.publishedFileId))
        assertNull(store.peek(summary.appId, 999uL))
    }

    @Test fun failedRefreshRetainsLastSummaryAndCanRetry() = runBlocking {
        supervisorScope {
            val store = CommunityPatchSummaryStore(this)
            store.load(summary.appId, summary.publishedFileId) { summary }
            assertTrue(runCatching {
                store.load(summary.appId, summary.publishedFileId, force = true) { throw IOException("Offline") }
            }.isFailure)
            assertEquals(summary, store.peek(summary.appId, summary.publishedFileId))
            val fresh = summary.copy(downloadCount = 456)
            assertEquals(fresh, store.load(summary.appId, summary.publishedFileId, force = true) { fresh })
        }
    }
}
