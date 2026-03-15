package io.stamethyst.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SettingsFileServiceDuplicateImportConflictTest {
    @Test
    fun collectDuplicateModImportConflicts_detectsInstalledAndBatchDuplicates() {
        val conflicts = SettingsFileService.collectDuplicateModImportConflicts(
            existingByModId = linkedMapOf(
                "sameid" to listOf("old-a.jar", "old-b.jar"),
                "otherid" to listOf("existing-other.jar")
            ),
            incomingMods = listOf(
                ModImportIdentityPreview(
                    normalizedModId = "sameid",
                    displayModId = "SameId",
                    displayName = "new-a.jar"
                ),
                ModImportIdentityPreview(
                    normalizedModId = "otherid",
                    displayModId = "OtherId",
                    displayName = "new-other.jar"
                ),
                ModImportIdentityPreview(
                    normalizedModId = "batchonly",
                    displayModId = "BatchOnly",
                    displayName = "batch-1.jar"
                ),
                ModImportIdentityPreview(
                    normalizedModId = "batchonly",
                    displayModId = "BatchOnly",
                    displayName = "batch-2.jar"
                ),
                ModImportIdentityPreview(
                    normalizedModId = "unique",
                    displayModId = "Unique",
                    displayName = "unique.jar"
                )
            )
        )

        assertEquals(listOf("batchonly", "otherid", "sameid"), conflicts.map { it.normalizedModId })

        val conflictsById = conflicts.associateBy { it.normalizedModId }
        assertEquals(
            listOf("batch-1.jar", "batch-2.jar"),
            conflictsById.getValue("batchonly").importingDisplayNames
        )
        assertEquals(
            emptyList<String>(),
            conflictsById.getValue("batchonly").existingDisplayNames
        )
        assertEquals(
            listOf("new-other.jar"),
            conflictsById.getValue("otherid").importingDisplayNames
        )
        assertEquals(
            listOf("existing-other.jar"),
            conflictsById.getValue("otherid").existingDisplayNames
        )
        assertEquals(
            listOf("old-a.jar", "old-b.jar"),
            conflictsById.getValue("sameid").existingDisplayNames
        )
        assertFalse(conflictsById.containsKey("unique"))
    }
}
