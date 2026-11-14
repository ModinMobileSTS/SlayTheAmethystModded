package io.stamethyst.iotask

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.stamethyst.iotask.durable.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Dedicated UUID directories only; no user files or launcher/game process termination. */
@RunWith(AndroidJUnit4::class)
class FileIoInstrumentedTest {
    private val androidContext get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun fixture(block: suspend (File, TaskManager) -> Unit) = runBlocking {
        val root = File(io.stamethyst.config.RuntimePaths.taskStorageRoot(androidContext), "verification/${UUID.randomUUID()}").canonicalFile
        try {
            FileTaskStore(File(root, "manager")).use { store ->
                block(root, TaskManager(store, FileIoTasks(File(root, "history")), 1))
            }
        } finally { root.deleteRecursively() }
    }

    private suspend fun conflict(block: suspend () -> Unit) {
        try { block(); fail("Expected conflict") } catch (_: IllegalStateException) {
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun copyWriteMoveDeleteAndMkdirRevert() = fixture { root, manager ->
        val source = File(root, "source").apply { writeBytes(ByteArray(131073) { (it % 251).toByte() }) }
        val target = File(root, "target")
        listOf(FileIoTasks.copy(source, target), FileIoTasks.write(source, target),
            FileIoTasks.move(source, target), FileIoTasks.delete(source), FileIoTasks.mkdir(target))
            .forEachIndexed { index, definition ->
                val id = "task$index"
                manager.publish(definition, id)
                assertEquals(State.SUCCEEDED, manager.run(id).state)
                if (definition.input["operation"] in setOf("copy", "write"))
                    assertEquals(FileInspection.sha256(source), FileInspection.sha256(target))
                assertEquals(State.PAUSED, manager.revert(id).state)
                assertTrue(source.isFile)
                assertFalse(target.exists())
                manager.revert(id)
            }
    }

    @Test fun replacementProtectsModifiedAndSubstitutedTargets() = fixture { root, manager ->
        val source = File(root, "source").apply { writeText("new") }
        val target = File(root, "target").apply { writeText("old") }
        manager.publish(FileIoTasks.write(source, target, true), "replace")
        manager.run("replace")
        target.writeText("external")
        conflict { manager.revert("replace") }
        assertEquals("external", target.readText())
        target.writeText("new")
        manager.revert("replace")
        assertEquals("old", target.readText())
        val copied = File(root, "copied")
        manager.publish(FileIoTasks.copy(source, copied), "copy")
        manager.run("copy")
        Files.move(copied.toPath(), File(root, "original").toPath())
        copied.writeText("new")
        conflict { manager.revert("copy") }
        assertEquals("new", copied.readText())
    }

    @Test fun directoryQuarantineAndExternalChildrenProtection() = fixture { root, manager ->
        val directory = File(root, "directory")
        manager.publish(FileIoTasks.mkdir(directory), "mkdir")
        manager.run("mkdir")
        val child = File(directory, "child").apply { writeText("keep") }
        conflict { manager.revert("mkdir") }
        manager.publish(FileIoTasks.delete(directory), "delete")
        manager.run("delete")
        assertFalse(directory.exists())
        manager.revert("delete")
        assertEquals("keep", child.readText())
        child.delete()
        manager.revert("mkdir")
        assertFalse(directory.exists())
    }

    @Test fun cancellationEmptyFilesAndPathConflicts() = fixture { root, manager ->
        val source = File(root, "source").apply { writeText("") }
        manager.publish(FileIoTasks.delete(source), "cancel")
        assertEquals(State.CANCELLED, manager.cancel("cancel").state)
        assertTrue(source.exists())
        val target = File(root, "target")
        manager.publish(FileIoTasks.copy(source, target), "empty")
        manager.run("empty")
        assertTrue(target.isFile)
        assertEquals(0L, target.length())
        manager.publish(FileIoTasks.copy(source, target), "conflict")
        conflict { manager.run("conflict") }
    }

    @Test fun internalSymlinkOperandsAreRejected() = runBlocking {
        val root = File(androidContext.filesDir, "file-io-link/${UUID.randomUUID()}").canonicalFile
        check(root.mkdirs())
        try {
            val source = File(root, "source").apply { writeText("keep") }
            val link = File(root, "link")
            Files.createSymbolicLink(link.toPath(), source.toPath())
            conflict { FileIoTasks.copy(link, File(root, "unused")) }
            assertEquals("keep", source.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun committedReplacementSurvivesProcessKill() = verifyKill("file-commit")
    @Test fun completedUndoSurvivesProcessKill() = verifyKill("file-undo")

    private fun verifyKill(mode: String) {
        val case = UUID.randomUUID().toString()
        val root = File(io.stamethyst.config.RuntimePaths.taskStorageRoot(androidContext), "verification/$case").canonicalFile
        val connected = CountDownLatch(1)
        val died = CountDownLatch(1)
        val binder = AtomicReference<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                binder.set(service)
                service.linkToDeath({ died.countDown() }, 0)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        var bound = false
        try {
            bound = androidContext.bindService(Intent(androidContext, DurableRecoveryTestService::class.java), connection, android.content.Context.BIND_AUTO_CREATE)
            assertTrue(bound)
            assertTrue(connected.await(15, TimeUnit.SECONDS))
            Messenger(binder.get()).send(Message.obtain().apply {
                data = Bundle().apply { putString("case", case); putString("mode", mode) }
            })
            assertTrue("Dedicated process must die", died.await(30, TimeUnit.SECONDS))
            androidContext.unbindService(connection)
            bound = false
            FileTaskStore(File(root, "manager")).use { store ->
                assertEquals(if (mode == "file-commit") State.RUNNING else State.REVERTING, store.read("probe")!!.state)
            }
            val runtime = io.stamethyst.backend.tasks.TaskRuntime.verification(androidContext, case)
            try {
                val result = runBlocking { runtime.awaitReady(); runtime.snapshot("probe") }
                assertEquals(if (mode == "file-commit") State.SUCCEEDED else State.PAUSED, result.state)
                assertEquals(if (mode == "file-commit") "new" else "old", File(root, "target").readText())
                if (mode == "file-commit") runBlocking { runtime.revert("probe").await() }
                assertEquals("old", File(root, "target").readText())
            } finally { runBlocking { runtime.close() } }
        } finally {
            if (bound) androidContext.unbindService(connection)
            root.deleteRecursively()
        }
    }
}
