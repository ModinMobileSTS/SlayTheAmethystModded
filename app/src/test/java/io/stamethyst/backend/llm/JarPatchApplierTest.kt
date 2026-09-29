package io.stamethyst.backend.llm

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.Base64
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JarPatchApplierTest {
    @Test
    fun appliesTextReplacementAndKeepsBackup() {
        val root = createTempDirectory("jar-patch").toFile()
        val jar = root.resolve("example.jar")
        writeJar(jar, mapOf("ModTheSpire.json" to "{\"name\":\"Old\"}\n", "config.txt" to "old"))

        val patch = JarPatch(
            expectedSha256 = JarPatchApplier.sha256(jar),
            operations = listOf(
                JarPatchOperation("replace_text", "config.txt", "new"),
                JarPatchOperation("add_text", "ai-note.txt", "generated"),
            ),
        )
        val result = JarPatchApplier.applyToJar(jar, patch)

        assertEquals(listOf("config.txt", "ai-note.txt"), result.changedEntries)
        assertEquals("new", readEntry(jar, "config.txt"))
        assertEquals("generated", readEntry(jar, "ai-note.txt"))
        assertEquals("old", readEntry(result.backupFile, "config.txt"))
        assertTrue(result.backupFile.isFile)
    }

    @Test
    fun rejectsStaleSourceAndExecutableEntries() {
        val root = createTempDirectory("jar-patch").toFile()
        val jar = root.resolve("example.jar")
        writeJar(jar, mapOf("ModTheSpire.json" to "{}", "Config.txt" to "old"))

        val stalePatch = JarPatch(
            expectedSha256 = "0".repeat(64),
            operations = listOf(JarPatchOperation("replace_text", "Config.txt", "new")),
        )
        assertThrows { JarPatchApplier.applyToJar(jar, stalePatch) }

        val executablePatch = JarPatch(
            expectedSha256 = JarPatchApplier.sha256(jar),
            operations = listOf(JarPatchOperation("replace_text", "Main.class", "bad")),
        )
        assertThrows { JarPatchApplier.applyToJar(jar, executablePatch) }
        assertEquals("old", readEntry(jar, "Config.txt"))
    }

    @Test
    fun allowsArbitraryBinaryReplacementAndDeletion() {
        val root = createTempDirectory("jar-patch").toFile()
        val jar = root.resolve("example.jar")
        writeJar(jar, mapOf("ModTheSpire.json" to "{}", "Main.class" to "old", "remove.txt" to "remove"))
        val binary = byteArrayOf(0, 1, 2, 127, -1, 42)
        val patch = JarPatch(
            expectedSha256 = JarPatchApplier.sha256(jar),
            operations = listOf(
                JarPatchOperation(
                    action = "replace_bytes",
                    entry = "Main.class",
                    contentBase64 = Base64.getEncoder().encodeToString(binary),
                ),
                JarPatchOperation(action = "delete", entry = "remove.txt"),
            ),
        )

        JarPatchApplier.applyToJar(jar, patch)
        assertTrue(jar.isFile)
        assertEquals(binary.toList(), readEntryBytes(jar, "Main.class")?.toList())
        assertFalse(readEntry(jar, "remove.txt") != null)
    }

    private fun writeJar(file: File, entries: Map<String, String>) {
        ZipOutputStream(FileOutputStream(file)).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
    }

    private fun readEntry(file: File, name: String): String? = ZipFile(file).use { zip ->
        zip.getEntry(name)?.let { entry -> zip.getInputStream(entry).bufferedReader().readText() }
    }

    private fun readEntryBytes(file: File, name: String): ByteArray? = ZipFile(file).use { zip ->
        zip.getEntry(name)?.let { entry -> zip.getInputStream(entry).readBytes() }
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected the operation to fail")
        } catch (_: java.io.IOException) {
            // Expected.
        }
    }
}
