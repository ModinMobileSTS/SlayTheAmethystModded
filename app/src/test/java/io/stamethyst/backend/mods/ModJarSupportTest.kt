package io.stamethyst.backend.mods

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Test

class ModJarSupportTest {
    @Test(expected = IOException::class)
    fun validateAmethystRuntimeCompatJar_rejectsVersionBeforeJavaSoundProvider() {
        val jarFile = Files.createTempDirectory("mod-jar-support-runtime-compat-old")
            .resolve("AmethystRuntimeCompat.jar").toFile()
        writeJar(jarFile, linkedMapOf(
            "io/stamethyst/compatmod/AmethystRuntimeCompat.class" to byteArrayOf(0x01),
            "ModTheSpire.json" to """{"modid":"amethystruntimecompat","version":"1.0.39"}""".toByteArray()
        ))
        ModJarSupport.validateAmethystRuntimeCompatJar(jarFile)
    }

    @Test
    fun validateAmethystRuntimeCompatJar_acceptsBundledManifestVersion() {
        val tempDir = Files.createTempDirectory("mod-jar-support-runtime-compat")
        val jarFile = tempDir.resolve("AmethystRuntimeCompat.jar").toFile()
        writeJar(
            jarFile,
            linkedMapOf(
                "io/stamethyst/compatmod/AmethystRuntimeCompat.class" to byteArrayOf(0x01),
                "ModTheSpire.json" to runtimeCompatManifest().readBytes()
            )
        )

        ModJarSupport.validateAmethystRuntimeCompatJar(jarFile)
    }

    private fun runtimeCompatManifest(): File {
        return sequenceOf(
            File("mods/amethyst-runtime-compat/src/main/resources/ModTheSpire.json"),
            File("../mods/amethyst-runtime-compat/src/main/resources/ModTheSpire.json")
        ).firstOrNull { it.isFile }
            ?: error("Missing runtime compat manifest fixture")
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
}
