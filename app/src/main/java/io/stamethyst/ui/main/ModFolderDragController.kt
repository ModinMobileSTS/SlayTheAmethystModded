package io.stamethyst.ui.main

import androidx.compose.ui.geometry.Offset
import io.stamethyst.model.ModItemUi
import kotlin.math.abs

internal class ModFolderDragController(
    private val interactionState: ModFolderSectionInteractionState,
    private val resolveFolderTokenFromItemKey: (Any) -> String?,
    private val resolveAssignedFolderToken: (ModItemUi) -> String,
    private val onAssignModToFolder: (ModItemUi, String) -> Unit,
    private val onMoveModToUnassigned: (ModItemUi) -> Unit,
    private val onCollapseAllFoldersForModDrag: () -> Unit,
    private val onExpandOnlySourceFolderAfterModDrag: (String) -> Unit
) {
    fun onDragStart(
        mod: ModItemUi,
        dragInfo: ModCardDragStartInfo,
        isExpanded: Boolean,
        sourceFolderTokenId: String
    ) {
        interactionState.activeDragFolderId = null
        interactionState.activeDragModSourceFolderId = sourceFolderTokenId
        interactionState.activeDragModStoragePath = mod.storagePath
        interactionState.dragSessionActive = true
        interactionState.activeDragOverlay = ModCardDragOverlayState(
            mod = mod,
            pointerWindow = dragInfo.pointerWindow,
            touchOffsetInsideCard = dragInfo.pointerWindow - dragInfo.cardTopLeftWindow,
            cardSizePx = dragInfo.cardSizePx,
            isExpanded = isExpanded
        )
        onCollapseAllFoldersForModDrag()
        interactionState.lastPointerWindowRef.set(dragInfo.pointerWindow)
        updateDragHover(dragInfo.pointerWindow)
    }

    fun onDragMove(mod: ModItemUi, position: Offset) {
        interactionState.lastPointerWindowRef.set(position)
        updateDragHover(position)
        val current = interactionState.activeDragOverlay
        if (current != null && current.mod.storagePath == mod.storagePath) {
            interactionState.activeDragOverlay = current.copy(pointerWindow = position)
        }
    }

    fun onDragEnd(mod: ModItemUi, rawDropPosition: Offset) {
        interactionState.lastPointerWindowRef.set(rawDropPosition)
        val current = interactionState.activeDragOverlay
        if (current != null && current.mod.storagePath == mod.storagePath) {
            interactionState.activeDragOverlay = current.copy(pointerWindow = rawDropPosition)
        }
        handleModDrop(mod, rawDropPosition)
        expandSourceFolderAfterModDrag()
    }

    fun onDragCancel(mod: ModItemUi) {
        interactionState.dragHoveredFolderId = null
        interactionState.dragSessionActive = false
        interactionState.activeDragOverlay = null
        if (interactionState.activeDragModStoragePath == mod.storagePath) {
            interactionState.activeDragModStoragePath = null
        }
        expandSourceFolderAfterModDrag()
    }

    fun clearFolderDragSession() {
        interactionState.activeDragFolderId = null
        interactionState.dragHoveredFolderId = null
        interactionState.dragSessionActive = false
        interactionState.activeDragOverlay = null
    }

    fun updateDragHover(position: Offset, ignoreFolderId: String? = null) {
        if (!interactionState.dragSessionActive) {
            if (interactionState.dragHoveredFolderId != null) {
                interactionState.dragHoveredFolderId = null
            }
            return
        }
        val nextHoverId = findExactFolderAt(position, ignoreFolderId)
        if (interactionState.dragHoveredFolderId != nextHoverId) {
            interactionState.dragHoveredFolderId = nextHoverId
        }
    }

    fun computeAutoScrollDelta(pointerY: Float): Float {
        val viewport = interactionState.listViewportInWindow ?: return 0f
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

    private fun findExactFolderAt(position: Offset, ignoreFolderId: String? = null): String? {
        val viewport = interactionState.listViewportInWindow ?: return null
        val pointerY = position.y
        return interactionState.listState.layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { itemInfo ->
            val folderToken = resolveFolderTokenFromItemKey(itemInfo.key) ?: return@firstNotNullOfOrNull null
            if (folderToken == ignoreFolderId) {
                return@firstNotNullOfOrNull null
            }
            val top = viewport.top + itemInfo.offset
            val bottom = top + itemInfo.size
            if (pointerY in top..bottom) folderToken else null
        }
    }

    private fun findDropFolderAt(position: Offset, ignoreFolderId: String? = null): String? {
        val exact = findExactFolderAt(position, ignoreFolderId)
        if (exact != null) {
            return exact
        }
        val viewport = interactionState.listViewportInWindow ?: return null
        val pointerY = position.y

        val candidates = interactionState.listState.layoutInfo.visibleItemsInfo.mapNotNull { itemInfo ->
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

    private fun handleModDrop(mod: ModItemUi, rawDropPosition: Offset) {
        val realDropPosition = interactionState.lastPointerWindowRef.get() ?: rawDropPosition
        val targetFolderId = findDropFolderAt(realDropPosition)
        val sourceFolderId = resolveAssignedFolderToken(mod)

        interactionState.dragHoveredFolderId = null
        interactionState.dragSessionActive = false
        interactionState.activeDragOverlay = null
        if (interactionState.activeDragModStoragePath == mod.storagePath) {
            interactionState.activeDragModStoragePath = null
        }

        when {
            targetFolderId == null -> Unit
            targetFolderId == sourceFolderId -> Unit
            targetFolderId == UNASSIGNED_FOLDER_ID -> onMoveModToUnassigned(mod)
            else -> onAssignModToFolder(mod, targetFolderId)
        }
    }

    private fun expandSourceFolderAfterModDrag() {
        val sourceFolderTokenId = interactionState.activeDragModSourceFolderId
        if (!sourceFolderTokenId.isNullOrBlank()) {
            onExpandOnlySourceFolderAfterModDrag(sourceFolderTokenId)
        }
        interactionState.activeDragModSourceFolderId = null
    }
}
