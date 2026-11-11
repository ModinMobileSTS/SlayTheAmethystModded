package io.stamethyst.backend.workshop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkshopSubscriptionSyncCoordinatorTest {
    @Test
    fun requiresForegroundStartupAndAuthenticationThenScansImmediately() = scenario {
        update(foreground = false)
        update(ready = false)
        update(account = null)
        assertTrue(loads.isEmpty())
        update()
        assertEquals(listOf(a), loads)
        assertEquals(listOf(1uL), queued)
    }

    @Test
    fun successfulScanUsesTenMinuteMonotonicIntervalWithoutRepeatingJobs() = scenario {
        update()
        now = 599_999L
        update()
        assertEquals(1, loads.size)
        now = 600_000L
        assertEquals("Time alone must not start a job", 1, loads.size)
        update()
        assertEquals(2, loads.size)
    }

    @Test
    fun activeTriggersCoalesceAndDoNotScheduleATrailingScan() = scenario {
        val gate = CompletableDeferred<Unit>()
        load = { gate.await(); page() }
        update()
        repeat(5) { update() }
        assertEquals(1, loads.size)
        gate.complete(Unit)
        assertEquals(listOf(1uL), queued)
        assertEquals(1, loads.size)
    }

    @Test
    fun leavingForegroundCancelsSuspendedScan() = scenario {
        var cancelled = false
        load = { try { awaitCancellation() } finally { cancelled = true } }
        update()
        update(foreground = false)
        assertTrue(cancelled)
        assertTrue(queued.isEmpty())
    }

    @Test
    fun accountChangeAndSameAccountCredentialRevisionFenceLatePageCompletion() = scenario {
        for (next in listOf(b, a.copy(credentialRevision = 2L))) {
            val gate = CompletableDeferred<Unit>()
            load = { identity ->
                if (identity == a) withContext(NonCancellable) { gate.await() }
                page()
            }
            update(account = null)
            update(account = a)
            update(account = next)
            val before = queued.size
            gate.complete(Unit)
            assertEquals("Old account must not enqueue after a late completion", before, queued.size)
            assertEquals(next, loads.last())
        }
    }

    @Test
    fun accountChangeDuringEligibilityCannotReachPersistentEnqueue() = scenario {
        val gate = CompletableDeferred<Unit>()
        eligible = { identity, _ ->
            if (identity == a) withContext(NonCancellable) { gate.await() }
            true
        }
        update()
        update(account = b)
        val before = queued.size
        gate.complete(Unit)
        assertEquals(before, queued.size)
    }

    @Test
    fun fullScanFailureDoesNotStampSuccessOrRetryWithoutExplicitTrigger() = scenario {
        load = { throw IllegalStateException("subscription page unavailable") }
        update()
        assertEquals(1, loads.size)
        assertTrue(queued.isEmpty())
        load = { page() }
        update()
        assertEquals(2, loads.size)
        assertEquals(listOf(1uL), queued)
        update()
        assertEquals(2, loads.size)
    }

    @Test
    fun confirmedSubscriptionDuringScanIsNotLostAndBypassesIntervalWithLiveEligibility() = scenario {
        val gate = CompletableDeferred<Unit>()
        load = { gate.await(); page() }
        update()
        val immediate = scope.async { controller.enqueueSubscribed(a, item(2uL)) }
        gate.complete(Unit)
        assertEquals(WorkshopSubscriptionEnqueueResult.Queued, immediate.await())
        assertEquals(listOf(1uL, 2uL), queued.sorted())
        eligible = { _, _ -> false }
        assertEquals(WorkshopSubscriptionEnqueueResult.Skipped, controller.enqueueSubscribed(a, item(3uL)))
        assertEquals(1, loads.size)
        eligible = { _, _ -> true }
        assertEquals(WorkshopSubscriptionEnqueueResult.Queued, controller.enqueueSubscribed(a, item(5uL)))
        assertEquals(1, loads.size)
        update(foreground = false)
        assertEquals(WorkshopSubscriptionEnqueueResult.Skipped, controller.enqueueSubscribed(a, item(4uL)))
        assertEquals(listOf(1uL, 2uL, 5uL), queued.sorted())
    }

    @Test
    fun immediateAndScanCandidatePreparationShareSerialization() = scenario {
        val gate = CompletableDeferred<Unit>()
        var active = 0
        var maxActive = 0
        eligible = { _, candidate ->
            active++
            maxActive = maxOf(maxActive, active)
            try { if (candidate.publishedFileId == 1uL) gate.await(); true }
            finally { active-- }
        }
        update()
        val immediate = scope.async { controller.enqueueSubscribed(a, item(2uL)) }
        assertEquals(1, maxActive)
        gate.complete(Unit)
        assertEquals(WorkshopSubscriptionEnqueueResult.Queued, immediate.await())
        assertEquals(1, maxActive)
        assertEquals(listOf(1uL, 2uL), queued.sorted())
    }

    @Test
    fun immediateReportsLiveQueueDecisionRatherThanAssumingCommitSucceeded() = scenario {
        load = { page(emptyList()) }
        update()
        enqueue = { WorkshopSubscriptionEnqueueResult.Skipped }
        assertEquals(WorkshopSubscriptionEnqueueResult.Skipped, controller.enqueueSubscribed(a, item(2uL)))
        enqueue = { WorkshopSubscriptionEnqueueResult.PreparationFailed }
        assertEquals(WorkshopSubscriptionEnqueueResult.PreparationFailed, controller.enqueueSubscribed(a, item(2uL)))
    }

    @Test
    fun immediatePreparationFailureIsDistinctFromAlreadyPresent() = scenario {
        load = { page(emptyList()) }
        update()
        enqueue = { throw IllegalStateException("details failed") }
        assertEquals(WorkshopSubscriptionEnqueueResult.PreparationFailed, controller.enqueueSubscribed(a, item(2uL)))
        assertTrue(queued.isEmpty())
    }

    private fun scenario(block: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture()
        try { fixture.block() } finally { fixture.scope.cancel() }
    }

    private class Fixture {
        val a = WorkshopSubscriptionAccountIdentity("account-a", 1L)
        val b = WorkshopSubscriptionAccountIdentity("account-b", 1L)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var now = 0L
        val loads = mutableListOf<WorkshopSubscriptionAccountIdentity>()
        val queued = mutableListOf<ULong>()
        var load: suspend (WorkshopSubscriptionAccountIdentity) -> WorkshopBrowseResult = { page() }
        var eligible: suspend (WorkshopSubscriptionAccountIdentity, WorkshopItemSummary) -> Boolean = { _, _ -> true }
        var enqueue: suspend (WorkshopItemSummary) -> WorkshopSubscriptionEnqueueResult = {
            queued += it.publishedFileId
            WorkshopSubscriptionEnqueueResult.Queued
        }
        val controller = WorkshopSubscriptionSyncCoordinator(
            scope = scope,
            appId = 646570u,
            monotonicMillis = { now },
            loadPage = { identity: WorkshopSubscriptionAccountIdentity, number: Int ->
                assertEquals(1, number)
                loads += identity
                load(identity)
            },
            shouldEnqueue = { identity: WorkshopSubscriptionAccountIdentity, item: WorkshopItemSummary -> eligible(identity, item) },
            enqueue = { _: WorkshopSubscriptionAccountIdentity, item: WorkshopItemSummary -> enqueue(item) },
        )

        fun update(foreground: Boolean = true, ready: Boolean = true, account: WorkshopSubscriptionAccountIdentity? = a) {
            controller.update(foreground = foreground, startupReady = ready, account = account)
        }
    }

    companion object {
        private fun item(id: ULong) = WorkshopItemSummary(id, 646570u, "Item $id", "", "")
        private fun page(items: List<WorkshopItemSummary> = listOf(item(1uL))) =
            WorkshopBrowseResult(items, items.size, 1, 30, false)
    }
}
