package io.stamethyst.ui.main

import io.stamethyst.model.ModItemUi
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

    private fun createMod(storagePath: String): ModItemUi {
        return ModItemUi(
            modId = "testmod",
            manifestModId = "testmod",
            storagePath = storagePath,
            name = "Test Mod",
            version = "1.0.0",
            description = "",
            dependencies = emptyList(),
            required = false,
            installed = true,
            enabled = true,
            priorityRoot = false,
            priorityLoad = false
        )
    }
}
