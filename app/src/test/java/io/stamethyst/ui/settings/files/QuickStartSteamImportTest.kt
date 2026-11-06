package io.stamethyst.ui.settings.files

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import io.stamethyst.backend.steam.SteamStsJarDownloadService
import io.stamethyst.config.RuntimePaths
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CancellationException

class QuickStartSteamImportTest {
    @Test fun sourceIsDeletedOnlyAfterTargetIsCommitted() = withContext { context, _ ->
        val source = source(context)
        val target = RuntimePaths.importedStsJar(context)
        val events = mutableListOf<String>()
        val cleaned = QuickStartSteamImport.importDownloadedJar(context, source,
            importJar = { downloaded, installed ->
                assertTrue(downloaded.isFile)
                assertEquals(target, installed)
                events += "import"
                installed.parentFile!!.mkdirs()
                installed.writeBytes(downloaded.readBytes())
                events += "commit"
            },
            cleanupSource = {
                assertEquals("downloaded-game", target.readText())
                events += "cleanup"
                SteamStsJarDownloadService.clearQuickStartSteamCache(context)
            })
        assertTrue(cleaned)
        assertEquals(listOf("import", "commit", "cleanup"), events)
        assertFalse(RuntimePaths.workshopQuickStartSteamRoot(context).exists())
        assertEquals("downloaded-game", target.readText())
    }

    @Test fun defaultCleanupRemovesSourceAndSidecarsButNotOtherWorkshopItems() = withContext { context, _ ->
        val source = source(context)
        val sidecar = File(source.parentFile, "download.resume").apply { writeText("resume") }
        val mod = File(RuntimePaths.workshopRoot(context), "646570/123/Keep.jar")
            .apply { parentFile!!.mkdirs(); writeText("mod") }
        assertTrue(QuickStartSteamImport.importDownloadedJar(context, source,
            importJar = { downloaded, installed ->
                installed.parentFile!!.mkdirs()
                installed.writeBytes(downloaded.readBytes())
            }))
        assertFalse(source.exists())
        assertFalse(sidecar.exists())
        assertEquals("mod", mod.readText())
        assertTrue(RuntimePaths.importedStsJar(context).isFile)
    }

    @Test fun importFailureLeavesSourceAndPreviousTargetUntouched() = withContext { context, _ ->
        val source = source(context)
        val target = RuntimePaths.importedStsJar(context).apply { parentFile!!.mkdirs(); writeText("old-game") }
        var cleaned = false
        val failure = runCatching {
            QuickStartSteamImport.importDownloadedJar(context, source,
                importJar = { _, _ -> throw IOException("Validation or atomic commit failed") },
                cleanupSource = { cleaned = true; true })
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertFalse(cleaned)
        assertEquals("downloaded-game", source.readText())
        assertEquals("old-game", target.readText())
    }

    @Test fun realValidatorRejectsInvalidDownloadWithoutDeletingSourceOrPreviousTarget() = withContext { context, _ ->
        val source = source(context)
        val target = RuntimePaths.importedStsJar(context).apply { parentFile!!.mkdirs(); writeText("old-game") }
        var cleaned = false
        val failure = runCatching {
            QuickStartSteamImport.importDownloadedJar(context, source,
                cleanupSource = { cleaned = true; true })
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertFalse(cleaned)
        assertEquals("downloaded-game", source.readText())
        assertEquals("old-game", target.readText())
        assertEquals(listOf(target.name), target.parentFile!!.list()!!.toList())
    }

    @Test fun sourceCleanupDoesNotFollowSymlinksToInstalledGameOrSaves() = withContext { context, root ->
        val source = source(context)
        val target = RuntimePaths.importedStsJar(context).apply { parentFile!!.mkdirs(); writeText("installed-game") }
        val save = File(root, "saves/progress.json").apply { parentFile!!.mkdirs(); writeText("save") }
        Files.createSymbolicLink(File(source.parentFile, "target-link.jar").toPath(), target.toPath())
        Files.createSymbolicLink(File(source.parentFile, "saves-link").toPath(), save.parentFile.toPath())

        assertTrue(SteamStsJarDownloadService.clearQuickStartSteamCache(context))
        assertFalse(RuntimePaths.workshopQuickStartSteamRoot(context).exists())
        assertEquals("installed-game", target.readText())
        assertEquals("save", save.readText())
    }

    @Test fun cancellationBeforeCommitDoesNotRunSuccessCleanup() = withContext { context, _ ->
        val source = source(context)
        var cleaned = false
        val failure = runCatching {
            QuickStartSteamImport.importDownloadedJar(context, source,
                importJar = { _, _ -> throw CancellationException("cancelled") },
                cleanupSource = { cleaned = true; true })
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertFalse(cleaned)
        assertTrue(source.isFile)
    }

    @Test fun cleanupFailureDoesNotFailTheCommittedImport() = withContext { context, _ ->
        for (throwOnCleanup in listOf(false, true)) {
            val source = source(context)
            val cleaned = QuickStartSteamImport.importDownloadedJar(context, source,
                importJar = { downloaded, installed ->
                    installed.parentFile!!.mkdirs()
                    installed.writeBytes(downloaded.readBytes())
                },
                cleanupSource = {
                    if (throwOnCleanup) throw IOException("Cannot remove source")
                    false
                })
            assertFalse(cleaned)
            assertEquals("downloaded-game", RuntimePaths.importedStsJar(context).readText())
            assertTrue(source.isFile)
        }
    }

    private fun source(context: Context) = File(RuntimePaths.workshopQuickStartSteamRoot(context), "desktop-1.0.jar")
        .apply { parentFile!!.mkdirs(); writeText("downloaded-game") }

    private fun withContext(test: (Context, File) -> Unit) {
        val root = Files.createTempDirectory("quick-start-import-test").toFile()
        try {
            val context = object : ContextWrapper(Application()) {
                override fun getFilesDir() = File(root, "internal-files")
                override fun getExternalFilesDir(type: String?) = File(root, "external-files")
                override fun getPackageName() = "io.stamethyst.test"
            }
            test(context, root)
        } finally { root.deleteRecursively() }
    }
}
