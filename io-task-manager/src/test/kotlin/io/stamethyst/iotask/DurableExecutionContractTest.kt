package io.stamethyst.iotask

import io.stamethyst.iotask.durable.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Surviving business contracts from the retired in-memory executor, using the durable API only. */
class DurableExecutionContractTest {
    private fun fixture(block: suspend (File) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("durable-contract").toFile()
        try { withTimeout(5_000) { block(root) } } finally { root.deleteRecursively() }
    }

    @Test fun duplicatePublicationIsRejectedAndConcurrentRunsShareOneExecution() = fixture { root ->
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var executions = 0
        var notifications = 0
        FileTaskStore(root).use { store ->
            val manager = TaskManager(store, TaskFactory { object : Task() {
                override suspend fun execute(context: Context): Result {
                    executions++
                    entered.complete(Unit)
                    release.await()
                    return Result.Success(mapOf("artifact" to "verified"))
                }
                override suspend fun cleanup(context: Context) = Unit
            } }, 1) { notifications++ }
            manager.publish(Definition("copy"), "copy")
            try { manager.publish(Definition("copy"), "copy"); fail("Duplicate accepted") }
            catch (_: IllegalArgumentException) { }
            kotlinx.coroutines.coroutineScope {
                val first = async { manager.run("copy") }
                entered.await()
                val second = async(start = CoroutineStart.UNDISPATCHED) { manager.run("copy") }
                assertFalse(second.isCompleted)
                release.complete(Unit)
                assertEquals(first.await(), second.await())
            }
            assertEquals(State.SUCCEEDED, manager.run("copy").state)
            assertEquals(1, executions)
            assertEquals(1, notifications)
        }
    }

    @Test fun queuedCancellationSkipsBodyAndDoesNotLeakPermits() = fixture { root ->
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val executed = mutableListOf<String>()
        val cleaned = mutableListOf<String>()
        FileTaskStore(root).use { store ->
            val manager = TaskManager(store, TaskFactory { object : Task() {
                override suspend fun execute(context: Context): Result {
                    executed += context.id
                    if (context.id == "first") { entered.complete(Unit); release.await() }
                    return Result.Success()
                }
                override suspend fun cleanup(context: Context) { cleaned += context.id }
            } }, 1)
            listOf("first", "queued", "next").forEach { manager.publish(Definition("copy"), it) }
            kotlinx.coroutines.coroutineScope {
                val first = async { manager.run("first") }
                entered.await()
                val queued = async(start = CoroutineStart.UNDISPATCHED) { manager.run("queued") }
                assertEquals(State.QUEUED, manager.snapshot("queued").state)
                assertTrue(manager.requestCancel("queued"))
                release.complete(Unit)
                assertEquals(State.SUCCEEDED, first.await().state)
                assertEquals(State.CANCELLED, queued.await().state)
            }
            assertEquals(State.SUCCEEDED, manager.run("next").state)
            assertEquals(listOf("first", "next"), executed)
            assertEquals(listOf("queued"), cleaned)
            assertEquals(State.CANCELLED, store.read("queued")!!.state)
        }
    }

    @Test fun recordWriteFailurePropagatesWithoutInventingBusinessFailureOrCallback() = fixture { root ->
        // An empty directory at the temporary record path deterministically rejects FileOutputStream,
        // including when tests run as root. No permissions or production fault-injection API needed.
        for (failAtCommit in listOf(false, true)) {
            val directory = File(root, if (failAtCommit) "commit" else "start")
            var executions = 0
            var notifications = 0
            FileTaskStore(directory).use { store ->
                val pending = File(directory, "copy.pending")
                val manager = TaskManager(store, TaskFactory { object : Task() {
                    override suspend fun execute(context: Context): Result {
                        executions++
                        if (failAtCommit) assertTrue(pending.mkdir())
                        return Result.Success()
                    }
                    override suspend fun cleanup(context: Context) = Unit
                } }, 1) { notifications++ }
                manager.publish(Definition("copy"), "copy")
                if (!failAtCommit) assertTrue(pending.mkdir())
                try { manager.run("copy"); fail("Store failure swallowed") } catch (_: IOException) { }
                val expected = if (failAtCommit) State.RUNNING else State.QUEUED
                assertEquals(expected, manager.snapshot("copy").state)
                assertEquals(expected, store.read("copy")!!.state)
                assertEquals(if (failAtCommit) 1 else 0, executions)
                assertEquals(0, notifications)
                assertTrue(pending.delete())
            }
            FileTaskStore(directory).use { store ->
                val manager = TaskManager(store, TaskFactory { object : Task() {
                    override suspend fun execute(context: Context) = Result.Success()
                    override suspend fun cleanup(context: Context) = Unit
                } }, 1)
                val restored = manager.recover().single()
                assertEquals(if (failAtCommit) State.WAITING else State.SUCCEEDED, restored.state)
            }
        }
    }

    @Test fun unexpectedExceptionRemainsRecoverableAndDoesNotBlockOtherTasks() = fixture { root ->
        var notifications = 0
        FileTaskStore(root).use { store ->
            val manager = TaskManager(store, TaskFactory { definition -> object : Task() {
                override suspend fun execute(context: Context): Result {
                    if (definition.type == "broken") error("unexpected interruption")
                    return Result.Success()
                }
                override suspend fun cleanup(context: Context) = error("Not safe cancellation")
            } }, 1) { notifications++ }
            manager.publish(Definition("broken"), "broken")
            try { manager.run("broken"); fail("Exception swallowed") }
            catch (error: IllegalStateException) { assertEquals("unexpected interruption", error.message) }
            assertEquals(State.RUNNING, store.read("broken")!!.state)
            assertEquals(0, notifications)
            manager.publish(Definition("next"), "next")
            assertEquals(State.SUCCEEDED, manager.run("next").state)
            assertEquals(State.WAITING, manager.recover().single().state)
            assertEquals(1, notifications)
        }
    }
}
