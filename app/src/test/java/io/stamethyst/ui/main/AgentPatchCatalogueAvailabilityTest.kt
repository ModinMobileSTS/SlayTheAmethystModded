package io.stamethyst.ui.main

import io.stamethyst.model.AgentPatchModUi
import io.stamethyst.model.ModItemUi
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPatchCatalogueAvailabilityTest {
    private val mod = ModItemUi(
        modId = "local-name", manifestModId = "ParentMod", storagePath = "/mods/parent.jar",
        name = "Parent", version = "1.0", description = "Description", dependencies = listOf("basemod"),
        required = false, installed = true, enabled = true, explicitPriority = 2, effectivePriority = 2,
        favorite = true, agentPatchMods = listOf(AgentPatchModUi("patch", "patch_mod", "Patch", "1", "", true)),
    )

    @Test fun catalogueUpdateOnlyChangesBadgesAndPreservesAssociatedModState() {
        val dependency = mod.copy(modId = "dependency", manifestModId = "dependency", required = true)
        val associations = ModAssociationState(listOf(ModAssociationGroup("group", 123, setOf("a", "b"))))
        val before = MainScreenViewModel.UiState(
            initializing = false, controlsEnabled = true, optionalMods = listOf(mod),
            dependencyMods = listOf(dependency), modAssociationState = associations,
            folderAssignments = mapOf(mod.storagePath to "folder"), folderCollapsed = mapOf("folder" to false),
        )

        val after = before.withAgentPatchCatalogueAvailability(setOf("parentmod", "dependency"))

        assertEquals(before.copy(
            optionalMods = listOf(mod.copy(hasAgentPatchCatalogueEntries = true)),
            dependencyMods = listOf(dependency.copy(hasAgentPatchCatalogueEntries = true)),
        ), after)
        assertFalse(after.initializing)
        assertSame(associations, after.modAssociationState)
        assertSame(mod.agentPatchMods, after.optionalMods.single().agentPatchMods)
    }

    @Test fun removedCatalogueEntryClearsBadgeAndUnchangedItemsKeepTheirIdentity() {
        val available = mod.copy(hasAgentPatchCatalogueEntries = true)
        assertEquals(mod, available.withAgentPatchCatalogueAvailability(emptySet()))
        assertSame(mod, mod.withAgentPatchCatalogueAvailability(emptySet()))
        assertSame(available, available.withAgentPatchCatalogueAvailability(setOf("parentmod")))
        assertTrue(mod.copy(manifestModId = "").withAgentPatchCatalogueAvailability(setOf("local-name"))
            .hasAgentPatchCatalogueEntries)
    }

    @Test fun catalogueCallbacksAreNotWiredToFullModRefreshButInstallationStillIs() {
        // UI callback wiring is checked offline; runtime state preservation is exercised above.
        fun source(name: String): String {
            val relative = "src/main/java/io/stamethyst/ui/main/$name.kt"
            return listOf(File(relative), File("app/$relative")).first { it.isFile }.readText()
        }
        val card = source("ModCardItem")
        assertFalse(card.contains("onCatalogueRefreshed = callbacks.onRefreshAgentPatchMods"))
        assertEquals(2, Regex("onCatalogueRefreshed = callbacks.onAgentPatchCatalogueRefreshed").findAll(card).count())
        assertTrue(card.contains("onInstalled = callbacks.onRefreshAgentPatchMods"))
        assertTrue(source("ModFolderSection").contains(
            "onAgentPatchCatalogueRefreshed = { latestCallbacks.value.onAgentPatchCatalogueRefreshed() }"))
        assertTrue(source("MainScreen").contains("onAgentPatchCatalogueRefreshed = actions.onAgentPatchCatalogueRefreshed"))
        assertTrue(source("MainScreenActions").contains(
            "onAgentPatchCatalogueRefreshed = { viewModel.refreshAgentPatchCatalogueAvailability(activity) }"))
    }
}
