package io.stamethyst.backend.mods

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

class OptionalModStorageCoordinatorTest {
    @Test
    fun migrateLegacyOptionalMods_movesOptionalJarsIntoLibraryAndRewritesConfigs() {
        val tempDir = Files.createTempDirectory("optional-mod-storage-migration-test")
        val runtimeModsDir = Files.createDirectory(tempDir.resolve("mods")).toFile()
        val libraryDir = Files.createDirectory(tempDir.resolve("mods_library")).toFile()
        val enabledModsConfig = tempDir.resolve("enabled_mods.txt").toFile()
        val priorityModsConfig = tempDir.resolve("priority_mod_roots.txt").toFile()

        Files.write(runtimeModsDir.toPath().resolve("BaseMod.jar"), byteArrayOf(1))
        Files.write(runtimeModsDir.toPath().resolve("StSLib.jar"), byteArrayOf(2))
        Files.write(runtimeModsDir.toPath().resolve("AmethystRuntimeCompat.jar"), byteArrayOf(5))
        Files.write(runtimeModsDir.toPath().resolve("RamSaver.jar"), byteArrayOf(6))
        val firstOptional = Files.write(runtimeModsDir.toPath().resolve("Alpha.jar"), byteArrayOf(3)).toFile()
        val secondOptional = Files.write(runtimeModsDir.toPath().resolve("Beta.jar"), byteArrayOf(4)).toFile()

        enabledModsConfig.writeText(
            listOf(firstOptional.absolutePath, "alpha", secondOptional.absolutePath).joinToString("\n"),
            StandardCharsets.UTF_8
        )
        priorityModsConfig.writeText(
            secondOptional.absolutePath,
            StandardCharsets.UTF_8
        )

        OptionalModStorageCoordinator.migrateLegacyOptionalMods(
            legacyRuntimeModsDir = runtimeModsDir,
            libraryDir = libraryDir,
            enabledModsConfig = enabledModsConfig,
            priorityModsConfig = priorityModsConfig,
            normalizeSelectionPath = { it }
        )

        assertFalse(firstOptional.exists())
        assertFalse(secondOptional.exists())
        assertTrue(runtimeModsDir.toPath().resolve("BaseMod.jar").toFile().isFile)
        assertTrue(runtimeModsDir.toPath().resolve("StSLib.jar").toFile().isFile)
        assertTrue(runtimeModsDir.toPath().resolve("AmethystRuntimeCompat.jar").toFile().isFile)
        assertTrue(runtimeModsDir.toPath().resolve("RamSaver.jar").toFile().isFile)

        val migratedFirst = libraryDir.toPath().resolve("Alpha.jar").toFile()
        val migratedSecond = libraryDir.toPath().resolve("Beta.jar").toFile()
        assertTrue(migratedFirst.isFile)
        assertTrue(migratedSecond.isFile)
        assertEquals(
            listOf(migratedFirst.absolutePath, "alpha", migratedSecond.absolutePath),
            enabledModsConfig.readLines(StandardCharsets.UTF_8)
        )
        assertEquals(
            migratedSecond.absolutePath,
            priorityModsConfig.readText(StandardCharsets.UTF_8).trim()
        )
    }

    @Test
    fun cleanupLegacyRuntimeMods_salvagesUniqueOptionalJarsRewritesConfigsAndPreservesModData() {
        val tempDir = Files.createTempDirectory("optional-mod-storage-cleanup-test")
        val runtimeModsDir = Files.createDirectory(tempDir.resolve("mods")).toFile()
        val libraryDir = Files.createDirectory(tempDir.resolve("mods_library")).toFile()
        val enabledModsConfig = tempDir.resolve("enabled_mods.txt").toFile()
        val priorityModsConfig = tempDir.resolve("priority_mod_roots.txt").toFile()

        Files.write(runtimeModsDir.toPath().resolve("BaseMod.jar"), byteArrayOf(1))
        Files.write(runtimeModsDir.toPath().resolve("StSLib.jar"), byteArrayOf(2))
        Files.write(runtimeModsDir.toPath().resolve("AmethystRuntimeCompat.jar"), byteArrayOf(10))
        Files.write(runtimeModsDir.toPath().resolve("RamSaver.jar"), byteArrayOf(11))
        val runtimeAlpha = Files.write(runtimeModsDir.toPath().resolve("Alpha.jar"), byteArrayOf(3, 4, 5)).toFile()
        val runtimeBeta = Files.write(runtimeModsDir.toPath().resolve("Beta.jar"), byteArrayOf(6, 7, 8)).toFile()
        val libraryBeta = Files.write(libraryDir.toPath().resolve("Beta.jar"), byteArrayOf(6, 7, 8)).toFile()
        val wordSpireDir = File(runtimeModsDir, "WordSpire").apply { mkdirs() }
        val dictionary = File(wordSpireDir, "JLPT10k.apkg").apply { writeText("dictionary data") }
        val audioDir = File(wordSpireDir, "audio").apply { mkdirs() }
        val audioFile = File(audioDir, "word.mp3").apply { writeText("audio data") }
        val configFile = File(runtimeModsDir, "config.json").apply { writeText("{}") }

        enabledModsConfig.writeText(
            listOf(runtimeAlpha.absolutePath, runtimeBeta.absolutePath).joinToString("\n"),
            StandardCharsets.UTF_8
        )
        priorityModsConfig.writeText(runtimeBeta.absolutePath, StandardCharsets.UTF_8)

        OptionalModStorageCoordinator.salvageLegacyOptionalModsForRuntimeCleanup(
            legacyRuntimeModsDir = runtimeModsDir,
            libraryDir = libraryDir,
            enabledModsConfig = enabledModsConfig,
            priorityModsConfig = priorityModsConfig,
            normalizeSelectionPath = { it }
        )
        OptionalModStorageCoordinator.deleteLegacyRuntimeModJars(runtimeModsDir)

        val migratedAlpha = libraryDir.toPath().resolve("Alpha.jar").toFile()
        assertTrue(runtimeModsDir.isDirectory)
        listOf("BaseMod.jar", "StSLib.jar", "AmethystRuntimeCompat.jar", "RamSaver.jar").forEach { name ->
            assertFalse(File(runtimeModsDir, name).exists())
        }
        assertFalse(runtimeAlpha.exists())
        assertFalse(runtimeBeta.exists())
        assertEquals("dictionary data", dictionary.readText())
        assertEquals("audio data", audioFile.readText())
        assertEquals("{}", configFile.readText())
        assertTrue(migratedAlpha.isFile)
        assertTrue(libraryBeta.isFile)
        assertFalse(libraryDir.toPath().resolve("Beta (2).jar").toFile().exists())
        assertEquals(
            listOf(migratedAlpha.absolutePath, libraryBeta.absolutePath),
            enabledModsConfig.readLines(StandardCharsets.UTF_8)
        )
        assertEquals(libraryBeta.absolutePath, priorityModsConfig.readText(StandardCharsets.UTF_8).trim())
    }

    @Test
    fun deleteLegacyRuntimeModJars_onlyDeletesTopLevelJarFilesAndPreservesDirectories() {
        val tempDir = Files.createTempDirectory("optional-mod-storage-jar-cleanup-test")
        val runtimeModsDir = Files.createDirectory(tempDir.resolve("mods")).toFile()
        val jarFiles = listOf("BaseMod.jar", "Uppercase.JAR", "Mixed.JaR").map { name ->
            File(runtimeModsDir, name).apply { writeText("legacy jar") }
        }
        val backupFile = File(runtimeModsDir, "WordSpire.jar.bak").apply { writeText("backup") }
        val jarNamedDirectory = File(runtimeModsDir, "data.jar").apply { mkdirs() }
        val nestedJar = File(jarNamedDirectory, "helper.jar").apply { writeText("mod data") }
        val emptyDirectory = File(runtimeModsDir, "empty").apply { mkdirs() }

        repeat(2) {
            OptionalModStorageCoordinator.deleteLegacyRuntimeModJars(runtimeModsDir)

            jarFiles.forEach { assertFalse(it.exists()) }
            assertTrue(runtimeModsDir.isDirectory)
            assertTrue(jarNamedDirectory.isDirectory)
            assertTrue(emptyDirectory.isDirectory)
            assertEquals("backup", backupFile.readText())
            assertEquals("mod data", nestedJar.readText())
        }
    }

    @Test
    fun deleteLegacyRuntimeModJars_keepsEmptyRuntimeDirectory() {
        val tempDir = Files.createTempDirectory("optional-mod-storage-empty-cleanup-test")
        val runtimeModsDir = Files.createDirectory(tempDir.resolve("mods")).toFile()

        OptionalModStorageCoordinator.deleteLegacyRuntimeModJars(runtimeModsDir)

        assertTrue(runtimeModsDir.isDirectory)
    }

    @Test
    fun deleteLegacyRuntimeModJars_doesNotCreateMissingRuntimeDirectory() {
        val tempDir = Files.createTempDirectory("optional-mod-storage-missing-cleanup-test")
        val runtimeModsDir = tempDir.resolve("mods").toFile()

        OptionalModStorageCoordinator.deleteLegacyRuntimeModJars(runtimeModsDir)

        assertFalse(runtimeModsDir.exists())
    }

    @Test
    fun writeMtsModFileList_writesAbsoluteJarPathsInOrder() {
        val tempDir = Files.createTempDirectory("optional-mod-storage-file-list-test")
        val first = Files.write(tempDir.resolve("BaseMod.jar"), byteArrayOf(1)).toFile()
        val second = Files.write(tempDir.resolve("Alpha.jar"), byteArrayOf(2)).toFile()
        val fileList = tempDir.resolve(".mts_mod_file_list").toFile()

        OptionalModStorageCoordinator.writeMtsModFileList(fileList, listOf(first, second))

        assertEquals(
            listOf(first.absolutePath, second.absolutePath),
            fileList.readLines(StandardCharsets.UTF_8)
        )
    }

    @Test
    fun ensureOptionalModLibraryReady_keepsCommittedImportTargetWhenMarkerStale() {
        val roots = TestRoots.create("optional-mod-storage-interrupted-import-test")
        val libraryDir = RuntimePaths.optionalModsLibraryDir(roots.context).apply { mkdirs() }
        val target = Files.write(libraryDir.toPath().resolve("HalfImported.jar"), byteArrayOf(1, 2, 3)).toFile()
        val marker = libraryDir.toPath().resolve(".HalfImported.jar.importing.marker").toFile()
        marker.writeText(target.name, StandardCharsets.UTF_8)
        val scratch = Files.write(libraryDir.toPath().resolve(".HalfImported.jar.123.importing"), byteArrayOf(9)).toFile()
        val staleTimestamp = System.currentTimeMillis() - 2L * 60L * 60L * 1000L
        marker.setLastModified(staleTimestamp)
        scratch.setLastModified(staleTimestamp)

        OptionalModStorageCoordinator.ensureOptionalModLibraryReady(roots.context)

        assertTrue(target.exists())
        assertFalse(marker.exists())
        assertFalse(scratch.exists())
    }

    @Test
    fun ensureOptionalModLibraryReady_keepsFreshImportArtifacts() {
        val roots = TestRoots.create("optional-mod-storage-active-import-test")
        val libraryDir = RuntimePaths.optionalModsLibraryDir(roots.context).apply { mkdirs() }
        val marker = libraryDir.toPath().resolve(".Active.jar.importing.marker").toFile()
        marker.writeText("Active.jar", StandardCharsets.UTF_8)
        val scratch = Files.write(libraryDir.toPath().resolve(".Active.jar.123.importing"), byteArrayOf(9)).toFile()

        OptionalModStorageCoordinator.ensureOptionalModLibraryReady(roots.context)

        assertTrue(marker.exists())
        assertTrue(scratch.exists())
    }

    private class TestRoots private constructor(
        val rootDir: File,
        val context: Context
    ) {
        companion object {
            fun create(prefix: String): TestRoots {
                val rootDir = Files.createTempDirectory(prefix).toFile()
                val filesDir = File(rootDir, "internal-files").apply { mkdirs() }
                val externalFilesDir = File(rootDir, "external-files").apply { mkdirs() }
                return TestRoots(
                    rootDir = rootDir,
                    context = object : ContextWrapper(Application()) {
                        override fun getFilesDir(): File = filesDir

                        override fun getExternalFilesDir(type: String?): File = externalFilesDir

                        override fun getPackageName(): String = "io.stamethyst.test"
                    }
                )
            }
        }
    }
}
