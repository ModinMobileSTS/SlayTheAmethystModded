package io.stamethyst.ui.main

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class ModDragOverlayAnchorTest {
    @Test
    fun overlayTopLeftInWindow_appliesPointerAndVisualOffsets() {
        val anchor = ModDragOverlayAnchor(
            pointerWindow = Offset(320f, 480f),
            pointerOffsetInsideCard = Offset(40f, 64f),
            visualOffsetFromPointer = Offset(0f, 10f),
            cardSizePx = IntSize(280, 132)
        )

        assertEquals(280f, anchor.overlayTopLeftInWindow().x, 0.001f)
        assertEquals(426f, anchor.overlayTopLeftInWindow().y, 0.001f)
    }
}
