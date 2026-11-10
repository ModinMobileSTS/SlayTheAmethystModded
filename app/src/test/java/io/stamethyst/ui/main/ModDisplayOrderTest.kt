package io.stamethyst.ui.main

import org.junit.Assert.assertEquals
import org.junit.Test

class ModDisplayOrderTest {
    @Test fun movesSelectedGroupInOriginalOrder() {
        assertEquals(listOf("b", "d", "a", "c"),
            reorderModPaths(listOf("a", "b", "c", "d"), setOf("c", "a"), "d", true))
    }

    @Test fun ignoresSelectedOrMissingTarget() {
        val order = listOf("a", "b", "c")
        assertEquals(order, reorderModPaths(order, setOf("a", "b"), "b", false))
        assertEquals(order, reorderModPaths(order, setOf("a"), "other-folder", false))
    }

    @Test fun insertsBeforeTargetAndIgnoresOtherFolderSelection() {
        assertEquals(listOf("c", "a", "b"),
            reorderModPaths(listOf("a", "b", "c"), setOf("c", "other-folder"), "a", false))
    }
}
