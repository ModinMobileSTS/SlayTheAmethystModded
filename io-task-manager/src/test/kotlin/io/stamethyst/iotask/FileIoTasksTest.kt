package io.stamethyst.iotask

import io.stamethyst.iotask.durable.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FileIoTasksTest {
    private fun fixture(block: suspend (File, TaskManager) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("file-io").toFile()
        try {
            FileTaskStore(File(root, "manager")).use { store ->
                block(root, TaskManager(store, FileIoTasks(File(root, "history")), 1))
            }
        } finally { root.deleteRecursively() }
    }

    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Expected conflict") } catch (_: IllegalStateException) {
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun mutationsRoundTrip() = fixture { root, manager ->
        val source = File(root, "source").apply { writeText("content") }
        val target = File(root, "target")
        listOf(FileIoTasks.copy(source, target), FileIoTasks.write(source, target),
            FileIoTasks.move(source, target), FileIoTasks.delete(source)).forEachIndexed { index, definition ->
            val id = "task$index"
            manager.publish(definition, id)
            assertEquals(State.SUCCEEDED, manager.run(id).state)
            if (definition.input["operation"] == "delete") assertFalse(source.exists())
            else assertEquals("content", target.readText())
            assertEquals(State.PAUSED, manager.revert(id).state)
            assertEquals("content", source.readText())
            assertFalse(target.exists())
            manager.revert(id)
        }
    }

    @Test fun replacementRejectsModifiedResultAndRestoresOldBytes() = fixture { root, manager ->
        val source = File(root, "source").apply { writeText("new") }
        val target = File(root, "target").apply { writeText("old") }
        manager.publish(FileIoTasks.write(source, target, true), "replace")
        manager.run("replace")
        target.writeText("external")
        rejected { manager.revert("replace") }
        assertEquals("external", target.readText())
        target.writeText("new")
        manager.revert("replace")
        assertEquals("old", target.readText())
    }

    @Test fun directoriesProtectExternalChildrenAndQuarantineTrees() = fixture { root, manager ->
        val directory = File(root, "directory")
        manager.publish(FileIoTasks.mkdir(directory), "mkdir")
        manager.run("mkdir")
        val child = File(directory, "child").apply { writeText("keep") }
        rejected { manager.revert("mkdir") }
        assertTrue(child.exists())
        manager.publish(FileIoTasks.delete(directory), "delete")
        manager.run("delete")
        assertFalse(directory.exists())
        manager.revert("delete")
        assertEquals("keep", child.readText())
        child.delete()
        manager.revert("mkdir")
        assertFalse(directory.exists())
    }

    @Test fun conflictsAndUnsafePathsLeaveOperandsUntouched() = fixture { root, manager ->
        val source = File(root, "source").apply { writeText("source") }
        val target = File(root, "target").apply { writeText("keep") }
        listOf(FileIoTasks.copy(source, target), FileIoTasks.move(source, source),
            FileIoTasks.copy(source, File(root, "history/operand")),
            FileIoTasks.copy(source, File(root, "missing/target")), FileIoTasks.mkdir(root))
            .forEachIndexed { index, definition ->
                manager.publish(definition, "bad$index")
                rejected { manager.run("bad$index") }
            }
        assertEquals("keep", target.readText())
        assertEquals("source", source.readText())
        val link = File(root, "link")
        Files.createSymbolicLink(link.toPath(), source.toPath())
        rejected { FileIoTasks.copy(link, File(root, "unused")) }
    }

    @Test fun sameBytesAtDifferentIdentityCannotBeUndone() = fixture { root, manager ->
        val source = File(root, "source").apply { writeText("bytes") }
        val target = File(root, "target")
        manager.publish(FileIoTasks.copy(source, target), "copy")
        manager.run("copy")
        Files.move(target.toPath(), File(root, "original").toPath())
        target.writeText("bytes")
        rejected { manager.revert("copy") }
        assertEquals("bytes", target.readText())
    }

    @Test fun allReplacementCommitAndUndoWindowsRecover() = runBlocking {
        for (committed in 0..2) {
            val root = Files.createTempDirectory("file-window").toFile()
            try {
                val history = File(root, "history")
                val source = File(root, "source").apply { writeText("new") }
                val target = File(root, "target").apply { writeText("old") }
                FileTaskStore(File(root, "manager")).use { store ->
                    val manager = TaskManager(store, FileIoTasks(history), 1)
                    manager.publish(FileIoTasks.write(source, target, true), "replace")
                    manager.run("replace")
                    store.write(store.read("replace")!!.copy(state = State.RUNNING))
                }
                FileTaskStore(history).use { store ->
                    val saved = store.read("replace")!!
                    if (committed < 2) Files.move(target.toPath(), File(saved.metadata.getValue("from.1")).toPath())
                    if (committed == 0) Files.move(File(saved.metadata.getValue("to.0")).toPath(), target.toPath())
                    store.write(saved.copy(metadata = saved.metadata + ("phase" to "ready")))
                }
                FileTaskStore(File(root, "manager")).use { store ->
                    val manager = TaskManager(store, FileIoTasks(history), 1)
                    assertEquals(State.SUCCEEDED, manager.recover().single().state)
                    assertEquals("new", target.readText())
                    store.write(store.read("replace")!!.copy(state = State.REVERTING))
                }
                FileTaskStore(history).use { store ->
                    val saved = store.read("replace")!!
                    if (committed > 0) Files.move(target.toPath(), File(saved.metadata.getValue("from.1")).toPath())
                    if (committed == 2) Files.move(File(saved.metadata.getValue("to.0")).toPath(), target.toPath())
                    store.write(saved.copy(metadata = saved.metadata + mapOf("phase" to "undoing", "undoCount" to "2")))
                }
                FileTaskStore(File(root, "manager")).use { store ->
                    assertEquals(State.PAUSED, TaskManager(store, FileIoTasks(history), 1).recover().single().state)
                    assertEquals("old", target.readText())
                }
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun cancelledBeforeExecutionAndEmptyFile() = fixture { root, manager ->
        val source = File(root, "source").apply { writeText("") }
        manager.publish(FileIoTasks.delete(source), "cancel")
        assertEquals(State.CANCELLED, manager.cancel("cancel").state)
        assertTrue(source.exists())
        val target = File(root, "empty")
        manager.publish(FileIoTasks.copy(source, target), "empty")
        manager.run("empty")
        assertEquals(0L, target.length())
        manager.revert("empty")
        assertFalse(target.exists())
    }

    @Test fun undoReconcilesEveryPartialCommitPrefix() = runBlocking {
        for (committed in 0..2) {
            val root = Files.createTempDirectory("undo-prefix").toFile()
            try {
                val source = File(root, "source").apply { writeText("new") }
                val target = File(root, "target").apply { writeText("old") }
                val history = File(root, "history")
                FileTaskStore(File(root, "manager")).use { store ->
                    val manager = TaskManager(store, FileIoTasks(history), 1)
                    manager.publish(FileIoTasks.write(source, target, true), "replace")
                    manager.run("replace")
                }
                FileTaskStore(history).use { store ->
                    val saved = store.read("replace")!!
                    if (committed < 2) Files.move(target.toPath(), File(saved.metadata.getValue("from.1")).toPath())
                    if (committed == 0) Files.move(File(saved.metadata.getValue("to.0")).toPath(), target.toPath())
                    store.write(saved.copy(metadata = saved.metadata + ("phase" to "ready")))
                }
                FileTaskStore(File(root, "manager")).use { store ->
                    val manager = TaskManager(store, FileIoTasks(history), 1)
                    manager.revert("replace")
                    assertEquals("old", target.readText())
                    manager.revert("replace")
                    assertEquals("old", target.readText())
                }
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun historySurvivesPlanReclaimAndNewUndoTask() = fixture { root, manager ->
        val source = File(root, "source").apply { writeText("keep") }
        val io = FileIoTasks(File(root, "history"))
        FileTaskStore(File(root, "plan-manager")).use { store ->
            val composed = TaskFactory { definition ->
                if (definition.kind == Kind.PLAN) object : TaskPlan() {
                    override suspend fun execute(context: Context): Result {
                        val child = context.child("delete", FileIoTasks.delete(source))
                        context.await(child)
                        return Result.Success(mapOf("child" to child.id))
                    }
                    override suspend fun cleanup(context: Context) = Unit
                } else io.create(definition)
            }
            val planManager = TaskManager(store, composed, 1)
            planManager.publish(Definition("plan", kind = Kind.PLAN), "plan")
            val result = planManager.run("plan")
            assertTrue(store.all().isEmpty())
            assertFalse(source.exists())
            manager.publish(FileIoTasks.undo(result.output.getValue("child")), "undo")
            assertEquals(State.SUCCEEDED, manager.run("undo").state)
            assertEquals("keep", source.readText())
        }
    }

    @Test fun readOnlyInspection() = fixture { root, _ ->
        val directory = File(root, "tree").apply { mkdir() }
        File(directory, "b").writeText("b")
        val a = File(directory, "a").apply { writeText("a") }
        assertEquals(listOf("a", "b"), FileInspection.scan(directory).map { it.name })
        assertEquals("ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb", FileInspection.sha256(a))
    }
}
