package io.stamethyst.ui.main

import io.stamethyst.model.ModItemUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModSortOrderTest {
    @Test fun sortsNamesInBothDirectionsWithoutReadingFiles() {
        val mods = listOf(mod("z", "Zulu"), mod("a", "alpha"), mod("b", "Bravo"))
        val noFileReads: (ModItemUi) -> Long? = { error("Name sorting must not read files") }
        assertEquals(listOf("a", "b", "z"), sortedModDisplayPaths(mods, ModSortOrder.NAME_ASCENDING, noFileReads))
        assertEquals(listOf("z", "b", "a"), sortedModDisplayPaths(mods, ModSortOrder.NAME_DESCENDING, noFileReads))
    }

    @Test fun sortsByVisibleAliasAndFallsBackToManifestIdForBlankNames() {
        val mods = listOf(
            mod("c", "Alpha").copy(alias = " Charlie "),
            mod("a", "").copy(manifestModId = "Alpha"),
            mod("b", "Bravo"),
        )
        assertEquals(listOf("a", "b", "c"), sortedModDisplayPaths(mods, ModSortOrder.NAME_ASCENDING))
    }

    @Test fun sortsModificationTimeInBothDirectionsAndLeavesUnknownFilesLast() {
        val mods = listOf(mod("missing"), mod("old"), mod("new"), mod("same"))
        val timestamps = mapOf("old" to 10L, "new" to 100L, "same" to 100L)
        assertEquals(listOf("new", "same", "old", "missing"),
            sortedModDisplayPaths(mods, ModSortOrder.MODIFIED_NEWEST_FIRST) { timestamps[it.storagePath] })
        assertEquals(listOf("old", "new", "same", "missing"),
            sortedModDisplayPaths(mods, ModSortOrder.MODIFIED_OLDEST_FIRST) { timestamps[it.storagePath] })
    }

    @Test fun equalNamesRetainInputOrderIgnoringCase() {
        val mods = listOf(mod("first", "Alpha"), mod("second", "alpha"), mod("third", "ALPHA"))
        for (order in listOf(ModSortOrder.NAME_ASCENDING, ModSortOrder.NAME_DESCENDING)) {
            assertEquals(mods.map { it.storagePath }, sortedModDisplayPaths(mods, order))
        }
    }

    @Test fun deduplicatesPathsAndReadsEachTimestampOnlyOnce() {
        val mods = listOf(mod("a"), mod("b"), mod("a"))
        val reads = mutableListOf<String>()
        assertEquals(listOf("a", "b"), sortedModDisplayPaths(mods, ModSortOrder.MODIFIED_NEWEST_FIRST) {
            reads.add(it.storagePath)
            1L
        })
        assertEquals(listOf("a", "b"), reads)
        assertTrue(sortedModDisplayPaths(emptyList(), ModSortOrder.NAME_ASCENDING).isEmpty())
    }

    @Test fun missingLocalFilesDoNotFailTimeSort() {
        val mods = listOf(mod("/does-not-exist/mod-sort-a.jar"), mod("workshop:646570:123"))
        assertEquals(mods.map { it.storagePath }, sortedModDisplayPaths(mods, ModSortOrder.MODIFIED_NEWEST_FIRST))
        assertEquals(mods.map { it.storagePath }, sortedModDisplayPaths(mods, ModSortOrder.MODIFIED_OLDEST_FIRST))
    }

    @Test fun returnsOnlyDisplayPathsWithoutChangingModStateOrLoadPriority() {
        val mods = listOf(mod("z", "Zulu").copy(enabled = true, explicitPriority = 20, effectivePriority = 20),
            mod("a", "Alpha").copy(favorite = true, explicitPriority = -10, effectivePriority = -10))
        val before = mods.map { it.copy() }
        assertEquals(listOf("a", "z"), sortedModDisplayPaths(mods, ModSortOrder.NAME_ASCENDING))
        assertEquals(before, mods)
    }

    private fun mod(path: String, name: String = path) = ModItemUi(
        modId = path, manifestModId = path, storagePath = path, name = name,
        version = "1", description = "", dependencies = emptyList(), required = false,
        installed = true, enabled = false, explicitPriority = null, effectivePriority = null,
    )
}
