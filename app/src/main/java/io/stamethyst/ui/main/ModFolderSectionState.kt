package io.stamethyst.ui.main

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.IntSize
import io.stamethyst.R
import io.stamethyst.model.ModItemUi

internal data class ModDragOverlayAnchor(
    val pointerWindow: Offset,
    val pointerOffsetInsideCard: Offset,
    val visualOffsetFromPointer: Offset,
    val cardSizePx: IntSize
) {
    fun overlayTopLeftInWindow(): Offset {
        return pointerWindow - pointerOffsetInsideCard + visualOffsetFromPointer
    }
}

internal data class ModDragSession(
    val mod: ModItemUi,
    val sourceFolderTokenId: String,
    val hoveredFolderTokenId: String? = null,
    val overlayAnchor: ModDragOverlayAnchor,
    val isExpanded: Boolean
) {
    fun updatePointer(pointerWindow: Offset): ModDragSession {
        return copy(overlayAnchor = overlayAnchor.copy(pointerWindow = pointerWindow))
    }
}

internal class ModFolderSectionInteractionState(
    val listState: LazyListState,
    initialFolderOrder: List<String>
) {
    var filterText by mutableStateOf("")
    var folderPreviewOrder by mutableStateOf(initialFolderOrder)

    var activeModDragSession by mutableStateOf<ModDragSession?>(null)
    var activeDragFolderId by mutableStateOf<String?>(null)
    var forceCollapseDuringDrag by mutableStateOf(false)
    val expandedCards = mutableStateMapOf<String, Boolean>()
    var listViewportInWindow by mutableStateOf<Rect?>(null)
    var sectionTopLeftInWindow by mutableStateOf(Offset.Zero)

    val isModDragActive: Boolean
        get() = activeModDragSession != null
}

@Composable
internal fun rememberModFolderSectionInteractionState(
    folderTargetIds: List<String>
): ModFolderSectionInteractionState {
    val listState = rememberLazyListState()
    val state = remember(listState) {
        ModFolderSectionInteractionState(
            listState = listState,
            initialFolderOrder = folderTargetIds
        )
    }
    LaunchedEffect(folderTargetIds) {
        state.folderPreviewOrder = folderTargetIds
    }
    return state
}

internal fun buildFolderTargetIds(
    folders: List<MainScreenViewModel.ModFolder>,
    unassignedFolderOrder: Int
): List<String> {
    val insertUnassignedAt = unassignedFolderOrder.coerceIn(0, folders.size)
    return buildList {
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

internal fun buildFolderUiModels(
    displayFolderTargetIds: List<String>,
    foldersById: Map<String, MainScreenViewModel.ModFolder>,
    modsByFolderId: Map<String?, List<ModItemUi>>,
    folderCollapsed: Map<String, Boolean>,
    unassignedCollapsed: Boolean,
    unassignedFolderName: String
): List<FolderUiModel> {
    return displayFolderTargetIds.mapNotNull { folderTokenId ->
        val isUnassigned = folderTokenId == UNASSIGNED_FOLDER_ID
        val folder = if (isUnassigned) null else foldersById[folderTokenId] ?: return@mapNotNull null
        val modsInFolder = if (isUnassigned) {
            modsByFolderId[null].orEmpty()
        } else {
            modsByFolderId[folderTokenId].orEmpty()
        }
        val isCollapsed = if (isUnassigned) {
            unassignedCollapsed
        } else {
            folderCollapsed[folderTokenId] == true
        }
        val selectedCount = modsInFolder.count { it.enabled }
        val toggleState = when {
            modsInFolder.isEmpty() -> ToggleableState.Off
            selectedCount == 0 -> ToggleableState.Off
            selectedCount == modsInFolder.size -> ToggleableState.On
            else -> ToggleableState.Indeterminate
        }
        FolderUiModel(
            key = "folder:$folderTokenId",
            folderTokenId = folderTokenId,
            folderName = if (isUnassigned) unassignedFolderName else folder?.name.orEmpty(),
            mods = modsInFolder,
            isCollapsed = isCollapsed,
            isUnassigned = isUnassigned,
            emptyTextResId = if (isUnassigned) {
                R.string.main_folder_unassigned_empty
            } else {
                R.string.main_folder_drag_here
            },
            selectedCount = selectedCount,
            toggleState = toggleState
        )
    }
}
