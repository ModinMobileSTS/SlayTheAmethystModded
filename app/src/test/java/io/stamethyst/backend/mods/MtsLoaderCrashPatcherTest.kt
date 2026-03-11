package io.stamethyst.backend.mods

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MtsLoaderCrashPatcherTest {
    @Test
    fun ensurePatchedMtsJar_removesRunModsSwallowHandlers() {
        val sourceJar = sequenceOf(
            File("src/main/assets/components/mods/ModTheSpire.jar"),
            File("app/src/main/assets/components/mods/ModTheSpire.jar")
        ).firstOrNull { it.isFile }
            ?: error("Missing test fixture jar: ModTheSpire.jar")
        check(sourceJar.isFile) { "Missing test fixture jar: ${sourceJar.absolutePath}" }

        val tempJar = Files.createTempFile("mts-loader-patch-", ".jar").toFile()
        try {
            JarFileIoUtils.copyFileReplacing(sourceJar, tempJar)

            val originalLoaderBytes = JarFileIoUtils.readJarEntryBytes(
                tempJar,
                "com/evacipated/cardcrawl/modthespire/Loader.class"
            )
            requireNotNull(originalLoaderBytes)
            assertFalse(MtsLoaderCrashPatcher.isPatchedLoaderClass(originalLoaderBytes))

            MtsLoaderCrashPatcher.ensurePatchedMtsJar(tempJar)

            val patchedLoaderBytes = JarFileIoUtils.readJarEntryBytes(
                tempJar,
                "com/evacipated/cardcrawl/modthespire/Loader.class"
            )
            requireNotNull(patchedLoaderBytes)
            assertTrue(MtsLoaderCrashPatcher.isPatchedLoaderClass(patchedLoaderBytes))
        } finally {
            tempJar.delete()
        }
    }
}
