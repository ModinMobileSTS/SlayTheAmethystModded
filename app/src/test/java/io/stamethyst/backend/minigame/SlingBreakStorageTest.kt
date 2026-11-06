package io.stamethyst.backend.minigame

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SlingBreakStorageTest {
    @Test fun componentLeaseExcludesUpdatesAndNewReadersUntilClosed() = withRoot { root ->
        val first = SlingBreakStorage.tryAcquireComponents(root)!!
        try {
            assertNull(SlingBreakStorage.tryAcquireComponents(root))
            val otherThread = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                assertNull(otherThread.submit<java.io.Closeable?> {
                    SlingBreakStorage.tryAcquireComponents(root)
                }.get())
            } finally { otherThread.shutdownNow() }
        } finally { first.close() }
        val next = SlingBreakStorage.tryAcquireComponents(root)
        assertNotNull(next)
        next!!.close()
    }

    @Test fun localContentionCannotReleaseTheLockSeenByAnotherProcess() = withRoot { root ->
        val lease = SlingBreakStorage.tryAcquireComponents(root)!!
        val source = File(root, "Probe.java").apply { writeText("""
            import java.io.RandomAccessFile;
            import java.nio.channels.FileLock;
            public class Probe {
                public static void main(String[] args) throws Exception {
                    try (RandomAccessFile file = new RandomAccessFile(args[0], "rw")) {
                        FileLock lock = file.getChannel().tryLock();
                        System.out.println(lock == null ? "BUSY" : "ACQUIRED");
                        if (lock != null) lock.release();
                    }
                }
            }
        """.trimIndent()) }
        fun probe(): String {
            val process = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path,
                source.path, File(root, "components.lock").path).redirectErrorStream(true).start()
            try {
                assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS))
                val output = process.inputStream.bufferedReader().readText().trim()
                assertEquals(output, 0, process.exitValue())
                return output
            } finally { process.destroyForcibly() }
        }
        try {
            assertNull(SlingBreakStorage.tryAcquireComponents(root))
            assertEquals("BUSY", probe())
        } finally { lease.close() }
        assertEquals("ACQUIRED", probe())
        // Closing an already closed lease must not release a subsequent owner's lock.
        SlingBreakStorage.tryAcquireComponents(root)!!.use {
            lease.close()
            assertEquals("BUSY", probe())
        }
    }

    @Test fun pruningUsesExactManagedNamesAndDoesNotFollowSymlinks() = withRoot { root ->
        val components = File(root, "components").apply { mkdirs() }
        val current = "a".repeat(64)
        val old = "b".repeat(64)
        val engine = "c".repeat(64)
        val keep = listOf("game-$current.pck", "assets-$current.zip", "engine-$engine/engine.apk")
            .map { path -> File(components, path).apply { parentFile!!.mkdirs(); writeText("current") } }
        val save = File(root, "godot-prototype-user/save.json").apply { parentFile!!.mkdirs(); writeText("saved") }
        val nestedSave = File(components, "godot-prototype-user/save.json")
            .apply { parentFile!!.mkdirs(); writeText("also-saved") }
        val unknown = listOf("notes.txt", "engine-not-a-hash/keep", "game-backup.pck",
            "assets-backup.zip", "installed-release.json", "install.lock", "components.lock")
            .map { path -> File(components, path).apply { parentFile!!.mkdirs(); writeText("keep") } }
        val linkedGame = File(components, "game-$old.pck")
        Files.createSymbolicLink(linkedGame.toPath(), save.toPath())
        val oldEngine = File(components, "engine-$old").apply { mkdirs() }
        Files.createSymbolicLink(File(oldEngine, "external-save").toPath(), save.parentFile.toPath())
        File(oldEngine, "engine.apk").writeText("old")
        File(components, "assets-$old.zip").writeText("old")

        assertTrue(SlingBreakStorage.prune(components, current, engine).isEmpty())
        assertEquals("saved", save.readText())
        assertEquals("also-saved", nestedSave.readText())
        assertFalse(Files.exists(linkedGame.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertFalse(oldEngine.exists())
        assertFalse(File(components, "assets-$old.zip").exists())
        (keep + unknown).forEach { assertTrue(it.path, it.isFile) }
        assertTrue(SlingBreakStorage.prune(components, current, engine).isEmpty())
    }

    @Test fun pruningFailureIsReportedWithoutRemovingActivatedFiles() = withRoot { root ->
        val missing = File(root, "missing")
        assertEquals(listOf(missing), SlingBreakStorage.prune(missing, "a".repeat(64), "b".repeat(64)))
    }

    @Test fun malformedHashesCannotSelectFilesForDeletion() = withRoot { root ->
        val file = File(root, "game-${"a".repeat(64)}.pck").apply { writeText("old") }
        assertTrue(runCatching { SlingBreakStorage.prune(root, "../save", "b".repeat(64)) }.isFailure)
        assertTrue(file.isFile)
    }

    private fun withRoot(test: (File) -> Unit) {
        val root = Files.createTempDirectory("slingbreak-storage-test").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }
}
