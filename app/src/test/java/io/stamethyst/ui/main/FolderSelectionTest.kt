package io.stamethyst.ui.main

import androidx.compose.ui.state.ToggleableState
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderSelectionTest {
    @Test fun aggregateDistinguishesEmptyPartialAndAll() {
        assertEquals(ToggleableState.Off, folderAggregateState(0, 0))
        assertEquals(ToggleableState.Off, folderAggregateState(0, 8))
        assertEquals(ToggleableState.Indeterminate, folderAggregateState(3, 8))
        assertEquals(ToggleableState.On, folderAggregateState(8, 8))
    }

    @Test fun selectingFilteredGroupPreservesOtherAndHiddenSelections() {
        val existing = setOf("other-folder", "hidden", "a")
        assertEquals(existing + "b", toggleFolderBatchSelection(existing, setOf("a", "b")))
        assertEquals(setOf("other-folder", "hidden"),
            toggleFolderBatchSelection(existing + "b", setOf("a", "b")))
        assertEquals(existing, toggleFolderBatchSelection(existing, emptySet()))
    }
}
