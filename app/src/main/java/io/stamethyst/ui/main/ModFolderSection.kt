package io.stamethyst.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.stamethyst.R
import io.stamethyst.model.ModItemUi
import kotlin.math.abs
import kotlinx.coroutines.delay
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
internal fun ModFolderSection(
    modifier: Modifier = Modifier,
    uiState: MainScreenViewModel.UiState,
    contentBottomInset: Dp = 0.dp,
    hostAvailable: Boolean,
    onBatchEditBarStateChange: (BatchEditBarState?) -> Unit = {},
    callbacks: ModFolderSectionCallbacks
) {
    val dependencyMods = uiState.dependencyMods
    val mods = uiState.optionalMods
    val folders = uiState.modFolders
    val folderAssignments = uiState.folderAssignments
    val folderCollapsed = uiState.folderCollapsed
    val unassignedCollapsed = uiState.unassignedCollapsed
    val dependencyFolderCollapsed = uiState.dependencyFolderCollapsed
    val showModFileName = uiState.showModFileName
    val unassignedFolderName = uiState.unassignedFolderName
    val unassignedFolderOrder = uiState.unassignedFolderOrder

    val folderTargetIds = remember(folders, unassignedFolderOrder) {
        buildFolderTargetIds(folders = folders, unassignedFolderOrder = unassignedFolderOrder)
    }
    val interactionState = rememberModFolderSectionInteractionState(folderTargetIds = folderTargetIds)
    var pendingDeleteMod by remember { mutableStateOf<ModItemUi?>(null) }
    var pendingMoveMod by remember { mutableStateOf<ModItemUi?>(null) }
    var batchSelectionMode by remember { mutableStateOf(false) }
    var batchMoveDialogVisible by remember { mutableStateOf(false) }
    var batchDeleteDialogVisible by remember { mutableStateOf(false) }
    var selectedBatchStoragePaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    val effectiveDragLocked = uiState.dragLocked || batchSelectionMode
    val organizationControlsEnabled = uiState.controlsEnabled && hostAvailable
    val organizationDragEnabled = organizationControlsEnabled && !effectiveDragLocked
    val modFileActionsEnabled = !uiState.busy && hostAvailable
    val filterText = interactionState.filterText
    val filteredMods = remember(mods, filterText) {
        val keyword = filterText.trim()
        if (keyword.isEmpty()) {
            mods
        } else {
            mods.filter { mod ->
                mod.name.contains(keyword, ignoreCase = true) ||
                    mod.modId.contains(keyword, ignoreCase = true) ||
                    mod.manifestModId.contains(keyword, ignoreCase = true) ||
                    mod.description.contains(keyword, ignoreCase = true)
            }
        }
    }

    val folderIds = remember(folders) { folders.map { it.id }.toSet() }
    val foldersById = remember(folders) { folders.associateBy { it.id } }
    val modsByFolderId = remember(filteredMods, folderAssignments, folderIds) {
        filteredMods
            .groupBy { resolveAssignedFolderId(it, folderAssignments, folderIds) }
            .mapValues { (_, modsInFolder) ->
                modsInFolder.sortedBy { if (it.favorite) 0 else 1 }
            }
    }
    val unassignedMods = remember(modsByFolderId) { modsByFolderId[null].orEmpty() }
    val optionalModSuggestionInfoByStoragePath = remember(filteredMods, uiState.modSuggestions) {
        buildModSuggestionInfoByStoragePath(filteredMods, uiState.modSuggestions)
    }
    val dependencyModSuggestionInfoByStoragePath = remember(dependencyMods, uiState.modSuggestions) {
        buildModSuggestionInfoByStoragePath(dependencyMods, uiState.modSuggestions)
    }
    val filteredModStoragePaths = remember(filteredMods) { filteredMods.map { it.storagePath }.toSet() }
    LaunchedEffect(filteredModStoragePaths) {
        val pruned = selectedBatchStoragePaths.filterTo(LinkedHashSet()) { filteredModStoragePaths.contains(it) }
        if (pruned.size != selectedBatchStoragePaths.size) {
            selectedBatchStoragePaths = pruned
            if (pruned.isEmpty()) {
                batchSelectionMode = false
            }
        }
    }
    val selectedBatchMods = remember(filteredMods, selectedBatchStoragePaths) {
        filteredMods.filter { selectedBatchStoragePaths.contains(it.storagePath) }
    }
    val batchSelectionControlsEnabled = organizationControlsEnabled && selectedBatchMods.isNotEmpty()
    fun exitBatchSelection() {
        batchSelectionMode = false
        selectedBatchStoragePaths = emptySet()
        batchMoveDialogVisible = false
        batchDeleteDialogVisible = false
    }
    val latestSelectedBatchMods = rememberUpdatedState(selectedBatchMods)
    val latestBatchSelectionControlsEnabled = rememberUpdatedState(batchSelectionControlsEnabled)
    val latestExitBatchSelection = rememberUpdatedState(::exitBatchSelection)
    val batchEditBarState = remember(batchSelectionMode) {
        if (!batchSelectionMode) {
            null
        } else {
            BatchEditBarState(
                selectedCount = 0,
                controlsEnabled = false,
                onMove = { batchMoveDialogVisible = true },
                onDelete = { batchDeleteDialogVisible = true },
                onEnable = {
                    val selectedMods = latestSelectedBatchMods.value
                    latestExitBatchSelection.value()
                    callbacks.onSetModsSelected(selectedMods, true)
                },
                onDisable = {
                    val selectedMods = latestSelectedBatchMods.value
                    latestExitBatchSelection.value()
                    callbacks.onSetModsSelected(selectedMods, false)
                },
                onCancel = { latestExitBatchSelection.value() }
            )
        }
    }?.copy(
        selectedCount = selectedBatchMods.size,
        controlsEnabled = latestBatchSelectionControlsEnabled.value
    )
    LaunchedEffect(batchEditBarState) {
        onBatchEditBarStateChange(batchEditBarState)
    }
    DisposableEffect(Unit) {
        onDispose { onBatchEditBarStateChange(null) }
    }
    BackHandler(
        enabled = batchSelectionMode && !batchMoveDialogVisible && !batchDeleteDialogVisible
    ) {
        exitBatchSelection()
    }
    val modDragState = interactionState.modDragState
    val activeModDragSession = modDragState.session
    val shouldCollapseFolders = interactionState.activeDragFolderId != null || modDragState.collapseFoldersDuringDrag

    val displayFolderTargetIds = interactionState.folderPreviewOrder
    val folderDisplayIndexMap = remember(displayFolderTargetIds) {
        displayFolderTargetIds.withIndex().associate { it.value to it.index }
    }
    val folderUiModels = remember(
        displayFolderTargetIds,
        foldersById,
        modsByFolderId,
        folderCollapsed,
        unassignedCollapsed,
        unassignedFolderName
    ) {
        buildFolderUiModels(
            displayFolderTargetIds = displayFolderTargetIds,
            foldersById = foldersById,
            modsByFolderId = modsByFolderId,
            folderCollapsed = folderCollapsed,
            unassignedCollapsed = unassignedCollapsed,
            unassignedFolderName = unassignedFolderName
        )
    }
    val activeDragSourceFolderId = remember(
        interactionState.activeDragFolderId,
        activeModDragSession?.sourceFolderTokenId
    ) {
        when {
            interactionState.activeDragFolderId != null -> interactionState.activeDragFolderId
            activeModDragSession != null -> activeModDragSession.sourceFolderTokenId
            else -> null
        }
    }
    val activeDragMod = activeModDragSession?.mod
    val latestFolderBodyVisibilityByToken = remember { mutableMapOf<String, Boolean>() }
    val folderBodyVisibilityByToken = remember(folderUiModels, shouldCollapseFolders, activeDragSourceFolderId, activeDragMod) {
        buildFolderBodyVisibilityByToken(
            folderUiModels = folderUiModels,
            shouldCollapseFolders = shouldCollapseFolders,
            activeDragSourceFolderId = activeDragSourceFolderId,
            activeDragMod = activeDragMod
        )
    }
    val folderBodyExitingTokens = remember { mutableStateOf<Set<String>>(emptySet()) }
    var collapseToggleLockedTokens by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(folderBodyVisibilityByToken) {
        val previous = latestFolderBodyVisibilityByToken.toMap()
        latestFolderBodyVisibilityByToken.clear()
        latestFolderBodyVisibilityByToken.putAll(folderBodyVisibilityByToken)
        val exitingTokens = previous
            .filter { (folderTokenId, wasVisible) ->
                wasVisible && folderBodyVisibilityByToken[folderTokenId] != true
            }
            .keys
        val visibleTokens = folderBodyVisibilityByToken
            .filterValues { it }
            .keys
        if (exitingTokens.isNotEmpty()) {
            folderBodyExitingTokens.value = (folderBodyExitingTokens.value + exitingTokens) - visibleTokens
        } else if (visibleTokens.any { folderBodyExitingTokens.value.contains(it) }) {
            folderBodyExitingTokens.value = folderBodyExitingTokens.value - visibleTokens
        }
    }
    val latestFolderBodyVisibility = rememberUpdatedState(folderBodyVisibilityByToken)
    LaunchedEffect(folderBodyExitingTokens.value) {
        val scheduledExitingTokens = folderBodyExitingTokens.value
        if (scheduledExitingTokens.isEmpty()) {
            return@LaunchedEffect
        }
        delay(COLLAPSE_ANIMATION_MS.toLong())
        val removableTokens = scheduledExitingTokens
            .filter { folderTokenId -> latestFolderBodyVisibility.value[folderTokenId] != true }
            .toSet()
        if (removableTokens.isNotEmpty()) {
            folderBodyExitingTokens.value = folderBodyExitingTokens.value - removableTokens
        }
    }
    LaunchedEffect(collapseToggleLockedTokens) {
        val lockedSnapshot = collapseToggleLockedTokens
        if (lockedSnapshot.isEmpty()) {
            return@LaunchedEffect
        }
        delay(FOLDER_TOGGLE_THROTTLE_MS)
        collapseToggleLockedTokens = collapseToggleLockedTokens - lockedSnapshot
    }
    val lazyListItems = remember(
        dependencyMods.isNotEmpty(),
        folderUiModels,
        folderBodyVisibilityByToken,
        folderBodyExitingTokens.value,
        shouldCollapseFolders,
        activeDragSourceFolderId,
        activeDragMod
    ) {
        buildModFolderLazyItems(
            hasDependencyMods = dependencyMods.isNotEmpty(),
            folderUiModels = folderUiModels,
            folderBodyVisibilityByToken = folderBodyVisibilityByToken,
            previousFolderBodyVisibilityByToken = latestFolderBodyVisibilityByToken,
            folderBodyExitingTokens = folderBodyExitingTokens.value,
            shouldCollapseFolders = shouldCollapseFolders,
            activeDragSourceFolderId = activeDragSourceFolderId,
            activeDragMod = activeDragMod
        )
    }
    val topLevelItemKeys = remember(lazyListItems) {
        lazyListItems.map { it.key }
    }
    val folderTokenByItemKey = remember(lazyListItems) {
        lazyListItems.mapNotNull { item ->
            item.dropFolderTokenId?.let { folderTokenId -> item.key to folderTokenId }
        }.toMap()
    }
    val reorderFolderTokenByItemKey = remember(folderUiModels) {
        folderUiModels.associate { it.key to it.folderTokenId }
    }
    fun resolveFolderTokenFromItemKey(itemKey: Any): String? {
        val key = itemKey as? String ?: return null
        return folderTokenByItemKey[key]
    }
    fun resolveReorderFolderTokenFromItemKey(itemKey: Any): String? {
        val key = itemKey as? String ?: return null
        return reorderFolderTokenByItemKey[key]
    }

    val latestFolderTokenByItemKey = rememberUpdatedState(folderTokenByItemKey)
    val latestFolderAssignments = rememberUpdatedState(folderAssignments)
    val latestFolderIds = rememberUpdatedState(folderIds)
    val latestCallbacks = rememberUpdatedState(callbacks)
    val latestOrganizationDragEnabled = rememberUpdatedState(organizationDragEnabled)
    val latestOrganizationControlsEnabled = rememberUpdatedState(organizationControlsEnabled)
    val latestSelectedBatchStoragePaths = rememberUpdatedState(selectedBatchStoragePaths)

    // Keep the coordinator stable during a drag gesture, but resolve folder state
    // from the latest UI snapshot after moves have already updated assignments.
    val dragCoordinator = remember(interactionState) {
        ModDragSessionCoordinator(
            interactionState = interactionState,
            resolveFolderTokenFromItemKey = { itemKey ->
                val key = itemKey as? String ?: return@ModDragSessionCoordinator null
                latestFolderTokenByItemKey.value[key]
            },
            resolveAssignedFolderToken = { mod ->
                resolveAssignedFolderId(
                    mod,
                    latestFolderAssignments.value,
                    latestFolderIds.value
                ) ?: UNASSIGNED_FOLDER_ID
            },
            onAssignModToFolder = { mod, folderId ->
                latestCallbacks.value.onAssignModToFolder(mod, folderId)
            },
            onMoveModToUnassigned = { mod ->
                latestCallbacks.value.onMoveModToUnassigned(mod)
            },
            onRevealFolderToken = { folderTokenId ->
                latestCallbacks.value.onRevealFolderToken(folderTokenId)
            }
        )
    }

    LaunchedEffect(batchSelectionMode) {
        if (batchSelectionMode) {
            dragCoordinator.cancelActiveDrags()
            interactionState.expandedCards.clear()
            interactionState.folderPreviewOrder = folderTargetIds
        }
    }

    val reorderState = rememberReorderableLazyListState(
        lazyListState = interactionState.listState,
        onMove = { from, to ->
            val movingFolderId = resolveReorderFolderTokenFromItemKey(from.key)
                ?: return@rememberReorderableLazyListState
            val targetFolderId = resolveFolderTokenFromItemKey(to.key)
                ?: return@rememberReorderableLazyListState
            val mutable = interactionState.folderPreviewOrder.toMutableList()
            val fromIndex = mutable.indexOf(movingFolderId)
            val toIndex = mutable.indexOf(targetFolderId)
            if (fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex) {
                val moved = mutable.removeAt(fromIndex)
                mutable.add(toIndex, moved)
                interactionState.folderPreviewOrder = mutable
            }
        }
    )

    LaunchedEffect(interactionState.isModDragActive) {
        if (!interactionState.isModDragActive) {
            return@LaunchedEffect
        }
        var smoothAutoScrollDelta = 0f
        while (interactionState.modDragState.isActive) {
            val target = dragCoordinator.nextAutoScrollDelta()
            smoothAutoScrollDelta = if (target == 0f) {
                smoothAutoScrollDelta * 0.5f
            } else {
                smoothAutoScrollDelta * 0.5f + target * 0.3f
            }
            if (abs(smoothAutoScrollDelta) < 0.25f) {
                smoothAutoScrollDelta = 0f
            }
            if (smoothAutoScrollDelta != 0f) {
                interactionState.listState.scrollBy(smoothAutoScrollDelta)
            }
            delay(16)
        }
    }

    LaunchedEffect(interactionState.pendingDragScrollRestore, topLevelItemKeys) {
        val anchor = interactionState.pendingDragScrollRestore ?: return@LaunchedEffect
        val fallbackIndex = anchor.firstVisibleItemIndex
            .coerceIn(0, (topLevelItemKeys.size - 1).coerceAtLeast(0))
        val targetIndex = anchor.firstVisibleItemKey
            ?.let { key -> topLevelItemKeys.indexOf(key).takeIf { it >= 0 } }
            ?: fallbackIndex
        interactionState.listState.scrollToItem(
            index = targetIndex,
            scrollOffset = anchor.firstVisibleItemScrollOffset
        )
        if (interactionState.pendingDragScrollRestore == anchor) {
            interactionState.pendingDragScrollRestore = null
        }
    }

    LaunchedEffect(effectiveDragLocked, folderTargetIds) {
        if (!effectiveDragLocked) {
            return@LaunchedEffect
        }
        dragCoordinator.cancelActiveDrags()
        interactionState.folderPreviewOrder = folderTargetIds
    }

    val folderBackgroundColor = MaterialTheme.colorScheme.surfaceContainer
    val hoveredFolderBackgroundColor = MaterialTheme.colorScheme.secondaryContainer
    val dragAffordanceAlpha = animateFloatAsState(
        targetValue = if (effectiveDragLocked) 0f else 1f,
        animationSpec = tween(durationMillis = 160, easing = LinearEasing),
        label = "dragAffordanceAlpha"
    )
    val batchSelectionProgress = animateFloatAsState(
        targetValue = if (batchSelectionMode) 1f else 0f,
        animationSpec = tween(durationMillis = 180, easing = LinearEasing),
        label = "batchSelectionProgress"
    )
    val effectiveContentBottomInset = if (batchSelectionMode) {
        BATCH_EDIT_BOTTOM_INSET
    } else {
        contentBottomInset
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                testTagsAsResourceId = true
            }
            .pointerInput(dragCoordinator) {
                awaitEachGesture {
                    awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial
                    )
                    while (true) {
                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                        dragCoordinator.onContainerPointerChanges(
                            changes = event.changes,
                            sectionTopLeftWindow = interactionState.sectionTopLeftInWindow
                        )
                        if (event.changes.none { it.pressed }) {
                            break
                        }
                    }
                }
            }
            .onGloballyPositioned { interactionState.sectionTopLeftInWindow = it.boundsInWindow().topLeft },
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        pendingDeleteMod?.let { mod ->
            AlertDeleteModDialog(
                mod = mod,
                onDismiss = { pendingDeleteMod = null },
                onConfirm = {
                    pendingDeleteMod = null
                    callbacks.onDeleteMod(mod)
                }
            )
        }

        pendingMoveMod?.let { mod ->
            val currentFolderTokenId = resolveAssignedFolderId(
                mod = mod,
                folderAssignments = folderAssignments,
                validFolderIds = folderIds
            ) ?: UNASSIGNED_FOLDER_ID
            MoveModToFolderDialog(
                visible = true,
                modName = resolveModDisplayName(mod, showModFileName = showModFileName),
                targets = folderUiModels.map { folderUiModel ->
                    ModFolderMoveTarget(
                        folderTokenId = folderUiModel.folderTokenId,
                        folderName = folderUiModel.folderName,
                        isCurrent = folderUiModel.folderTokenId == currentFolderTokenId
                    )
                },
                controlsEnabled = organizationControlsEnabled && mod.installed,
                onDismiss = { pendingMoveMod = null },
                onSelectTarget = { targetFolderTokenId ->
                    pendingMoveMod = null
                    if (targetFolderTokenId == UNASSIGNED_FOLDER_ID) {
                        callbacks.onMoveModToUnassigned(mod)
                    } else {
                        callbacks.onAssignModToFolder(mod, targetFolderTokenId)
                    }
                }
            )
        }

        if (batchMoveDialogVisible) {
            MoveSelectedModsToFolderDialog(
                visible = true,
                selectedCount = selectedBatchMods.size,
                targets = folderUiModels.map { folderUiModel ->
                    ModBatchMoveTarget(
                        folderTokenId = folderUiModel.folderTokenId,
                        folderName = folderUiModel.folderName
                    )
                },
                controlsEnabled = batchSelectionControlsEnabled,
                onDismiss = { batchMoveDialogVisible = false },
                onSelectTarget = { targetFolderTokenId ->
                    val selectedMods = selectedBatchMods
                    batchMoveDialogVisible = false
                    exitBatchSelection()
                    if (targetFolderTokenId == UNASSIGNED_FOLDER_ID) {
                        callbacks.onMoveModsToUnassigned(selectedMods)
                    } else {
                        callbacks.onAssignModsToFolder(selectedMods, targetFolderTokenId)
                    }
                }
            )
        }

        if (batchDeleteDialogVisible) {
            AlertDeleteSelectedModsDialog(
                selectedCount = selectedBatchMods.size,
                onDismiss = { batchDeleteDialogVisible = false },
                onConfirm = {
                    val selectedMods = selectedBatchMods
                    batchDeleteDialogVisible = false
                    exitBatchSelection()
                    callbacks.onDeleteMods(selectedMods)
                }
            )
        }

//        Text(
//            text = stringResource(
//                id = R.string.main_folder_summary_format,
//                filteredMods.size,
//                unassignedMods.size
//            ),
//            style = MaterialTheme.typography.bodySmall,
//            color = MaterialTheme.colorScheme.outline,
//            modifier = Modifier.padding(horizontal = 6.dp)
//        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            LazyColumn(
                state = interactionState.listState,
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { interactionState.listViewportInWindow = it.boundsInWindow() },
                contentPadding = PaddingValues(bottom = effectiveContentBottomInset)
            ) {
                items(
                    items = lazyListItems,
                    key = { it.key },
                    contentType = { it.contentType }
                ) { lazyItem ->
                    when (lazyItem) {
                        ModFolderLazyItem.TopPlaceholder -> {
                            Spacer(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(MOD_LIST_TOP_PLACEHOLDER_HEIGHT)
                            )
                        }

                        ModFolderLazyItem.FilterInput -> {
                            OutlinedTextField(
                                value = filterText,
                                onValueChange = { interactionState.filterText = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall,
                                placeholder = {
                                    Text(
                                        text = stringResource(R.string.main_folder_filter_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.52f),
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f),
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.48f),
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.32f),
                                    focusedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unfocusedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    cursorColor = MaterialTheme.colorScheme.outline
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }

                        ModFolderLazyItem.DependencyFolder -> {
                            val dependencyToggleLocked = collapseToggleLockedTokens.contains(DEPENDENCY_FOLDER_KEY)
                            Box(
                                modifier = folderPlacementAnimation(
                                    Modifier.fillMaxWidth()
                                )
                            ) {
                                DependencyFolderListItem(
                                    mods = dependencyMods,
                                    modSuggestionInfoByStoragePath = dependencyModSuggestionInfoByStoragePath,
                                    readModSuggestionKeys = uiState.readModSuggestionKeys,
                                    collapsed = dependencyFolderCollapsed,
                                    forceCollapsed = shouldCollapseFolders,
                                    showModFileName = showModFileName,
                                    dragAffordanceAlpha = dragAffordanceAlpha,
                                    interactionState = interactionState,
                                    collapseEnabled = uiState.controlsEnabled && !dependencyToggleLocked,
                                    onToggleCollapsed = {
                                        if (!dependencyToggleLocked) {
                                            collapseToggleLockedTokens = collapseToggleLockedTokens + DEPENDENCY_FOLDER_KEY
                                            callbacks.onToggleDependencyFolderCollapsed()
                                        }
                                    },
                                    onMarkModSuggestionRead = callbacks.onMarkModSuggestionRead
                                )
                            }
                        }

                        is ModFolderLazyItem.FolderHeader -> {
                            val folderUiModel = lazyItem.folderUiModel
                            val folderTokenId = folderUiModel.folderTokenId
                            val collapseToggleLocked = collapseToggleLockedTokens.contains(folderTokenId)
                            val isHovering =
                                activeModDragSession?.hoveredFolderTokenId == folderTokenId &&
                                    interactionState.activeDragFolderId == null
                            val isSourceFolderDuringModDrag =
                                activeModDragSession != null && activeDragSourceFolderId == folderTokenId
                            val isModDragActive = activeModDragSession != null
                            val folderOverlayZIndex = when {
                                isHovering && isModDragActive -> DRAGGED_FOLDER_Z_INDEX + 40f
                                isHovering -> DRAGGED_FOLDER_Z_INDEX + 30f
                                isSourceFolderDuringModDrag -> DRAGGED_FOLDER_Z_INDEX + 10f
                                else -> 0f
                            }
                            val folderShape = RoundedCornerShape(10.dp)
                            val headerShape = if (lazyItem.hasBody) {
                                RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)
                            } else {
                                folderShape
                            }
                            var renameDialog by remember(folderUiModel.key) { mutableStateOf(false) }
                            var manageMenuExpanded by remember(folderUiModel.key) { mutableStateOf(false) }

                            FolderNameDialog(
                                visible = renameDialog,
                                title = stringResource(R.string.main_folder_dialog_rename_title),
                                initialText = folderUiModel.folderName,
                                onDismiss = { renameDialog = false },
                                onConfirm = { newName ->
                                    callbacks.onRenameFolder(folderTokenId, newName)
                                    renameDialog = false
                                }
                            )

                            Box(
                                modifier = folderPlacementAnimation(
                                    Modifier
                                        .fillMaxWidth()
                                        .zIndex(folderOverlayZIndex)
                                )
                            ) {
                                ReorderableItem(
                                    state = reorderState,
                                    key = folderUiModel.key,
                                    animateItemModifier = Modifier
                                ) { isReordering ->
                                    val reorderableItemScope = this
                                    val folderScale by animateFloatAsState(
                                        targetValue = if (isHovering) HOVERED_FOLDER_SCALE else 1f,
                                        animationSpec = tween(
                                            durationMillis = FOLDER_HOVER_ANIMATION_MS,
                                            easing = LinearEasing
                                        ),
                                        label = "folderScale-$folderTokenId"
                                    )
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(
                                                start = 6.dp,
                                                top = if (lazyItem.displayIndex == 0) 0.dp else 8.dp,
                                                end = 6.dp
                                            )
                                            .zIndex(
                                                if (isReordering) {
                                                    DRAGGED_FOLDER_Z_INDEX + 60f
                                                } else {
                                                    folderOverlayZIndex
                                                }
                                            )
                                            .graphicsLayer {
                                                scaleX = folderScale
                                                scaleY = folderScale
                                            }
                                            .then(
                                                if (isSourceFolderDuringModDrag) {
                                                    Modifier
                                                } else {
                                                    Modifier.clip(headerShape)
                                                }
                                            )
                                            .background(
                                                if (isHovering) hoveredFolderBackgroundColor else folderBackgroundColor,
                                                headerShape
                                            )
                                            .border(
                                                if (isHovering) 2.dp else 1.dp,
                                                if (isHovering) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                                headerShape
                                            )
                                            .padding(vertical = 6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            FolderOrderHandle(
                                                reorderScope = reorderableItemScope,
                                                enabled = organizationDragEnabled,
                                                folderId = folderTokenId,
                                                dragAffordanceProgress = dragAffordanceAlpha.value,
                                                modifier = Modifier.graphicsLayer {
                                                    alpha = dragAffordanceAlpha.value
                                                    scaleX = 0.92f + alpha * 0.08f
                                                    scaleY = 0.92f + alpha * 0.08f
                                                },
                                                canMoveUp = (folderDisplayIndexMap[folderTokenId] ?: 0) > 0,
                                                canMoveDown = (folderDisplayIndexMap[folderTokenId] ?: 0) < displayFolderTargetIds.lastIndex,
                                                onMoveUp = {
                                                    if (folderUiModel.isUnassigned) {
                                                        callbacks.onMoveUnassignedUp()
                                                    } else {
                                                        callbacks.onMoveFolderUp(folderTokenId)
                                                    }
                                                },
                                                onMoveDown = {
                                                    if (folderUiModel.isUnassigned) {
                                                        callbacks.onMoveUnassignedDown()
                                                    } else {
                                                        callbacks.onMoveFolderDown(folderTokenId)
                                                    }
                                                },
                                                onDragStarted = {
                                                    dragCoordinator.beginFolderReorder(folderTokenId, folderTargetIds)
                                                },
                                                onDragStopped = {
                                                    val draggedId = interactionState.activeDragFolderId
                                                    if (draggedId != null) {
                                                        val targetIndex = interactionState.folderPreviewOrder.indexOf(draggedId)
                                                        val currentIndex = folderTargetIds.indexOf(draggedId)
                                                        if (targetIndex >= 0 && currentIndex >= 0 && targetIndex != currentIndex) {
                                                            callbacks.onMoveFolderTokenToIndex(draggedId, targetIndex)
                                                        }
                                                    }
                                                    dragCoordinator.finishFolderReorder()
                                                }
                                            )
                                            TriStateCheckbox(
                                                state = folderUiModel.toggleState,
                                                enabled = folderUiModel.mods.isNotEmpty() && organizationControlsEnabled,
                                                onClick = {
                                                    if (organizationControlsEnabled) {
                                                        if (folderUiModel.isUnassigned) {
                                                            callbacks.onSetUnassignedSelected(folderUiModel.toggleState != ToggleableState.On)
                                                        } else {
                                                            callbacks.onSetFolderSelected(
                                                                folderTokenId,
                                                                folderUiModel.toggleState != ToggleableState.On
                                                            )
                                                        }
                                                    }
                                                }
                                            )
                                            Text(
                                                text = stringResource(
                                                    R.string.main_folder_name_count_format,
                                                    folderUiModel.folderName,
                                                    folderUiModel.selectedCount,
                                                    folderUiModel.mods.size
                                                ),
                                                modifier = Modifier.weight(1f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            IconButton(
                                                enabled = uiState.controlsEnabled && !collapseToggleLocked,
                                                modifier = Modifier.testTag(FOLDER_TOGGLE_BUTTON_TAG),
                                                onClick = {
                                                    if (collapseToggleLocked) {
                                                        return@IconButton
                                                    }
                                                    collapseToggleLockedTokens = collapseToggleLockedTokens + folderTokenId
                                                    if (folderUiModel.isUnassigned) {
                                                        callbacks.onToggleUnassignedCollapsed()
                                                    } else {
                                                        callbacks.onToggleFolderCollapsed(folderTokenId)
                                                    }
                                                }
                                            ) {
                                                Icon(
                                                    painter = painterResource(
                                                        if (lazyItem.effectiveCollapsed) R.drawable.ic_chevron_right else R.drawable.ic_expand_more
                                                    ),
                                                    contentDescription = stringResource(
                                                        if (lazyItem.effectiveCollapsed) R.string.main_folder_expand else R.string.main_folder_collapse
                                                    )
                                                )
                                            }
                                            Box {
                                                IconButton(
                                                    enabled = organizationControlsEnabled,
                                                    onClick = { manageMenuExpanded = true }
                                                ) {
                                                    Icon(
                                                        painter = painterResource(R.drawable.ic_more_vert),
                                                        contentDescription = stringResource(R.string.main_folder_manage)
                                                    )
                                                }
                                                DropdownMenu(
                                                    expanded = manageMenuExpanded,
                                                    onDismissRequest = { manageMenuExpanded = false }
                                                ) {
                                                    DropdownMenuItem(
                                                        text = { Text(stringResource(R.string.main_folder_rename)) },
                                                        enabled = organizationControlsEnabled,
                                                        onClick = {
                                                            manageMenuExpanded = false
                                                            renameDialog = true
                                                        }
                                                    )
                                                    if (!folderUiModel.isUnassigned) {
                                                        DropdownMenuItem(
                                                            text = { Text(stringResource(R.string.main_folder_delete)) },
                                                            enabled = organizationControlsEnabled,
                                                            onClick = {
                                                                manageMenuExpanded = false
                                                                callbacks.onDeleteFolder(folderTokenId)
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        is ModFolderLazyItem.FolderEmpty -> {
                            val folderUiModel = lazyItem.folderUiModel
                            val folderTokenId = folderUiModel.folderTokenId
                            val isHovering =
                                activeModDragSession?.hoveredFolderTokenId == folderTokenId &&
                                    interactionState.activeDragFolderId == null
                            val emptyTextResId = when {
                                folderUiModel.isUnassigned -> R.string.main_folder_unassigned_empty
                                effectiveDragLocked -> R.string.main_folder_empty
                                else -> R.string.main_folder_drag_here
                            }
                            FolderBodyAnimatedLayer(
                                visible = lazyItem.bodyVisible,
                                folderTokenId = folderTokenId,
                                onExitComplete = { exitedFolderTokenId ->
                                    if (latestFolderBodyVisibility.value[exitedFolderTokenId] != true) {
                                        folderBodyExitingTokens.value = folderBodyExitingTokens.value - exitedFolderTokenId
                                    }
                                },
                                modifier = folderPlacementAnimation(
                                    Modifier.fillMaxWidth()
                                )
                            ) {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 6.dp),
                                    color = if (isHovering) hoveredFolderBackgroundColor else folderBackgroundColor,
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                    shape = RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp)
                                ) {
                                    FolderEmptyHint(text = stringResource(emptyTextResId))
                                }
                            }
                        }

                        is ModFolderLazyItem.FolderMod -> {
                            val mod = lazyItem.mod
                            val isHovering =
                                activeModDragSession?.hoveredFolderTokenId == lazyItem.folderTokenId &&
                                    interactionState.activeDragFolderId == null
                            val bodyShape = if (lazyItem.isLastInFolder) {
                                RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp)
                            } else {
                                RoundedCornerShape(0.dp)
                            }
                            val suggestionInfo = optionalModSuggestionInfoByStoragePath[mod.storagePath]
                            val suggestionText = suggestionInfo?.text
                            val suggestionReadKey = suggestionInfo?.readKey
                            val latestMod = rememberUpdatedState(mod)
                            val latestSuggestionText = rememberUpdatedState(suggestionText)
                            val setCardExpanded: (Boolean) -> Unit = remember(mod.storagePath) {
                                { expanded -> interactionState.expandedCards[mod.storagePath] = expanded }
                            }
                            val onBatchSelectionChange: (Boolean) -> Unit = remember(mod.storagePath) {
                                { selected ->
                                    val currentSelection = latestSelectedBatchStoragePaths.value
                                    val updatedSelection = currentSelection.toMutableSet().apply {
                                        if (selected) {
                                            add(latestMod.value.storagePath)
                                        } else {
                                            remove(latestMod.value.storagePath)
                                        }
                                    }
                                    selectedBatchStoragePaths = updatedSelection
                                    if (!selected && updatedSelection.isEmpty()) {
                                        batchSelectionMode = false
                                    }
                                }
                            }
                            val onSuggestionRead: () -> Unit = remember(mod.storagePath) {
                                {
                                    val currentSuggestionText = latestSuggestionText.value
                                    if (!currentSuggestionText.isNullOrBlank()) {
                                        latestCallbacks.value.onMarkModSuggestionRead(
                                            latestMod.value,
                                            currentSuggestionText
                                        )
                                    }
                                }
                            }
                            val modCardCallbacks = remember(mod.storagePath) {
                                ModCardCallbacks(
                                    onToggleMod = { item, enabled ->
                                        latestCallbacks.value.onToggleMod(item, enabled)
                                    },
                                    onSetPriority = { item, priority ->
                                        latestCallbacks.value.onSetPriority(item, priority)
                                    },
                                    onSetFavorite = { item, favorite ->
                                        latestCallbacks.value.onSetModFavorite(item, favorite)
                                    },
                                    onDeleteMod = { pendingDeleteMod = it },
                                    onExportMod = { latestCallbacks.value.onExportMod(it) },
                                    onShareMod = { latestCallbacks.value.onShareMod(it) },
                                    onRenameModFile = { item, fileName ->
                                        latestCallbacks.value.onRenameModFile(item, fileName)
                                    },
                                    onDragStart = { dragInfo ->
                                        val currentMod = latestMod.value
                                        dragCoordinator.startModDrag(
                                            mod = currentMod,
                                            dragInfo = dragInfo,
                                            isExpanded = interactionState.expandedCards[currentMod.storagePath] == true,
                                        )
                                    },
                                    onDragCancel = {
                                        dragCoordinator.cancelModDrag()
                                    },
                                    onMoveFolderPickerRequest = { pendingMoveMod = it },
                                    onBatchSelectionStart = { selectedMod ->
                                        if (latestOrganizationControlsEnabled.value && selectedMod.installed) {
                                            interactionState.expandedCards.clear()
                                            batchSelectionMode = true
                                            selectedBatchStoragePaths =
                                                latestSelectedBatchStoragePaths.value + selectedMod.storagePath
                                        }
                                    }
                                )
                            }
                            FolderBodyAnimatedLayer(
                                visible = lazyItem.bodyVisible,
                                folderTokenId = lazyItem.folderTokenId,
                                onExitComplete = { exitedFolderTokenId ->
                                    if (latestFolderBodyVisibility.value[exitedFolderTokenId] != true) {
                                        folderBodyExitingTokens.value = folderBodyExitingTokens.value - exitedFolderTokenId
                                    }
                                },
                                modifier = folderPlacementAnimation(
                                    Modifier.fillMaxWidth()
                                )
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 6.dp)
                                        .then(
                                            if (lazyItem.constrainHeightForDrag) {
                                                Modifier
                                                    .height(1.dp)
                                                    .clipToBounds()
                                            } else {
                                                Modifier
                                            }
                                        )
                                        .clip(bodyShape)
                                        .background(
                                            if (isHovering) hoveredFolderBackgroundColor else folderBackgroundColor,
                                            bodyShape
                                        )
                                ) {
                                    ModCard(
                                        mod = mod,
                                        suggestionText = suggestionText,
                                        suggestionRead = suggestionReadKey == null ||
                                            uiState.readModSuggestionKeys.contains(suggestionReadKey),
                                        isExpanded = interactionState.expandedCards[mod.storagePath] == true,
                                        isDraggedInOverlay = activeModDragSession?.mod?.storagePath == mod.storagePath,
                                        showModFileName = showModFileName,
                                        setExpanded = setCardExpanded,
                                        selectionEnabled = organizationControlsEnabled && mod.installed,
                                        fileActionsEnabled = modFileActionsEnabled && mod.installed,
                                        dragEnabled = mod.installed,
                                        dragEnabledState = latestOrganizationDragEnabled,
                                        showDragHandle = !batchSelectionMode,
                                        dragAffordanceAlpha = dragAffordanceAlpha,
                                        batchSelectionProgress = batchSelectionProgress,
                                        batchSelectionMode = batchSelectionMode,
                                        batchSelected = selectedBatchStoragePaths.contains(mod.storagePath),
                                        batchSelectionEnabled = organizationControlsEnabled && mod.installed,
                                        onBatchSelectionChange = onBatchSelectionChange,
                                        onSuggestionRead = onSuggestionRead,
                                        callbacks = modCardCallbacks
                                    )
                                }
                            }
                        }
                    }
                }
            }
    }
    }

    DraggingModCardOverlayLayer(
        dragSession = modDragState.session,
        showModFileName = showModFileName,
        overlayHostTopLeftWindow = interactionState.sectionTopLeftInWindow
    )
}

@Composable
private fun FolderBodyAnimatedLayer(
    visible: Boolean,
    folderTokenId: String,
    onExitComplete: (String) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val offsetPx = remember(density) { with(density) { FOLDER_BODY_ANIMATION_OFFSET.toPx() } }
    var targetVisible by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) {
            // Defer the target change so newly inserted lazy items animate in from 0f.
            delay(FOLDER_BODY_ENTER_DELAY_MS)
            targetVisible = true
        } else {
            targetVisible = false
        }
    }
    val progress by animateFloatAsState(
        targetValue = if (targetVisible) 1f else 0f,
        animationSpec = tween(
            durationMillis = FOLDER_BODY_LAYER_ANIMATION_MS,
            easing = LinearEasing
        ),
        label = "folderBodyLayer-$folderTokenId"
    )
    LaunchedEffect(targetVisible, progress, folderTokenId) {
        if (!targetVisible && progress == 0f) {
            onExitComplete(folderTokenId)
        }
    }
    Box(
        modifier = modifier.graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * -offsetPx
        }
    ) {
        content()
    }
}

private fun LazyItemScope.folderPlacementAnimation(modifier: Modifier = Modifier): Modifier = modifier.animateItem(
    fadeInSpec = null,
    placementSpec = tween<IntOffset>(
        durationMillis = FOLDER_PLACEMENT_ANIMATION_MS,
        easing = LinearEasing
    ),
    fadeOutSpec = null
)

@Composable
private fun DependencyFolderListItem(
    mods: List<ModItemUi>,
    modSuggestionInfoByStoragePath: Map<String, ModSuggestionInfo>,
    readModSuggestionKeys: Set<String>,
    collapsed: Boolean,
    forceCollapsed: Boolean,
    showModFileName: Boolean,
    dragAffordanceAlpha: State<Float>,
    interactionState: ModFolderSectionInteractionState,
    collapseEnabled: Boolean,
    onToggleCollapsed: () -> Unit,
    onMarkModSuggestionRead: (ModItemUi, String) -> Unit
) {
    val effectiveCollapsed = if (forceCollapsed) true else collapsed
    val readyCount = mods.count { it.enabled }
    val folderShape = RoundedCornerShape(10.dp)
    val latestOnMarkModSuggestionRead = rememberUpdatedState(onMarkModSuggestionRead)
    val emptyModCardCallbacks = remember { ModCardCallbacks() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, top = 8.dp, end = 6.dp, bottom = 8.dp)
            .clip(folderShape)
            .background(MaterialTheme.colorScheme.surfaceContainer, folderShape)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = folderShape
            )
            .padding(vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = stringResource(
                    R.string.main_folder_name_count_format,
                    stringResource(R.string.main_status_card_title),
                    readyCount,
                    mods.size
                ),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.primary
            )
            IconButton(
                enabled = collapseEnabled,
                onClick = onToggleCollapsed
            ) {
                Icon(
                    painter = painterResource(
                        if (effectiveCollapsed) R.drawable.ic_chevron_right else R.drawable.ic_expand_more
                    ),
                    contentDescription = stringResource(
                        if (effectiveCollapsed) R.string.main_folder_expand else R.string.main_folder_collapse
                    )
                )
            }
        }
        AnimatedVisibility(
            visible = !effectiveCollapsed,
            enter = expandVertically(
                expandFrom = Alignment.Top,
                animationSpec = tween(
                    durationMillis = COLLAPSE_ANIMATION_MS,
                    easing = LinearEasing
                )
            ),
            exit = shrinkVertically(
                shrinkTowards = Alignment.Top,
                animationSpec = tween(
                    durationMillis = COLLAPSE_ANIMATION_MS,
                    easing = LinearEasing
                )
            ),
            label = "dependencyFolderBody"
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                mods.forEach { mod ->
                    key(mod.storagePath) {
                        val suggestionInfo = modSuggestionInfoByStoragePath[mod.storagePath]
                        val suggestionText = suggestionInfo?.text
                        val suggestionReadKey = suggestionInfo?.readKey
                        val latestMod = rememberUpdatedState(mod)
                        val latestSuggestionText = rememberUpdatedState(suggestionText)
                        val setCardExpanded: (Boolean) -> Unit = remember(mod.storagePath) {
                            { expanded -> interactionState.expandedCards[mod.storagePath] = expanded }
                        }
                        val onSuggestionRead: () -> Unit = remember(mod.storagePath) {
                            {
                                val currentSuggestionText = latestSuggestionText.value
                                if (!currentSuggestionText.isNullOrBlank()) {
                                    latestOnMarkModSuggestionRead.value(
                                        latestMod.value,
                                        currentSuggestionText
                                    )
                                }
                            }
                        }
                        ModCard(
                            mod = mod,
                            suggestionText = suggestionText,
                            suggestionRead = suggestionReadKey == null ||
                                readModSuggestionKeys.contains(suggestionReadKey),
                            isExpanded = interactionState.expandedCards[mod.storagePath] == true,
                            isDraggedInOverlay = false,
                            showModFileName = showModFileName,
                            showActionsButton = false,
                            setExpanded = setCardExpanded,
                            selectionEnabled = false,
                            fileActionsEnabled = false,
                            dragEnabled = false,
                            showDragHandle = true,
                            dragAffordanceAlpha = dragAffordanceAlpha,
                            batchSelectionProgress = null,
                            batchSelectionMode = false,
                            batchSelectionEnabled = false,
                            onSuggestionRead = onSuggestionRead,
                            callbacks = emptyModCardCallbacks
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertDeleteModDialog(
    mod: ModItemUi,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val displayName = resolveModDisplayName(mod, showModFileName = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.main_mod_delete)) },
        text = {
            Text(
                text = stringResource(
                    R.string.main_mod_delete_confirm_format,
                    displayName
                )
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(text = stringResource(R.string.main_folder_dialog_confirm))
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text(text = stringResource(R.string.main_folder_dialog_cancel))
            }
        }
    )
}

@Composable
private fun AlertDeleteSelectedModsDialog(
    selectedCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.main_batch_delete_title)) },
        text = {
            Text(
                text = stringResource(
                    R.string.main_batch_delete_confirm_format,
                    selectedCount
                )
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(text = stringResource(R.string.main_folder_dialog_confirm))
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text(text = stringResource(R.string.main_folder_dialog_cancel))
            }
        }
    )
}

@Composable
internal fun BatchEditToolbar(
    modifier: Modifier = Modifier,
    selectedCount: Int,
    controlsEnabled: Boolean,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 10.dp,
        shadowElevation = 14.dp,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
                ) {
                    Text(
                        text = stringResource(R.string.main_batch_edit_selected_format, selectedCount),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Text(stringResource(R.string.main_batch_action_cancel))
                }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BatchActionChip(
                    modifier = Modifier.weight(1f).height(42.dp),
                    iconResId = R.drawable.ic_move_folder,
                    text = stringResource(R.string.main_batch_action_move),
                    enabled = controlsEnabled,
                    onClick = onMove
                )
                BatchActionChip(
                    modifier = Modifier.weight(1f).height(42.dp),
                    iconResId = R.drawable.ic_check_circle,
                    text = stringResource(R.string.main_batch_action_enable),
                    enabled = controlsEnabled,
                    onClick = onEnable
                )
                BatchActionChip(
                    modifier = Modifier.weight(1f).height(42.dp),
                    iconResId = R.drawable.ic_remove_circle,
                    text = stringResource(R.string.main_batch_action_disable),
                    enabled = controlsEnabled,
                    onClick = onDisable
                )
                BatchActionChip(
                    modifier = Modifier.weight(1f).height(42.dp),
                    iconResId = R.drawable.ic_delete,
                    text = stringResource(R.string.main_batch_action_delete),
                    enabled = controlsEnabled,
                    destructive = true,
                    onClick = onDelete
                )
            }
        }
    }
}

@Composable
private fun BatchActionChip(
    modifier: Modifier = Modifier,
    iconResId: Int,
    text: String,
    enabled: Boolean,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val containerColor = if (destructive) {
        colorScheme.errorContainer.copy(alpha = 0.9f)
    } else {
        colorScheme.primaryContainer.copy(alpha = 0.88f)
    }
    val contentColor = if (destructive) {
        colorScheme.onErrorContainer
    } else {
        colorScheme.onPrimaryContainer
    }
    val disabledContentColor = colorScheme.onSurface.copy(alpha = 0.38f)
    AssistChip(
        modifier = modifier.defaultMinSize(minHeight = 0.dp),
        onClick = onClick,
        enabled = enabled,
        label = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val currentContentColor = LocalContentColor.current
                Icon(
                    painter = painterResource(iconResId),
                    contentDescription = null,
                    tint = if (enabled) contentColor else disabledContentColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = text,
                    color = if (enabled) contentColor else currentContentColor,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        shape = RoundedCornerShape(999.dp),
        colors = AssistChipDefaults.assistChipColors(
            containerColor = containerColor,
            labelColor = contentColor,
            leadingIconContentColor = contentColor,
            disabledContainerColor = colorScheme.surfaceContainerHighest.copy(alpha = 0.58f),
            disabledLabelColor = disabledContentColor,
            disabledLeadingIconContentColor = disabledContentColor
        ),
        border = AssistChipDefaults.assistChipBorder(
            enabled = enabled,
            borderColor = if (destructive) {
                colorScheme.error.copy(alpha = 0.34f)
            } else {
                colorScheme.primary.copy(alpha = 0.34f)
            },
            disabledBorderColor = colorScheme.outlineVariant.copy(alpha = 0.26f)
        )
    )
}

private data class ModSuggestionInfo(
    val text: String,
    val readKey: String?
)

private sealed interface ModFolderLazyItem {
    val key: String
    val contentType: String
    val dropFolderTokenId: String?

    data object TopPlaceholder : ModFolderLazyItem {
        override val key: String = MOD_LIST_TOP_PLACEHOLDER_KEY
        override val contentType: String = "topPlaceholder"
        override val dropFolderTokenId: String? = null
    }

    data object FilterInput : ModFolderLazyItem {
        override val key: String = MODS_FILTER_INPUT_KEY
        override val contentType: String = "filterInput"
        override val dropFolderTokenId: String? = null
    }

    data object DependencyFolder : ModFolderLazyItem {
        override val key: String = DEPENDENCY_FOLDER_KEY
        override val contentType: String = "dependencyFolder"
        override val dropFolderTokenId: String? = null
    }

    data class FolderHeader(
        val folderUiModel: FolderUiModel,
        val displayIndex: Int,
        val effectiveCollapsed: Boolean,
        val hasBody: Boolean
    ) : ModFolderLazyItem {
        override val key: String = folderUiModel.key
        override val contentType: String = "folderHeader"
        override val dropFolderTokenId: String = folderUiModel.folderTokenId
    }

    data class FolderEmpty(
        val folderUiModel: FolderUiModel,
        val bodyVisible: Boolean
    ) : ModFolderLazyItem {
        override val key: String = "${folderUiModel.key}:empty"
        override val contentType: String = "folderEmpty"
        override val dropFolderTokenId: String = folderUiModel.folderTokenId
    }

    data class FolderMod(
        val folderTokenId: String,
        val mod: ModItemUi,
        val constrainHeightForDrag: Boolean,
        val isLastInFolder: Boolean,
        val bodyVisible: Boolean
    ) : ModFolderLazyItem {
        override val key: String = "mod:${mod.storagePath}"
        override val contentType: String = "folderMod"
        override val dropFolderTokenId: String = folderTokenId
    }
}

private fun buildModFolderLazyItems(
    hasDependencyMods: Boolean,
    folderUiModels: List<FolderUiModel>,
    folderBodyVisibilityByToken: Map<String, Boolean>,
    previousFolderBodyVisibilityByToken: Map<String, Boolean>,
    folderBodyExitingTokens: Set<String>,
    shouldCollapseFolders: Boolean,
    activeDragSourceFolderId: String?,
    activeDragMod: ModItemUi?
): List<ModFolderLazyItem> {
    return buildList {
        add(ModFolderLazyItem.TopPlaceholder)
        add(ModFolderLazyItem.FilterInput)
        if (hasDependencyMods) {
            add(ModFolderLazyItem.DependencyFolder)
        }
        folderUiModels.forEachIndexed { index, folderUiModel ->
            val folderTokenId = folderUiModel.folderTokenId
            val keepSourceFolderBodyAlive = activeDragMod != null && activeDragSourceFolderId == folderTokenId
            val effectiveCollapsed = if (shouldCollapseFolders) true else folderUiModel.isCollapsed
            val bodyVisible = folderBodyVisibilityByToken[folderTokenId] == true
            val bodyPresent = bodyVisible ||
                previousFolderBodyVisibilityByToken[folderTokenId] == true ||
                folderBodyExitingTokens.contains(folderTokenId)
            add(
                ModFolderLazyItem.FolderHeader(
                    folderUiModel = folderUiModel,
                    displayIndex = index,
                    effectiveCollapsed = effectiveCollapsed,
                    hasBody = bodyPresent
                )
            )
            if (!bodyPresent) {
                return@forEachIndexed
            }
            val bodyMods = if (keepSourceFolderBodyAlive && effectiveCollapsed) {
                listOfNotNull(activeDragMod)
            } else {
                folderUiModel.mods
            }
            if (bodyMods.isEmpty()) {
                add(
                    ModFolderLazyItem.FolderEmpty(
                        folderUiModel = folderUiModel,
                        bodyVisible = bodyVisible
                    )
                )
            } else {
                bodyMods.forEachIndexed { modIndex, mod ->
                    add(
                        ModFolderLazyItem.FolderMod(
                            folderTokenId = folderTokenId,
                            mod = mod,
                            constrainHeightForDrag = keepSourceFolderBodyAlive && folderUiModel.isCollapsed,
                            isLastInFolder = modIndex == bodyMods.lastIndex,
                            bodyVisible = bodyVisible
                        )
                    )
                }
            }
        }
    }
}

private fun buildFolderBodyVisibilityByToken(
    folderUiModels: List<FolderUiModel>,
    shouldCollapseFolders: Boolean,
    activeDragSourceFolderId: String?,
    activeDragMod: ModItemUi?
): Map<String, Boolean> {
    return buildMap {
        folderUiModels.forEach { folderUiModel ->
            val folderTokenId = folderUiModel.folderTokenId
            val keepSourceFolderBodyAlive = activeDragMod != null && activeDragSourceFolderId == folderTokenId
            val effectiveCollapsed = if (shouldCollapseFolders) true else folderUiModel.isCollapsed
            put(folderTokenId, !effectiveCollapsed || keepSourceFolderBodyAlive)
        }
    }
}

private fun buildModSuggestionInfoByStoragePath(
    mods: List<ModItemUi>,
    suggestions: Map<String, String>
): Map<String, ModSuggestionInfo> {
    if (mods.isEmpty() || suggestions.isEmpty()) {
        return emptyMap()
    }
    return buildMap {
        mods.forEach { mod ->
            val suggestionText = resolveModSuggestionText(mod, suggestions) ?: return@forEach
            put(
                mod.storagePath,
                ModSuggestionInfo(
                    text = suggestionText,
                    readKey = resolveModSuggestionReadKey(mod, suggestionText)
                )
            )
        }
    }
}

private const val MOD_LIST_TOP_PLACEHOLDER_KEY = "modListTopPlaceholder"
private const val MODS_FILTER_INPUT_KEY = "modsFilterInput"
private const val DEPENDENCY_FOLDER_KEY = "dependencyFolder"
private const val FOLDER_TOGGLE_BUTTON_TAG = "mod_folder_toggle_button"
private const val FOLDER_BODY_ENTER_DELAY_MS = 16L
private const val FOLDER_BODY_LAYER_ANIMATION_MS = 140
private const val FOLDER_PLACEMENT_ANIMATION_MS = 220
private const val FOLDER_TOGGLE_THROTTLE_MS = 260L
private val FOLDER_BODY_ANIMATION_OFFSET = 10.dp
private val BATCH_EDIT_BOTTOM_INSET = 148.dp
private val MOD_LIST_TOP_PLACEHOLDER_HEIGHT = 84.dp
