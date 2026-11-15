package io.stamethyst.ui.main

import io.stamethyst.backend.mods.ModManager
import io.stamethyst.model.ModItemUi

internal fun ModItemUi.withAgentPatchCatalogueAvailability(parents: Set<String>): ModItemUi {
    val available = ModManager.normalizeModId(manifestModId.ifBlank { modId }) in parents
    return if (hasAgentPatchCatalogueEntries == available) this
    else copy(hasAgentPatchCatalogueEntries = available)
}

/** Preserve selection, associations, folders and loading state when only the catalogue changes. */
internal fun MainScreenViewModel.UiState.withAgentPatchCatalogueAvailability(
    parents: Set<String>,
): MainScreenViewModel.UiState = copy(
    optionalMods = optionalMods.map { it.withAgentPatchCatalogueAvailability(parents) },
    dependencyMods = dependencyMods.map { it.withAgentPatchCatalogueAvailability(parents) },
)
