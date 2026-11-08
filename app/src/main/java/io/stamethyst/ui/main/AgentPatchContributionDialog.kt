package io.stamethyst.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.mods.AgentPatchContributionStore
import io.stamethyst.backend.mods.RemoteAgentPatch
import io.stamethyst.model.AgentPatchModUi
import io.stamethyst.model.ModItemUi
import io.stamethyst.ui.LauncherAlertDialog

@Composable
internal fun AgentPatchContributionDialog(
    mod: ModItemUi,
    patches: List<AgentPatchModUi>,
    remotePatches: List<RemoteAgentPatch>?,
    error: String?,
    onDismiss: () -> Unit,
    onUpload: (AgentPatchModUi) -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    // Read receipts again whenever an upload in the overlaid form completes.
    val revision by AgentPatchContributionStore.revision.collectAsState()
    val parent = mod.manifestModId.ifBlank { mod.modId }
    val receipts = androidx.compose.runtime.remember(patches, parent, revision) {
        patches.associate { it.patchModId to AgentPatchContributionStore.uploadedVersion(context, parent, it.patchModId) }
    }
    val active = receipts.values.any { it != null }
    LauncherAlertDialog(
        scrollableText = false,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_patch_contribution_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(if (active) R.string.ai_patch_contribution_thanks else R.string.ai_patch_contribution_invite),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(mod.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (remotePatches == null && error == null) {
                    Text(stringResource(R.string.ai_patch_contribution_checking), style = MaterialTheme.typography.bodySmall)
                }
                if (error != null) {
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.ai_patch_contribution_retry)) }
                }
                LazyColumn(
                    modifier = Modifier.heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(patches, key = { it.patchId }) { patch ->
                        val remoteVersion = remotePatches.orEmpty().filter { it.patchModId == patch.patchModId }
                            .map { it.version }.maxWithOrNull(::comparePatchVersions)
                        val sharedVersion = listOfNotNull(remoteVersion, receipts[patch.patchModId])
                            .maxWithOrNull(::comparePatchVersions)
                        val action = contributionAction(patch.version, sharedVersion)
                        Card(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(patch.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Text(stringResource(R.string.ai_patch_contribution_local, patch.version), style = MaterialTheme.typography.labelSmall)
                                    Text(
                                        if (sharedVersion != null) stringResource(R.string.ai_patch_contribution_shared, sharedVersion)
                                        else stringResource(if (remotePatches != null) R.string.ai_patch_contribution_unshared else R.string.ai_patch_contribution_checking),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                when (action) {
                                    ContributionAction.UPLOAD, ContributionAction.UPGRADE -> Button(
                                        onClick = { onUpload(patch) },
                                        enabled = remotePatches != null || sharedVersion != null,
                                     ) {
                                        Icon(
                                            painterResource(if (action == ContributionAction.UPGRADE) R.drawable.ic_workshop_update else R.drawable.ic_arrow_upward),
                                            contentDescription = null,
                                            modifier = Modifier.padding(end = 4.dp).size(16.dp),
                                        )
                                         Text(stringResource(if (action == ContributionAction.UPGRADE) R.string.ai_patch_contribution_upgrade else R.string.ai_patch_contribution_upload))
                                     }
                                    else -> Column(
                                        modifier = Modifier.widthIn(max = 80.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Icon(
                                            painterResource(if (action == ContributionAction.SYNCED) R.drawable.ic_check_circle else R.drawable.ic_cloud_sync),
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            stringResource(if (action == ContributionAction.SYNCED) R.string.ai_patch_contribution_synced else R.string.ai_patch_contribution_remote_newer),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_close)) } },
    )
}
