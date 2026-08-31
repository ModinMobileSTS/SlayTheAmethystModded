package io.stamethyst.ui.modimport

import android.app.Activity
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import io.stamethyst.R
import io.stamethyst.backend.mods.AtlasOfflineDownscaleMode
import io.stamethyst.backend.mods.AtlasOfflineDownscaleStrategy
import io.stamethyst.backend.mods.importing.DuplicateImportDecision
import io.stamethyst.backend.mods.importing.ImportPatchPlan
import io.stamethyst.backend.mods.importing.ImportPatchResult
import io.stamethyst.backend.mods.importing.ModImportItemPlan
import io.stamethyst.backend.mods.importing.ModImportPlan
import io.stamethyst.backend.workshop.WorkshopMetadataStore
import io.stamethyst.config.RuntimePaths
import java.io.File

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
        viewModel.start(context, currentRequest.uris, currentRequest.workshopSource)
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
            onSetTargetFolder = viewModel::setTargetFolder,
            onExecute = {
                viewModel.execute(context) {
                    val source = viewModel.uiState.workshopSource
                    val importedResults = viewModel.uiState.report?.importedResults.orEmpty()
                    val importedPaths = importedResults.mapNotNull { it.storagePath?.trim()?.takeIf(String::isNotEmpty) }
                    if (source != null && importedPaths.isNotEmpty()) {
                        val importedNames = importedResults.map { it.modName }.filter { it.isNotBlank() }
                        val statusText = when {
                            importedNames.isEmpty() -> "已安装 ${importedPaths.size} 个模组"
                            importedNames.size == 1 -> "已安装 ${importedNames.single()}"
                            else -> "已安装 ${importedNames.size} 个模组：${importedNames.joinToString("、")}"
                        }
                        WorkshopMetadataStore(context).markPatched(
                            appId = source.appId,
                            publishedFileId = source.publishedFileId,
                            localJarPaths = importedPaths,
                            statusText = statusText,
                        )
                        cleanWorkshopDownloadedContent(context, source)
                    }
                    activity?.runOnUiThread(onImportCompleted)
                }
            }
        )
    }
}

private fun cleanWorkshopDownloadedContent(context: Context, source: WorkshopImportSource) {
    RuntimePaths.workshopItemDirs(context, source.appId, source.publishedFileId).forEach { outputDir ->
        if (!outputDir.isDirectory) return@forEach
        outputDir.listFiles().orEmpty().forEach { file ->
            if (!file.name.startsWith("preview.", ignoreCase = true)) {
                file.deleteRecursively()
            }
        }
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
    onSetTargetFolder: (String, String?) -> Unit,
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
        properties = DialogProperties(dismissOnClickOutside = false),
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
                    ModImportStep.Confirm -> ConfirmStep(state, onSetTargetFolder)
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
            Text(
                text = item.source.displayName,
                style = MaterialTheme.typography.titleSmall
            )
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
private fun DuplicateStep(
    state: ModImportUiState,
    onSetDuplicateDecision: (String, DuplicateImportDecision) -> Unit,
    onSetReusePreviousFileName: (Boolean) -> Unit,
    onSetReusePreviousFolder: (Boolean) -> Unit
) {
    val plan = state.plan ?: return
    val showReplaceOptions = plan.duplicateConflicts.any { conflict ->
        state.decisions.duplicateDecisionFor(conflict.normalizedModId) == DuplicateImportDecision.ReplaceExisting
    }
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
        AnimatedVisibility(
            visible = showReplaceOptions,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
    }
}

@Composable
private fun DuplicateOption(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
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
    val patchItems = plan.importableItems.flatMap { item ->
        item.patchPlans.filter { it.userConfigurable }.map { item to it }
    }
    var showAtlasHelp by remember { mutableStateOf(false) }
    val atlasHelpText = buildAtlasDownscaleHelpText()
    if (showAtlasHelp) {
        AlertDialog(
            onDismissRequest = { showAtlasHelp = false },
            properties = DialogProperties(dismissOnClickOutside = false),
            title = { Text(stringResource(R.string.mod_import_dialog_atlas_downscale_confirm_title)) },
            text = {
                Text(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    text = atlasHelpText
                )
            },
            confirmButton = {
                Button(onClick = { showAtlasHelp = false }) {
                    Text(stringResource(R.string.common_action_confirm))
                }
            }
        )
    }
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
                onCheckedChange = { enabled ->
                    onSetPatchEnabled(item.id, patch.moduleId, enabled)
                    if (enabled && patch.moduleId == ATLAS_OFFLINE_DOWNSCALE_PATCH_ID) {
                        onSetAtlasStrategy(
                            state.decisions.atlasDownscaleStrategy ?: defaultAtlasDownscaleStrategy()
                        )
                    }
                }
            )
        }
        val atlasItems = patchItems.filter { it.second.moduleId == ATLAS_OFFLINE_DOWNSCALE_PATCH_ID }
        val showAtlasLevel = atlasItems.any { (item, patch) -> state.decisions.isPatchEnabled(item.id, patch) }
        AnimatedVisibility(
            visible = showAtlasLevel,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            AtlasDownscaleLevelSection(
                state = state,
                onShowHelp = { showAtlasHelp = true },
                onSetAtlasStrategy = onSetAtlasStrategy
            )
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
                    enabled = true,
                    onCheckedChange = onCheckedChange
                )
            }
            Text(resolvePatchSummary(patch), style = MaterialTheme.typography.bodySmall)
            patch.details.forEach { detail -> Text(detail, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun AtlasDownscaleLevelSection(
    state: ModImportUiState,
    onShowHelp: () -> Unit,
    onSetAtlasStrategy: (AtlasOfflineDownscaleStrategy?) -> Unit
) {
    val selectedStrategy = state.decisions.atlasDownscaleStrategy ?: defaultAtlasDownscaleStrategy()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.mod_import_wizard_atlas_downscale_level),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onShowHelp) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("?", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = selectedStrategy.mode == AtlasOfflineDownscaleMode.PERCENTAGE,
                onClick = {
                    onSetAtlasStrategy(
                        AtlasOfflineDownscaleStrategy.percentage(
                            selectedStrategy.takeIf { it.mode == AtlasOfflineDownscaleMode.PERCENTAGE }?.value
                                ?: AtlasOfflineDownscaleStrategy.DEFAULT_PERCENTAGE
                        )
                    )
                },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
            ) {
                Text(stringResource(R.string.mod_import_atlas_downscale_strategy_percentage))
            }
            SegmentedButton(
                selected = selectedStrategy.mode == AtlasOfflineDownscaleMode.MAX_EDGE,
                onClick = {
                    onSetAtlasStrategy(
                        AtlasOfflineDownscaleStrategy.maxEdge(
                            selectedStrategy.takeIf { it.mode == AtlasOfflineDownscaleMode.MAX_EDGE }?.value
                                ?: AtlasOfflineDownscaleStrategy.DEFAULT_MAX_EDGE_PX
                        )
                    )
                },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
            ) {
                Text(stringResource(R.string.mod_import_atlas_downscale_strategy_max_edge))
            }
        }
        val options = when (selectedStrategy.mode) {
            AtlasOfflineDownscaleMode.PERCENTAGE -> AtlasOfflineDownscaleStrategy.percentageOptions().toList()
            AtlasOfflineDownscaleMode.MAX_EDGE -> AtlasOfflineDownscaleStrategy.maxEdgeOptions().toList()
        }
        options.forEach { option ->
            val strategy = when (selectedStrategy.mode) {
                AtlasOfflineDownscaleMode.PERCENTAGE -> AtlasOfflineDownscaleStrategy.percentage(option)
                AtlasOfflineDownscaleMode.MAX_EDGE -> AtlasOfflineDownscaleStrategy.maxEdge(option)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = selectedStrategy == strategy) { onSetAtlasStrategy(strategy) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selectedStrategy == strategy,
                    onClick = null
                )
                Text(
                    when (selectedStrategy.mode) {
                        AtlasOfflineDownscaleMode.PERCENTAGE -> stringResource(
                            R.string.mod_import_atlas_downscale_level_percent_label,
                            option
                        )
                        AtlasOfflineDownscaleMode.MAX_EDGE -> stringResource(
                            R.string.mod_import_atlas_downscale_level_max_edge_label,
                            option
                        )
                    }
                )
            }
        }
        Text(
            when (selectedStrategy.mode) {
                AtlasOfflineDownscaleMode.PERCENTAGE -> stringResource(
                    R.string.mod_import_atlas_downscale_level_percent_desc,
                    selectedStrategy.value,
                    AtlasOfflineDownscaleStrategy.CANDIDATE_PREVIEW_MAX_EDGE_PX
                )
                AtlasOfflineDownscaleMode.MAX_EDGE -> stringResource(
                    R.string.mod_import_atlas_downscale_level_max_edge_desc,
                    selectedStrategy.value
                )
            },
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun ConfirmStep(
    state: ModImportUiState,
    onSetTargetFolder: (String, String?) -> Unit
) {
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
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.mod_import_wizard_confirm_ready, importing))
        Text(stringResource(R.string.mod_import_wizard_confirm_replacing, replacing))
        Text(stringResource(R.string.mod_import_wizard_confirm_skipped_by_decision, skippedByDecision))
        Text(stringResource(R.string.mod_import_wizard_confirm_blocked, plan.blockedItems.size))
        Text(stringResource(R.string.mod_import_wizard_confirm_skipped_archives, plan.skippedItems.size))
        Text(stringResource(R.string.mod_import_wizard_confirm_enabled_patches, plan.importableItems.sumOf { item -> item.patchPlans.count { state.decisions.isPatchEnabled(item.id, it) } }))
        val folderItems = plan.importableItems.filter { item ->
            val conflictKey = item.duplicateConflictKey
            conflictKey == null || state.decisions.duplicateDecisionFor(conflictKey) != DuplicateImportDecision.SkipNew
        }
        if (folderItems.isNotEmpty() && state.folderOptions.isNotEmpty()) {
            val validFolderIds = state.folderOptions.mapNotNull { it.id }.toSet()
            HorizontalDivider()
            Text(stringResource(R.string.main_import_folder_picker_list_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.main_import_folder_picker_list_message), style = MaterialTheme.typography.bodySmall)
            folderItems.forEach { item ->
                val selectedFolderId = if (state.decisions.hasTargetFolderDecision(item.id)) {
                    state.decisions.targetFolderIdFor(item.id)
                } else {
                    state.decisions.targetFolderIdFor(item.id)
                        ?: state.decisions.defaultReplacementFolderIdFor(plan, item, validFolderIds)
                }
                ImportFolderSelector(
                    item = item,
                    options = state.folderOptions,
                    selectedFolderId = selectedFolderId,
                    onSetTargetFolder = onSetTargetFolder
                )
            }
        }
    }
}

@Composable
private fun ImportFolderSelector(
    item: ModImportItemPlan,
    options: List<ModImportFolderOptionUi>,
    selectedFolderId: String?,
    onSetTargetFolder: (String, String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedOption = options.firstOrNull { it.id == selectedFolderId }
        ?: options.firstOrNull { it.id == null }
        ?: options.first()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(item.displayModName, style = MaterialTheme.typography.titleSmall)
            Box(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { expanded = true }
                ) {
                    Text(
                        text = stringResource(R.string.main_import_folder_picker_item_target_format, selectedOption.name),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.name) },
                            onClick = {
                                expanded = false
                                onSetTargetFolder(item.id, option.id)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExecutingStep(state: ModImportUiState) {
    val progress = state.progress
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (progress != null) {
            val animatedProgress by animateFloatAsState(
                targetValue = progress.percent / 100f,
                label = "modImportProgress"
            )
            LinearProgressIndicator(
                progress = { animatedProgress },
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

@Composable
private fun buildAtlasDownscaleHelpText(): String {
    return buildString {
        append(stringResource(R.string.mod_import_atlas_downscale_confirm_message_pros))
        append('\n')
        append(stringResource(R.string.mod_import_atlas_downscale_confirm_message_cons))
        append('\n')
        append(stringResource(R.string.mod_import_atlas_downscale_confirm_message_outro))
    }
}

private const val ATLAS_OFFLINE_DOWNSCALE_PATCH_ID = "texture.atlas_offline_downscale"

private fun defaultAtlasDownscaleStrategy(): AtlasOfflineDownscaleStrategy {
    return AtlasOfflineDownscaleStrategy.maxEdge(AtlasOfflineDownscaleStrategy.DEFAULT_MAX_EDGE_PX)
}
