package io.stamethyst.backend.mods

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
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
    fun syncRuntimeOptionalMods_keepsReservedAndMirrorsEnabledLibraryFilesOnly() {
        val tempDir = Files.createTempDirectory("optional-mod-storage-sync-test")
        val runtimeModsDir = Files.createDirectory(tempDir.resolve("mods")).toFile()
        val libraryDir = Files.createDirectory(tempDir.resolve("mods_library")).toFile()

        Files.write(runtimeModsDir.toPath().resolve("BaseMod.jar"), byteArrayOf(1))
        Files.write(runtimeModsDir.toPath().resolve("StSLib.jar"), byteArrayOf(2))
        Files.write(runtimeModsDir.toPath().resolve("AmethystRuntimeCompat.jar"), byteArrayOf(10))
        Files.write(runtimeModsDir.toPath().resolve("stale.jar"), byteArrayOf(9))
        val runtimeAlpha = Files.write(runtimeModsDir.toPath().resolve("Alpha.jar"), byteArrayOf(0)).toFile()

        val libraryAlpha = Files.write(libraryDir.toPath().resolve("Alpha.jar"), byteArrayOf(3, 4, 5)).toFile()
        val libraryBeta = Files.write(libraryDir.toPath().resolve("Beta.jar"), byteArrayOf(6, 7, 8)).toFile()

        OptionalModStorageCoordinator.syncRuntimeOptionalMods(
            runtimeModsDir = runtimeModsDir,
            enabledLibraryFiles = listOf(libraryAlpha, libraryBeta)
        )

        assertTrue(runtimeModsDir.toPath().resolve("BaseMod.jar").toFile().isFile)
        assertTrue(runtimeModsDir.toPath().resolve("StSLib.jar").toFile().isFile)
        assertTrue(runtimeModsDir.toPath().resolve("AmethystRuntimeCompat.jar").toFile().isFile)
        assertFalse(runtimeModsDir.toPath().resolve("stale.jar").toFile().exists())
        assertTrue(runtimeModsDir.toPath().resolve("Alpha.jar").toFile().isFile)
        assertTrue(runtimeModsDir.toPath().resolve("Beta.jar").toFile().isFile)
        assertArrayEquals(
            Files.readAllBytes(libraryAlpha.toPath()),
            Files.readAllBytes(runtimeAlpha.toPath())
        )
        assertArrayEquals(
            Files.readAllBytes(libraryBeta.toPath()),
            Files.readAllBytes(runtimeModsDir.toPath().resolve("Beta.jar"))
        )
    }
}
