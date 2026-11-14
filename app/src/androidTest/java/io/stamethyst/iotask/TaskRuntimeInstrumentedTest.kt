package io.stamethyst.iotask

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.stamethyst.backend.tasks.TaskRuntime
import io.stamethyst.config.RuntimePaths
import io.stamethyst.iotask.durable.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskRuntimeInstrumentedTest {
    @Test fun corruptJournalBlocksStartupWithoutDroppingRecords() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val root = File(RuntimePaths.taskStorageRoot(context), "verification/$id").canonicalFile
        val directory = File(root, "manager").apply { check(mkdirs()) }
        val broken = File(directory, "broken.task").apply { writeText("corrupt") }
        val runtime = TaskRuntime.verification(context, id)
        try {
            try { runtime.awaitReady(); fail("Corruption ignored") } catch (_: IllegalArgumentException) { }
            assertEquals("corrupt", broken.readText())
            assertFalse(File(context.filesDir, "io-tasks/verification/$id").exists())
        } finally { runtime.close(); root.deleteRecursively() }
    }

    @Test fun productionHostUsesExternalStorageAndRecoversSameNamespace() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val root = File(RuntimePaths.taskStorageRoot(context), "verification/$id").canonicalFile
        var runtime = TaskRuntime.verification(context, id)
        try {
            runtime.awaitReady()
            assertTrue(root.toPath().startsWith(context.getExternalFilesDir(null)!!.canonicalFile.toPath()))
            val source = File(root, "source").apply { writeText("new") }
            val target = File(root, "target").apply { writeText("old") }
            assertEquals(State.SUCCEEDED, runtime.execute(FileIoTasks.write(source, target, true), "replace").await().state)
            assertTrue(File(root, "manager/replace.task").isFile)
            assertTrue(File(root, "history/replace.task").isFile)
            runtime.close()
            // Model lost Manager completion after filesystem publication.
            FileTaskStore(File(root, "manager")).use {
                it.write(it.read("replace")!!.copy(state = State.RUNNING))
            }
            runtime = TaskRuntime.verification(context, id)
            runtime.awaitReady()
            assertEquals(State.SUCCEEDED, runtime.snapshot("replace").state)
            assertEquals("new", target.readText())
            runtime.revert("replace").await()
            assertEquals("old", target.readText())
            assertFalse(File(context.filesDir, "io-tasks/verification/$id").exists())
        } finally { runtime.close(); root.deleteRecursively() }
    }

    @Test fun singleOwnerAndConcurrentSubmissionsUseProductionSerialization() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val root = File(RuntimePaths.taskStorageRoot(context), "verification/$id").canonicalFile
        val runtime = TaskRuntime.verification(context, id)
        try {
            runtime.awaitReady()
            val second = TaskRuntime.verification(context, id)
            try {
                try { second.awaitReady(); fail("Second owner accepted") } catch (_: IllegalStateException) {
                } catch (_: java.nio.channels.OverlappingFileLockException) { }
            } finally { second.close() }
            val source = File(root, "source").apply { writeText("bytes") }
            val tasks = (0..5).map { runtime.execute(FileIoTasks.copy(source, File(root, "target$it")), "copy$it") }
            tasks.forEach { assertEquals(State.SUCCEEDED, it.await().state) }
            (0..5).forEach { runtime.revert("copy$it").await() }
        } finally { runtime.close(); root.deleteRecursively() }
    }
}
