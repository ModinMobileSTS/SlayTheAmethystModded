package io.stamethyst.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class SaveArchiveLayoutTest {
    @Test
    fun existingSourceDirectories_preserveDistinctDirectoryNames() {
        val root = Files.createTempDirectory("save-archive-layout").toFile()
        try {
            check(root.isDirectory)
            check(root.resolve("preferences").mkdirs())
            check(root.resolve("perference").mkdirs())
            check(root.resolve("multiple").mkdirs())

            val names = SaveArchiveLayout.existingSourceDirectories(root).map { it.name }

            assertEquals(listOf("preferences", "perference", "multiple"), names)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun resolveImportablePath_keepsSupportedDirectoryNamesAsIs() {
        assertEquals(
            "preferences/BetaPreferences",
            SaveArchiveLayout.resolveImportablePath("sts/preferences/BetaPreferences")
        )
        assertEquals(
            "perference/BetaPreferences",
            SaveArchiveLayout.resolveImportablePath("sts/perference/BetaPreferences")
        )
        assertEquals(
            "sendToDevs/logs/SlayTheSpire.log",
            SaveArchiveLayout.resolveImportablePath(
                "Android/data/io.stamethyst/files/sts/sendToDevs/logs/SlayTheSpire.log"
            )
        )
        assertEquals(
            "multiple/saveSlot",
            SaveArchiveLayout.resolveImportablePath("multiple/saveSlot")
        )
    }

    @Test
    fun resolveImportablePath_rejectsLegacyFlatPreferenceFiles() {
        assertNull(SaveArchiveLayout.resolveImportablePath("sts/BetaPreferences"))
        assertNull(SaveArchiveLayout.resolveImportablePath("BetaPreferences"))
        assertNull(SaveArchiveLayout.resolveImportablePath("wrapper/preferences/BetaPreferences"))
    }

    @Test
    fun buildArchiveEntryName_preservesSourceRootName() {
        val root = Files.createTempDirectory("save-archive-layout").toFile()
        try {
            val sourceRoot = root.resolve("sendtodevs")
            val logFile = sourceRoot.resolve("logs/SlayTheSpire.log")
            check(logFile.parentFile!!.mkdirs())
            logFile.writeText("test")

            val entryName = SaveArchiveLayout.buildArchiveEntryName(sourceRoot, logFile)

            assertEquals("sts/sendtodevs/logs/SlayTheSpire.log", entryName)
        } finally {
            root.deleteRecursively()
        }
    }
}
