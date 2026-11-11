package io.stamethyst.ui.main

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import io.stamethyst.model.ModItemUi

internal const val MOD_DRAG_PLACEHOLDER_KEY = "modDragPlaceholder"

internal class ModDragSessionCoordinator(
    private val interactionState: ModFolderSectionInteractionState,
    private val resolveFolderTokenFromItemKey: (Any) -> String?,
    private val resolveAssignedFolderToken: (ModItemUi) -> String,
    private val dragEnabled: () -> Boolean,
    private val createTransaction: (ModItemUi) -> ModDragTransaction,
) {
    private var transaction: ModDragTransaction? = null

    fun startModDrag(mod: ModItemUi, dragInfo: ModCardDragStartInfo, isExpanded: Boolean) {
        if (!dragEnabled()) return
        cancelActiveDrags()
        interactionState.rememberDragScrollAnchor()
        val source = resolveAssignedFolderToken(mod)
        val drag = createTransaction(mod)
        transaction = drag
        interactionState.movingModPaths = drag.movingPaths
        interactionState.modDragPreview = drag.previewFolders
        interactionState.dragExpandedFolderTokens = setOf(source)
        interactionState.modDragState = ModDragInteractionState(
            session = ModDragSession(mod, source, overlayAnchor = dragInfo.overlayAnchor, isExpanded = isExpanded),
            activePointerIdValue = dragInfo.pointerIdValue,
        )
        // Keep the initial gathered anchor slot until the next pointer event/layout.
    }

    fun onContainerPointerChanges(changes: List<PointerInputChange>, sectionTopLeftWindow: Offset) {
        val pointerId = interactionState.modDragState.activePointerIdValue ?: return
        val change = changes.firstOrNull { it.id.value == pointerId }
        if (change == null || !dragEnabled()) {
            cancelModDrag()
            return
        }
        val position = sectionTopLeftWindow + change.position
        change.consume()
        when {
            change.changedToUpIgnoreConsumed() -> {
                updateModDragHover(position)
                transaction?.drop()
                cancelModDrag()
            }
            change.pressed -> updateModDragHover(position)
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

    fun cancelActiveDrags() {
        if (interactionState.activeDragFolderId != null) finishFolderReorder()
        cancelModDrag()
    }

    fun cancelModDrag() {
        transaction?.cancel()
        transaction = null
        interactionState.modDragPreview = null
        interactionState.movingModPaths = emptyList()
        interactionState.dragExpandedFolderTokens = emptySet()
        if (interactionState.modDragState.isActive) {
            interactionState.modDragState = ModDragInteractionState()
            interactionState.scheduleDragScrollRestore()
        }
    }

    fun updateModDragHover(position: Offset) {
        val session = interactionState.modDragState.session ?: return
        val drag = transaction ?: return
        val viewport = interactionState.listViewportInWindow
        val layout = interactionState.listState.layoutInfo
        val target = if (viewport != null && viewport.contains(position)) {
            layout.visibleItemsInfo.firstOrNull {
                val top = viewport.top + it.offset - layout.viewportStartOffset
                position.y >= top && position.y < top + it.size
            }
        } else null
        val targetPath = (target?.key as? String)?.takeIf { it.startsWith("mod:") }?.removePrefix("mod:")
        // During remeasurement the layout may still contain a moving row replaced by the placeholder.
        val movingTarget = targetPath != null && targetPath in drag.movingPaths
        val folder = target?.let { resolveFolderTokenFromItemKey(it.key) }
            ?: if (movingTarget) drag.previewFolders.entries.firstOrNull { targetPath in it.value }?.key else null
        when {
            target == null || folder == null -> drag.previewOutsideViewport()
            target.key == MOD_DRAG_PLACEHOLDER_KEY || movingTarget -> drag.previewPlaceholder()
            else -> {
                interactionState.dragExpandedFolderTokens += folder
                val center = viewport!!.top + target.offset - layout.viewportStartOffset + target.size / 2f
                drag.preview(folder, targetPath, position.y > center)
            }
        }
        interactionState.modDragPreview = drag.previewFolders
        interactionState.modDragState = interactionState.modDragState.copy(
            session = session.updatePointer(position).copy(hoveredFolderTokenId = folder)
        )
    }

    fun nextAutoScrollDelta(): Float {
        val position = interactionState.modDragState.session?.overlayAnchor?.pointerWindow ?: return 0f
        if (!dragEnabled()) {
            cancelModDrag()
            return 0f
        }
        updateModDragHover(position)
        val viewport = interactionState.listViewportInWindow ?: return 0f
        val topTrigger = viewport.top + MOD_DRAG_AUTO_SCROLL_EDGE_PX
        val bottomTrigger = viewport.bottom - MOD_DRAG_AUTO_SCROLL_EDGE_PX
        val ratio = when {
            position.y < topTrigger -> -((topTrigger - position.y) / MOD_DRAG_AUTO_SCROLL_EDGE_PX).coerceIn(0f, 1f)
            position.y > bottomTrigger -> ((position.y - bottomTrigger) / MOD_DRAG_AUTO_SCROLL_EDGE_PX).coerceIn(0f, 1f)
            else -> 0f
        }
        val eased = ratio * kotlin.math.abs(ratio)
        return if (kotlin.math.abs(eased) < 0.04f) 0f else MOD_DRAG_AUTO_SCROLL_MAX_STEP_PX * eased
    }
}
