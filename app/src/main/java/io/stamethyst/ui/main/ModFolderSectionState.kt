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
import io.stamethyst.R
import io.stamethyst.model.ModItemUi
import java.util.concurrent.atomic.AtomicReference

internal class ModFolderSectionInteractionState(
    val listState: LazyListState,
    initialFolderOrder: List<String>
) {
    var filterText by mutableStateOf("")
    var folderPreviewOrder by mutableStateOf(initialFolderOrder)

    var dragHoveredFolderId by mutableStateOf<String?>(null)
    var activeDragModStoragePath by mutableStateOf<String?>(null)
    var activeDragFolderId by mutableStateOf<String?>(null)
    var activeDragModSourceFolderId by mutableStateOf<String?>(null)
    var dragSessionActive by mutableStateOf(false)
    val activeDragOverlayState = mutableStateOf<ModCardDragOverlayState?>(null)
    var folderDragSnapshot by mutableStateOf<MainScreenViewModel.FolderCollapseSnapshot?>(null)
    val expandedCards = mutableStateMapOf<String, Boolean>()
    val lastPointerWindowRef = AtomicReference<Offset?>(null)
    var listViewportInWindow by mutableStateOf<Rect?>(null)
    var sectionTopLeftInWindow by mutableStateOf(Offset.Zero)
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

internal fun parseStatusSummaryEntries(summary: String): List<StatusSummaryEntry> {
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
