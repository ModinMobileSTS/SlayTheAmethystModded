package io.stamethyst.ui.main

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import io.stamethyst.model.ModItemUi
import kotlin.math.abs

internal class ModDragSessionCoordinator(
    private val interactionState: ModFolderSectionInteractionState,
    private val resolveFolderTokenFromItemKey: (Any) -> String?,
    private val resolveAssignedFolderToken: (ModItemUi) -> String,
    private val onAssignModToFolder: (ModItemUi, String) -> Unit,
    private val onMoveModToUnassigned: (ModItemUi) -> Unit,
    private val onRevealFolderToken: (String) -> Unit
) {
    val shouldCollapseFolders: Boolean
        get() = interactionState.activeDragFolderId != null || interactionState.modDragState.collapseFoldersDuringDrag

    fun startModDrag(
        mod: ModItemUi,
        dragInfo: ModCardDragStartInfo,
        isExpanded: Boolean,
    ) {
        interactionState.activeDragFolderId = null
        interactionState.rememberDragScrollAnchor()
        interactionState.modDragState = ModDragInteractionState(
            session = ModDragSession(
                mod = mod,
                sourceFolderTokenId = resolveAssignedFolderToken(mod),
                overlayAnchor = dragInfo.overlayAnchor,
                isExpanded = isExpanded
            ),
            activePointerIdValue = dragInfo.pointerIdValue,
            collapseFoldersDuringDrag = true
        )
        updateModDragHover(dragInfo.overlayAnchor.pointerWindow)
    }

    fun onContainerPointerChanges(
        changes: List<PointerInputChange>,
        sectionTopLeftWindow: Offset
    ) {
        val activePointerIdValue = interactionState.modDragState.activePointerIdValue ?: return
        val trackedChange = changes.firstOrNull { change ->
            change.id.value == activePointerIdValue
        }
        if (trackedChange == null) {
            cancelModDrag()
            return
        }
        val pointerWindow = sectionTopLeftWindow + trackedChange.position
        trackedChange.consume()
        when {
            trackedChange.changedToUpIgnoreConsumed() -> endModDrag(pointerWindow)
            trackedChange.pressed -> updateModDragHover(pointerWindow)
            else -> cancelModDrag()
        }
    }

    fun beginFolderReorder(folderTokenId: String, folderTargetIds: List<String>) {
        cancelModDrag()
        interactionState.rememberDragScrollAnchor()
        interactionState.activeDragFolderId = folderTokenId
        interactionState.folderPreviewOrder = folderTargetIds
    }

    fun finishFolderReorder() {
        interactionState.activeDragFolderId = null
        interactionState.scheduleDragScrollRestore()
    }

    fun cancelModDrag() {
        if (interactionState.modDragState.isActive || interactionState.modDragState.activePointerIdValue != null) {
            interactionState.modDragState = ModDragInteractionState()
            interactionState.scheduleDragScrollRestore()
        }
    }

    fun updateModDragHover(position: Offset, ignoreFolderId: String? = null) {
        val currentSession = interactionState.modDragState.session ?: return
        val nextHoverId = findExactFolderAt(position, ignoreFolderId)
        val updatedSession = currentSession
            .updatePointer(position)
            .copy(hoveredFolderTokenId = nextHoverId)
        if (updatedSession != currentSession) {
            interactionState.modDragState = interactionState.modDragState.copy(session = updatedSession)
        }
    }

    fun nextAutoScrollDelta(): Float {
        val pointerWindow = interactionState.modDragState.session?.overlayAnchor?.pointerWindow ?: return 0f
        updateModDragHover(pointerWindow)
        val pointerY = pointerWindow.y
        val viewport = interactionState.listViewportInWindow ?: return 0f
        val edgeSize = MOD_DRAG_AUTO_SCROLL_EDGE_PX
        val maxStep = MOD_DRAG_AUTO_SCROLL_MAX_STEP_PX
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

    private fun endModDrag(pointerWindow: Offset) {
        val currentSession = interactionState.modDragState.session ?: return
        val completedSession = currentSession.updatePointer(pointerWindow)
        interactionState.modDragState = interactionState.modDragState.copy(session = completedSession)
        handleModDrop(completedSession)
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
        if (pointerY > maxCenter + MOD_DRAG_DROP_AFTER_LAST_MARGIN_PX) {
            return null
        }
        return candidates.minByOrNull { (_, centerY) -> abs(centerY - pointerY) }?.first
    }

    private fun handleModDrop(session: ModDragSession) {
        val targetFolderId = findDropFolderAt(session.overlayAnchor.pointerWindow)
        val sourceFolderId = session.sourceFolderTokenId
        cancelModDrag()

        when {
            targetFolderId == null -> Unit
            targetFolderId == sourceFolderId -> Unit
            targetFolderId == UNASSIGNED_FOLDER_ID -> {
                onMoveModToUnassigned(session.mod)
                onRevealFolderToken(targetFolderId)
            }

            else -> {
                onAssignModToFolder(session.mod, targetFolderId)
                onRevealFolderToken(targetFolderId)
            }
        }
    }
}
