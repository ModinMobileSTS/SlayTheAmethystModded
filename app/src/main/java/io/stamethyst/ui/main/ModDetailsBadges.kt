package io.stamethyst.ui.main

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.model.ModItemUi
import io.stamethyst.model.WorkshopModState

@Composable
internal fun ModDetailsBadges(
    mod: ModItemUi,
    suggestionText: String?,
    suggestionRead: Boolean,
    associationBadge: ModAssociationBadgeUi?,
    associationEnabled: Boolean,
    priorityEnabled: Boolean,
    onSuggestion: () -> Unit,
    onImportPatch: () -> Unit,
    onAssociation: () -> Unit,
    onUpdate: () -> Unit,
    onWorkshop: () -> Unit,
    onPriority: () -> Unit,
    onCatalogue: () -> Unit,
    onContributions: () -> Unit,
    onAiMods: () -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        if (!suggestionText.isNullOrBlank()) {
            ModDetailsBadge(R.drawable.ic_error_outline,
                stringResource(if (suggestionRead) R.string.mod_details_badge_suggestion else R.string.mod_details_badge_suggestion_unread),
                onClick = onSuggestion)
        }
        if (!mod.importPatchDetails.isNullOrBlank() || mod.importPatches.isNotEmpty()) {
            ModDetailsBadge(R.drawable.ic_build,
                stringResource(if (mod.needsImportPatchUpdate) R.string.mod_details_badge_patch_update else R.string.mod_details_badge_patch),
                onClick = onImportPatch,
                containerColor = if (mod.needsImportPatchUpdate) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                contentColor = if (mod.needsImportPatchUpdate) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer)
        }
        associationBadge?.let {
            val color = Color(it.colorArgb)
            ModDetailsBadge(R.drawable.ic_link, stringResource(R.string.mod_details_badge_association, it.associatedCount),
                enabled = associationEnabled, onClick = onAssociation, containerColor = color,
                contentColor = if (color.luminance() > 0.48f) Color.Black else Color.White)
        }
        if (mod.workshop?.state == WorkshopModState.UpdateAvailable) {
            ModDetailsBadge(R.drawable.ic_workshop_update, stringResource(R.string.main_mod_update_available), onClick = onUpdate)
        }
        if (mod.favorite) ModDetailsBadge(R.drawable.ic_favorite_heart, stringResource(R.string.mod_details_badge_favorite))
        if (mod.newlyImported) ModDetailsBadge(R.drawable.ic_image_mod, stringResource(R.string.main_mod_new_import_badge))
        mod.workshop?.let {
            ModDetailsBadge(R.drawable.ic_settings_market, stringResource(R.string.mod_details_badge_workshop), onClick = onWorkshop)
            if (it.state != WorkshopModState.ImportedPatched && it.state != WorkshopModState.UpdateAvailable) {
                ModDetailsBadge(R.drawable.ic_workshop_download, workshopStateBadgeText(it.state))
            }
        }
        mod.effectivePriority?.let {
            ModDetailsBadge(R.drawable.ic_speed, stringResource(R.string.mod_details_badge_priority, it),
                enabled = priorityEnabled, onClick = onPriority)
        }
        if (mod.hasAgentPatchCatalogueEntries) {
            ModDetailsBadge(R.drawable.ic_code, stringResource(R.string.main_mod_agent_patch_badge), onClick = onCatalogue)
        }
        if (mod.agentPatchMods.isNotEmpty()) {
            ModDetailsBadge(R.drawable.ic_code,
                stringResource(R.string.mod_details_ai_badge, mod.agentPatchMods.size), onClick = onAiMods)
            ModContributionBadge(mod, enabled = true, onClick = onContributions, showLabel = true)
        }
    }
}

@Composable
internal fun ModDetailsBadge(
    @DrawableRes icon: Int,
    text: String,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Surface(
        modifier = if (onClick == null) Modifier else Modifier.clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(10.dp), color = containerColor,
        contentColor = contentColor.copy(alpha = if (enabled) 1f else 0.45f),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(painterResource(icon), null, Modifier.size(16.dp))
            Text(text, style = MaterialTheme.typography.labelSmall)
        }
    }
}
