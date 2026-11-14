package io.stamethyst.iotask

import io.stamethyst.iotask.durable.*
import io.stamethyst.iotask.durable.Task
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import org.junit.Assert.*
import org.junit.Test

class ObjectRevertTest {
    @Test fun activeTaskCannotBeReverted() = runBlocking {
        val root = Files.createTempDirectory("active-revert").toFile()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var reverted = false
        try {
            FileTaskStore(root).use { store ->
                val manager = TaskManager(store, TaskFactory {
                    object : Task() {
                        override suspend fun execute(context: Context): Result {
                            entered.complete(Unit)
                            release.await()
                            return Result.Paused
                        }
                        override suspend fun cleanup(context: Context) = Unit
                        override suspend fun revert(context: Context) { reverted = true }
                    }
                }, 1)
                manager.publish(Definition("active"), "task")
                val running = async { manager.run("task") }
                entered.await()
                try {
                    try { manager.revert("task"); fail("Active revert accepted") } catch (_: IllegalStateException) { }
                    assertFalse(reverted)
                    assertEquals(State.RUNNING, manager.snapshot("task").state)
                } finally { release.complete(Unit) }
                assertEquals(State.PAUSED, running.await().state)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun successfulTaskRevertsItsEffectsAndClearsOutput() = runBlocking {
        val root = Files.createTempDirectory("object-revert").toFile()
        val artifact = File(root, "published")
        try {
            FileTaskStore(File(root, "store")).use { store ->
                val manager = TaskManager(store, TaskFactory {
                    object : Task() {
                        override suspend fun execute(context: Context): Result {
                            artifact.writeText("result")
                            return Result.Success(mapOf("file" to artifact.path))
                        }
                        override suspend fun cleanup(context: Context) = Unit
                        override suspend fun revert(context: Context) { artifact.delete() }
                    }
                }, 1)
                manager.publish(Definition("publish"), "task")
                assertEquals(State.SUCCEEDED, manager.run("task").state)
                assertTrue(artifact.exists())
                val reverted = manager.revert("task")
                assertEquals(State.PAUSED, reverted.state)
                assertTrue(reverted.output.isEmpty())
                assertFalse(artifact.exists())
                assertEquals(State.PAUSED, store.read("task")!!.state)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun planExplicitlyRevertsSelectedChildAndResumesInterruptedRevert() = runBlocking {
        val root = Files.createTempDirectory("plan-revert").toFile()
        val effects = mutableSetOf<String>()
        var interrupted = true
        var childInterrupted = true
        val factory = TaskFactory { definition ->
            if (definition.kind == Kind.PLAN) object : TaskPlan() {
                override suspend fun execute(context: Context): Result {
                    context.await(context.child("keep", Definition("keep")))
                    context.await(context.child("undo", Definition("undo")))
                    return Result.Waiting("decision")
                }
                override suspend fun cleanup(context: Context) = Unit
                override suspend fun revert(context: Context) {
                    context.revert(context.child("undo", Definition("undo")))
                    if (interrupted) error("process interrupted")
                }
            } else object : Task() {
                override suspend fun execute(context: Context): Result {
                    effects.add(definition.type)
                    return Result.Success()
                }
                override suspend fun cleanup(context: Context) = Unit
                override suspend fun revert(context: Context) {
                    effects.remove(definition.type)
                    if (childInterrupted) error("child interrupted")
                }
            }
        }
        try {
            FileTaskStore(root).use { store ->
                val manager = TaskManager(store, factory, 1)
                manager.publish(Definition("plan", kind = Kind.PLAN), "plan")
                assertEquals(State.WAITING, manager.run("plan").state)
                try { manager.revert("plan"); fail("Expected interruption") } catch (_: IllegalStateException) { }
                assertEquals(State.REVERTING, store.read("plan")!!.state)
                assertEquals(State.REVERTING, manager.snapshots().single { it.definition.type == "undo" }.state)
                assertEquals(State.SUCCEEDED, manager.snapshots().single { it.definition.type == "keep" }.state)
                assertEquals(setOf("keep"), effects)
            }
            childInterrupted = false
            FileTaskStore(root).use { store ->
                val manager = TaskManager(store, factory, 1)
                try { manager.recover(); fail("Expected Plan interruption") } catch (_: IllegalStateException) { }
                assertEquals(State.REVERTING, store.read("plan")!!.state)
                assertEquals(setOf("keep"), effects)
            }
            interrupted = false
            FileTaskStore(root).use { store ->
                val manager = TaskManager(store, factory, 1)
                assertEquals(State.PAUSED, manager.recover().single().state)
                assertEquals(setOf("keep"), effects)
                assertEquals(State.PAUSED, manager.snapshots().single { it.definition.type == "undo" }.state)
                assertEquals(State.SUCCEEDED, manager.snapshots().single { it.definition.type == "keep" }.state)
                assertTrue(manager.recover().isEmpty())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun queuedTaskCannotRevertAndUnsupportedRevertDoesNotPretendSuccess() = runBlocking {
        val root = Files.createTempDirectory("unsupported-revert").toFile()
        try {
            FileTaskStore(root).use { store ->
                val manager = TaskManager(store, TaskFactory {
                    object : Task() {
                        override suspend fun execute(context: Context) = Result.Paused
                        override suspend fun cleanup(context: Context) = Unit
                    }
                }, 1)
                manager.publish(Definition("unsupported"), "task")
                try { manager.revert("task"); fail("Queued revert accepted") } catch (_: IllegalArgumentException) { }
                assertEquals(State.QUEUED, manager.snapshot("task").state)
                manager.run("task")
                try { manager.revert("task"); fail("Unsupported revert accepted") } catch (_: IllegalStateException) { }
                assertEquals(State.REVERTING, store.read("task")!!.state)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun oldRecordSchemaIsExplicitlyRejected() {
        val root = Files.createTempDirectory("old-schema").toFile()
        try {
            FileTaskStore(root).use { store ->
                store.write(Snapshot("task", Definition("test")))
                val file = File(root, "task.task")
                val payload = file.readBytes().copyOfRange(32, file.length().toInt())
                ByteBuffer.wrap(payload).putInt(1)
                file.writeBytes(MessageDigest.getInstance("SHA-256").digest(payload) + payload)
                try { store.read("task"); fail("Old schema accepted") } catch (error: IllegalArgumentException) {
                    assertTrue(error.message!!.contains("schema"))
                }
            }
        } finally { root.deleteRecursively() }
    }
}
