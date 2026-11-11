package io.stamethyst.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure display/assignment transaction contract; does not exercise Compose or load priority. */
class ModDragTransactionTest {
    private val original = linkedMapOf(
        "source" to listOf("a", "b", "c"),
        "target" to listOf("d", "e", "f"),
        "empty" to emptyList<String>(),
    )

    private fun transaction(
        dragged: String = "c",
        selected: Set<String> = linkedSetOf("e", "c", "a"),
        locked: Boolean = false,
        commits: MutableList<Map<String, List<String>>> = mutableListOf(),
    ): ModDragTransaction = ModDragTransaction(
        folders = original,
        draggedPath = dragged,
        selectedPaths = selected,
        dragLocked = locked,
        onCommit = { folders -> commits.add(folders.mapValues { it.value.toList() }) },
    )

    @Test fun startGathersGroupAtDraggedAnchorWithoutPersisting() {
        val commits = mutableListOf<Map<String, List<String>>>()
        val drag = transaction(commits = commits)
        // Of the nonmoving source rows, b preceded the dragged anchor c.
        val expected = linkedMapOf(
            "source" to listOf("b", "a", "c", "e"),
            "target" to listOf("d", "f"),
            "empty" to emptyList<String>(),
        )
        assertEquals(expected, drag.previewFolders)
        assertEquals(listOf("a", "b", "c"), original["source"])
        assertEquals(listOf("d", "e", "f"), original["target"])
        assertTrue(commits.isEmpty())
        drag.drop()
        assertEquals(listOf(expected), commits)
    }

    @Test fun selectedTargetRetainsInitialAnchorSlotAndAllowsRelease() {
        val commits = mutableListOf<Map<String, List<String>>>()
        val drag = transaction(commits = commits)
        val expected = linkedMapOf(
            "source" to listOf("b", "a", "c", "e"),
            "target" to listOf("d", "f"),
            "empty" to emptyList<String>(),
        )
        drag.preview("source", "a", after = false)
        assertEquals(expected, drag.previewFolders)
        assertTrue(commits.isEmpty())
        drag.drop()
        assertEquals(listOf(expected), commits)
    }

    @Test fun selectedTargetRetainsLatestSlotRatherThanResettingToStart() {
        val commits = mutableListOf<Map<String, List<String>>>()
        val drag = transaction(commits = commits)
        drag.preview("target", "f", after = true)
        val expected = linkedMapOf(
            "source" to listOf("b"),
            "target" to listOf("d", "f", "a", "c", "e"),
            "empty" to emptyList<String>(),
        )
        drag.preview("target", "e", after = false)
        assertEquals(expected, drag.previewFolders)
        drag.preview("target", "e", after = true)
        assertEquals(expected, drag.previewFolders)
        assertTrue(commits.isEmpty())
        drag.drop()
        assertEquals(listOf(expected), commits)
    }

    @Test fun hoveringLivePlaceholderRetainsLatestValidSlotAndAllowsRelease() {
        val commits = mutableListOf<Map<String, List<String>>>()
        val drag = transaction(commits = commits)
        drag.preview("empty", targetPath = null, after = true)
        val expected = linkedMapOf(
            "source" to listOf("b"),
            "target" to listOf("d", "f"),
            "empty" to listOf("a", "c", "e"),
        )
        drag.previewPlaceholder()
        assertEquals(expected, drag.previewFolders)
        assertTrue(commits.isEmpty())
        drag.drop()
        assertEquals(listOf(expected), commits)
    }

    @Test fun leavingViewportInvalidatesReleaseWithoutPersistingPreviousSlot() {
        val commits = mutableListOf<Map<String, List<String>>>()
        val drag = transaction(commits = commits)
        drag.preview("target", "d", after = false)
        drag.previewOutsideViewport()
        drag.drop()
        assertTrue(commits.isEmpty())
    }

    @Test fun returningFromInvalidHoverRestoresValidTargeting() {
        val commits = mutableListOf<Map<String, List<String>>>()
        val drag = transaction(commits = commits)
        drag.preview("target", "f", after = true)
        drag.previewOutsideViewport()
        drag.preview("missing-folder", targetPath = null, after = true)
        drag.preview("target", "d", after = false)
        val expected = linkedMapOf(
            "source" to listOf("b"),
            "target" to listOf("a", "c", "e", "d", "f"),
            "empty" to emptyList<String>(),
        )
        assertEquals(expected, drag.previewFolders)
        assertTrue(commits.isEmpty())
        drag.drop()
        assertEquals(listOf(expected), commits)
    }

    @Test fun crossFolderPreviewRemovesSourcesAndInsertsOneBlockInVisibleOrder() {
        val commits = mutableListOf<Map<String, List<String>>>()
        val drag = transaction(commits = commits)

        drag.preview(folderToken = "target", targetPath = "d", after = false)

        assertEquals(linkedMapOf(
            "source" to listOf("b"),
            "target" to listOf("a", "c", "e", "d", "f"),
            "empty" to emptyList<String>(),
        ), drag.previewFolders)
        assertTrue("Hover must not persist assignment or order", commits.isEmpty())
        assertEquals(listOf("a", "b", "c"), original["source"])
        assertEquals(listOf("d", "e", "f"), original["target"])
    }

    @Test fun releaseCommitsExactlyTheLastPreviewIncludingAssignments() {
        val commits = mutableListOf<Map<String, List<String>>>()
        val drag = transaction(commits = commits)
        drag.preview("target", "d", after = false)
        drag.preview("target", "f", after = true)
        val expected = linkedMapOf(
            "source" to listOf("b"),
            "target" to listOf("d", "f", "a", "c", "e"),
            "empty" to emptyList<String>(),
        )
        assertEquals(expected, drag.previewFolders)
        assertTrue(commits.isEmpty())

        drag.drop()

        assertEquals(listOf(expected), commits)
    }

    @Test fun unselectedDraggedItemMovesAloneAndLeavesSelectionInPlace() {
        val drag = transaction(dragged = "b")
        drag.preview("target", "d", after = true)
        assertEquals(listOf("a", "c"), drag.previewFolders["source"])
        assertEquals(listOf("d", "b", "e", "f"), drag.previewFolders["target"])
    }

    @Test fun endSlotAppendsToNonemptyFolder() {
        val drag = transaction()
        drag.preview("target", targetPath = null, after = true)
        assertEquals(listOf("b"), drag.previewFolders["source"])
        assertEquals(listOf("d", "f", "a", "c", "e"), drag.previewFolders["target"])
    }

    @Test fun emptyFolderAcceptsTheWholeGroupAndCommitsSourceRemoval() {
        val commits = mutableListOf<Map<String, List<String>>>()
        val drag = transaction(commits = commits)
        drag.preview("empty", targetPath = null, after = true)
        val expected = linkedMapOf(
            "source" to listOf("b"),
            "target" to listOf("d", "f"),
            "empty" to listOf("a", "c", "e"),
        )
        assertEquals(expected, drag.previewFolders)
        assertTrue(commits.isEmpty())
        drag.drop()
        assertEquals(listOf(expected), commits)
    }

    @Test fun sameFolderPreviewReordersWithoutDuplicatingTheMovingRows() {
        val drag = transaction(selected = setOf("c", "a"))
        drag.preview("source", "b", after = true)
        assertEquals(listOf("b", "a", "c"), drag.previewFolders["source"])
        assertEquals(original["target"], drag.previewFolders["target"])
    }

    @Test fun cancelRestoresOrderAndAssignmentWithoutPersisting() {
        val commits = mutableListOf<Map<String, List<String>>>()
        val drag = transaction(commits = commits)
        drag.preview("empty", targetPath = null, after = true)

        drag.cancel()

        assertEquals(original, drag.previewFolders)
        assertTrue(commits.isEmpty())
    }

    @Test fun invalidDropDoesNotPersistAPreviousValidPreview() {
        val commits = mutableListOf<Map<String, List<String>>>()
        val drag = transaction(commits = commits)
        drag.preview("target", "d", after = true)
        drag.preview("missing-folder", targetPath = null, after = true)
        drag.drop()
        assertTrue(commits.isEmpty())
    }

    @Test fun targetOutsideDestinationFolderIsNotAValidDrop() {
        val commits = mutableListOf<Map<String, List<String>>>()
        val drag = transaction(commits = commits)
        drag.preview("target", "b", after = false)
        drag.drop()
        assertTrue(commits.isEmpty())
    }

    @Test fun lockedDragCannotPreviewOrPersistChanges() {
        val commits = mutableListOf<Map<String, List<String>>>()
        val drag = transaction(locked = true, commits = commits)
        drag.preview("empty", targetPath = null, after = true)
        assertEquals(original, drag.previewFolders)
        drag.drop()
        assertTrue(commits.isEmpty())
    }
}
