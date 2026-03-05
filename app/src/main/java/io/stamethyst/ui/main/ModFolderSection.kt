package io.stamethyst.ui.main

import android.app.Activity
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import io.stamethyst.model.ModItemUi
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlinx.coroutines.delay
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private data class FolderListItem(
    val folderTokenId: String,
    val mods: List<ModItemUi>,
    val isCollapsed: Boolean,
    val emptyTextResId: Int
) {
    val key: String = "folder:$folderTokenId"
}

private enum class StatusTone {
    Ok,
    Warn,
    Error,
    Info
}

private data class StatusSummaryEntry(
    val label: String,
    val value: String,
    val tone: StatusTone
)

private fun parseStatusSummaryEntries(summary: String): List<StatusSummaryEntry> {
    return summary.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            val colonIndex = line.indexOf(':')
            val label = if (colonIndex >= 0) line.substring(0, colonIndex).trim() else line
            val value = if (colonIndex >= 0) line.substring(colonIndex + 1).trim() else ""
            val lowercase = value.lowercase()
            val tone = when {
                lowercase.contains("ok") -> StatusTone.Ok
                lowercase.contains("warn") -> StatusTone.Warn
                lowercase.contains("missing") || lowercase.contains("error") || lowercase.contains("fail") -> StatusTone.Error
                else -> StatusTone.Info
            }
            StatusSummaryEntry(label = label, value = value, tone = tone)
        }
        .toList()
}

@Composable
internal fun ModFolderSection(
    modifier: Modifier = Modifier,
    uiState: MainScreenViewModel.UiState,
    statusSummary: String,
    contentBottomInset: Dp = 0.dp,
    hostActivity: Activity?,
    onToggleMod: (ModItemUi, Boolean) -> Unit,
    onDeleteMod: (ModItemUi) -> Unit,
    onExportMod: (ModItemUi) -> Unit,
    onShareMod: (ModItemUi) -> Unit,
    onRenameModFile: (ModItemUi, String) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onSetFolderSelected: (String, Boolean) -> Unit,
    onSetUnassignedSelected: (Boolean) -> Unit,
    onToggleFolderCollapsed: (String) -> Unit,
    onToggleUnassignedCollapsed: () -> Unit,
    onToggleStatusSummaryCollapsed: () -> Unit,
    onMoveFolderUp: (String) -> Unit,
    onMoveFolderDown: (String) -> Unit,
    onMoveUnassignedUp: () -> Unit,
    onMoveUnassignedDown: () -> Unit,
    onMoveFolderTokenToIndex: (String, Int) -> Unit,
    onAssignModToFolder: (ModItemUi, String) -> Unit,
    onMoveModToUnassigned: (ModItemUi) -> Unit,
    onCollapseAllFoldersForDragWithSnapshot: () -> MainScreenViewModel.FolderCollapseSnapshot?,
    onRestoreFolderCollapseSnapshot: (MainScreenViewModel.FolderCollapseSnapshot?) -> Unit
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

    var filterText by remember { mutableStateOf("") }
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

    val folderTargetIds = remember(folders, unassignedFolderOrder) {
        val insertUnassignedAt = unassignedFolderOrder.coerceIn(0, folders.size)
        buildList {
            folders.forEachIndexed { index, folder ->
                if (index == insertUnassignedAt) {
                    add(UNASSIGNED_FOLDER_ID)
                }
                add(folder.id)
            }
            if (insertUnassignedAt == folders.size) {
                add(UNASSIGNED_FOLDER_ID)
            }
        }
    }
    var folderPreviewOrder by remember(folderTargetIds) { mutableStateOf(folderTargetIds) }
    LaunchedEffect(folderTargetIds) {
        folderPreviewOrder = folderTargetIds
    }
    val displayFolderTargetIds = folderPreviewOrder
    val folderDisplayIndexMap = remember(displayFolderTargetIds) {
        displayFolderTargetIds.withIndex().associate { it.value to it.index }
    }
    val folderItems = remember(displayFolderTargetIds, folderCollapsed, unassignedCollapsed, modsByFolderId) {
        buildList<FolderListItem> {
            displayFolderTargetIds.forEach { folderTokenId ->
                val modsInFolder = if (folderTokenId == UNASSIGNED_FOLDER_ID) {
                    modsByFolderId[null].orEmpty()
                } else {
                    modsByFolderId[folderTokenId].orEmpty()
                }
                val collapsed = if (folderTokenId == UNASSIGNED_FOLDER_ID) {
                    unassignedCollapsed
                } else {
                    folderCollapsed[folderTokenId] == true
                }
                add(
                    FolderListItem(
                        folderTokenId = folderTokenId,
                        mods = modsInFolder,
                        isCollapsed = collapsed,
                        emptyTextResId = if (folderTokenId == UNASSIGNED_FOLDER_ID) {
                            R.string.main_folder_unassigned_empty
                        } else {
                            R.string.main_folder_drag_here
                        }
                    )
                )
            }
        }
    }
    val folderTokenByItemKey = remember(folderItems) {
        folderItems.associate { it.key to it.folderTokenId }
    }

    var dragHoveredFolderId by remember { mutableStateOf<String?>(null) }
    var activeDragModStoragePath by remember { mutableStateOf<String?>(null) }
    var activeDragFolderId by remember { mutableStateOf<String?>(null) }
    var dragSessionActive by remember { mutableStateOf(false) }
    var activeDragOverlay by remember { mutableStateOf<ModCardDragOverlayState?>(null) }
    var folderDragSnapshot by remember { mutableStateOf<MainScreenViewModel.FolderCollapseSnapshot?>(null) }
    val expandedCards = remember { mutableStateMapOf<String, Boolean>() }

    val activeDragSourceFolderId = remember(
        activeDragFolderId,
        activeDragModStoragePath,
        modsByStoragePath,
        folderAssignments,
        folderIds
    ) {
        when {
            activeDragFolderId != null -> activeDragFolderId
            activeDragModStoragePath != null -> {
                modsByStoragePath[activeDragModStoragePath]
                    ?.let { resolveAssignedFolderId(it, folderAssignments, folderIds) ?: UNASSIGNED_FOLDER_ID }
            }
            else -> null
        }
    }

    val lastPointerWindowRef = remember { AtomicReference<Offset?>(null) }
    val listState = rememberLazyListState()
    var listViewportInWindow by remember { mutableStateOf<Rect?>(null) }
    var sectionTopLeftInWindow by remember { mutableStateOf(Offset.Zero) }

    fun resolveFolderTokenFromItemKey(itemKey: Any): String? {
        val key = itemKey as? String ?: return null
        return folderTokenByItemKey[key]
    }

    fun findExactFolderAt(position: Offset, ignoreFolderId: String? = null): String? {
        val viewport = listViewportInWindow ?: return null
        val pointerY = position.y
        return listState.layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { itemInfo ->
            val folderToken = resolveFolderTokenFromItemKey(itemInfo.key) ?: return@firstNotNullOfOrNull null
            if (folderToken == ignoreFolderId) {
                return@firstNotNullOfOrNull null
            }
            val top = viewport.top + itemInfo.offset
            val bottom = top + itemInfo.size
            if (pointerY in top..bottom) folderToken else null
        }
    }

    fun findDropFolderAt(position: Offset, ignoreFolderId: String? = null): String? {
        val exact = findExactFolderAt(position, ignoreFolderId)
        if (exact != null) {
            return exact
        }
        val viewport = listViewportInWindow ?: return null
        val pointerY = position.y

        val candidates = listState.layoutInfo.visibleItemsInfo.mapNotNull { itemInfo ->
            val folderToken = resolveFolderTokenFromItemKey(itemInfo.key) ?: return@mapNotNull null
            if (folderToken == ignoreFolderId) {
                return@mapNotNull null
            }
            val top = viewport.top + itemInfo.offset
            val bottom = top + itemInfo.size
            folderToken to ((top + bottom) / 2f)
        }
        if (candidates.isEmpty()) {
            return null
        }
        val maxCenter = candidates.maxOfOrNull { it.second } ?: return null
        if (pointerY > maxCenter + 80f) {
            return null
        }
        return candidates.minByOrNull { (_, centerY) -> abs(centerY - pointerY) }?.first
    }

    fun updateDragHover(position: Offset, ignoreFolderId: String? = null) {
        if (!dragSessionActive) {
            if (dragHoveredFolderId != null) {
                dragHoveredFolderId = null
            }
            return
        }
        val nextHoverId = findExactFolderAt(position, ignoreFolderId)
        if (dragHoveredFolderId != nextHoverId) {
            dragHoveredFolderId = nextHoverId
        }
    }

    fun handleModDrop(mod: ModItemUi, rawDropPosition: Offset) {
        val realDropPosition = lastPointerWindowRef.get() ?: rawDropPosition
        val targetFolderId = findDropFolderAt(realDropPosition)
        val sourceFolderId = resolveAssignedFolderId(mod, folderAssignments, folderIds) ?: UNASSIGNED_FOLDER_ID

        dragHoveredFolderId = null
        dragSessionActive = false
        activeDragOverlay = null
        if (activeDragModStoragePath == mod.storagePath) {
            activeDragModStoragePath = null
        }

        when {
            targetFolderId == null -> Unit
            targetFolderId == sourceFolderId -> Unit
            targetFolderId == UNASSIGNED_FOLDER_ID -> onMoveModToUnassigned(mod)
            else -> onAssignModToFolder(mod, targetFolderId)
        }
    }

    fun computeAutoScrollDelta(pointerY: Float): Float {
        val viewport = listViewportInWindow ?: return 0f
        val edgeSize = 136f
        val maxStep = 56f
        val topTrigger = viewport.top + edgeSize
        val bottomTrigger = viewport.bottom - edgeSize
        return when {
            pointerY < topTrigger -> {
                val ratio = ((topTrigger - pointerY) / edgeSize).coerceIn(0f, 1f)
                val eased = ratio * ratio
                if (eased < 0.04f) 0f else -maxStep * eased
            }

            pointerY > bottomTrigger -> {
                val ratio = ((pointerY - bottomTrigger) / edgeSize).coerceIn(0f, 1f)
                val eased = ratio * ratio
                if (eased < 0.04f) 0f else maxStep * eased
            }

            else -> 0f
        }
    }

    val reorderState = rememberReorderableLazyListState(
        lazyListState = listState,
        onMove = { from, to ->
            val fromItem = folderItems.getOrNull(from.index) ?: return@rememberReorderableLazyListState
            val toItem = folderItems.getOrNull(to.index) ?: return@rememberReorderableLazyListState
            val movingFolderId = fromItem.folderTokenId
            val targetFolderId = toItem.folderTokenId
            val mutable = folderPreviewOrder.toMutableList()
            val fromIndex = mutable.indexOf(movingFolderId)
            val toIndex = mutable.indexOf(targetFolderId)
            if (fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex) {
                val moved = mutable.removeAt(fromIndex)
                mutable.add(toIndex, moved)
                folderPreviewOrder = mutable
            }
        }
    )

    LaunchedEffect(dragSessionActive) {
        if (!dragSessionActive) {
            return@LaunchedEffect
        }
        var smoothAutoScrollDelta = 0f
        while (dragSessionActive) {
            val pointer = lastPointerWindowRef.get()
            if (pointer != null && activeDragModStoragePath != null) {
                updateDragHover(pointer)
                val target = computeAutoScrollDelta(pointer.y)
                smoothAutoScrollDelta = if (target == 0f) {
                    smoothAutoScrollDelta * 0.5f
                } else {
                    smoothAutoScrollDelta * 0.5f + target * 0.3f
                }
                if (abs(smoothAutoScrollDelta) < 0.25f) {
                    smoothAutoScrollDelta = 0f
                }
                if (smoothAutoScrollDelta != 0f) {
                    listState.scrollBy(smoothAutoScrollDelta)
                }
            }
            delay(16)
        }
    }

    val folderBackgroundColor = MaterialTheme.colorScheme.surfaceContainer
    val hoveredFolderBackgroundColor = MaterialTheme.colorScheme.secondaryContainer
    val statusEntries = remember(statusSummary) { parseStatusSummaryEntries(statusSummary) }
    val hasStatusEntries = statusEntries.isNotEmpty()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { sectionTopLeftInWindow = it.boundsInWindow().topLeft },
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
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onGloballyPositioned { listViewportInWindow = it.boundsInWindow() },
            contentPadding = PaddingValues(bottom = contentBottomInset),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item(key = "modsFilterInput") {
                OutlinedTextField(
                    value = filterText,
                    onValueChange = { filterText = it },
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

            if (hasStatusEntries) {
                item(key = "statusSummaryFolder") {
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
                                    statusEntries.count { it.tone == StatusTone.Ok },
                                    statusEntries.size
                                ),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            IconButton(
                                onClick = onToggleStatusSummaryCollapsed
                            ) {
                                Icon(
                                    painter = painterResource(
                                        if (statusCollapsed) R.drawable.ic_chevron_right else R.drawable.ic_expand_more
                                    ),
                                    contentDescription = stringResource(
                                        if (statusCollapsed) R.string.main_folder_expand else R.string.main_folder_collapse
                                    )
                                )
                            }
                        }

                        AnimatedVisibility(
                            visible = !statusCollapsed,
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
                                statusEntries.forEachIndexed { index, entry ->
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
            }

            itemsIndexed(
                items = folderItems,
                key = { _, item -> item.key }
            ) { index, item ->
                val folderTokenId = item.folderTokenId
                val isUnassigned = folderTokenId == UNASSIGNED_FOLDER_ID
                val folder = if (isUnassigned) null else foldersById[folderTokenId] ?: return@itemsIndexed
                val modsInFolder = item.mods
                val isCollapsed = item.isCollapsed
                val selectedCount = modsInFolder.count { it.enabled }
                val toggleState = when {
                    modsInFolder.isEmpty() -> ToggleableState.Off
                    selectedCount == 0 -> ToggleableState.Off
                    selectedCount == modsInFolder.size -> ToggleableState.On
                    else -> ToggleableState.Indeterminate
                }
                val folderName = if (isUnassigned) unassignedFolderName else folder?.name.orEmpty()
                val isHovering = dragHoveredFolderId == folderTokenId && activeDragFolderId == null
                val isSourceFolderDuringModDrag =
                    dragSessionActive &&
                        activeDragModStoragePath != null &&
                        activeDragSourceFolderId == folderTokenId
                val isModDragActive = dragSessionActive && activeDragModStoragePath != null
                val folderOverlayZIndex = when {
                    // Hovered target folder must stay above any other folder while scaling.
                    isHovering && isModDragActive -> DRAGGED_FOLDER_Z_INDEX + 40f
                    isHovering -> DRAGGED_FOLDER_Z_INDEX + 30f
                    isSourceFolderDuringModDrag -> DRAGGED_FOLDER_Z_INDEX + 10f
                    else -> 0f
                }
                val folderShape = RoundedCornerShape(10.dp)
                var renameDialog by remember(item.key) { mutableStateOf(false) }
                var manageMenuExpanded by remember(item.key) { mutableStateOf(false) }

                FolderNameDialog(
                    visible = renameDialog,
                    title = stringResource(R.string.main_folder_dialog_rename_title),
                    initialText = folderName,
                    onDismiss = { renameDialog = false },
                    onConfirm = { newName ->
                        onRenameFolder(folderTokenId, newName)
                    }
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(folderOverlayZIndex)
                ) {
                    ReorderableItem(state = reorderState, key = item.key) { isReordering ->
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
                                    top = if (index == 0 && !hasStatusEntries) 0.dp else 8.dp,
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
                                    if (isUnassigned) {
                                        onMoveUnassignedUp()
                                    } else {
                                        onMoveFolderUp(folderTokenId)
                                    }
                                },
                                onMoveDown = {
                                    if (isUnassigned) {
                                        onMoveUnassignedDown()
                                    } else {
                                        onMoveFolderDown(folderTokenId)
                                    }
                                },
                                onDragStarted = {
                                    activeDragModStoragePath = null
                                    activeDragOverlay = null
                                    activeDragFolderId = folderTokenId
                                    folderPreviewOrder = folderTargetIds
                                    folderDragSnapshot = onCollapseAllFoldersForDragWithSnapshot()
                                    dragSessionActive = true
                                },
                                onDragStopped = {
                                    val draggedId = activeDragFolderId
                                    if (draggedId != null) {
                                        val targetIndex = folderPreviewOrder.indexOf(draggedId)
                                        val currentIndex = folderTargetIds.indexOf(draggedId)
                                        if (targetIndex >= 0 && currentIndex >= 0 && targetIndex != currentIndex) {
                                            onMoveFolderTokenToIndex(draggedId, targetIndex)
                                        }
                                    }
                                    activeDragFolderId = null
                                    dragHoveredFolderId = null
                                    dragSessionActive = false
                                    activeDragOverlay = null
                                    onRestoreFolderCollapseSnapshot(folderDragSnapshot)
                                    folderDragSnapshot = null
                                }
                            )
                            TriStateCheckbox(
                                state = toggleState,
                                enabled = modsInFolder.isNotEmpty() && hostActivity != null,
                                onClick = {
                                    if (hostActivity != null) {
                                        if (isUnassigned) {
                                            onSetUnassignedSelected(toggleState != ToggleableState.On)
                                        } else {
                                            onSetFolderSelected(folderTokenId, toggleState != ToggleableState.On)
                                        }
                                    }
                                }
                            )
                            Text(
                                text = stringResource(
                                    R.string.main_folder_name_count_format,
                                    folderName,
                                    selectedCount,
                                    modsInFolder.size
                                ),
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.primary
                            )
                            IconButton(
                                onClick = {
                                    if (isUnassigned) {
                                        onToggleUnassignedCollapsed()
                                    } else {
                                        onToggleFolderCollapsed(folderTokenId)
                                    }
                                }
                            ) {
                                Icon(
                                    painter = painterResource(
                                        if (isCollapsed) R.drawable.ic_chevron_right else R.drawable.ic_expand_more
                                    ),
                                    contentDescription = stringResource(
                                        if (isCollapsed) R.string.main_folder_expand else R.string.main_folder_collapse
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
                                        enabled = hostActivity != null,
                                        onClick = {
                                            manageMenuExpanded = false
                                            renameDialog = true
                                        }
                                    )
                                    if (!isUnassigned) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.main_folder_delete)) },
                                            enabled = hostActivity != null,
                                            onClick = {
                                                manageMenuExpanded = false
                                                onDeleteFolder(folderTokenId)
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        AnimatedVisibility(
                            visible = !isCollapsed,
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
                            Column(modifier = Modifier.fillMaxWidth()) {
                                if (modsInFolder.isEmpty()) {
                                    FolderEmptyHint(text = stringResource(item.emptyTextResId))
                                } else {
                                    modsInFolder.forEach { mod ->
                                        key(mod.storagePath) {
                                            ModCard(
                                                mod = mod,
                                                isExpanded = expandedCards[mod.storagePath] == true,
                                                isDraggedInOverlay = activeDragModStoragePath == mod.storagePath,
                                                showModFileName = showModFileName,
                                                setExpanded = { expandedCards[mod.storagePath] = it },
                                                controlsEnabled = uiState.controlsEnabled && mod.installed && hostActivity != null,
                                                onToggleMod = onToggleMod,
                                                onDeleteMod = onDeleteMod,
                                                onExportMod = onExportMod,
                                                onShareMod = onShareMod,
                                                onRenameModFile = onRenameModFile,
                                                onDragStart = { dragInfo ->
                                                    activeDragFolderId = null
                                                    activeDragModStoragePath = mod.storagePath
                                                    dragSessionActive = true
                                                    activeDragOverlay = ModCardDragOverlayState(
                                                        mod = mod,
                                                        pointerWindow = dragInfo.pointerWindow,
                                                        touchOffsetInsideCard = dragInfo.pointerWindow - dragInfo.cardTopLeftWindow,
                                                        cardSizePx = dragInfo.cardSizePx,
                                                        isExpanded = expandedCards[mod.storagePath] == true
                                                    )
                                                    lastPointerWindowRef.set(dragInfo.pointerWindow)
                                                    updateDragHover(dragInfo.pointerWindow)
                                                },
                                                onDragMove = { position ->
                                                    lastPointerWindowRef.set(position)
                                                    updateDragHover(position)
                                                    val current = activeDragOverlay
                                                    if (current != null && current.mod.storagePath == mod.storagePath) {
                                                        activeDragOverlay = current.copy(pointerWindow = position)
                                                    }
                                                },
                                                onDragEnd = { position ->
                                                    lastPointerWindowRef.set(position)
                                                    val current = activeDragOverlay
                                                    if (current != null && current.mod.storagePath == mod.storagePath) {
                                                        activeDragOverlay = current.copy(pointerWindow = position)
                                                    }
                                                    handleModDrop(mod, position)
                                                },
                                                onDragCancel = {
                                                    dragHoveredFolderId = null
                                                    dragSessionActive = false
                                                    activeDragOverlay = null
                                                    if (activeDragModStoragePath == mod.storagePath) {
                                                        activeDragModStoragePath = null
                                                    }
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
            }
        }
    }
    DraggingModCardOverlay(
        overlayState = activeDragOverlay,
        showModFileName = showModFileName,
        overlayHostTopLeftWindow = sectionTopLeftInWindow
    )
}
