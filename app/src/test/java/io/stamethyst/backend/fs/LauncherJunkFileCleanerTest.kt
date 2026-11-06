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
            writeFile(File(RuntimePaths.transientFilesRoot(roots.context), "remote-bitmap-cache/image.img"), "bitmap")
            val quickStartSource = writeFile(File(RuntimePaths.workshopQuickStartSteamRoot(roots.context), "desktop-1.0.jar"), "download-source")
            val legacyQuickStartSource = writeFile(File(roots.context.filesDir, "workshop/quickstart-steam/desktop-1.0.jar"), "legacy-source")

            val keptMod = writeFile(File(RuntimePaths.modsDir(roots.context), "Keep.jar"), "keep-me")
            val keptSave = writeFile(File(RuntimePaths.preferencesDir(roots.context), "STSDataVagabond"), "save")
            val keptGame = writeFile(RuntimePaths.importedStsJar(roots.context), "installed-game")
            val keptWorkshopMod = writeFile(File(RuntimePaths.workshopRoot(roots.context), "646570/123/Keep.jar"), "workshop-mod")

            val result = LauncherJunkFileCleaner.clear(roots.context)

            assertEquals(8, result.deletedTargetCount)
            assertEquals(0, result.failedTargetCount)
            assertTrue(result.deletedBytes >= "cache-jarlegacy-logsessionpreview".toByteArray(StandardCharsets.UTF_8).size)
            assertFalse(RuntimePaths.mtsPatchCacheDir(roots.context).exists())
            assertFalse(File(RuntimePaths.legacyInternalStsRoot(roots.context), "mts_patch_cache_debug.log").exists())
            assertFalse(File(roots.externalCacheDir, "mod-import-sessions").exists())
            assertFalse(File(roots.externalCacheDir, "mod-import-preview").exists())
            assertFalse(RuntimePaths.workshopImportSessionsRoot(roots.context).exists())
            assertFalse(RuntimePaths.transientFilesRoot(roots.context).exists())
            assertFalse(quickStartSource.exists())
            assertFalse(legacyQuickStartSource.exists())
            assertTrue(keptMod.isFile)
            assertTrue(keptSave.isFile)
            assertEquals("installed-game", keptGame.readText())
            assertEquals("workshop-mod", keptWorkshopMod.readText())
        } finally {
            roots.rootDir.deleteRecursively()
        }
    }

    @Test
    fun quickStartSourceTargetsAreDeduplicatedWithoutExternalStorage() {
        val roots = TestRoots.create("launcher-junk-internal-")
        try {
            val internalContext = object : ContextWrapper(roots.context) {
                override fun getExternalFilesDir(type: String?): File? = null
            }
            val sourceRoot = RuntimePaths.workshopQuickStartSteamRoot(internalContext)
            val source = writeFile(File(sourceRoot, "desktop-1.0.jar"), "source")
            val target = writeFile(RuntimePaths.importedStsJar(internalContext), "installed")
            val targets = LauncherJunkFileCleaner.buildCleanupTargets(internalContext)
            assertEquals(1, targets.count { it.canonicalFile == sourceRoot.canonicalFile })
            val result = LauncherJunkFileCleaner.clearTargets(targets)
            assertEquals(1, result.deletedTargetCount)
            assertEquals(0, result.failedTargetCount)
            assertEquals("source".length.toLong(), result.deletedBytes)
            assertFalse(source.exists())
            assertEquals("installed", target.readText())
        } finally { roots.rootDir.deleteRecursively() }
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
