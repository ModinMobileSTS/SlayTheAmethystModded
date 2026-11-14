package io.stamethyst.ui.main

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.model.ModItemUi
import io.stamethyst.model.WorkshopModState

@Composable
internal fun ModDetailsBadges(
    mod: ModItemUi,
    associationBadge: ModAssociationBadgeUi?,
    associationEnabled: Boolean,
    onImportPatch: () -> Unit,
    onAssociation: () -> Unit,
    onUpdate: () -> Unit,
    onWorkshop: () -> Unit,
    onCatalogue: () -> Unit,
    onContributions: () -> Unit,
    onAiMods: () -> Unit,
) {
    val relations = buildList {
        if (!mod.importPatchDetails.isNullOrBlank() || mod.importPatches.isNotEmpty()) {
            add(ModDetailsRelationUi(
                icon = R.drawable.ic_build,
                title = stringResource(R.string.mod_details_badge_patch),
                description = stringResource(R.string.mod_details_patch_description),
                status = stringResource(if (mod.needsImportPatchUpdate) R.string.mod_details_patch_update else R.string.mod_details_patch_applied),
                attention = mod.needsImportPatchUpdate,
                onClick = onImportPatch,
            ))
        }
        associationBadge?.let {
            add(ModDetailsRelationUi(
                icon = R.drawable.ic_link,
                title = stringResource(R.string.mod_details_badge_association, it.associatedCount),
                iconColor = Color(it.colorArgb),
                enabled = associationEnabled,
                onClick = onAssociation,
            ))
        }
        if (mod.workshop?.state == WorkshopModState.UpdateAvailable) {
            add(ModDetailsRelationUi(
                icon = R.drawable.ic_workshop_update,
                title = stringResource(R.string.main_mod_update_available),
                onClick = onUpdate,
            ))
        }
        add(ModDetailsRelationUi(
            icon = R.drawable.ic_code,
            title = stringResource(R.string.mod_details_ai_title),
            description = stringResource(R.string.mod_details_ai_relation_description),
            status = mod.agentPatchMods.takeIf { it.isNotEmpty() }?.let {
                stringResource(R.string.mod_details_ai_installed, it.size)
            },
            onClick = onAiMods,
        ))
        if (mod.hasAgentPatchCatalogueEntries) {
            add(ModDetailsRelationUi(
                icon = R.drawable.ic_code,
                title = stringResource(R.string.main_mod_agent_patch_badge),
                onClick = onCatalogue,
            ))
        }
        if (mod.workshop != null) {
            add(ModDetailsRelationUi(
                icon = R.drawable.ic_settings_market,
                title = stringResource(R.string.mod_details_market),
                description = stringResource(R.string.mod_details_market_description),
                onClick = onWorkshop,
            ))
        }
    }
    Column(Modifier.fillMaxWidth().testTag("mod_details_section_badges"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ModDetailsSectionTitle(stringResource(R.string.mod_details_relations))
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f),
        ) {
            Column {
                relations.forEachIndexed { index, relation ->
                    if (index > 0) HorizontalDivider(
                        Modifier.padding(horizontal = 14.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                    ModDetailsRelationRow(relation)
                }
            }
        }
        if (mod.newlyImported || mod.agentPatchMods.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                if (mod.newlyImported) ModDetailsBadge(R.drawable.ic_image_mod, stringResource(R.string.main_mod_new_import_badge))
                if (mod.agentPatchMods.isNotEmpty()) {
                    ModContributionBadge(mod, enabled = true, onClick = onContributions, showLabel = true)
                }
            }
        }
    }
}

private data class ModDetailsRelationUi(
    @DrawableRes val icon: Int,
    val title: String,
    val description: String? = null,
    val status: String? = null,
    val attention: Boolean = false,
    val iconColor: Color? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

@Composable
private fun ModDetailsRelationRow(relation: ModDetailsRelationUi) {
    val contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (relation.enabled) 1f else 0.38f)
    Row(
        Modifier.fillMaxWidth().clickable(enabled = relation.enabled, onClick = relation.onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Icon(painterResource(relation.icon), null, Modifier.padding(8.dp).size(18.dp),
                tint = (relation.iconColor ?: MaterialTheme.colorScheme.primary).copy(alpha = if (relation.enabled) 1f else 0.38f))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(relation.title, style = MaterialTheme.typography.labelLarge, color = contentColor)
            relation.description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (relation.enabled) 1f else 0.38f))
            }
        }
        relation.status?.let {
            Surface(
                modifier = Modifier.widthIn(max = 96.dp), shape = RoundedCornerShape(7.dp),
                color = if (relation.attention) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                contentColor = if (relation.attention) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Text(it, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
        Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (relation.enabled) 0.7f else 0.38f))
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
