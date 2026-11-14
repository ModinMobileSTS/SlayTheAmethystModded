package io.stamethyst.ui.main

import io.stamethyst.model.ModItemUi

/** Enablement is stored by patchModId and shared by every installation of the parent. */
internal fun ModItemUi.withAgentPatchEnabled(patchModId: String, enabled: Boolean): ModItemUi {
    if (agentPatchMods.none { it.patchModId == patchModId && it.enabled != enabled }) return this
    return copy(agentPatchMods = agentPatchMods.map { patch ->
        if (patch.patchModId == patchModId && patch.enabled != enabled) patch.copy(enabled = enabled) else patch
    })
}
