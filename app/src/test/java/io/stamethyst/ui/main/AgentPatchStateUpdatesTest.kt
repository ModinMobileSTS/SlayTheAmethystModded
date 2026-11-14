package io.stamethyst.ui.main

import io.stamethyst.model.AgentPatchModUi
import io.stamethyst.model.ModItemUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AgentPatchStateUpdatesTest {
    private val shared = AgentPatchModUi("P", "shared-parent-p", "Shared patch", "1", "", false)
    private val sibling = AgentPatchModUi("Q", "shared-parent-q", "Other revision", "1", "", true)

    @Test fun sharedPatchFollowsBothPathsInRequiredAndOptionalSnapshotsInBothDirections() {
        for (required in listOf(true, false)) {
            val original = listOf(
                mod("/a.jar", "Shared-Parent", required),
                mod("/b.jar", "shared-parent", required).copy(enabled = false),
            )
            var snapshot = original
            repeat(3) {
                for (enabled in listOf(true, false)) {
                    snapshot = snapshot.map { it.withAgentPatchEnabled(shared.patchModId, enabled) }
                    snapshot.forEachIndexed { index, updated ->
                        assertEquals(enabled, updated.agentPatchMods.first().enabled)
                        assertSame(sibling, updated.agentPatchMods[1])
                        // Parent selection, path, metadata and other patches must survive the local update.
                        assertEquals(original[index].copy(
                            agentPatchMods = listOf(shared.copy(enabled = enabled), sibling),
                        ), updated)
                    }
                }
            }
            assertEquals(false, original.first().agentPatchMods.first().enabled)
        }
    }

    @Test fun samePatchIdUnderAnotherParentAndOtherRevisionsKeepTheirState() {
        for (enabled in listOf(true, false)) {
            val otherParentPatch = shared.copy(patchModId = "other-parent-p", enabled = !enabled)
            val otherParent = mod("/other.jar", "other-parent", required = false)
                .copy(agentPatchMods = listOf(otherParentPatch))
            assertSame(otherParent, otherParent.withAgentPatchEnabled(shared.patchModId, enabled))

            val parent = mod("/a.jar", "shared-parent", required = false)
                .copy(agentPatchMods = listOf(shared.copy(enabled = !enabled), sibling.copy(enabled = !enabled)))
            val updated = parent.withAgentPatchEnabled(shared.patchModId, enabled)
            assertEquals(enabled, updated.agentPatchMods.first().enabled)
            assertSame(parent.agentPatchMods[1], updated.agentPatchMods[1])
        }
    }

    @Test fun sharedPatchModIdUpdatesEveryReferenceRegardlessOfLocalPatchId() {
        val alias = shared.copy(patchId = "local-alias", jarPath = "/other-copy.jar")
        val parent = mod("/a.jar", "shared-parent", required = false)
            .copy(agentPatchMods = listOf(shared, alias, sibling))

        assertEquals(listOf(shared.copy(enabled = true), alias.copy(enabled = true), sibling),
            parent.withAgentPatchEnabled(shared.patchModId, true).agentPatchMods)
    }

    @Test fun unchangedOrAbsentPatchKeepsTheCardInstance() {
        val parent = mod("/a.jar", "shared-parent", required = false)
        assertSame(parent, parent.withAgentPatchEnabled(shared.patchModId, false))
        assertSame(parent, parent.withAgentPatchEnabled("missing-patch", true))
        val withoutPatches = parent.copy(agentPatchMods = emptyList())
        assertSame(withoutPatches, withoutPatches.withAgentPatchEnabled(shared.patchModId, true))
    }

    private fun mod(path: String, manifestId: String, required: Boolean) = ModItemUi(
        modId = manifestId.lowercase(), manifestModId = manifestId, storagePath = path,
        name = "Parent $path", version = "1", description = "Parent description",
        dependencies = listOf("dependency"), required = required, installed = true, enabled = true,
        explicitPriority = 3, effectivePriority = 4, favorite = true,
        agentPatchMods = listOf(shared, sibling),
    )
}
