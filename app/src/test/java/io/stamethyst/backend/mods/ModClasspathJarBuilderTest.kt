package io.stamethyst.backend.mods

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModClasspathJarBuilderTest {
    @Test
    fun gdxApiRejectsMtsOwnedBatchAndTextureTypes() {
        val root = Files.createTempDirectory("gdx-api-classpath-").toFile()
        try {
            val entries = REQUIRED_GDX_CLASSES.toList()
            assertTrue(hasRequiredGdxApi(root, entries))
            assertFalse(
                hasRequiredGdxApi(
                    root,
                    entries + "com/badlogic/gdx/graphics/g2d/Batch.class"
                )
            )
            assertFalse(
                hasRequiredGdxApi(
                    root,
                    entries + "com/badlogic/gdx/graphics/Texture.class"
                )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun hasRequiredGdxApi(root: File, entries: List<String>): Boolean {
        val jar = File(root, "mts-gdx-api.jar")
        ZipOutputStream(jar.outputStream()).use { zip ->
            entries.forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(name.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
        }
        return ModClasspathJarBuilder.hasRequiredGdxApi(jar)
    }
}
