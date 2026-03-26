package io.stamethyst.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
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
    val organizationControlsEnabled = uiState.controlsEnabled && hostAvailable
    val modFileActionsEnabled = !uiState.busy && hostAvailable
    val interactionState = rememberModFolderSectionInteractionState(folderTargetIds = folderTargetIds)
    var pendingDeleteMod by remember { mutableStateOf<ModItemUi?>(null) }
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
        filteredMods.groupBy { resolveAssignedFolderId(it, folderAssignments, folderIds) }
    }
    val unassignedMods = remember(modsByFolderId) { modsByFolderId[null].orEmpty() }
    val modDragState = interactionState.modDragState
    val activeModDragSession = modDragState.session

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
    val topLevelItemKeys = remember(dependencyMods.isNotEmpty(), folderUiModels) {
        buildList {
            add(MOD_LIST_TOP_PLACEHOLDER_KEY)
            add("modsFilterInput")
            if (dependencyMods.isNotEmpty()) {
                add("dependencyFolder")
            }
            addAll(folderUiModels.map { it.key })
        }
    }
    val folderTokenByItemKey = remember(folderUiModels) {
        folderUiModels.associate { it.key to it.folderTokenId }
    }
    fun resolveFolderTokenFromItemKey(itemKey: Any): String? {
        val key = itemKey as? String ?: return null
        return folderTokenByItemKey[key]
    }

    val dragCoordinator = remember(
        interactionState,
        folderTokenByItemKey,
        callbacks
    ) {
        ModDragSessionCoordinator(
            interactionState = interactionState,
            resolveFolderTokenFromItemKey = { itemKey ->
                val key = itemKey as? String ?: return@ModDragSessionCoordinator null
                folderTokenByItemKey[key]
            },
            resolveAssignedFolderToken = { mod ->
                resolveAssignedFolderId(mod, folderAssignments, folderIds) ?: UNASSIGNED_FOLDER_ID
            },
            onAssignModToFolder = callbacks.onAssignModToFolder,
            onMoveModToUnassigned = callbacks.onMoveModToUnassigned,
            onRevealFolderToken = callbacks.onRevealFolderToken
        )
    }

    val activeDragSourceFolderId = remember(
        interactionState.activeDragFolderId,
        activeModDragSession
    ) {
        when {
            interactionState.activeDragFolderId != null -> interactionState.activeDragFolderId
            activeModDragSession != null -> activeModDragSession.sourceFolderTokenId
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

    val folderBackgroundColor = MaterialTheme.colorScheme.surfaceContainer
    val hoveredFolderBackgroundColor = MaterialTheme.colorScheme.secondaryContainer

    Column(
        modifier = modifier
            .fillMaxWidth()
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

        LazyColumn(
            state = interactionState.listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onGloballyPositioned { interactionState.listViewportInWindow = it.boundsInWindow() },
            contentPadding = PaddingValues(bottom = contentBottomInset)
        ) {
            item(key = MOD_LIST_TOP_PLACEHOLDER_KEY) {
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(MOD_LIST_TOP_PLACEHOLDER_HEIGHT)
                )
            }

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

            if (dependencyMods.isNotEmpty()) {
                item(key = "dependencyFolder") {
                    DependencyFolderListItem(
                        mods = dependencyMods,
                        modSuggestions = uiState.modSuggestions,
                        collapsed = dependencyFolderCollapsed,
                        forceCollapsed = dragCoordinator.shouldCollapseFolders,
                        showModFileName = showModFileName,
                        interactionState = interactionState,
                        collapseEnabled = uiState.controlsEnabled,
                        onToggleCollapsed = callbacks.onToggleDependencyFolderCollapsed
                    )
                }
            }

            itemsIndexed(
                items = folderUiModels,
                key = { _, item -> item.key }
            ) { index, folderUiModel ->
                val folderTokenId = folderUiModel.folderTokenId
                val isHovering =
                    activeModDragSession?.hoveredFolderTokenId == folderTokenId &&
                        interactionState.activeDragFolderId == null
                val isSourceFolderDuringModDrag =
                    activeModDragSession != null &&
                        activeDragSourceFolderId == folderTokenId
                val keepSourceFolderBodyAlive =
                    activeModDragSession != null &&
                        activeDragSourceFolderId == folderTokenId
                val effectiveCollapsed = if (dragCoordinator.shouldCollapseFolders) {
                    true
                } else {
                    folderUiModel.isCollapsed
                }
                val bodyVisible = !effectiveCollapsed || keepSourceFolderBodyAlive
                val bodyMods = if (keepSourceFolderBodyAlive && effectiveCollapsed) {
                    folderUiModel.mods.filter { it.storagePath == activeModDragSession.mod.storagePath }
                } else {
                    folderUiModel.mods
                }
                val isModDragActive = activeModDragSession != null
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
                        renameDialog = false
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
                                    top = if (index == 0) 0.dp else 8.dp,
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
                                    enabled = organizationControlsEnabled,
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
                                    enabled = uiState.controlsEnabled,
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
                                            if (effectiveCollapsed) R.drawable.ic_chevron_right else R.drawable.ic_expand_more
                                        ),
                                        contentDescription = stringResource(
                                            if (effectiveCollapsed) R.string.main_folder_expand else R.string.main_folder_collapse
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
                            AnimatedVisibility(
                                visible = bodyVisible,
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
                                                    suggestionText = resolveModSuggestionText(mod, uiState.modSuggestions),
                                                    isExpanded = interactionState.expandedCards[mod.storagePath] == true,
                                                    isDraggedInOverlay = activeModDragSession?.mod?.storagePath == mod.storagePath,
                                                    showModFileName = showModFileName,
                                                    setExpanded = { interactionState.expandedCards[mod.storagePath] = it },
                                                    selectionEnabled = organizationControlsEnabled && mod.installed,
                                                    fileActionsEnabled = modFileActionsEnabled && mod.installed,
                                                    dragEnabled = organizationControlsEnabled && mod.installed,
                                                    callbacks = ModCardCallbacks(
                                                        onToggleMod = callbacks.onToggleMod,
                                                        onTogglePriorityLoad = callbacks.onTogglePriorityLoad,
                                                        onDeleteMod = { pendingDeleteMod = it },
                                                        onExportMod = callbacks.onExportMod,
                                                        onShareMod = callbacks.onShareMod,
                                                        onRenameModFile = callbacks.onRenameModFile,
                                                        onDragStart = { dragInfo ->
                                                            dragCoordinator.startModDrag(
                                                                mod = mod,
                                                                dragInfo = dragInfo,
                                                                isExpanded = interactionState.expandedCards[mod.storagePath] == true,
                                                            )
                                                        },
                                                        onDragCancel = {
                                                            dragCoordinator.cancelModDrag()
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

    DraggingModCardOverlayLayer(
        dragSession = modDragState.session,
        showModFileName = showModFileName,
        overlayHostTopLeftWindow = interactionState.sectionTopLeftInWindow
    )
}

@Composable
private fun DependencyFolderListItem(
    mods: List<ModItemUi>,
    modSuggestions: Map<String, String>,
    collapsed: Boolean,
    forceCollapsed: Boolean,
    showModFileName: Boolean,
    interactionState: ModFolderSectionInteractionState,
    collapseEnabled: Boolean,
    onToggleCollapsed: () -> Unit
) {
    val effectiveCollapsed = if (forceCollapsed) true else collapsed
    val readyCount = mods.count { it.enabled }
    val folderShape = RoundedCornerShape(10.dp)
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
                        ModCard(
                            mod = mod,
                            suggestionText = resolveModSuggestionText(mod, modSuggestions),
                            isExpanded = interactionState.expandedCards[mod.storagePath] == true,
                            isDraggedInOverlay = false,
                            showModFileName = showModFileName,
                            showActionsButton = false,
                            setExpanded = { interactionState.expandedCards[mod.storagePath] = it },
                            selectionEnabled = false,
                            fileActionsEnabled = false,
                            dragEnabled = false,
                            callbacks = ModCardCallbacks()
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

private const val MOD_LIST_TOP_PLACEHOLDER_KEY = "modListTopPlaceholder"
private val MOD_LIST_TOP_PLACEHOLDER_HEIGHT = 84.dp
