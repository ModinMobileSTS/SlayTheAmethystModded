package io.stamethyst.backend.fs

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import io.stamethyst.config.RuntimePaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class LauncherJunkFileCleanerTest {
    @Test
    fun clearRemovesSafeJunkButKeepsUserData() {
        val roots = TestRoots.create("launcher-junk-cleaner-")
        try {
            writeFile(RuntimePaths.mtsPatchCacheJar(roots.context), "cache-jar")
            writeFile(File(RuntimePaths.legacyInternalStsRoot(roots.context), "mts_patch_cache_debug.log"), "legacy-log")
            writeFile(File(roots.externalCacheDir, "mod-import-sessions/1/source.jar"), "session")
            writeFile(File(roots.externalCacheDir, "mod-import-preview/preview.jar"), "preview")
            writeFile(File(RuntimePaths.workshopImportSessionsRoot(roots.context), "1/source.jar"), "persistent-session")

            val keptMod = writeFile(File(RuntimePaths.modsDir(roots.context), "Keep.jar"), "keep-me")
            val keptSave = writeFile(File(RuntimePaths.preferencesDir(roots.context), "STSDataVagabond"), "save")

            val result = LauncherJunkFileCleaner.clear(roots.context)

            assertEquals(5, result.deletedTargetCount)
            assertEquals(0, result.failedTargetCount)
            assertTrue(result.deletedBytes >= "cache-jarlegacy-logsessionpreview".toByteArray(StandardCharsets.UTF_8).size)
            assertFalse(RuntimePaths.mtsPatchCacheDir(roots.context).exists())
            assertFalse(File(RuntimePaths.legacyInternalStsRoot(roots.context), "mts_patch_cache_debug.log").exists())
            assertFalse(File(roots.externalCacheDir, "mod-import-sessions").exists())
            assertFalse(File(roots.externalCacheDir, "mod-import-preview").exists())
            assertFalse(RuntimePaths.workshopImportSessionsRoot(roots.context).exists())
            assertTrue(keptMod.isFile)
            assertTrue(keptSave.isFile)
        } finally {
            roots.rootDir.deleteRecursively()
        }
    }

    private fun writeFile(file: File, text: String): File {
        file.parentFile?.mkdirs()
        file.writeText(text, StandardCharsets.UTF_8)
        return file
    }

    private class TestRoots private constructor(
        val rootDir: File,
        val context: Context,
        val externalCacheDir: File,
    ) {
        companion object {
            fun create(prefix: String): TestRoots {
                val rootDir = Files.createTempDirectory(prefix).toFile()
                val filesDir = File(rootDir, "internal-files").apply { mkdirs() }
                val cacheDir = File(rootDir, "cache").apply { mkdirs() }
                val externalCacheDir = File(rootDir, "external-cache").apply { mkdirs() }
                val externalFilesDir = File(rootDir, "external-files").apply { mkdirs() }
                return TestRoots(
                    rootDir = rootDir,
                    externalCacheDir = externalCacheDir,
                    context = object : ContextWrapper(Application()) {
                        override fun getFilesDir(): File = filesDir

                        override fun getCacheDir(): File = cacheDir

                        override fun getExternalCacheDir(): File = externalCacheDir

                        override fun getExternalFilesDir(type: String?): File = externalFilesDir

                        override fun getPackageName(): String = "io.stamethyst.test"
                    }
                )
            }
        }
    }
}
