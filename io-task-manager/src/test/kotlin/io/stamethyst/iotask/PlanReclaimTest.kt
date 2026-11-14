package io.stamethyst.iotask

import io.stamethyst.iotask.durable.*
import io.stamethyst.iotask.durable.Task
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlanReclaimTest {
    @Test fun interruptedCollectionRetainsIntentAndNeverReplaysBusiness() = runBlocking {
        val root = Files.createTempDirectory("plan-reclaim").toFile()
        val published = File(root, "published").apply { writeText("keep") }
        var interrupt = true
        var executions = 0
        val factory = TaskFactory { definition ->
            if (definition.kind == Kind.PLAN) object : TaskPlan() {
                override suspend fun execute(context: Context): Result {
                    executions++
                    val child = context.child("copy", Definition("copy"))
                    context.await(child)
                    assertTrue(File(root, "work/${child.id}/part").exists())
                    return Result.Success(mapOf("output" to published.path))
                }
                override suspend fun cleanup(context: Context) = Unit
            } else object : Task() {
                override suspend fun execute(context: Context): Result {
                    File(context.workspace, "part").writeText("temporary")
                    return Result.Success()
                }
                override suspend fun cleanup(context: Context) = Unit
                override suspend fun reclaim(context: Context) {
                    context.checkpoint(mapOf("reclaim" to "started"))
                    if (interrupt) error("interrupted")
                }
            }
        }
        try {
            FileTaskStore(root).use {
                val manager = TaskManager(it, factory, 1)
                manager.publish(Definition("plan", kind = Kind.PLAN), "plan")
                try { manager.run("plan"); fail("Expected interruption") } catch (_: IllegalStateException) { }
                assertEquals(State.SUCCEEDED, manager.snapshot("plan").state)
                assertEquals(2, manager.snapshots().size)
            }
            interrupt = false
            FileTaskStore(root).use {
                val manager = TaskManager(it, factory, 1)
                assertEquals(State.SUCCEEDED, manager.recover().single().state)
                assertTrue(manager.snapshots().isEmpty())
                assertTrue(it.all().isEmpty())
                assertTrue(File(root, "work").listFiles().orEmpty().isEmpty())
                assertEquals("keep", published.readText())
                assertEquals(1, executions)
            }
        } finally { root.deleteRecursively() }
    }
}
