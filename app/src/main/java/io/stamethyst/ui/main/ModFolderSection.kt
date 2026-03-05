package io.stamethyst.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.stamethyst.R
import kotlin.math.abs
import kotlinx.coroutines.delay
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
internal fun ModFolderSection(
    modifier: Modifier = Modifier,
    uiState: MainScreenViewModel.UiState,
    statusSummary: String,
    contentBottomInset: Dp = 0.dp,
    hostAvailable: Boolean,
    callbacks: ModFolderSectionCallbacks
) {
    val mods = uiState.optionalMods
    val folders = uiState.modFolders
    val folderAssignments = uiState.folderAssignments
    val folderCollapsed = uiState.folderCollapsed
    val unassignedCollapsed = uiState.unassignedCollapsed
    val statusCollapsed = uiState.statusSummaryCollapsed
    val showModFileName = uiState.showModFileName
    val unassignedFolderName = uiState.unassignedFolderName
    val unassignedFolderOrder = uiState.unassignedFolderOrder

    val folderTargetIds = remember(folders, unassignedFolderOrder) {
        buildFolderTargetIds(folders = folders, unassignedFolderOrder = unassignedFolderOrder)
    }
    val interactionState = rememberModFolderSectionInteractionState(folderTargetIds = folderTargetIds)
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
    val modsByStoragePath = remember(filteredMods) { filteredMods.associateBy { it.storagePath } }
    val modsByFolderId = remember(filteredMods, folderAssignments, folderIds) {
        filteredMods.groupBy { resolveAssignedFolderId(it, folderAssignments, folderIds) }
    }
    val unassignedMods = remember(modsByFolderId) { modsByFolderId[null].orEmpty() }

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
    val folderTokenByItemKey = remember(folderUiModels) {
        folderUiModels.associate { it.key to it.folderTokenId }
    }
    fun resolveFolderTokenFromItemKey(itemKey: Any): String? {
        val key = itemKey as? String ?: return null
        return folderTokenByItemKey[key]
    }

    val dragController = remember(
        interactionState,
        folderTokenByItemKey,
        folderAssignments,
        folderIds,
        modsByStoragePath,
        callbacks
    ) {
        ModFolderDragController(
            interactionState = interactionState,
            resolveFolderTokenFromItemKey = { itemKey ->
                val key = itemKey as? String ?: return@ModFolderDragController null
                folderTokenByItemKey[key]
            },
            resolveAssignedFolderToken = { mod ->
                resolveAssignedFolderId(mod, folderAssignments, folderIds) ?: UNASSIGNED_FOLDER_ID
            },
            onAssignModToFolder = callbacks.onAssignModToFolder,
            onMoveModToUnassigned = callbacks.onMoveModToUnassigned,
            onCollapseAllFoldersForModDrag = callbacks.onCollapseAllFoldersForModDrag,
            onExpandOnlySourceFolderAfterModDrag = callbacks.onExpandOnlySourceFolderAfterModDrag
        )
    }

    val activeDragSourceFolderId = remember(
        interactionState.activeDragFolderId,
        interactionState.activeDragModStoragePath,
        modsByStoragePath,
        folderAssignments,
        folderIds
    ) {
        when {
            interactionState.activeDragFolderId != null -> interactionState.activeDragFolderId
            interactionState.activeDragModStoragePath != null -> {
                modsByStoragePath[interactionState.activeDragModStoragePath]
                    ?.let { resolveAssignedFolderId(it, folderAssignments, folderIds) ?: UNASSIGNED_FOLDER_ID }
            }
            else -> null
        }
    }

    val reorderState = rememberReorderableLazyListState(
        lazyListState = interactionState.listState,
        onMove = { from, to ->
            val movingFolderId = resolveFolderTokenFromItemKey(from.key)
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

    LaunchedEffect(interactionState.dragSessionActive) {
        if (!interactionState.dragSessionActive) {
            return@LaunchedEffect
        }
        var smoothAutoScrollDelta = 0f
        while (interactionState.dragSessionActive) {
            val pointer = interactionState.lastPointerWindowRef.get()
            if (pointer != null && interactionState.activeDragModStoragePath != null) {
                dragController.updateDragHover(pointer)
                val target = dragController.computeAutoScrollDelta(pointer.y)
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
            }
            delay(16)
        }
    }

    val folderBackgroundColor = MaterialTheme.colorScheme.surfaceContainer
    val hoveredFolderBackgroundColor = MaterialTheme.colorScheme.secondaryContainer
    val statusSummaryUiModel = remember(statusSummary) {
        StatusSummaryUiModel(entries = parseStatusSummaryEntries(statusSummary))
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { interactionState.sectionTopLeftInWindow = it.boundsInWindow().topLeft },
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(
                id = R.string.main_folder_summary_format,
                filteredMods.size,
                unassignedMods.size
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 6.dp)
        )

        LazyColumn(
            state = interactionState.listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onGloballyPositioned { interactionState.listViewportInWindow = it.boundsInWindow() },
            contentPadding = PaddingValues(bottom = contentBottomInset)
        ) {
            item(key = "modsFilterInput") {
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

            if (statusSummaryUiModel.hasEntries) {
                item(key = "statusSummaryFolder") {
                    StatusSummaryFolderCard(
                        uiModel = statusSummaryUiModel,
                        collapsed = statusCollapsed,
                        folderBackgroundColor = folderBackgroundColor,
                        onToggleCollapsed = callbacks.onToggleStatusSummaryCollapsed
                    )
                }
            }

            itemsIndexed(
                items = folderUiModels,
                key = { _, item -> item.key }
            ) { index, folderUiModel ->
                val folderTokenId = folderUiModel.folderTokenId
                val isHovering =
                    interactionState.dragHoveredFolderId == folderTokenId &&
                        interactionState.activeDragFolderId == null
                val isSourceFolderDuringModDrag =
                    interactionState.dragSessionActive &&
                        interactionState.activeDragModStoragePath != null &&
                        activeDragSourceFolderId == folderTokenId
                val keepSourceFolderBodyAlive =
                    interactionState.activeDragModStoragePath != null &&
                        activeDragSourceFolderId == folderTokenId
                val bodyVisible = !folderUiModel.isCollapsed || keepSourceFolderBodyAlive
                val bodyMods = if (keepSourceFolderBodyAlive && folderUiModel.isCollapsed) {
                    folderUiModel.mods.filter { it.storagePath == interactionState.activeDragModStoragePath }
                } else {
                    folderUiModel.mods
                }
                val isModDragActive = interactionState.dragSessionActive && interactionState.activeDragModStoragePath != null
                val folderOverlayZIndex = when {
                    isHovering && isModDragActive -> DRAGGED_FOLDER_Z_INDEX + 40f
                    isHovering -> DRAGGED_FOLDER_Z_INDEX + 30f
                    isSourceFolderDuringModDrag -> DRAGGED_FOLDER_Z_INDEX + 10f
                    else -> 0f
                }
                val folderShape = RoundedCornerShape(10.dp)
                var renameDialog by remember(folderUiModel.key) { mutableStateOf(false) }
                var manageMenuExpanded by remember(folderUiModel.key) { mutableStateOf(false) }

                FolderNameDialog(
                    visible = renameDialog,
                    title = stringResource(R.string.main_folder_dialog_rename_title),
                    initialText = folderUiModel.folderName,
                    onDismiss = { renameDialog = false },
                    onConfirm = { newName ->
                        callbacks.onRenameFolder(folderTokenId, newName)
                    }
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(folderOverlayZIndex)
                ) {
                    ReorderableItem(
                        state = reorderState,
                        key = folderUiModel.key,
                        animateItemModifier = Modifier
                    ) { isReordering ->
                        val reorderableItemScope = this
                        val folderScale by animateFloatAsState(
                            targetValue = if (isHovering) HOVERED_FOLDER_SCALE else 1f,
                            animationSpec = tween(durationMillis = 140, easing = LinearEasing),
                            label = "folderScale-$folderTokenId"
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = 6.dp,
                                    top = if (index == 0 && !statusSummaryUiModel.hasEntries) 0.dp else 8.dp,
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
                                        Modifier.clip(folderShape)
                                    }
                                )
                                .background(
                                    if (isHovering) hoveredFolderBackgroundColor else folderBackgroundColor,
                                    folderShape
                                )
                                .border(
                                    if (isHovering) 2.dp else 1.dp,
                                    if (isHovering) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    folderShape
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
                                    folderId = folderTokenId,
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
                                        interactionState.activeDragModStoragePath = null
                                        interactionState.activeDragOverlay = null
                                        interactionState.activeDragFolderId = folderTokenId
                                        interactionState.folderPreviewOrder = folderTargetIds
                                        interactionState.folderDragSnapshot =
                                            callbacks.onCollapseAllFoldersForDragWithSnapshot()
                                        interactionState.dragSessionActive = true
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
                                        dragController.clearFolderDragSession()
                                        callbacks.onRestoreFolderCollapseSnapshot(interactionState.folderDragSnapshot)
                                        interactionState.folderDragSnapshot = null
                                    }
                                )
                                TriStateCheckbox(
                                    state = folderUiModel.toggleState,
                                    enabled = folderUiModel.mods.isNotEmpty() && hostAvailable,
                                    onClick = {
                                        if (hostAvailable) {
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
                                    onClick = {
                                        if (folderUiModel.isUnassigned) {
                                            callbacks.onToggleUnassignedCollapsed()
                                        } else {
                                            callbacks.onToggleFolderCollapsed(folderTokenId)
                                        }
                                    }
                                ) {
                                    Icon(
                                        painter = painterResource(
                                            if (folderUiModel.isCollapsed) R.drawable.ic_chevron_right else R.drawable.ic_expand_more
                                        ),
                                        contentDescription = stringResource(
                                            if (folderUiModel.isCollapsed) R.string.main_folder_expand else R.string.main_folder_collapse
                                        )
                                    )
                                }
                                Box {
                                    IconButton(onClick = { manageMenuExpanded = true }) {
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
                                            enabled = hostAvailable,
                                            onClick = {
                                                manageMenuExpanded = false
                                                renameDialog = true
                                            }
                                        )
                                        if (!folderUiModel.isUnassigned) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.main_folder_delete)) },
                                                enabled = hostAvailable,
                                                onClick = {
                                                    manageMenuExpanded = false
                                                    callbacks.onDeleteFolder(folderTokenId)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                            AnimatedVisibility(
                                visible = bodyVisible,
                                enter = expandVertically(
                                    expandFrom = Alignment.Top,
                                    animationSpec = tween(
                                        durationMillis = COLLAPSE_ANIMATION_MS,
                                        easing = LinearEasing
                                    )
                                ) + fadeIn(
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
                                ) + fadeOut(
                                    animationSpec = tween(
                                        durationMillis = COLLAPSE_ANIMATION_MS,
                                        easing = LinearEasing
                                    )
                                ),
                                label = "folderBody-$folderTokenId"
                            ) {
                                val bodyContainerModifier = if (keepSourceFolderBodyAlive && folderUiModel.isCollapsed) {
                                    Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                } else {
                                    Modifier.fillMaxWidth()
                                }
                                Column(modifier = bodyContainerModifier) {
                                    if (bodyMods.isEmpty()) {
                                        FolderEmptyHint(text = stringResource(folderUiModel.emptyTextResId))
                                    } else {
                                        bodyMods.forEach { mod ->
                                            key(mod.storagePath) {
                                                ModCard(
                                                    mod = mod,
                                                    isExpanded = interactionState.expandedCards[mod.storagePath] == true,
                                                    isDraggedInOverlay = interactionState.activeDragModStoragePath == mod.storagePath,
                                                    showModFileName = showModFileName,
                                                    setExpanded = { interactionState.expandedCards[mod.storagePath] = it },
                                                    controlsEnabled = uiState.controlsEnabled && mod.installed && hostAvailable,
                                                    callbacks = ModCardCallbacks(
                                                        onToggleMod = callbacks.onToggleMod,
                                                        onDeleteMod = callbacks.onDeleteMod,
                                                        onExportMod = callbacks.onExportMod,
                                                        onShareMod = callbacks.onShareMod,
                                                        onRenameModFile = callbacks.onRenameModFile,
                                                        onDragStart = { dragInfo ->
                                                            val sourceFolderTokenId =
                                                                resolveAssignedFolderId(mod, folderAssignments, folderIds)
                                                                    ?: UNASSIGNED_FOLDER_ID
                                                            dragController.onDragStart(
                                                                mod = mod,
                                                                dragInfo = dragInfo,
                                                                isExpanded = interactionState.expandedCards[mod.storagePath] == true,
                                                                sourceFolderTokenId = sourceFolderTokenId
                                                            )
                                                        },
                                                        onDragMove = { position ->
                                                            dragController.onDragMove(mod = mod, position = position)
                                                        },
                                                        onDragEnd = { position ->
                                                            dragController.onDragEnd(mod = mod, rawDropPosition = position)
                                                        },
                                                        onDragCancel = {
                                                            dragController.onDragCancel(mod = mod)
                                                        }
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    DraggingModCardOverlay(
        overlayState = interactionState.activeDragOverlay,
        showModFileName = showModFileName,
        overlayHostTopLeftWindow = interactionState.sectionTopLeftInWindow
    )
}

@Composable
private fun StatusSummaryFolderCard(
    uiModel: StatusSummaryUiModel,
    collapsed: Boolean,
    folderBackgroundColor: Color,
    onToggleCollapsed: () -> Unit
) {
    val folderShape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, top = 4.dp, end = 6.dp)
            .clip(folderShape)
            .background(folderBackgroundColor, folderShape)
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
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(
                    R.string.main_folder_name_count_format,
                    stringResource(R.string.main_status_card_title),
                    uiModel.okCount,
                    uiModel.totalCount
                ),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            IconButton(onClick = onToggleCollapsed) {
                Icon(
                    painter = painterResource(
                        if (collapsed) R.drawable.ic_chevron_right else R.drawable.ic_expand_more
                    ),
                    contentDescription = stringResource(
                        if (collapsed) R.string.main_folder_expand else R.string.main_folder_collapse
                    )
                )
            }
        }

        AnimatedVisibility(
            visible = !collapsed,
            enter = expandVertically(
                expandFrom = Alignment.Top,
                animationSpec = tween(
                    durationMillis = COLLAPSE_ANIMATION_MS,
                    easing = LinearEasing
                )
            ) + fadeIn(
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
            ) + fadeOut(
                animationSpec = tween(
                    durationMillis = COLLAPSE_ANIMATION_MS,
                    easing = LinearEasing
                )
            ),
            label = "statusSummaryBody"
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                uiModel.entries.forEachIndexed { index, entry ->
                    val dotColor = when (entry.tone) {
                        StatusTone.Ok -> MaterialTheme.colorScheme.primary
                        StatusTone.Warn -> MaterialTheme.colorScheme.tertiary
                        StatusTone.Error -> MaterialTheme.colorScheme.error
                        StatusTone.Info -> MaterialTheme.colorScheme.outline
                    }
                    val badgeContainerColor = when (entry.tone) {
                        StatusTone.Ok -> MaterialTheme.colorScheme.primaryContainer
                        StatusTone.Warn -> MaterialTheme.colorScheme.tertiaryContainer
                        StatusTone.Error -> MaterialTheme.colorScheme.errorContainer
                        StatusTone.Info -> MaterialTheme.colorScheme.surfaceVariant
                    }
                    val badgeTextColor = when (entry.tone) {
                        StatusTone.Error -> MaterialTheme.colorScheme.onErrorContainer
                        StatusTone.Warn -> MaterialTheme.colorScheme.onTertiaryContainer
                        StatusTone.Ok -> MaterialTheme.colorScheme.onPrimaryContainer
                        StatusTone.Info -> MaterialTheme.colorScheme.onSurfaceVariant
                    }

                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(dotColor)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = entry.label,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (entry.value.isNotBlank()) {
                            Text(
                                text = entry.value,
                                style = MaterialTheme.typography.labelMedium,
                                color = badgeTextColor,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(badgeContainerColor)
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
