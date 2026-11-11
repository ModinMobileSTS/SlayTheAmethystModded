package io.stamethyst.backend.workshop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class WorkshopSubscriptionSyncEngineTest {
    private val appId = 646570u

    @Test
    fun scansEveryPageAndDeduplicatesByAppAndFileAcrossTheRun() = runBlocking {
        val first = item(1uL)
        val second = item(2uL)
        val third = item(3uL)
        val requests = mutableListOf<Int>()
        val checked = mutableListOf<WorkshopItemSummary>()
        val queued = mutableListOf<WorkshopItemSummary>()

        val report: WorkshopSubscriptionSyncReport = WorkshopSubscriptionSyncEngine(appId = appId).sync(
            loadPage = { page: Int ->
                requests += page
                when (page) {
                    1 -> page(1, listOf(item(1uL, appId + 1u), first, first), true)
                    2 -> page(2, listOf(first.copy(title = "Changed metadata"), second), true)
                    3 -> page(3, listOf(third, second), false)
                    else -> throw AssertionError("Loaded past the last page: $page")
                }
            },
            shouldEnqueue = { item: WorkshopItemSummary -> checked += item; true },
            enqueue = { item: WorkshopItemSummary -> queued += item },
        )

        assertEquals(listOf(1, 2, 3), requests)
        assertEquals(listOf(first, second, third), checked)
        assertEquals(listOf(first, second, third), queued)
        assertReport(report, queued = 3, failed = 0)
    }

    @Test
    fun checksLiveEligibilityImmediatelyBeforeEachEnqueue() = runBlocking {
        val events = mutableListOf<String>()
        var nextItemBlocked = false
        val report = WorkshopSubscriptionSyncEngine(appId = appId).sync(
            loadPage = { _: Int -> page(1, listOf(item(1uL), item(2uL), item(3uL)), false) },
            shouldEnqueue = { item: WorkshopItemSummary ->
                events += "check:${item.publishedFileId}"
                item.publishedFileId != 2uL || !nextItemBlocked
            },
            enqueue = { item: WorkshopItemSummary ->
                events += "enqueue:${item.publishedFileId}"
                nextItemBlocked = true
            },
        )

        assertEquals(listOf("check:1", "enqueue:1", "check:2", "check:3", "enqueue:3"), events)
        assertReport(report, queued = 2, failed = 0)
    }

    @Test
    fun countsPredicateAndEnqueueFailuresOnceAndContinuesToLaterPages() = runBlocking {
        val checked = mutableListOf<ULong>()
        val attempted = mutableListOf<ULong>()
        val report = WorkshopSubscriptionSyncEngine(appId = appId).sync(
            loadPage = { page: Int ->
                when (page) {
                    1 -> page(1, listOf(item(1uL), item(2uL), item(3uL)), true)
                    2 -> page(2, listOf(item(1uL), item(2uL), item(4uL)), false)
                    else -> throw AssertionError("Unexpected page $page")
                }
            },
            shouldEnqueue = { item: WorkshopItemSummary ->
                checked += item.publishedFileId
                if (item.publishedFileId == 1uL) throw IllegalArgumentException("Predicate failed")
                item.publishedFileId != 3uL
            },
            enqueue = { item: WorkshopItemSummary ->
                attempted += item.publishedFileId
                if (item.publishedFileId == 2uL) throw IllegalStateException("Enqueue failed")
            },
        )

        assertEquals(listOf(1uL, 2uL, 3uL, 4uL), checked)
        assertEquals(listOf(2uL, 4uL), attempted)
        assertReport(report, queued = 1, failed = 2)
    }

    @Test
    fun deduplicationIsPerRunRatherThanPersistent() = runBlocking {
        val engine = WorkshopSubscriptionSyncEngine(appId = appId)
        val queued = mutableListOf<WorkshopItemSummary>()
        repeat(2) {
            val report = engine.sync(
                loadPage = { page: Int ->
                    assertEquals(1, page)
                    page(1, listOf(item(1uL)), false)
                },
                shouldEnqueue = { _: WorkshopItemSummary -> true },
                enqueue = { item: WorkshopItemSummary -> queued += item },
            )
            assertReport(report, queued = 1, failed = 0)
        }
        assertEquals(listOf(item(1uL), item(1uL)), queued)
    }

    @Test
    fun emptyFinalLibraryIsSuccessfulWithoutCallbacks() = runBlocking {
        val report = WorkshopSubscriptionSyncEngine(appId = appId).sync(
            loadPage = { page: Int ->
                assertEquals(1, page)
                page(1, emptyList(), false)
            },
            shouldEnqueue = { _: WorkshopItemSummary -> throw AssertionError("No candidate") },
            enqueue = { _: WorkshopItemSummary -> throw AssertionError("No candidate") },
        )
        assertReport(report, queued = 0, failed = 0)
    }

    @Test
    fun pageFailurePropagatesInsteadOfReturningPartialSuccess() = runBlocking {
        val failure = IllegalArgumentException("Page unavailable")
        val requests = mutableListOf<Int>()
        val queued = mutableListOf<WorkshopItemSummary>()
        val actual = expectFailure<IllegalArgumentException> {
            WorkshopSubscriptionSyncEngine(appId = appId).sync(
                loadPage = { page: Int ->
                    requests += page
                    if (page == 1) page(1, listOf(item(1uL)), true) else throw failure
                },
                shouldEnqueue = { _: WorkshopItemSummary -> true },
                enqueue = { item: WorkshopItemSummary -> queued += item },
            )
        }
        assertSame(failure, actual)
        assertEquals(listOf(1, 2), requests)
        assertEquals(listOf(item(1uL)), queued)
    }

    @Test
    fun emptyNonterminalPageFailsWithoutRequestingAnotherPage() = runBlocking {
        val requests = mutableListOf<Int>()
        expectFailure<IllegalStateException> {
            WorkshopSubscriptionSyncEngine(appId = appId).sync(
                loadPage = { page: Int ->
                    requests += page
                    if (page != 1) throw AssertionError("Continued after empty page")
                    page(1, emptyList(), true)
                },
                shouldEnqueue = { _: WorkshopItemSummary -> throw AssertionError("No candidate") },
                enqueue = { _: WorkshopItemSummary -> throw AssertionError("No candidate") },
            )
        }
        assertEquals(listOf(1), requests)
    }

    @Test
    fun repeatedNonterminalPageFailsButDoesNotRetryDuplicateItems() = runBlocking {
        val requests = mutableListOf<Int>()
        val queued = mutableListOf<WorkshopItemSummary>()
        expectFailure<IllegalStateException> {
            WorkshopSubscriptionSyncEngine(appId = appId).sync(
                loadPage = { page: Int ->
                    requests += page
                    if (page > 2) throw AssertionError("Continued after repeated page")
                    page(page, listOf(item(1uL)), true)
                },
                shouldEnqueue = { _: WorkshopItemSummary -> true },
                enqueue = { item: WorkshopItemSummary -> queued += item },
            )
        }
        assertEquals(listOf(1, 2), requests)
        assertEquals(listOf(item(1uL)), queued)
    }

    @Test
    fun duplicateOnlyFinalPageIsAllowed() = runBlocking {
        val report = WorkshopSubscriptionSyncEngine(appId = appId).sync(
            loadPage = { page: Int ->
                if (page > 2) throw AssertionError("Loaded past final page")
                page(page, listOf(item(1uL)), page == 1)
            },
            shouldEnqueue = { _: WorkshopItemSummary -> true },
            enqueue = { _: WorkshopItemSummary -> },
        )
        assertReport(report, queued = 1, failed = 0)
    }

    @Test
    fun syncRethrowsCancellationFromEveryCallbackWithoutContinuing() = runBlocking {
        for (cancelAt in listOf("load", "predicate", "enqueue")) {
            val cancellation = CancellationException(cancelAt)
            val events = mutableListOf<String>()
            val actual = expectFailure<CancellationException> {
                WorkshopSubscriptionSyncEngine(appId = appId).sync(
                    loadPage = { page: Int ->
                        events += "load"
                        if (cancelAt == "load") throw cancellation
                        if (page != 1) throw AssertionError("Continued after cancellation")
                        page(1, listOf(item(1uL), item(2uL)), true)
                    },
                    shouldEnqueue = { _: WorkshopItemSummary ->
                        events += "predicate"
                        if (cancelAt == "predicate") throw cancellation
                        true
                    },
                    enqueue = { _: WorkshopItemSummary ->
                        events += "enqueue"
                        throw cancellation
                    },
                )
            }
            assertSame(cancellation, actual)
            val expected = when (cancelAt) {
                "load" -> listOf("load")
                "predicate" -> listOf("load", "predicate")
                else -> listOf("load", "predicate", "enqueue")
            }
            assertEquals(expected, events)
        }
    }

    @Test
    fun immediateEnqueueFiltersWrongAppAndIneligibleItems() = runBlocking {
        val engine = WorkshopSubscriptionSyncEngine(appId = appId)
        val wrongApp = engine.enqueueItem(
            item = item(1uL, appId + 1u),
            shouldEnqueue = { _: WorkshopItemSummary -> throw AssertionError("Wrong app checked") },
            enqueue = { _: WorkshopItemSummary -> throw AssertionError("Wrong app enqueued") },
        )
        assertReport(wrongApp, queued = 0, failed = 0)
        var checks = 0
        val ineligible = engine.enqueueItem(
            item = item(1uL),
            shouldEnqueue = { candidate: WorkshopItemSummary ->
                assertEquals(item(1uL), candidate)
                checks++
                false
            },
            enqueue = { _: WorkshopItemSummary -> throw AssertionError("Ineligible item enqueued") },
        )
        assertEquals(1, checks)
        assertReport(ineligible, queued = 0, failed = 0)
    }

    @Test
    fun immediateEnqueueReportsSuccessOnlyAfterEnqueue() = runBlocking {
        val events = mutableListOf<String>()
        val candidate = item(1uL)
        val report = WorkshopSubscriptionSyncEngine(appId = appId).enqueueItem(
            item = candidate,
            shouldEnqueue = { item: WorkshopItemSummary ->
                assertSame(candidate, item)
                events += "predicate"
                true
            },
            enqueue = { item: WorkshopItemSummary ->
                assertSame(candidate, item)
                events += "enqueue"
            },
        )
        assertEquals(listOf("predicate", "enqueue"), events)
        assertReport(report, queued = 1, failed = 0)
    }

    @Test
    fun immediateEnqueueCountsPredicateOrEnqueueFailure() = runBlocking {
        for (failPredicate in listOf(true, false)) {
            var enqueueCalls = 0
            val report = WorkshopSubscriptionSyncEngine(appId = appId).enqueueItem(
                item = item(1uL),
                shouldEnqueue = { _: WorkshopItemSummary ->
                    if (failPredicate) throw IllegalArgumentException("Predicate failed")
                    true
                },
                enqueue = { _: WorkshopItemSummary ->
                    enqueueCalls++
                    throw IllegalStateException("Enqueue failed")
                },
            )
            assertReport(report, queued = 0, failed = 1)
            assertEquals(if (failPredicate) 0 else 1, enqueueCalls)
        }
    }

    @Test
    fun immediateEnqueueRethrowsCancellationFromPredicateAndEnqueue() = runBlocking {
        for (cancelPredicate in listOf(true, false)) {
            val cancellation = CancellationException("Cancelled")
            var enqueueCalls = 0
            val actual = expectFailure<CancellationException> {
                WorkshopSubscriptionSyncEngine(appId = appId).enqueueItem(
                    item = item(1uL),
                    shouldEnqueue = { _: WorkshopItemSummary ->
                        if (cancelPredicate) throw cancellation
                        true
                    },
                    enqueue = { _: WorkshopItemSummary ->
                        enqueueCalls++
                        throw cancellation
                    },
                )
            }
            assertSame(cancellation, actual)
            assertEquals(if (cancelPredicate) 0 else 1, enqueueCalls)
        }
    }

    private fun item(id: ULong, appId: UInt = this.appId) = WorkshopItemSummary(
        publishedFileId = id,
        appId = appId,
        title = "Item $id",
        previewUrl = "",
        description = "",
    )

    private fun page(number: Int, items: List<WorkshopItemSummary>, hasNext: Boolean) =
        WorkshopBrowseResult(
            items = items,
            total = 100,
            page = number,
            pageSize = 30,
            hasNextPage = hasNext,
        )

    private fun assertReport(report: WorkshopSubscriptionSyncReport, queued: Int, failed: Int) {
        assertEquals(queued, report.queuedCount)
        assertEquals(failed, report.failedCount)
    }

    private suspend inline fun <reified T : Throwable> expectFailure(block: suspend () -> Unit): T {
        try {
            block()
        } catch (failure: Throwable) {
            if (failure is T) return failure
            throw AssertionError("Expected ${T::class.java.simpleName}, got $failure", failure)
        }
        throw AssertionError("Expected ${T::class.java.simpleName}, but completed normally")
    }
}
