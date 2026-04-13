package io.stamethyst.ui.main

import io.stamethyst.model.ModItemUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModFolderUiHelpersTest {
    @Test
    fun resolveAssignmentKeyCandidates_includesLegacyExternalPathsForInternalStoragePath() {
        val mod = createMod(
            storagePath = "/data/user/0/io.stamethyst/files/sts/mods/TestMod.jar"
        )

        val candidates = resolveAssignmentKeyCandidates(mod)

        assertTrue(
            candidates.contains(
                "/storage/emulated/0/Android/data/io.stamethyst/files/sts/mods/TestMod.jar"
            )
        )
        assertTrue(
            candidates.contains(
                "/sdcard/Android/data/io.stamethyst/files/sts/mods/TestMod.jar"
            )
        )
    }

    @Test
    fun resolveAssignmentKeyCandidates_includesLegacyInternalPathsForExternalStoragePath() {
        val mod = createMod(
            storagePath = "/storage/emulated/0/Android/data/io.stamethyst/files/sts/mods/TestMod.jar"
        )

        val candidates = resolveAssignmentKeyCandidates(mod)

        assertTrue(
            candidates.contains(
                "/data/user/0/io.stamethyst/files/sts/mods/TestMod.jar"
            )
        )
        assertTrue(
            candidates.contains(
                "/data/data/io.stamethyst/files/sts/mods/TestMod.jar"
            )
        )
    }

    @Test
    fun resolveAssignmentKeyCandidates_includesLegacyModsPathForLibraryStoragePath() {
        val mod = createMod(
            storagePath = "/storage/emulated/0/Android/data/io.stamethyst/files/sts/mods_library/TestMod.jar"
        )

        val candidates = resolveAssignmentKeyCandidates(mod)

        assertTrue(
            candidates.contains(
                "/storage/emulated/0/Android/data/io.stamethyst/files/sts/mods/TestMod.jar"
            )
        )
        assertTrue(
            candidates.contains(
                "/data/user/0/io.stamethyst/files/sts/mods/TestMod.jar"
            )
        )
    }

    @Test
    fun resolveExistingModStoragePath_prefersDirectStoragePathWhenPresent() {
        val storagePath = "/storage/emulated/0/Android/data/io.stamethyst/files/sts/mods_library/TestMod.jar"

        val resolved = resolveExistingModStoragePath(storagePath) { candidate ->
            candidate == storagePath
        }

        assertEquals(storagePath, resolved)
    }

    @Test
    fun resolveExistingModStoragePath_fallsBackToSiblingRuntimePath() {
        val storagePath = "/storage/emulated/0/Android/data/io.stamethyst/files/sts/mods_library/TestMod.jar"
        val runtimePath = "/storage/emulated/0/Android/data/io.stamethyst/files/sts/mods/TestMod.jar"

        val resolved = resolveExistingModStoragePath(storagePath) { candidate ->
            candidate == runtimePath
        }

        assertEquals(runtimePath, resolved)
    }

    @Test
    fun resolveExistingModStoragePath_fallsBackToLegacyInternalLibraryPath() {
        val storagePath = "/storage/emulated/0/Android/data/io.stamethyst/files/sts/mods_library/TestMod.jar"
        val legacyInternalPath = "/data/user/0/io.stamethyst/files/sts/mods_library/TestMod.jar"

        val resolved = resolveExistingModStoragePath(storagePath) { candidate ->
            candidate == legacyInternalPath
        }

        assertEquals(legacyInternalPath, resolved)
    }

    @Test
    fun resolveModSuggestionReadKey_prefersManifestModIdAndHashesSuggestionContent() {
        val mod = createMod(
            storagePath = "/storage/emulated/0/Android/data/io.stamethyst/files/sts/mods/TestMod.jar"
        )

        val readKey = resolveModSuggestionReadKey(mod, "This mod needs a compatibility patch.")
        val updatedReadKey = resolveModSuggestionReadKey(mod, "This mod needs a restart.")

        assertTrue(readKey?.startsWith("manifest:testmod|") == true)
        assertTrue(readKey != updatedReadKey)
    }

    @Test
    fun resolveModSuggestionReadKey_fallsBackToStoragePathWhenIdsMissing() {
        val mod = createMod(
            storagePath = "C:\\mods\\TestMod.jar",
            modId = "",
            manifestModId = ""
        )

        val readKey = resolveModSuggestionReadKey(mod, "Read me.")

        assertEquals(
            true,
            readKey?.startsWith("path:C:/mods/TestMod.jar|")
        )
    }

    @Test
    fun collectEnabledUnreadSuggestionModDisplayNames_onlyReturnsEnabledUnreadMods() {
        val alpha = createMod(
            storagePath = "C:\\mods\\Alpha.jar",
            modId = "alpha",
            manifestModId = "alpha"
        )
        val beta = createMod(
            storagePath = "C:\\mods\\Beta.jar",
            modId = "beta",
            manifestModId = "beta"
        ).copy(enabled = false)
        val gamma = createMod(
            storagePath = "C:\\mods\\Gamma.jar",
            modId = "gamma",
            manifestModId = "gamma"
        )
        val suggestions = mapOf(
            "alpha" to "Alpha notice",
            "beta" to "Beta notice",
            "gamma" to "Gamma notice"
        )
        val readKeys = setOfNotNull(resolveModSuggestionReadKey(gamma, "Gamma notice"))

        val unreadNames = collectEnabledUnreadSuggestionModDisplayNames(
            mods = listOf(alpha, beta, gamma),
            suggestions = suggestions,
            readSuggestionKeys = readKeys
        )

        assertEquals(listOf("Test Mod"), unreadNames)
    }

    private fun createMod(
        storagePath: String,
        modId: String = "testmod",
        manifestModId: String = "testmod"
    ): ModItemUi {
        return ModItemUi(
            modId = modId,
            manifestModId = manifestModId,
            storagePath = storagePath,
            name = "Test Mod",
            version = "1.0.0",
            description = "",
            dependencies = emptyList(),
            required = false,
            installed = true,
            enabled = true,
            explicitPriority = null,
            effectivePriority = null
        )
    }
}
