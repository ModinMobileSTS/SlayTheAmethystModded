package io.stamethyst.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.mods.AgentPatchContributionStore
import io.stamethyst.backend.mods.AgentPatchCatalogueService
import io.stamethyst.model.ModItemUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ModContributionBadge(mod: ModItemUi, enabled: Boolean, onClick: () -> Unit, showLabel: Boolean = false) {
    val context = LocalContext.current
    val active = contributionActive(mod)
    val parent = mod.manifestModId.ifBlank { mod.modId }
    val indexedIds by produceState<Set<String>?>(null, parent, mod) {
        value = withContext(Dispatchers.IO) {
            AgentPatchCatalogueService.readCached(context.applicationContext, parent)
                ?.patches?.map { it.patchModId }?.toSet()
        }
    }
    // Catalogue downloads alone never earn a contribution badge. An upload receipt does.
    if (active || mod.agentPatchMods.any { it.patchModId !in indexedIds.orEmpty() }) {
        if (showLabel) {
            ModDetailsBadge(
                R.drawable.ic_ai_patch_contribution,
                stringResource(if (active) R.string.mod_details_badge_contributed else R.string.mod_details_badge_contribute),
                enabled = enabled,
                onClick = onClick,
            )
        } else {
            AgentPatchContributionBadge(active, enabled, onClick)
        }
    }
}

@Composable
internal fun contributionActive(mod: ModItemUi): Boolean {
    val context = LocalContext.current
    val revision by AgentPatchContributionStore.revision.collectAsState()
    return remember(mod, revision) {
        val parent = mod.manifestModId.ifBlank { mod.modId }
        mod.agentPatchMods.any {
            AgentPatchContributionStore.uploadedVersion(context, parent, it.patchModId) != null
        }
    }
}

/** Compact icon-only badge; the animation only exists while an activated badge is composed. */
@Composable
internal fun AgentPatchContributionBadge(active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    ModCardBadgeSurface(
        enabled = enabled,
        onClick = onClick,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painterResource(R.drawable.ic_ai_patch_contribution),
                contentDescription = stringResource(if (active) R.string.ai_patch_contribution_active else R.string.ai_patch_contribution_pending),
                modifier = Modifier.padding(4.dp).size(12.dp),
            )
            if (active) {
                AgentPatchContributionEffects(Modifier.matchParentSize())
            }
        }
    }
}
