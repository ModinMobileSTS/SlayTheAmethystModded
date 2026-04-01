package io.stamethyst.ui.settings

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JarImportInspectionServiceTest {
    @Test
    fun inspectPreparedModJar_marksReservedBaseModComponent() {
        val tempDir = Files.createTempDirectory("jar-import-inspection-reserved")
        val jarFile = tempDir.resolve("BaseMod.jar").toFile()
        writeJar(
            jarFile,
            linkedMapOf(
                "ModTheSpire.json" to """
                    {
                      "modid": "basemod",
                      "name": "BaseMod"
                    }
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            )
        )

        val inspection = JarImportInspectionService.inspectPreparedModJar(
            jarFile = jarFile,
            displayName = "BaseMod.jar",
            manifestRootCompatEnabled = false
        )

        assertNotNull(inspection.manifest)
        assertEquals("basemod", inspection.normalizedModId)
        assertEquals(JarImportInspectionService.RESERVED_COMPONENT_BASEMOD, inspection.reservedComponent)
        assertNull(inspection.parseError)
    }

    @Test
    fun inspectPreparedModJar_marksReservedRuntimeCompatComponent() {
        val tempDir = Files.createTempDirectory("jar-import-inspection-runtime-compat")
        val jarFile = tempDir.resolve("AmethystRuntimeCompat.jar").toFile()
        writeJar(
            jarFile,
            linkedMapOf(
                "ModTheSpire.json" to """
                    {
                      "modid": "amethystruntimecompat",
                      "name": "Amethyst Runtime Compat"
                    }
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            )
        )

        val inspection = JarImportInspectionService.inspectPreparedModJar(
            jarFile = jarFile,
            displayName = "AmethystRuntimeCompat.jar",
            manifestRootCompatEnabled = false
        )

        assertNotNull(inspection.manifest)
        assertEquals("amethystruntimecompat", inspection.normalizedModId)
        assertEquals(
            JarImportInspectionService.RESERVED_COMPONENT_AMETHYST_RUNTIME_COMPAT,
            inspection.reservedComponent
        )
        assertNull(inspection.parseError)
    }

    @Test
    fun inspectPreparedModJar_reportsParseErrorWhenManifestMissing() {
        val tempDir = Files.createTempDirectory("jar-import-inspection-invalid")
        val jarFile = tempDir.resolve("Broken.jar").toFile()
        writeJar(
            jarFile,
            linkedMapOf(
                "example/Placeholder.class" to byteArrayOf(0x01)
            )
        )

        val inspection = JarImportInspectionService.inspectPreparedModJar(
            jarFile = jarFile,
            displayName = "Broken.jar",
            manifestRootCompatEnabled = false
        )

        assertNull(inspection.manifest)
        assertEquals("", inspection.normalizedModId)
        assertNull(inspection.reservedComponent)
        assertTrue(inspection.parseError.orEmpty().contains("ModTheSpire.json"))
    }

    @Test
    fun inspectPreparedModJar_appliesManifestRootCompatWhenEnabled() {
        val tempDir = Files.createTempDirectory("jar-import-inspection-root-fix")
        val jarFile = tempDir.resolve("Nested.jar").toFile()
        writeJar(
            jarFile,
            linkedMapOf(
                "nested/ModTheSpire.json" to """
                    {
                      "modid": "nestedmod",
                      "name": "Nested Mod"
                    }
                """.trimIndent().toByteArray(StandardCharsets.UTF_8),
                "nested/example.txt" to "ok".toByteArray(StandardCharsets.UTF_8)
            )
        )

        val inspection = JarImportInspectionService.inspectPreparedModJar(
            jarFile = jarFile,
            displayName = "Nested.jar",
            manifestRootCompatEnabled = true
        )

        assertNotNull(inspection.manifest)
        assertEquals("nestedmod", inspection.normalizedModId)
        assertEquals(2, inspection.patchedManifestRootEntries)
        assertEquals("nested/", inspection.patchedManifestRootPrefix)
        assertEquals(
            listOf("ModTheSpire.json", "example.txt"),
            readEntryNames(jarFile)
        )
    }

    @Test
    fun isLikelyModTheSpireJar_checksNameAndLoaderEntry() {
        val tempDir = Files.createTempDirectory("jar-import-inspection-mts")
        val jarFile = tempDir.resolve("MtsLike.jar").toFile()
        writeJar(
            jarFile,
            linkedMapOf(
                "com/evacipated/cardcrawl/modthespire/Loader.class" to byteArrayOf(0x01)
            )
        )

        assertTrue(JarImportInspectionService.isLikelyModTheSpireJar(jarFile, "whatever.jar"))
        assertTrue(JarImportInspectionService.isLikelyModTheSpireJar(File("missing.jar"), "ModTheSpire.jar"))
    }

    private fun writeJar(jarFile: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(jarFile.outputStream()).use { zipOut ->
            entries.forEach { (entryName, bytes) ->
                zipOut.putNextEntry(ZipEntry(entryName))
                zipOut.write(bytes)
                zipOut.closeEntry()
            }
        }
    }

    private fun readEntryNames(jarFile: File): List<String> {
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
