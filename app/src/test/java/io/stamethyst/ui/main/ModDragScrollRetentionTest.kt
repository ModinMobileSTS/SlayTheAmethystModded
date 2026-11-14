package io.stamethyst.ui.main

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import io.stamethyst.model.ModItemUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ModDragScrollRetentionTest {
    @Test fun contentPaddingDoesNotPairPreviousRowKeyWithCurrentRowOffset() {
        val anchor = captureDragScrollAnchor(12, 37, listOf(
            row("padding-row", 11, -137),
            row("current-row", 12, -37),
            row("next-row", 13, 63),
        ))

        assertEquals("current-row", anchor.firstVisibleItemKey)
        assertEquals(1 to 37, anchor.resolvePosition(listOf("padding-row", "current-row", "next-row")))
    }

    @Test fun removedPlaceholderKeepsNextRowAtItsOwnScreenPosition() {
        val anchor = captureDragScrollAnchor(12, 37, listOf(
            row(MOD_DRAG_PLACEHOLDER_KEY, 12, -37),
            row("next-row", 13, 263),
        ))

        // One gathered placeholder becomes multiple cards, changing all later indices.
        assertEquals(4 to -263, anchor.resolvePosition(listOf(
            "header", "dropped-a", "dropped-b", "dropped-c", "next-row"
        )))
    }

    @Test fun removedFolderRowsUseNextSurvivingVisibleRow() {
        val anchor = captureDragScrollAnchor(12, 37, listOf(
            row("temporary-folder-mod", 12, -37),
            row("temporary-folder-last-mod", 13, 63),
            row("next-folder", 14, 163),
        ))

        assertEquals(1 to -163, anchor.resolvePosition(listOf("collapsed-folder", "next-folder")))
    }

    @Test fun survivingFirstRowKeepsOffsetEvenWhenItsIndexChanges() {
        val anchor = captureDragScrollAnchor(12, 37, listOf(
            row("current-row", 12, -37),
            row("next-row", 13, 63),
        ))

        assertEquals(0 to 37, anchor.resolvePosition(listOf("current-row", "next-row")))
    }

    @Test fun unmeasuredListFallsBackToClampedIndexAndOriginalOffset() {
        val anchor = captureDragScrollAnchor(12, 37, emptyList())

        assertEquals(1 to 37, anchor.resolvePosition(listOf("header", "only-row")))
        assertEquals(0 to 37, anchor.resolvePosition(emptyList()))
    }

    @Test fun noSurvivingVisibleRowsFallsBackSafely() {
        val anchor = captureDragScrollAnchor(12, 37, listOf(row("removed-row", 12, -37)))

        assertEquals(0 to 37, anchor.resolvePosition(listOf("remaining-row")))
    }

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

    private fun row(key: String, index: Int, offset: Int): LazyListItemInfo = object : LazyListItemInfo {
        override val key: Any = key
        override val index: Int = index
        override val offset: Int = offset
        override val size: Int = 100
        override val contentType: Any? = null
    }
}
