package io.stamethyst.ui.main

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class SteamCloudUiEventQueueTest {
    private data class Event(val id: String, val sequence: Long)

    @Test fun blockingValidationDoesNotBlockUiAndDuplicatesAreValidatedOnlyOnce() = runBlocking {
        Executors.newSingleThreadExecutor { Thread(it, "test-cloud-ui") }.asCoroutineDispatcher().use { ui ->
            Executors.newSingleThreadExecutor { Thread(it, "test-cloud-io") }.asCoroutineDispatcher().use { io ->
                val scope = CoroutineScope(SupervisorJob() + ui)
                val gate = SteamCloudOperationEventGate()
                val started = CompletableDeferred<Unit>()
                val release = CountDownLatch(1)
                val applied = Channel<Event>(Channel.UNLIMITED)
                val validated = mutableListOf<Event>()
                val queue = SteamCloudUiEventQueue<Event, Unit>(scope,
                    isRelevant = { gate.canAccept(it.id, it.sequence) },
                    validate = { event -> withContext(io) {
                        assertEquals("test-cloud-io", Thread.currentThread().name.substringBefore(" @"))
                        validated += event
                        if (event.sequence == 1L) {
                            started.complete(Unit)
                            check(release.await(5, TimeUnit.SECONDS))
                        }
                    } },
                    apply = { event, _ ->
                        assertEquals("test-cloud-ui", Thread.currentThread().name.substringBefore(" @"))
                        check(gate.accept(event.id, event.sequence))
                        applied.trySend(event)
                    },
                    onFailure = { applied.close(it) },
                )
                try {
                    withContext(ui) {
                        gate.begin("sync")
                        queue.offer(Event("sync", 1))
                        queue.offer(Event("sync", 1))
                        queue.offer(Event("sync", 2)) // Terminal event must not overtake progress.
                    }
                    withTimeout(5000) { started.await() }
                    withTimeout(2000) { withContext(ui) { assertFalse(gate.canAccept("other", 1)) } }
                    release.countDown()
                    withTimeout(5000) {
                        assertEquals(Event("sync", 1), applied.receive())
                        assertEquals(Event("sync", 2), applied.receive())
                    }
                    assertEquals(listOf(Event("sync", 1), Event("sync", 2)), validated)
                } finally {
                    release.countDown()
                    queue.close()
                    scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
                }
            }
        }
    }

    @Test fun aNewRequestDuringValidationRejectsTheOldCompletion() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { ui ->
            val scope = CoroutineScope(SupervisorJob() + ui)
            val gate = SteamCloudOperationEventGate()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val applied = Channel<Event>(Channel.UNLIMITED)
            val queue = SteamCloudUiEventQueue<Event, Unit>(scope,
                isRelevant = { gate.canAccept(it.id, it.sequence) },
                validate = { event -> if (event.id == "old") { started.complete(Unit); release.await() } },
                apply = { event, _ -> check(gate.accept(event.id, event.sequence)); applied.trySend(event) },
                onFailure = { applied.close(it) },
            )
            try {
                withContext(ui) { gate.begin("old"); queue.offer(Event("old", 10)) }
                withTimeout(5000) { started.await() }
                withContext(ui) { gate.begin("new"); queue.offer(Event("new", 1)) }
                release.complete(Unit)
                withTimeout(5000) { assertEquals(Event("new", 1), applied.receive()) }
                assertTrue(applied.tryReceive().isFailure)
            } finally {
                queue.close()
                scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            }
        }
    }

    @Test fun failedValidationDoesNotLoseTheFollowingTerminalEvent() = runBlocking {
        val gate = SteamCloudOperationEventGate().also { it.begin("sync") }
        val applied = Channel<Event>(Channel.UNLIMITED)
        var failures = 0
        val queue = SteamCloudUiEventQueue<Event, Boolean>(this,
            isRelevant = { gate.canAccept(it.id, it.sequence) },
            validate = { event -> if (event.sequence == 1L) error("read unavailable") else true },
            apply = { event, valid -> if (valid && gate.accept(event.id, event.sequence)) applied.trySend(event) },
            onFailure = { failures++ },
        )
        try {
            queue.offer(Event("sync", 1))
            queue.offer(Event("sync", 2))
            withTimeout(5000) { assertEquals(Event("sync", 2), applied.receive()) }
            assertEquals(1, failures)
        } finally { queue.close() }
    }

    @Test fun rejectedAccountDoesNotConsumeTheEventSequence() = runBlocking {
        val gate = SteamCloudOperationEventGate().also { it.begin("sync") }
        val applied = Channel<Event>(Channel.UNLIMITED)
        var reads = 0
        val queue = SteamCloudUiEventQueue<Event, Boolean>(this,
            isRelevant = { gate.canAccept(it.id, it.sequence) },
            validate = { ++reads > 1 },
            apply = { event, valid -> if (valid && gate.accept(event.id, event.sequence)) applied.trySend(event) },
            onFailure = { applied.close(it) },
        )
        try {
            queue.offer(Event("sync", 1))
            queue.offer(Event("sync", 1))
            withTimeout(5000) { assertEquals(Event("sync", 1), applied.receive()) }
            assertEquals(2, reads)
        } finally { queue.close() }
    }
}
