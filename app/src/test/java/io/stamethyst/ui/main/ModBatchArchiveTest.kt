package io.stamethyst.ui.main

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModBatchArchiveTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun archivePreservesContentsAndDisambiguatesNamesWithoutDirectories() {
        val first = temp.newFile("first.jar").apply { writeText("first") }
        val second = temp.newFile("second.jar").apply { writeText("second") }
        val output = File(temp.root, "mods.zip")
        writeModBatchArchive(listOf(first to "../same.jar", second to "C:\\same.jar"), output)
        ZipFile(output).use { zip ->
            assertEquals(listOf("same.jar", "same-2.jar"), zip.entries().asSequence().map { it.name }.toList())
            assertEquals("first", zip.getInputStream(zip.getEntry("same.jar")).bufferedReader().readText())
            assertEquals("second", zip.getInputStream(zip.getEntry("same-2.jar")).bufferedReader().readText())
        }
    }

    @Test fun missingSourceRemovesIncompleteArchive() {
        val first = temp.newFile("first.jar")
        val output = File(temp.root, "mods.zip")
        try {
            writeModBatchArchive(listOf(first to "first.jar", File(temp.root, "missing") to "missing.jar"), output)
            fail("Expected missing source failure")
        } catch (_: IllegalArgumentException) {
            assertFalse(output.exists())
        }
    }

    @Test fun emptySelectionDoesNotCreateArchive() {
        val output = File(temp.root, "mods.zip")
        try {
            writeModBatchArchive(emptyList(), output)
            fail("Expected empty selection failure")
        } catch (_: IllegalArgumentException) {
            assertFalse(output.exists())
        }
    }

    @Test fun caseInsensitiveAndGeneratedNameCollisionsDoNotLoseFiles() {
        val source = temp.newFile("source.jar").apply { writeText("payload") }
        val output = File(temp.root, "mods.zip")
        writeModBatchArchive(listOf(source to "a.jar", source to "a-2.jar", source to "A.jar"), output)
        ZipFile(output).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            assertEquals(3, names.size)
            assertEquals(3, names.map { it.lowercase() }.toSet().size)
        }
    }
}
