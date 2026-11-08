package io.stamethyst.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import io.stamethyst.ui.LauncherAlertDialog as AlertDialog
import androidx.compose.material3.MaterialTheme
import io.stamethyst.ui.LauncherSwitch as Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.model.ModImportPatchUi
import io.stamethyst.model.ModItemUi

@Composable
internal fun ModImportPatchDialog(
    mod: ModItemUi,
    onDismiss: () -> Unit,
    onSetPatchEnabled: (String, Boolean) -> Unit,
    onUpgradeFromWorkshop: () -> Unit,
) {
    val enabledByModuleId = remember(mod.storagePath, mod.importPatches) {
        mutableStateMapOf<String, Boolean>().apply {
            mod.importPatches.forEach { patch -> put(patch.moduleId, patch.enabled) }
        }
    }
    val hasOutdatedPatches = mod.hasOutdatedImportPatches || mod.importPatches.any { it.isOutdated }
    val hasUnrecordedPatches = mod.importPatches.any { it.appliedVersion == null }
    val canUpgradeFromWorkshop = mod.needsImportPatchUpdate && mod.workshop != null
    var showWorkshopUpgradeConfirmation by remember(mod.storagePath) { mutableStateOf(false) }
    AlertDialog(
        scrollableText = false,
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    R.string.main_mod_patch_dialog_title_format,
                    resolveModDisplayName(mod, showModFileName = false)
                )
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (hasOutdatedPatches) {
                    Text(
                        text = stringResource(R.string.main_mod_patch_dialog_upgrade_message),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (hasUnrecordedPatches) {
                    Text(
                        text = stringResource(R.string.main_mod_patch_dialog_unrecorded_message),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                mod.importPatches.forEach { patch ->
                    ModImportPatchRow(
                        patch = patch,
                        enabled = enabledByModuleId[patch.moduleId] ?: patch.enabled,
                        onSetEnabled = { enabled ->
                            enabledByModuleId[patch.moduleId] = enabled
                            onSetPatchEnabled(patch.moduleId, enabled)
                        }
                    )
                }
                if (!mod.importPatchDetails.isNullOrBlank()) {
                    Text(
                        text = mod.importPatchDetails,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                if (mod.importPatches.any { it.userConfigurable }) {
                    Text(
                        text = stringResource(R.string.main_mod_patch_dialog_future_imports),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canUpgradeFromWorkshop) {
                    TextButton(onClick = { showWorkshopUpgradeConfirmation = true }) {
                        Text(text = stringResource(R.string.main_mod_patch_dialog_upgrade_from_market))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.common_action_confirm))
                }
            }
        }
    )

    if (showWorkshopUpgradeConfirmation) {
        AlertDialog(
            onDismissRequest = { showWorkshopUpgradeConfirmation = false },
            title = { Text(text = stringResource(R.string.main_mod_patch_upgrade_confirm_title)) },
            text = {
                Text(
                    text = stringResource(
                        R.string.main_mod_patch_upgrade_confirm_message,
                        resolveModDisplayName(mod, showModFileName = false)
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showWorkshopUpgradeConfirmation = false
                        onDismiss()
                        onUpgradeFromWorkshop()
                    }
                ) {
                    Text(text = stringResource(R.string.main_mod_patch_dialog_upgrade_from_market))
                }
            },
            dismissButton = {
                TextButton(onClick = { showWorkshopUpgradeConfirmation = false }) {
                    Text(text = stringResource(R.string.main_folder_dialog_cancel))
                }
            }
        )
    }
}

@Composable
private fun ModImportPatchRow(
    patch: ModImportPatchUi,
    enabled: Boolean,
    onSetEnabled: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = patch.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (patch.isOutdated || patch.appliedVersion == null) FontWeight.Bold else FontWeight.Normal
            )
            Text(
                text = patch.appliedVersion?.let { appliedVersion ->
                    stringResource(
                        R.string.main_mod_patch_dialog_version_format,
                        appliedVersion,
                        patch.currentVersion
                    )
                } ?: stringResource(
                    R.string.main_mod_patch_dialog_unrecorded_version_format,
                    patch.currentVersion
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (patch.isOutdated || patch.appliedVersion == null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.outline
                }
            )
            if (patch.summary.isNotBlank()) {
                Text(
                    text = patch.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
        if (patch.userConfigurable) {
            Switch(checked = enabled, onCheckedChange = onSetEnabled)
        }
    }
}
