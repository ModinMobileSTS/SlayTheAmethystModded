package io.stamethyst.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.mods.AgentPatchCatalogueService
import io.stamethyst.backend.mods.AgentPatchCatalogueSnapshot
import io.stamethyst.backend.mods.AgentPatchContributionStore
import io.stamethyst.backend.mods.RemoteAgentPatch
import io.stamethyst.model.AgentPatchModUi
import io.stamethyst.model.ModItemUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ModDetailsAiSection(
    mod: ModItemUi,
    controlsEnabled: Boolean,
    onNew: () -> Unit,
    onToggle: (AgentPatchModUi, Boolean) -> Unit,
    onDetails: (AgentPatchModUi) -> Unit,
    onManage: (AgentPatchModUi) -> Unit,
    onCommunityDetails: (RemoteAgentPatch) -> Unit,
    onCatalogueRefreshed: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val parentId = mod.manifestModId.ifBlank { mod.modId }
    var communitySelected by remember(parentId) { mutableStateOf(false) }
    var retryToken by remember(parentId) { mutableIntStateOf(0) }
    var snapshot by remember(parentId) { mutableStateOf<AgentPatchCatalogueSnapshot?>(null) }
    var loading by remember(parentId) { mutableStateOf(false) }
    var error by remember(parentId) { mutableStateOf<String?>(null) }
    val revision by AgentPatchContributionStore.revision.collectAsState()
    val receipts by produceState<Map<String, String?>>(emptyMap(), parentId, mod.agentPatchMods, revision) {
        value = withContext(Dispatchers.IO) {
            mod.agentPatchMods.associate { it.patchModId to AgentPatchContributionStore.uploadedVersion(context, parentId, it.patchModId) }
        }
    }
    // The installed tab stays entirely local. Network work starts only when requested.
    LaunchedEffect(parentId, communitySelected, retryToken) {
        if (!communitySelected) return@LaunchedEffect
        loading = true
        error = null
        try {
            if (snapshot == null) snapshot = withContext(Dispatchers.IO) { AgentPatchCatalogueService.readCached(context, parentId) }
            snapshot = AgentPatchCatalogueService.refresh(context, parentId, force = retryToken > 0)
            if (snapshot?.fromCache == false) onCatalogueRefreshed()
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error = failure.message ?: failure.javaClass.simpleName
        } finally {
            loading = false
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                ModDetailsSectionTitle(stringResource(R.string.mod_details_ai_title))
                Text(stringResource(R.string.mod_details_ai_enabled_count, mod.agentPatchMods.count { it.enabled }),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onNew, enabled = controlsEnabled) { Text(stringResource(R.string.mod_details_ai_new)) }
        }
        PrimaryTabRow(selectedTabIndex = if (communitySelected) 1 else 0) {
            Tab(selected = !communitySelected, onClick = { communitySelected = false },
                text = { Text(stringResource(R.string.mod_details_ai_installed, mod.agentPatchMods.size)) })
            Tab(selected = communitySelected, onClick = { communitySelected = true }, text = {
                Text(if (snapshot == null) stringResource(R.string.mod_details_ai_community)
                    else stringResource(R.string.mod_details_ai_community_count, snapshot!!.patches.size))
            })
        }
        if (!communitySelected) {
            if (!mod.enabled && mod.agentPatchMods.isNotEmpty()) {
                Text(stringResource(R.string.mod_details_ai_parent_disabled), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (mod.agentPatchMods.isEmpty()) {
                Text(stringResource(R.string.main_mod_agent_patch_local_empty), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            mod.agentPatchMods.forEach { patch ->
                LocalDetailsAiModRow(
                    patch = patch,
                    parentEnabled = mod.enabled,
                    controlsEnabled = controlsEnabled,
                    contributed = receipts[patch.patchModId] == patch.version,
                    onToggle = { onToggle(patch, it) },
                    onDetails = { onDetails(patch) },
                    onManage = { onManage(patch) },
                )
            }
        } else {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { retryToken++ }, enabled = !loading) { Text(stringResource(R.string.main_mod_agent_patch_retry)) }
            }
            if (!loading && snapshot?.patches?.isEmpty() == true) {
                Text(stringResource(R.string.main_mod_agent_patch_empty), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            snapshot?.patches.orEmpty().forEach { patch ->
                CommunityDetailsAiModRow(patch, onClick = { onCommunityDetails(patch) })
            }
        }
    }
}

@Composable
private fun LocalDetailsAiModRow(
    patch: AgentPatchModUi,
    parentEnabled: Boolean,
    controlsEnabled: Boolean,
    contributed: Boolean,
    onToggle: (Boolean) -> Unit,
    onDetails: () -> Unit,
    onManage: () -> Unit,
) {
    val name = patch.name.ifBlank { stringResource(R.string.main_mod_agent_patch_unnamed) }
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onDetails).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.ic_code), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.main_mod_agent_patch_version_format, patch.version),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val toggleDescription = stringResource(R.string.mod_details_toggle, name)
                Switch(patch.enabled, onCheckedChange = onToggle, enabled = controlsEnabled && parentEnabled,
                    modifier = Modifier.semantics { contentDescription = toggleDescription })
            }
            Text(patch.description.ifBlank { stringResource(R.string.main_mod_agent_patch_description_empty) },
                maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val state = when {
                    patch.enabled && !parentEnabled -> R.string.mod_details_ai_paused
                    patch.enabled -> R.string.mod_details_ai_on
                    else -> R.string.mod_details_ai_off
                }
                Text(stringResource(state) + " · " + stringResource(if (contributed) R.string.mod_details_ai_shared else R.string.mod_details_ai_local),
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = if (patch.enabled && parentEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                val description = stringResource(R.string.mod_details_ai_manage_description, name)
                TextButton(onClick = onManage, enabled = controlsEnabled,
                    modifier = Modifier.semantics { contentDescription = description }) {
                    Text(stringResource(R.string.mod_details_ai_manage))
                }
            }
        }
    }
}

@Composable
private fun CommunityDetailsAiModRow(patch: RemoteAgentPatch, onClick: () -> Unit) {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(patch.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(listOf(patch.version, patch.authorName).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onClick) { Text(stringResource(R.string.mod_details_ai_view)) }
            }
            Text(patch.description.ifBlank { stringResource(R.string.main_mod_agent_patch_description_empty) },
                maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
