package io.stamethyst.iotask

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.stamethyst.iotask.durable.*
import io.stamethyst.iotask.durable.Context
import io.stamethyst.iotask.durable.Task
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android filesystem and coroutine contract tests; no user business files. */
@RunWith(AndroidJUnit4::class)
class DurableContractInstrumentedTest {
    @Test fun planCancellationPropagatesToActiveChildBeforeCollection() = fixture { root -> runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var childCleaned = false
        var planCleaned = false
        FileTaskStore(root).use { store ->
            val manager = TaskManager(store, TaskFactory { definition ->
                if (definition.kind == Kind.PLAN) object : TaskPlan() {
                    override suspend fun execute(context: Context): Result {
                        context.await(context.child("leaf", Definition("leaf")))
                        return Result.Cancel
                    }
                    override suspend fun cleanup(context: Context) {
                        assertTrue(childCleaned)
                        planCleaned = true
                    }
                } else object : Task() {
                    override suspend fun execute(context: Context): Result {
                        entered.complete(Unit)
                        release.await()
                        assertEquals(Control.CANCEL, context.control)
                        return Result.Cancel
                    }
                    override suspend fun cleanup(context: Context) { childCleaned = true }
                }
            }, 1)
            manager.publish(Definition("plan", kind = Kind.PLAN), "plan")
            withTimeout(10000) {
                val running = async(Dispatchers.Default) { manager.run("plan") }
                entered.await()
                try {
                    assertTrue(manager.requestCancel("plan"))
                    assertEquals(Control.CANCEL, manager.snapshots().single { it.parent == "plan" }.control)
                } finally { release.complete(Unit) }
                assertEquals(State.CANCELLED, running.await().state)
            }
            assertTrue(childCleaned)
            assertTrue(planCleaned)
            assertTrue(store.all().isEmpty())
        }
    } }

    @Test fun parallelLeavesRespectBudgetAndActiveTaskRejectsRevert() = fixture { root -> runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        FileTaskStore(root).use { store ->
            val manager = TaskManager(store, TaskFactory {
                object : Task() {
                    override suspend fun execute(context: Context): Result {
                        val count = active.incrementAndGet()
                        peak.updateAndGet { maxOf(it, count) }
                        entered.complete(Unit)
                        try { release.await() } finally { active.decrementAndGet() }
                        return Result.Success()
                    }
                    override suspend fun cleanup(context: Context) = Unit
                }
            }, 1)
            manager.publish(Definition("leaf"), "first")
            manager.publish(Definition("leaf"), "second")
            expectFailure { manager.publish(Definition("leaf"), "first") }
            withTimeout(10000) {
                val first = async(Dispatchers.Default) { manager.run("first") }
                entered.await()
                val second = async(start = CoroutineStart.UNDISPATCHED) { manager.run("second") }
                try {
                    assertEquals(State.QUEUED, manager.snapshot("second").state)
                    var rejected = false
                    try { manager.revert("first") } catch (_: IllegalStateException) { rejected = true }
                    assertTrue(rejected)
                } finally { release.complete(Unit) }
                assertEquals(State.SUCCEEDED, first.await().state)
                assertEquals(State.SUCCEEDED, second.await().state)
            }
            assertEquals(1, peak.get())
            assertEquals(0, active.get())
        }
    } }

    @Test fun runningCancelWaitsForSafePointThenCleansAndRetainsRecord() = fixture { root -> runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        FileTaskStore(root).use { store ->
            val manager = TaskManager(store, TaskFactory {
                object : Task() {
                    override suspend fun execute(context: Context): Result {
                        File(context.workspace, "part").writeText("temporary")
                        entered.complete(Unit)
                        release.await()
                        check(context.control == Control.CANCEL)
                        return Result.Cancel
                    }
                    override suspend fun cleanup(context: Context) {
                        check(File(context.workspace, "part").delete())
                        context.checkpoint(mapOf("cleaned" to "true"))
                    }
                }
            }, 1)
            manager.publish(Definition("leaf"), "probe")
            withTimeout(10000) {
                val running = async(Dispatchers.Default) { manager.run("probe") }
                entered.await()
                val cancelling = async(start = CoroutineStart.UNDISPATCHED) { manager.cancel("probe") }
                try {
                    assertFalse(cancelling.isCompleted)
                    assertEquals(Control.CANCEL, store.read("probe")!!.control)
                    assertTrue(File(store.workspace("probe"), "part").exists())
                } finally { release.complete(Unit) }
                assertEquals(State.CANCELLED, cancelling.await().state)
                assertEquals(State.CANCELLED, running.await().state)
            }
            assertEquals("true", store.read("probe")!!.metadata["cleaned"])
            assertFalse(File(store.workspace("probe"), "part").exists())
        }
    } }

    @Test fun taskRevertClearsOutputAndUnsupportedRevertStaysRecoverable() = fixture { root -> runBlocking {
        FileTaskStore(root).use { store ->
            val manager = TaskManager(store, revertProbeFactory(root), 1)
            manager.publish(Definition("probe"), "probe")
            var rejected = false
            try { manager.revert("probe") } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            assertEquals(State.SUCCEEDED, manager.run("probe").state)
            assertEquals(State.PAUSED, manager.revert("probe").state)
            assertTrue(manager.snapshot("probe").output.isEmpty())
            assertFalse(File(root, "probe.published").exists())
            assertEquals(State.PAUSED, manager.revert("probe").state)
        }
        FileTaskStore(root).use { store ->
            val manager = TaskManager(store, TaskFactory {
                object : Task() {
                    override suspend fun execute(context: Context) = Result.Paused
                    override suspend fun cleanup(context: Context) = Unit
                }
            }, 1)
            manager.publish(Definition("unsupported"), "unsupported")
            manager.run("unsupported")
            var rejected = false
            try { manager.revert("unsupported") } catch (_: IllegalStateException) { rejected = true }
            assertTrue(rejected)
            assertEquals(State.REVERTING, store.read("unsupported")!!.state)
        }
    } }

    private fun root() = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
        "durable-recovery/${UUID.randomUUID()}").also { check(it.mkdirs()) }

    private inline fun fixture(block: (File) -> Unit) {
        val root = root()
        try { block(root) } finally { root.deleteRecursively() }
    }

    private fun expectFailure(block: () -> Unit) {
        var failed = false
        try { block() } catch (_: IllegalArgumentException) { failed = true }
        catch (_: IllegalStateException) { failed = true }
        assertTrue("Operation must reject invalid input", failed)
    }

    @Test fun schemaChecksumPendingAndOwnerAreValidated() = fixture { root ->
        FileTaskStore(root).use { store ->
            store.write(Snapshot("probe", Definition("probe")))
            expectFailure { FileTaskStore(root).close() }
            expectFailure { store.workspace("../escape") }
            File(root, "probe.pending").writeText("incomplete")
            assertEquals(State.QUEUED, store.read("probe")!!.state)
            val file = File(root, "probe.task")
            val original = file.readBytes()
            val payload = original.copyOfRange(32, original.size)
            ByteBuffer.wrap(payload).putInt(1)
            file.writeBytes(MessageDigest.getInstance("SHA-256").digest(payload) + payload)
            expectFailure { store.read("probe") }
            file.writeBytes(original.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() })
            expectFailure { store.all() }
            file.writeBytes(original)
        }
        FileTaskStore(root).use { assertEquals(State.QUEUED, it.read("probe")!!.state) }
    }

    @Test fun waitingResumesWithFactoryReconstructionAndCallbackIsolation() = fixture { root -> runBlocking {
        var executions = 0
        val factory = TaskFactory {
            object : Task() {
                override suspend fun execute(context: Context): Result {
                    executions++
                    context.checkpoint(mapOf("saved" to "yes"))
                    return if (executions == 1) Result.Waiting("input") else Result.Success(mapOf("ok" to "yes"))
                }
                override suspend fun cleanup(context: Context) = Unit
            }
        }
        FileTaskStore(root).use {
            val manager = TaskManager(it, factory, 1)
            manager.publish(Definition("probe"), "probe")
            assertEquals(State.WAITING, manager.run("probe").state)
        }
        FileTaskStore(root).use {
            val manager = TaskManager(it, factory, 1) { error("observer") }
            assertTrue(manager.recover().isEmpty())
            assertEquals("yes", manager.snapshot("probe").metadata["saved"])
            assertEquals(State.SUCCEEDED, manager.resume("probe").state)
            assertEquals("yes", manager.handle("probe").observe().value.output["ok"])
            assertEquals(State.SUCCEEDED, manager.run("probe").state)
            assertEquals(2, executions)
        }
    } }

    @Test fun failureRequiresRetryAndRetryReconcilesInsteadOfReplaying() = fixture { root -> runBlocking {
        var executions = 0
        var reconciliations = 0
        FileTaskStore(root).use {
            val manager = TaskManager(it, TaskFactory {
                object : Task() {
                    override suspend fun execute(context: Context): Result { executions++; return Result.Fail("failure") }
                    override suspend fun recover(context: Context): Recovery {
                        reconciliations++
                        return Recovery.Finished(Result.Success())
                    }
                    override suspend fun cleanup(context: Context) = Unit
                }
            }, 1)
            manager.publish(Definition("probe"), "probe")
            assertEquals(State.FAILED, manager.run("probe").state)
            assertTrue(manager.recover().isEmpty())
            assertEquals(State.SUCCEEDED, manager.retry("probe").state)
            assertEquals(1, executions)
            assertEquals(1, reconciliations)
        }
    } }

    @Test fun pauseRequestPersistsWithoutInterruptingCheckpointAndResume() = fixture { root -> runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val factory = TaskFactory {
            object : Task() {
                override suspend fun execute(context: Context): Result {
                    context.checkpoint(mapOf("verified" to "true"))
                    entered.complete(Unit)
                    release.await()
                    return if (context.control == Control.PAUSE) Result.Paused else Result.Success()
                }
                override suspend fun cleanup(context: Context) = Unit
            }
        }
        FileTaskStore(root).use { store ->
            val manager = TaskManager(store, factory, 1)
            manager.publish(Definition("probe"), "probe")
            withTimeout(10000) {
                val running = async(Dispatchers.Default) { manager.run("probe") }
                try {
                    entered.await()
                    assertEquals(State.RUNNING, manager.snapshot("probe").state)
                    assertTrue(manager.requestPause("probe"))
                    assertEquals(Control.PAUSE, store.read("probe")!!.control)
                } finally { release.complete(Unit) }
                assertEquals(State.PAUSED, running.await().state)
            }
        }
        FileTaskStore(root).use {
            val manager = TaskManager(it, factory, 1)
            assertTrue(manager.recover().isEmpty())
            assertEquals(State.SUCCEEDED, manager.resume("probe").state)
        }
    } }

    @Test fun abruptCancellationIsRecoverableNotSafeBusinessCancellation() = fixture { root -> runBlocking {
        val entered = CompletableDeferred<Unit>()
        FileTaskStore(root).use {
            val manager = TaskManager(it, TaskFactory {
                object : Task() {
                    override suspend fun execute(context: Context): Result {
                        context.checkpoint(mapOf("saved" to "true"))
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                    override suspend fun cleanup(context: Context) = error("Must not clean abruptly cancelled IO")
                }
            }, 1)
            manager.publish(Definition("probe"), "probe")
            withTimeout(10000) {
                val running = launch(Dispatchers.Default) { manager.run("probe") }
                entered.await()
                running.cancelAndJoin()
            }
            assertEquals(State.RUNNING, it.read("probe")!!.state)
            assertEquals("true", it.read("probe")!!.metadata["saved"])
            assertEquals(State.WAITING, manager.recover().single().state)
        }
    } }

    @Test fun planChildKeysAreStableAndConflictingDefinitionsRejected() = fixture { root -> runBlocking {
        FileTaskStore(root).use {
            val manager = TaskManager(it, TaskFactory { definition ->
                if (definition.kind == Kind.PLAN) object : TaskPlan() {
                    override suspend fun execute(context: Context): Result {
                        val child = context.child("one", Definition("leaf"))
                        assertEquals(child.id, context.child("one", Definition("leaf")).id)
                        expectFailure { context.child("one", Definition("changed")) }
                        assertEquals(State.SUCCEEDED, context.await(child).state)
                        return Result.Waiting("retain")
                    }
                    override suspend fun cleanup(context: Context) = Unit
                } else object : Task() {
                    override suspend fun execute(context: Context) = Result.Success()
                    override suspend fun cleanup(context: Context) = Unit
                }
            }, 1)
            manager.publish(Definition("plan", kind = Kind.PLAN), "plan")
            assertEquals(State.WAITING, withTimeout(10000) { manager.run("plan") }.state)
            assertEquals(2, manager.snapshots().size)
            assertEquals(State.CANCELLED, manager.cancel("plan").state)
            assertTrue(it.all().isEmpty())
        }
    } }

    @Test fun blockRevertPreservesBytesOtherBlocksAndRejectsChangedGeneration() = fixture { root ->
        val first = "first".toByteArray()
        val second = "second".toByteArray()
        fun block(offset: Long, data: ByteArray) = WriteBlock(offset, data.size.toLong(),
            MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) })
        val layout = listOf(block(0, first), block(5, second))
        ReplayableBlockWrite.open(root, "v1", 11, layout).use {
            it.write(0, first.inputStream()); it.write(1, second.inputStream())
            it.revert(0); it.revert(0)
            assertEquals("firstsecond", it.candidate.readText())
            assertFalse(it.isComplete(0)); assertTrue(it.isComplete(1))
        }
        ReplayableBlockWrite.open(root, "v1", 11, layout).use {
            assertFalse(it.isComplete(0)); assertTrue(it.isComplete(1))
            it.write(0, first.inputStream())
            assertTrue(it.verified())
        }
        expectFailure { ReplayableBlockWrite.open(root, "v2", 11, layout).close() }
    }

    @Test fun emptyBlockLayoutAndUnownedCandidatesAreHandledSafely() = fixture { root ->
        ReplayableBlockWrite.open(File(root, "empty"), "empty", 0, emptyList()).use {
            assertTrue(it.verified())
            assertEquals(0L, it.candidate.length())
        }
        val owned = File(root, "unowned").also { check(it.mkdir()) }
        File(owned, "candidate").writeText("preserve")
        expectFailure { ReplayableBlockWrite.open(owned, "v1", 0, emptyList()).close() }
        assertEquals("preserve", File(owned, "candidate").readText())
        expectFailure { ReplayableBlockWrite.open(File(root, "gap"), "v1", 1, emptyList()).close() }
    }

    @Test fun recoveredControlRequestsAreNotLost() = fixture { root -> runBlocking {
        FileTaskStore(root).use {
            it.write(Snapshot("paused", Definition("leaf"), control = Control.PAUSE))
            it.write(Snapshot("cancelled", Definition("leaf"), control = Control.CANCEL))
        }
        var executions = 0
        var cleanups = 0
        FileTaskStore(root).use {
            val manager = TaskManager(it, TaskFactory {
                object : Task() {
                    override suspend fun execute(context: Context): Result { executions++; return Result.Success() }
                    override suspend fun cleanup(context: Context) { cleanups++ }
                }
            }, 1)
            manager.recover()
            assertEquals(State.PAUSED, manager.snapshot("paused").state)
            assertEquals(State.CANCELLED, manager.snapshot("cancelled").state)
            assertEquals(0, executions)
            assertEquals(1, cleanups)
            assertTrue(manager.recover().isEmpty())
        }
    } }

    @Test fun successfulPlanReclaimsWorkButPreservesPublishedArtifactAndSymlinkTarget() = fixture { root -> runBlocking {
        val published = File(root, "published").also { check(it.mkdir()) }
        File(published, "result").writeText("keep")
        FileTaskStore(File(root, "store")).use { store ->
            val manager = TaskManager(store, TaskFactory { definition ->
                if (definition.kind == Kind.PLAN) object : TaskPlan() {
                    override suspend fun execute(context: Context): Result {
                        context.await(context.child("leaf", Definition("leaf")))
                        return Result.Success(mapOf("artifact" to File(published, "result").path))
                    }
                    override suspend fun cleanup(context: Context) = Unit
                } else object : Task() {
                    override suspend fun execute(context: Context): Result {
                        java.nio.file.Files.createSymbolicLink(File(context.workspace, "link").toPath(), published.toPath())
                        return Result.Success()
                    }
                    override suspend fun cleanup(context: Context) = Unit
                }
            }, 1)
            manager.publish(Definition("plan", kind = Kind.PLAN), "plan")
            val result = manager.run("plan")
            assertEquals(State.SUCCEEDED, result.state)
            assertEquals("keep", File(result.output.getValue("artifact")).readText())
            assertTrue(store.all().isEmpty())
            assertTrue(File(store.root, "work").listFiles().orEmpty().isEmpty())
            assertTrue(manager.recover().isEmpty())
        }
    } }
}
