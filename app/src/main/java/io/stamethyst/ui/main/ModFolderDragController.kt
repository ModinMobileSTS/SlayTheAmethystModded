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
    private val onRevealFolderToken: (String) -> Unit
) {
    fun onDragStart(
        mod: ModItemUi,
        dragInfo: ModCardDragStartInfo,
        isExpanded: Boolean,
    ) {
        interactionState.activeDragFolderId = null
        interactionState.forceCollapseDuringDrag = true
        interactionState.activeModDragSession = ModDragSession(
            mod = mod,
            sourceFolderTokenId = resolveAssignedFolderToken(mod),
            overlayAnchor = dragInfo.overlayAnchor,
            isExpanded = isExpanded
        )
        updateModDragHover(dragInfo.overlayAnchor.pointerWindow)
    }

    fun onDragMove(mod: ModItemUi, position: Offset) {
        val currentSession = interactionState.activeModDragSession
        if (currentSession == null || currentSession.mod.storagePath != mod.storagePath) {
            return
        }
        updateModDragHover(position)
    }

    fun onDragEnd(mod: ModItemUi, rawDropPosition: Offset) {
        val currentSession = interactionState.activeModDragSession
        if (currentSession == null || currentSession.mod.storagePath != mod.storagePath) {
            return
        }
        interactionState.activeModDragSession = currentSession.updatePointer(rawDropPosition)
        handleModDrop(mod, rawDropPosition)
    }

    fun onDragCancel(mod: ModItemUi) {
        val currentSession = interactionState.activeModDragSession
        if (currentSession != null && currentSession.mod.storagePath == mod.storagePath) {
            clearModDragSession()
        }
    }

    fun clearFolderDragSession() {
        interactionState.activeDragFolderId = null
        interactionState.forceCollapseDuringDrag = false
    }

    fun updateModDragHover(position: Offset, ignoreFolderId: String? = null) {
        val currentSession = interactionState.activeModDragSession ?: return
        val nextHoverId = findExactFolderAt(position, ignoreFolderId)
        val updatedSession = currentSession
            .updatePointer(position)
            .copy(hoveredFolderTokenId = nextHoverId)
        if (updatedSession != currentSession) {
            interactionState.activeModDragSession = updatedSession
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

    private fun clearModDragSession() {
        interactionState.activeModDragSession = null
        if (interactionState.activeDragFolderId == null) {
            interactionState.forceCollapseDuringDrag = false
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
        val currentSession = interactionState.activeModDragSession ?: return
        val realDropPosition = currentSession.overlayAnchor.pointerWindow
        val targetFolderId = findDropFolderAt(realDropPosition)
        val sourceFolderId = currentSession.sourceFolderTokenId
        clearModDragSession()

        when {
            targetFolderId == null -> Unit
            targetFolderId == sourceFolderId -> Unit
            targetFolderId == UNASSIGNED_FOLDER_ID -> {
                onMoveModToUnassigned(mod)
                onRevealFolderToken(targetFolderId)
            }

            else -> {
                onAssignModToFolder(mod, targetFolderId)
                onRevealFolderToken(targetFolderId)
            }
        }
    }
}
