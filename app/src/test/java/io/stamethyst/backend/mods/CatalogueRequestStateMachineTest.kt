package io.stamethyst.backend.mods

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogueRequestStateMachineTest {
    @Test
    fun simultaneousEntrypointsShareRequestAndAutomaticLoadsReuseSuccess() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val machine = CatalogueRequestStateMachine<String>(scope)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val first = async {
                machine.load {
                    calls.incrementAndGet()
                    started.complete(Unit)
                    release.await()
                    "catalogue"
                }
            }
            started.await()
            assertEquals(CatalogueRequestPhase.FETCHING, machine.state.value.phase)
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                machine.load(force = true) { error("Must join the running request") }
            }
            release.complete(Unit)
            assertEquals("catalogue", first.await())
            assertEquals("catalogue", second.await())
            assertEquals("catalogue", machine.load { error("Must reuse success") })
            assertEquals(1, calls.get())
            assertEquals(CatalogueRequestPhase.READY, machine.state.value.phase)
            assertNotNull(machine.state.value.refreshedAtMs)
            assertEquals("updated", machine.load(force = true) { "updated" })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun reusedCatalogueDoesNotReplayThePreviousFetchChangeFlag() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val machine = CatalogueRequestStateMachine<AgentPatchCatalogueData>(
                scope, cachedValue = { it.copy(changed = false) },
            )
            val fetched = AgentPatchCatalogueData(emptyList(), byteArrayOf(1), changed = true)
            assertTrue(machine.load { fetched }.changed)
            val cached = machine.load { error("Must reuse the successful catalogue") }
            assertEquals(false, cached.changed)
            assertTrue(cached.bytes === fetched.bytes)
            assertTrue(machine.load(force = true) { fetched }.changed)
            assertEquals(false, machine.load { error("Must reuse forced success") }.changed)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun failureRetainsDataAndCanBeRetried() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val machine = CatalogueRequestStateMachine<String>(scope)
            machine.load { "cached" }
            val failure = runCatching { machine.load(force = true) { throw IOException("offline") } }
            assertTrue(failure.exceptionOrNull() is IOException)
            assertEquals(CatalogueRequestPhase.FAILED, machine.state.value.phase)
            assertEquals("cached", machine.state.value.data)
            assertEquals("offline", machine.state.value.error)
            assertEquals("recovered", machine.load { "recovered" })
            assertEquals(CatalogueRequestPhase.READY, machine.state.value.phase)
            assertEquals(null, machine.state.value.error)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancellingOneWaiterDoesNotCancelSharedFetch() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val machine = CatalogueRequestStateMachine<String>(scope)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val first = async {
                machine.load {
                    started.complete(Unit)
                    release.await()
                    "result"
                }
            }
            started.await()
            first.cancel()
            first.join()
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                machine.load { error("Must not start another request") }
            }
            release.complete(Unit)
            assertEquals("result", second.await())
            assertEquals(CatalogueRequestPhase.READY, machine.state.value.phase)
        } finally {
            scope.cancel()
        }
    }
}
