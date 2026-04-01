package io.stamethyst.backend.mods

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateZipEntryNormalizerTest {
    @Test
    fun normalizeInPlaceIfNeeded_removesDuplicateEntriesAndPreservesManifest() {
        val tempDir = Files.createTempDirectory("duplicate-zip-normalizer")
        val jarFile = tempDir.resolve("Astesia.jar").toFile()
        writeJarWithDuplicateEntries(jarFile)

        assertEquals(
            listOf(
                "ModTheSpire.json",
                "tutorial3/Tutorial.class",
                "META-INF/maven/qwert.example/tutorial3/pom.xml",
                "META-INF/maven/qwert.example/tutorial3/pom.xml"
            ),
            readEntryNames(jarFile)
        )

        val result = DuplicateZipEntryNormalizer.normalizeInPlaceIfNeeded(jarFile)

        assertTrue(result.changed)
        assertEquals(4, result.totalEntries)
        assertEquals(3, result.uniqueEntries)
        assertEquals(1, result.duplicateEntriesRemoved)
        assertEquals(
            listOf(
                "ModTheSpire.json",
                "tutorial3/Tutorial.class",
                "META-INF/maven/qwert.example/tutorial3/pom.xml"
            ),
            readEntryNames(jarFile)
        )

        val manifest = ModJarSupport.readModManifest(jarFile)
        assertEquals("Astesiamod", manifest.modId)
        assertEquals("Astesia", manifest.name)
        assertEquals(listOf("basemod", "stslib"), manifest.dependencies)
    }

    @Test
    fun normalizeInPlaceIfNeeded_isNoOpWhenArchiveHasNoDuplicates() {
        val tempDir = Files.createTempDirectory("duplicate-zip-normalizer-clean")
        val jarFile = tempDir.resolve("Clean.jar").toFile()

        ZipArchiveOutputStream(jarFile).use { zipOut ->
            writeEntry(
                zipOut = zipOut,
                entryName = "ModTheSpire.json",
                bytes = """
                    {
                      "modid": "cleanmod",
                      "name": "Clean Mod"
                    }
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            )
        }

        val result = DuplicateZipEntryNormalizer.normalizeInPlaceIfNeeded(jarFile)

        assertFalse(result.changed)
        assertEquals(1, result.totalEntries)
        assertEquals(1, result.uniqueEntries)
        assertEquals(0, result.duplicateEntriesRemoved)
        assertEquals(listOf("ModTheSpire.json"), readEntryNames(jarFile))
    }

    private fun writeJarWithDuplicateEntries(jarFile: java.io.File) {
        ZipArchiveOutputStream(jarFile).use { zipOut ->
            writeEntry(
                zipOut = zipOut,
                entryName = "ModTheSpire.json",
                bytes = """
                    {
                      "modid": "Astesiamod",
                      "name": "Astesia",
                      "dependencies": ["basemod", "stslib"]
                    }
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            )
            writeEntry(
                zipOut = zipOut,
                entryName = "tutorial3/Tutorial.class",
                bytes = byteArrayOf(0x01, 0x02, 0x03)
            )
            writeEntry(
                zipOut = zipOut,
                entryName = "META-INF/maven/qwert.example/tutorial3/pom.xml",
                bytes = "<project/>".toByteArray(StandardCharsets.UTF_8)
            )
            writeEntry(
                zipOut = zipOut,
                entryName = "META-INF/maven/qwert.example/tutorial3/pom.xml",
                bytes = "<project version=\"2\"/>".toByteArray(StandardCharsets.UTF_8)
            )
        }
    }

    private fun writeEntry(
        zipOut: ZipArchiveOutputStream,
        entryName: String,
        bytes: ByteArray
    ) {
        val entry = ZipArchiveEntry(entryName)
        zipOut.putArchiveEntry(entry)
        zipOut.write(bytes)
        zipOut.closeArchiveEntry()
    }

    private fun readEntryNames(jarFile: java.io.File): List<String> {
        val names = ArrayList<String>()
        ZipInputStream(jarFile.inputStream()).use { zipInput ->
            while (true) {
                val entry = zipInput.nextEntry ?: break
                names += entry.name
                zipInput.closeEntry()
            }
        }
        return names
    }
}
