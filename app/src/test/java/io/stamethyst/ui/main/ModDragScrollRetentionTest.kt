package io.stamethyst.ui.main

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import io.stamethyst.model.ModItemUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ModDragScrollRetentionTest {
    @Test fun endingDragKeepsCurrentViewportInsteadOfStartingAnchor() {
        val state = ModFolderSectionInteractionState(LazyListState(12, 37), emptyList())
        state.activeDragScrollAnchor = DragScrollAnchor("old-start", 1, 0)
        val mod = ModItemUi(
            modId = "test", manifestModId = "test", storagePath = "test.jar",
            name = "Test", version = "1", description = "", dependencies = emptyList(),
            required = false, installed = true, enabled = true,
            explicitPriority = null, effectivePriority = null,
        )
        state.modDragState = ModDragInteractionState(session = ModDragSession(
            mod, "source", overlayAnchor = ModDragOverlayAnchor(
                Offset.Zero, Offset.Zero, Offset.Zero, IntSize(100, 100)), isExpanded = false,
        ))
        val coordinator = ModDragSessionCoordinator(state, { null }, { "source" }, { true },
            { error("No transaction needed when finishing the active preview") })

        coordinator.cancelModDrag()

        assertFalse(state.isModDragActive)
        assertEquals(12, state.pendingDragScrollRestore?.firstVisibleItemIndex)
        assertEquals(37, state.pendingDragScrollRestore?.firstVisibleItemScrollOffset)
        // A repeated cleanup must not overwrite the retained viewport.
        coordinator.cancelModDrag()
        assertEquals(12, state.pendingDragScrollRestore?.firstVisibleItemIndex)
    }
}
