package io.stamethyst.ui.main

import androidx.compose.ui.state.ToggleableState
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderSelectionTest {
    @Test fun selectAllCyclesOffPartialOnAndPreservesOutsideScope() {
        val scope = setOf("folder-a", "collapsed-folder-b", "unassigned")
        val outside = setOf("hidden-by-search")
        assertEquals(ToggleableState.Off, batchSelectionState(outside, scope))
        val partial = outside + "folder-a"
        assertEquals(ToggleableState.Indeterminate, batchSelectionState(partial, scope))
        val all = toggleFolderBatchSelection(partial, scope)
        assertEquals(outside + scope, all)
        assertEquals(ToggleableState.On, batchSelectionState(all, scope))
        assertEquals(outside, toggleFolderBatchSelection(all, scope))
    }

    @Test fun emptyScopeIsOffAndSelectionIsUnchanged() {
        val selected = setOf("a")
        assertEquals(ToggleableState.Off, batchSelectionState(selected, emptySet()))
        assertEquals(selected, toggleFolderBatchSelection(selected, emptySet()))
    }

    @Test fun changingSearchScopeDoesNotAutoSelectNewResults() {
        val selected = toggleFolderBatchSelection(emptySet(), setOf("a"))
        assertEquals(ToggleableState.On, batchSelectionState(selected, setOf("a")))
        assertEquals(ToggleableState.Off, batchSelectionState(selected, setOf("b")))
        assertEquals(ToggleableState.Indeterminate, batchSelectionState(selected, setOf("a", "b")))
        assertEquals(setOf("a"), retainEligibleBatchSelection(selected, setOf("a", "b")))
    }

    @Test fun pruningRemovesDeletedOrIneligibleModsButKeepsFilteredSelections() {
        assertEquals(setOf("hidden", "a"), retainEligibleBatchSelection(
            setOf("hidden", "a", "deleted", "now-ineligible"), setOf("hidden", "a", "b")))
        assertEquals(emptySet<String>(), retainEligibleBatchSelection(setOf("deleted"), emptySet()))
    }

    @Test fun singleItemAndDuplicatePathsUseUniqueSelectionIdentity() {
        val scope = listOf("a", "a").toSet()
        val all = toggleFolderBatchSelection(emptySet(), scope)
        assertEquals(setOf("a"), all)
        assertEquals(ToggleableState.On, batchSelectionState(all, scope))
        assertEquals(emptySet<String>(), toggleFolderBatchSelection(all, scope))
    }

    @Test fun longPressAlwaysAddsMatchingChildrenAndPreservesExistingSelection() {
        val existing = setOf("other", "hidden", "a")
        assertEquals(existing + "b", selectFolderBatchSelection(existing, setOf("a", "b")))
        assertEquals(existing, selectFolderBatchSelection(existing, setOf("a")))
        assertEquals(existing, selectFolderBatchSelection(existing, emptySet()))
    }
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
