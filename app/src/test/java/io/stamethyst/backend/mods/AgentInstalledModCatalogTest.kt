package io.stamethyst.backend.mods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AgentInstalledModCatalogTest {
    @Test
    fun entries_marksBuiltInModsAsRequired() {
        val entries = AgentInstalledModCatalog.entries(
            installedMods = listOf(
                mod("basemod", "BaseMod", required = true, installed = true, enabled = true),
                mod("stslib", "StSLib", required = true, installed = true, enabled = true),
            ),
            parentModId = "basemod",
        )

        assertTrue(entries.all { it.required })
        assertTrue(entries.first { it.modId == "basemod" }.isParent)
        assertFalse(entries.first { it.modId == "stslib" }.isParent)
    }

    @Test
    fun entries_matchesTheParentByManifestModIdRegardlessOfCasing() {
        val entries = AgentInstalledModCatalog.entries(
            installedMods = listOf(
                mod("downfall", "Downfall", required = false, installed = true, enabled = false),
            ),
            parentModId = "Downfall",
        )

        val parent = entries.single()
        assertTrue(parent.isParent)
        assertEquals("downfall", parent.modId)
        assertEquals("Downfall", parent.manifestModId)
    }

    @Test
    fun entries_preserveEnabledInstalledAndDependencies() {
        val entries = AgentInstalledModCatalog.entries(
            installedMods = listOf(
                mod(
                    modId = "someMod",
                    manifestModId = "SomeMod",
                    required = false,
                    installed = true,
                    enabled = true,
                    dependencies = listOf("basemod", "stslib"),
                ),
            ),
            parentModId = "other",
        )

        val entry = entries.single()
        assertEquals("somemod", entry.modId)
        assertFalse(entry.required)
        assertTrue(entry.installed)
        assertTrue(entry.enabled)
        assertFalse(entry.isParent)
        assertEquals(listOf("basemod", "stslib"), entry.dependencies)
    }

    private fun mod(
        modId: String,
        manifestModId: String,
        required: Boolean,
        installed: Boolean,
        enabled: Boolean,
        dependencies: List<String> = emptyList(),
    ): ModManager.InstalledMod = ModManager.InstalledMod(
        modId,
        manifestModId,
        modId,
        "1.0.0",
        "",
        dependencies,
        File("/tmp/$modId.jar"),
        required,
        installed,
        enabled,
        null,
        null,
    )
}
