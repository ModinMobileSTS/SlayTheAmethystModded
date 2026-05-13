package io.stamethyst.ui.modimport

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.stamethyst.R
import io.stamethyst.backend.mods.AtlasOfflineDownscaleStrategy
import io.stamethyst.backend.mods.importing.DuplicateImportDecision
import io.stamethyst.backend.mods.importing.ImportPatchPlan
import io.stamethyst.backend.mods.importing.ImportPatchResult
import io.stamethyst.backend.mods.importing.ModImportItemPlan
import io.stamethyst.backend.mods.importing.ModImportItemStatus
import io.stamethyst.backend.mods.importing.ModImportPlan

@Composable
internal fun ModImportHost(
    modifier: Modifier = Modifier,
    onImportCompleted: () -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val viewModel: ModImportSessionViewModel = viewModel()
    val request by ModImportRequestBus.request.collectAsState()
    val uiState = viewModel.uiState

    LaunchedEffect(request?.id) {
        val currentRequest = request ?: return@LaunchedEffect
        ModImportRequestBus.consume(currentRequest.id)
        viewModel.start(context, currentRequest.uris)
    }

    if (uiState.visible) {
        ModImportWizardDialog(
            modifier = modifier,
            state = uiState,
            onDismiss = viewModel::dismiss,
            onBack = viewModel::back,
            onNext = viewModel::next,
            onSetDuplicateDecision = viewModel::setDuplicateDecision,
            onSetReusePreviousFileName = viewModel::setReusePreviousFileName,
            onSetReusePreviousFolder = viewModel::setReusePreviousFolder,
            onSetPatchEnabled = viewModel::setPatchEnabled,
            onSetAtlasStrategy = viewModel::setAtlasDownscaleStrategy,
            onExecute = {
                viewModel.execute(context) {
                    activity?.runOnUiThread(onImportCompleted)
                }
            }
        )
    }
}

@Composable
private fun ModImportWizardDialog(
    modifier: Modifier = Modifier,
    state: ModImportUiState,
    onDismiss: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSetDuplicateDecision: (String, DuplicateImportDecision) -> Unit,
    onSetReusePreviousFileName: (Boolean) -> Unit,
    onSetReusePreviousFolder: (Boolean) -> Unit,
    onSetPatchEnabled: (String, String, Boolean) -> Unit,
    onSetAtlasStrategy: (AtlasOfflineDownscaleStrategy?) -> Unit,
    onExecute: () -> Unit
) {
    val title = when (state.step) {
        ModImportStep.Scanning -> stringResource(R.string.mod_import_wizard_title_scanning)
        ModImportStep.Review -> stringResource(R.string.mod_import_wizard_title_review)
        ModImportStep.Duplicates -> stringResource(R.string.mod_import_wizard_title_duplicates)
        ModImportStep.Patches -> stringResource(R.string.mod_import_wizard_title_patches)
        ModImportStep.Confirm -> stringResource(R.string.mod_import_wizard_title_confirm)
        ModImportStep.Executing -> stringResource(R.string.mod_import_wizard_title_executing)
        ModImportStep.Result -> stringResource(R.string.mod_import_wizard_title_result)
    }
    AlertDialog(
        modifier = modifier,
        onDismissRequest = {
            if (state.step != ModImportStep.Executing) onDismiss()
        },
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (state.step) {
                    ModImportStep.Scanning -> ScanningStep()
                    ModImportStep.Review -> ReviewStep(state.plan, state.errorMessage)
                    ModImportStep.Duplicates -> DuplicateStep(
                        state = state,
                        onSetDuplicateDecision = onSetDuplicateDecision,
                        onSetReusePreviousFileName = onSetReusePreviousFileName,
                        onSetReusePreviousFolder = onSetReusePreviousFolder
                    )
                    ModImportStep.Patches -> PatchStep(
                        state = state,
                        onSetPatchEnabled = onSetPatchEnabled,
                        onSetAtlasStrategy = onSetAtlasStrategy
                    )
                    ModImportStep.Confirm -> ConfirmStep(state)
                    ModImportStep.Executing -> ExecutingStep(state)
                    ModImportStep.Result -> ResultStep(state)
                }
            }
        },
        confirmButton = {
            when (state.step) {
                ModImportStep.Review,
                ModImportStep.Duplicates,
                ModImportStep.Patches -> {
                    Button(onClick = onNext) { Text(stringResource(R.string.settings_first_run_action_next)) }
                }
                ModImportStep.Confirm -> {
                    Button(enabled = state.canImport, onClick = onExecute) { Text(stringResource(R.string.mod_import_wizard_action_start_import)) }
                }
                ModImportStep.Result -> {
                    Button(onClick = onDismiss) { Text(stringResource(R.string.common_action_confirm)) }
                }
                else -> Unit
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.step == ModImportStep.Duplicates ||
                    state.step == ModImportStep.Patches ||
                    state.step == ModImportStep.Confirm
                ) {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.settings_first_run_action_back)) }
                }
                if (state.step != ModImportStep.Executing && state.step != ModImportStep.Result) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.main_folder_dialog_cancel)) }
                }
            }
        }
    )
}

@Composable
private fun ScanningStep() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator()
        Text(stringResource(R.string.mod_import_wizard_scanning_message))
    }
}

@Composable
private fun ReviewStep(plan: ModImportPlan?, errorMessage: String?) {
    if (errorMessage != null) {
        Text(errorMessage, color = MaterialTheme.colorScheme.error)
        return
    }
    if (plan == null) {
        Text(stringResource(R.string.mod_import_wizard_no_plan))
        return
    }
    SummaryLine(plan)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(plan.items, key = { it.id }) { item ->
            ImportFileCard(item)
        }
    }
}

@Composable
private fun SummaryLine(plan: ModImportPlan) {
    Text(
        text = stringResource(
            R.string.mod_import_wizard_review_summary,
            plan.importableItems.size,
            plan.blockedItems.size,
            plan.skippedItems.size
        ),
        style = MaterialTheme.typography.bodyMedium
    )
}

@Composable
private fun ImportFileCard(item: ModImportItemPlan) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.source.displayName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall
                )
                StatusChip(item)
            }
            if (item.manifest != null) {
                Text(
                    stringResource(R.string.mod_import_summary_item_title, item.displayModName, item.displayModId),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (item.manifest.version.isNotBlank()) {
                    Text(stringResource(R.string.mod_import_wizard_version, item.manifest.version), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (item.blockingDetail.isNotBlank()) {
                Text(item.blockingDetail, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (item.patchPlans.isNotEmpty()) {
                Text(stringResource(R.string.mod_import_wizard_planned_patches, item.patchPlans.size), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun StatusChip(item: ModImportItemPlan) {
    val selected = item.status == ModImportItemStatus.IMPORTABLE || item.status == ModImportItemStatus.NEEDS_DECISION
    FilterChip(
        selected = selected,
        onClick = {},
        label = { Text(stringResource(item.statusLabelResId())) },
        enabled = false
    )
}

@Composable
private fun DuplicateStep(
    state: ModImportUiState,
    onSetDuplicateDecision: (String, DuplicateImportDecision) -> Unit,
    onSetReusePreviousFileName: (Boolean) -> Unit,
    onSetReusePreviousFolder: (Boolean) -> Unit
) {
    val plan = state.plan ?: return
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.mod_import_wizard_duplicates_intro))
        plan.duplicateConflicts.forEach { conflict ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(conflict.displayModId, style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.mod_import_wizard_duplicates_importing, conflict.importingDisplayNames.joinToString()))
                    if (conflict.existingSources.isNotEmpty()) {
                        Text(stringResource(R.string.mod_import_wizard_duplicates_existing, conflict.existingSources.joinToString { it.fileName }))
                    }
                    DuplicateOption(
                        selected = state.decisions.duplicateDecisionFor(conflict.normalizedModId) == DuplicateImportDecision.ReplaceExisting,
                        label = stringResource(R.string.mod_import_wizard_duplicate_replace),
                        onClick = { onSetDuplicateDecision(conflict.normalizedModId, DuplicateImportDecision.ReplaceExisting) }
                    )
                    DuplicateOption(
                        selected = state.decisions.duplicateDecisionFor(conflict.normalizedModId) == DuplicateImportDecision.KeepMultiple,
                        label = stringResource(R.string.mod_import_wizard_duplicate_keep_multiple),
                        onClick = { onSetDuplicateDecision(conflict.normalizedModId, DuplicateImportDecision.KeepMultiple) }
                    )
                    DuplicateOption(
                        selected = state.decisions.duplicateDecisionFor(conflict.normalizedModId) == DuplicateImportDecision.SkipNew,
                        label = stringResource(R.string.mod_import_wizard_duplicate_skip),
                        onClick = { onSetDuplicateDecision(conflict.normalizedModId, DuplicateImportDecision.SkipNew) }
                    )
                }
            }
        }
        HorizontalDivider()
        ToggleRow(
            label = stringResource(R.string.mod_import_wizard_reuse_previous_file_name),
            checked = state.decisions.reusePreviousFileNameOnReplace,
            onCheckedChange = onSetReusePreviousFileName
        )
        ToggleRow(
            label = stringResource(R.string.mod_import_wizard_reuse_previous_folder),
            checked = state.decisions.reusePreviousFolderOnReplace,
            onCheckedChange = onSetReusePreviousFolder
        )
    }
}

@Composable
private fun DuplicateOption(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
private fun PatchStep(
    state: ModImportUiState,
    onSetPatchEnabled: (String, String, Boolean) -> Unit,
    onSetAtlasStrategy: (AtlasOfflineDownscaleStrategy?) -> Unit
) {
    val plan = state.plan ?: return
    val patchItems = plan.importableItems.flatMap { item -> item.patchPlans.map { item to it } }
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(stringResource(R.string.mod_import_wizard_patches_intro))
        patchItems.forEach { (item, patch) ->
            PatchCard(
                item = item,
                patch = patch,
                checked = state.decisions.isPatchEnabled(item.id, patch),
                onCheckedChange = { enabled -> onSetPatchEnabled(item.id, patch.moduleId, enabled) }
            )
        }
        if (patchItems.any { it.second.moduleId == "texture.atlas_offline_downscale" }) {
            HorizontalDivider()
            Text(stringResource(R.string.mod_import_wizard_atlas_downscale_level), style = MaterialTheme.typography.titleSmall)
            AtlasStrategyOption(stringResource(R.string.mod_import_wizard_atlas_strategy_max_edge, 512), AtlasOfflineDownscaleStrategy.maxEdge(512), state, onSetAtlasStrategy)
            AtlasStrategyOption(stringResource(R.string.mod_import_wizard_atlas_strategy_max_edge, 1024), AtlasOfflineDownscaleStrategy.maxEdge(1024), state, onSetAtlasStrategy)
            AtlasStrategyOption(stringResource(R.string.mod_import_wizard_atlas_strategy_max_edge, 2048), AtlasOfflineDownscaleStrategy.maxEdge(2048), state, onSetAtlasStrategy)
        }
    }
}

@Composable
private fun PatchCard(
    item: ModImportItemPlan,
    patch: ImportPatchPlan,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(resolvePatchTitle(patch), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(
                            R.string.mod_import_wizard_patch_subtitle,
                            item.displayModName,
                            stringResource(patch.category.displayLabelResId())
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = checked,
                    enabled = patch.userConfigurable,
                    onCheckedChange = onCheckedChange
                )
            }
            Text(resolvePatchSummary(patch), style = MaterialTheme.typography.bodySmall)
            patch.details.forEach { detail -> Text(detail, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun AtlasStrategyOption(
    label: String,
    strategy: AtlasOfflineDownscaleStrategy,
    state: ModImportUiState,
    onSetAtlasStrategy: (AtlasOfflineDownscaleStrategy?) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(
            selected = state.decisions.atlasDownscaleStrategy == strategy,
            onClick = { onSetAtlasStrategy(strategy) }
        )
        Text(label)
    }
}

@Composable
private fun ConfirmStep(state: ModImportUiState) {
    val plan = state.plan ?: return
    val importing = plan.importableItems.count { item ->
        val conflictKey = item.duplicateConflictKey
        conflictKey == null || state.decisions.duplicateDecisionFor(conflictKey) != DuplicateImportDecision.SkipNew
    }
    val replacing = plan.duplicateConflicts.count {
        state.decisions.duplicateDecisionFor(it.normalizedModId) == DuplicateImportDecision.ReplaceExisting
    }
    val skippedByDecision = plan.duplicateConflicts.count {
        state.decisions.duplicateDecisionFor(it.normalizedModId) == DuplicateImportDecision.SkipNew
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.mod_import_wizard_confirm_ready, importing))
        Text(stringResource(R.string.mod_import_wizard_confirm_replacing, replacing))
        Text(stringResource(R.string.mod_import_wizard_confirm_skipped_by_decision, skippedByDecision))
        Text(stringResource(R.string.mod_import_wizard_confirm_blocked, plan.blockedItems.size))
        Text(stringResource(R.string.mod_import_wizard_confirm_skipped_archives, plan.skippedItems.size))
        Text(stringResource(R.string.mod_import_wizard_confirm_enabled_patches, plan.importableItems.sumOf { item -> item.patchPlans.count { state.decisions.isPatchEnabled(item.id, it) } }))
    }
}

@Composable
private fun ExecutingStep(state: ModImportUiState) {
    val progress = state.progress
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress.percent / 100f },
                modifier = Modifier.fillMaxWidth()
            )
            Text(progress.message)
            if (progress.currentFileName.isNotBlank()) {
                Text(progress.currentFileName, style = MaterialTheme.typography.bodySmall)
            }
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.mod_import_wizard_executing_starting))
        }
    }
}

@Composable
private fun ResultStep(state: ModImportUiState) {
    val report = state.report
    val error = state.errorMessage
    if (error != null) {
        Text(error, color = MaterialTheme.colorScheme.error)
        return
    }
    if (report == null) {
        Text(stringResource(R.string.mod_import_wizard_no_result))
        return
    }
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            stringResource(
                R.string.mod_import_wizard_result_summary,
                report.importedCount,
                report.blockedCount,
                report.skippedCount,
                report.failedCount
            ),
            style = MaterialTheme.typography.titleSmall
        )
        report.results.forEach { result ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(result.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(result.message, style = MaterialTheme.typography.bodySmall)
                    result.patchResults.filter { it.applied }.forEach { patch ->
                        Text(stringResource(R.string.mod_import_wizard_result_patched, resolvePatchResultTitle(patch)), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun resolvePatchTitle(patch: ImportPatchPlan): String {
    return if (patch.displayNameResId != 0) {
        stringResource(patch.displayNameResId)
    } else {
        patch.displayName
    }
}

@Composable
private fun resolvePatchSummary(patch: ImportPatchPlan): String {
    return if (patch.summaryResId != 0) {
        stringResource(patch.summaryResId)
    } else {
        patch.summary
    }
}

@Composable
private fun resolvePatchResultTitle(patch: ImportPatchResult): String {
    return if (patch.displayNameResId != 0) {
        stringResource(patch.displayNameResId)
    } else {
        patch.displayName
    }
}
