package io.stamethyst.backend.mods

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JacketNoAnoKoModCompatPatcherTest {
    @Test
    fun patchInPlace_rewritesDesktopVersionHeadersAndIsIdempotent() {
        val tempDir = Files.createTempDirectory("jacketnoanoko-patcher-test")
        val jarFile = tempDir.resolve("JacketNoAnoKoMod.jar").toFile()
        createJar(
            jarFile,
            mapOf(
                "jacketnoanokomodResources/shaders/SinsShader.fs" to (
                    "#version 120\n" +
                        "void main() {\n" +
                        "    gl_FragColor = vec4(1.0);\n" +
                        "}\n"
                    ),
                "jacketnoanokomodResources/shaders/sins_rect.vs" to (
                    "#version 120\n" +
                        "attribute vec4 a_position;\n" +
                        "void main() {\n" +
                        "    gl_Position = a_position;\n" +
                        "}\n"
                    )
            )
        )

        val firstPatch = JacketNoAnoKoModCompatPatcher.patchInPlace(jarFile)
        assertEquals(2, firstPatch.patchedShaderEntries)
        assertEquals(2, firstPatch.removedDesktopVersionDirectives)
        assertEquals(1, firstPatch.insertedFragmentPrecisionBlocks)
        assertTrue(firstPatch.hasAnyPatch)

        val patchedFragment = readJarText(
            jarFile,
            "jacketnoanokomodResources/shaders/SinsShader.fs"
        )
        assertFalse(patchedFragment.contains("#version 120"))
        assertTrue(patchedFragment.contains("precision highp float;"))
        assertTrue(patchedFragment.contains("precision mediump int;"))

        val patchedVertex = readJarText(
            jarFile,
            "jacketnoanokomodResources/shaders/sins_rect.vs"
        )
        assertFalse(patchedVertex.contains("#version 120"))
        assertFalse(patchedVertex.contains("precision highp float;"))

        val secondPatch = JacketNoAnoKoModCompatPatcher.patchInPlace(jarFile)
        assertEquals(0, secondPatch.patchedShaderEntries)
        assertEquals(0, secondPatch.removedDesktopVersionDirectives)
        assertEquals(0, secondPatch.insertedFragmentPrecisionBlocks)
        assertFalse(secondPatch.hasAnyPatch)
    }

    @Test
    fun patchInPlace_insertsPrecisionForFragmentShaderWithoutDesktopVersion() {
        val tempDir = Files.createTempDirectory("jacketnoanoko-patcher-precision")
        val jarFile = tempDir.resolve("JacketNoAnoKoMod.jar").toFile()
        createJar(
            jarFile,
            mapOf(
                "jacketnoanokomodResources/shaders/Chromatic.fs" to (
                    "#define SPEED 5.0\n" +
                        "void main() {\n" +
                        "    gl_FragColor = vec4(SPEED);\n" +
                        "}\n"
                    )
            )
        )

        val patchResult = JacketNoAnoKoModCompatPatcher.patchInPlace(jarFile)
        assertEquals(1, patchResult.patchedShaderEntries)
        assertEquals(0, patchResult.removedDesktopVersionDirectives)
        assertEquals(1, patchResult.insertedFragmentPrecisionBlocks)

        val patchedFragment = readJarText(
            jarFile,
            "jacketnoanokomodResources/shaders/Chromatic.fs"
        )
        assertTrue(patchedFragment.contains("precision highp float;"))
        assertTrue(patchedFragment.contains("#define SPEED 5.0"))
    }

    @Test
    fun patchInPlace_patchesShaderEntriesOutsideCanonicalResourceRoot() {
        val tempDir = Files.createTempDirectory("jacketnoanoko-patcher-nested")
        val jarFile = tempDir.resolve("JacketNoAnoKoMod.jar").toFile()
        createJar(
            jarFile,
            mapOf(
                "nested/resources/shaders/ElectrocardiogramLoewe.frag" to (
                    "#version 120\n" +
                        "void main() {\n" +
                        "    gl_FragColor = vec4(1.0);\n" +
                        "}\n"
                    ),
                "nested/resources/shaders/common.vert" to (
                    "#version 120\n" +
                        "attribute vec4 a_position;\n" +
                        "void main() {\n" +
                        "    gl_Position = a_position;\n" +
                        "}\n"
                    )
            )
        )

        val patchResult = JacketNoAnoKoModCompatPatcher.patchInPlace(jarFile)
        assertEquals(2, patchResult.patchedShaderEntries)
        assertEquals(2, patchResult.removedDesktopVersionDirectives)
        assertEquals(1, patchResult.insertedFragmentPrecisionBlocks)

        val patchedFragment = readJarText(
            jarFile,
            "nested/resources/shaders/ElectrocardiogramLoewe.frag"
        )
        assertFalse(patchedFragment.contains("#version 120"))
        assertTrue(patchedFragment.contains("precision highp float;"))

        val patchedVertex = readJarText(
            jarFile,
            "nested/resources/shaders/common.vert"
        )
        assertFalse(patchedVertex.contains("#version 120"))
        assertFalse(patchedVertex.contains("precision highp float;"))
    }

    @Test
    fun patchInPlace_returnsZeroesWhenTargetShadersAreMissing() {
        val tempDir = Files.createTempDirectory("jacketnoanoko-patcher-empty")
        val jarFile = tempDir.resolve("OtherMod.jar").toFile()
        createJar(
            jarFile,
            mapOf(
                "example/Placeholder.txt" to "placeholder\n"
            )
        )

        val patchResult = JacketNoAnoKoModCompatPatcher.patchInPlace(jarFile)
        assertEquals(0, patchResult.patchedShaderEntries)
        assertEquals(0, patchResult.removedDesktopVersionDirectives)
        assertEquals(0, patchResult.insertedFragmentPrecisionBlocks)
        assertFalse(patchResult.hasAnyPatch)
    }

    private fun createJar(jarFile: File, entries: Map<String, String>) {
        ZipOutputStream(jarFile.outputStream()).use { zipOut ->
            entries.forEach { (entryName, content) ->
                zipOut.putNextEntry(ZipEntry(entryName))
                zipOut.write(content.toByteArray(StandardCharsets.UTF_8))
                zipOut.closeEntry()
            }
        }
    }

    private fun readJarText(jarFile: File, entryName: String): String {
        val bytes = JarFileIoUtils.readJarEntryBytes(jarFile, entryName)
        assertNotNull(bytes)
        return String(bytes!!, StandardCharsets.UTF_8)
    }
}
