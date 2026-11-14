package io.stamethyst.iotask

import io.stamethyst.iotask.durable.*
import io.stamethyst.iotask.durable.Task
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DurableTaskTest {
    private fun root() = Files.createTempDirectory("durable-task").toFile()
    private fun factory(block: () -> Task) = TaskFactory { block() }

    @Test fun cancellationPropagatesThroughActivePlanWithoutDeadlock() = runBlocking {
        val root = root()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val factory = TaskFactory { definition ->
            if (definition.kind == Kind.PLAN) object : TaskPlan() {
                override suspend fun execute(context: Context): Result {
                    context.await(context.child("worker", Definition("leaf")))
                    return Result.Cancel
                }
                override suspend fun cleanup(context: Context) {
                    context.checkpoint(mapOf("clean" to "yes"))
                }
            } else object : Task() {
                override suspend fun execute(context: Context): Result {
                    entered.complete(Unit)
                    release.await()
                    assertEquals(Control.CANCEL, context.control)
                    return Result.Cancel
                }
                override suspend fun cleanup(context: Context) {
                    context.checkpoint(mapOf("clean" to "yes"))
                }
            }
        }
        try {
            FileTaskStore(root).use {
                val manager = TaskManager(it, factory, 1)
                manager.publish(Definition("plan", kind = Kind.PLAN), "plan")
                val execution = async { manager.run("plan") }
                entered.await()
                manager.requestCancel("plan")
                release.complete(Unit)
                assertEquals(State.CANCELLED, execution.await().state)
                assertTrue(manager.snapshots().isEmpty())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun checkpointDoesNotPauseAndPauseReconstructs() = runBlocking {
        val root = root()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val factory = factory {
            object : Task() {
                override suspend fun execute(context: Context): Result {
                    if (context.metadata["offset"] == null) {
                        context.checkpoint(mapOf("offset" to "10"))
                        context.progress(mapOf("bytes" to "12"))
                        entered.complete(Unit)
                        release.await()
                    }
                    return if (context.control == Control.PAUSE) Result.Paused else Result.Success(context.metadata)
                }
                override suspend fun cleanup(context: Context) = Unit
            }
        }
        try {
            FileTaskStore(root).use { store ->
                val manager = TaskManager(store, factory, 1)
                val task = manager.publish(Definition("copy"), "copy")
                val running = async { manager.run(task.id) }
                entered.await()
                assertEquals(State.RUNNING, task.snapshot().state)
                assertEquals("12", task.progress().value["bytes"])
                manager.requestPause(task.id)
                release.complete(Unit)
                assertEquals(State.PAUSED, running.await().state)
            }
            FileTaskStore(root).use {
                val manager = TaskManager(it, factory, 1)
                assertTrue(manager.recover().isEmpty())
                assertEquals("10", manager.resume("copy").output["offset"])
                assertEquals(State.SUCCEEDED, manager.snapshot("copy").state)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun interruptedCleanupIsRetriedAndTerminalRecordRetained() = runBlocking {
        val root = root()
        var interruptCleanup = true
        val factory = factory {
            object : Task() {
                override suspend fun execute(context: Context) = Result.Paused
                override suspend fun cleanup(context: Context) {
                    File(context.workspace, "part").delete()
                    context.checkpoint(mapOf("cleanup" to "part-deleted"))
                    if (interruptCleanup) throw IllegalStateException("interrupted cleanup")
                }
            }
        }
        try {
            FileTaskStore(root).use {
                val manager = TaskManager(it, factory, 1)
                manager.publish(Definition("copy"), "copy")
                File(it.workspace("copy"), "part").writeText("temporary")
                try { manager.cancel("copy"); fail("Expected interruption") } catch (_: IllegalStateException) { }
                assertEquals(State.CLEANING, manager.snapshot("copy").state)
            }
            interruptCleanup = false
            FileTaskStore(root).use {
                val manager = TaskManager(it, factory, 1)
                assertEquals(State.CANCELLED, manager.recover().single().state)
                assertEquals("part-deleted", manager.snapshot("copy").metadata["cleanup"])
                assertFalse(File(it.workspace("copy"), "part").exists())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun planReusesChildAfterInterruptionWithSinglePermit() = runBlocking {
        val root = root()
        var interrupt = true
        var executions = 0
        val factory = TaskFactory { definition ->
            if (definition.kind == Kind.PLAN) object : TaskPlan() {
                override suspend fun execute(context: Context): Result {
                    val child = context.child("download", Definition("blocks"))
                    val result = context.await(child)
                    if (interrupt) throw IllegalStateException("plan lost before checkpoint")
                    context.checkpoint(mapOf("child" to child.id))
                    return Result.Success(result.output)
                }
                override suspend fun recover(context: Context) = Recovery.Execute
                override suspend fun cleanup(context: Context) = Unit
            } else object : Task() {
                override suspend fun execute(context: Context): Result {
                    executions++
                    return Result.Success(mapOf("blocks" to "verified"))
                }
                override suspend fun cleanup(context: Context) = Unit
            }
        }
        try {
            FileTaskStore(root).use {
                val manager = TaskManager(it, factory, 1)
                manager.publish(Definition("plan", kind = Kind.PLAN), "plan")
                try { manager.run("plan"); fail("Expected interruption") } catch (_: IllegalStateException) { }
            }
            interrupt = false
            FileTaskStore(root).use {
                val manager = TaskManager(it, factory, 1)
                assertEquals(State.SUCCEEDED, manager.recover().single().state)
                assertTrue(manager.snapshots().isEmpty())
                assertEquals(1, executions)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun committedOutputIsReconciledWithoutReplayAndCallbackCannotChangeResult() = runBlocking {
        val root = root()
        try {
            FileTaskStore(root).use {
                it.write(Snapshot("copy", Definition("copy"), state = State.RUNNING))
                File(it.workspace("copy"), "artifact").writeText("verified")
            }
            FileTaskStore(root).use {
                val manager = TaskManager(it, factory {
                    object : Task() {
                        override suspend fun recover(context: Context): Recovery {
                            assertEquals("verified", File(context.workspace, "artifact").readText())
                            return Recovery.Finished(Result.Success(mapOf("artifact" to "artifact")))
                        }
                        override suspend fun execute(context: Context): Result = error("Must not replay")
                        override suspend fun cleanup(context: Context) = Unit
                    }
                }, 1) { throw IllegalStateException("observer") }
                assertEquals(State.SUCCEEDED, manager.recover().single().state)
                assertTrue(manager.recover().isEmpty())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun ownershipCorruptionAndPartialWritesFailClosed() {
        val root = root()
        try {
            FileTaskStore(root).use {
                it.write(Snapshot("copy", Definition("copy")))
                try { FileTaskStore(root).close(); fail("Second owner accepted") } catch (_: Exception) { }
                File(root, "copy.pending").writeText("partial write")
            }
            FileTaskStore(root).use {
                assertEquals(State.QUEUED, it.read("copy")!!.state)
                File(root, "copy.task").writeBytes(ByteArray(64))
                try { it.read("copy"); fail("Corruption accepted") } catch (_: IllegalArgumentException) { }
                try { it.workspace("../escape"); fail("Traversal accepted") } catch (_: IllegalArgumentException) { }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun revertResumesAfterInterruptionAndFailureNeedsExplicitRetry() = runBlocking {
        val root = root()
        var interrupted = true
        val factory = factory {
            object : Task() {
                override suspend fun execute(context: Context) = Result.Fail("business failure")
                override suspend fun recover(context: Context) = Recovery.Finished(Result.Success())
                override suspend fun revert(context: Context) {
                    context.checkpoint(mapOf("reverted" to "true"))
                    if (interrupted) error("lost during revert")
                }
                override suspend fun cleanup(context: Context) = Unit
            }
        }
        try {
            FileTaskStore(root).use {
                val manager = TaskManager(it, factory, 1)
                manager.publish(Definition("copy"), "copy")
                assertEquals(State.FAILED, manager.run("copy").state)
                assertTrue(manager.recover().isEmpty())
                try { manager.revert("copy"); fail("Expected interruption") } catch (_: IllegalStateException) { }
            }
            interrupted = false
            FileTaskStore(root).use {
                val manager = TaskManager(it, factory, 1)
                assertEquals(State.PAUSED, manager.recover().single().state)
                assertEquals("true", manager.snapshot("copy").metadata["reverted"])
                assertEquals(State.FAILED, manager.resume("copy").state)
                assertEquals(State.SUCCEEDED, manager.retry("copy").state)
            }
        } finally { root.deleteRecursively() }
    }
}
