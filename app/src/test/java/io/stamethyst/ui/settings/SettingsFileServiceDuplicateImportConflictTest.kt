package io.stamethyst.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun modBatchImportResult_countsInvalidModJarsAsFailures() {
        val result = ModBatchImportResult(
            importedCount = 0,
            errors = emptyList(),
            blockedComponents = emptyList(),
            compressedArchives = emptyList(),
            invalidModJars = listOf(
                InvalidModImportFailure(
                    displayName = "not-a-mod.jar",
                    reason = "ModTheSpire.json not found"
                )
            ),
            patchedResults = emptyList()
        )

        assertEquals(1, result.failedCount)
        assertEquals("not-a-mod.jar: ModTheSpire.json not found", result.firstError)
    }

    @Test
    fun modBatchImportResult_prefersGenericErrorAsFirstError() {
        val result = ModBatchImportResult(
            importedCount = 0,
            errors = listOf("copy failed"),
            blockedComponents = emptyList(),
            compressedArchives = emptyList(),
            invalidModJars = listOf(
                InvalidModImportFailure(
                    displayName = "not-a-mod.jar",
                    reason = "ModTheSpire.json not found"
                )
            ),
            patchedResults = emptyList()
        )

        assertEquals("copy failed", result.firstError)
    }

    @Test
    fun modBatchImportResult_handlesEmptyErrors() {
        val result = ModBatchImportResult(
            importedCount = 1,
            errors = emptyList(),
            blockedComponents = emptyList(),
            compressedArchives = emptyList(),
            invalidModJars = emptyList(),
            patchedResults = emptyList()
        )

        assertEquals(0, result.failedCount)
        assertNull(result.firstError)
    }

    @Test
    fun buildDuplicateModImportReusePlan_prefersExistingFileNameAndFolder() {
        val plan = SettingsFileService.buildDuplicateModImportReusePlan(
            existingSources = listOf(
                ExistingDuplicateModImportSource(
                    storagePath = "/mods/z-last.jar",
                    fileName = "z-last.jar",
                    assignedFolderId = null
                ),
                ExistingDuplicateModImportSource(
                    storagePath = "/mods/a-first.jar",
                    fileName = "a-first.jar",
                    assignedFolderId = "folder-a"
                ),
                ExistingDuplicateModImportSource(
                    storagePath = "/mods/b-second.jar",
                    fileName = "b-second.jar",
                    assignedFolderId = "folder-b"
                )
            ),
            options = DuplicateModImportReplaceOptions(
                moveToPreviousFolder = true,
                renameToPreviousFileName = true
            )
        )

        assertEquals("a-first.jar", plan.targetFileName)
        assertEquals("folder-a", plan.assignedFolderId)
        assertEquals(
            listOf("/mods/a-first.jar", "/mods/b-second.jar", "/mods/z-last.jar"),
            plan.sourceStoragePaths
        )
    }

    @Test
    fun buildDuplicateModImportReusePlan_skipsDisabledBehaviorsIndependently() {
        val plan = SettingsFileService.buildDuplicateModImportReusePlan(
            existingSources = listOf(
                ExistingDuplicateModImportSource(
                    storagePath = "/mods/a-first.jar",
                    fileName = "a-first.jar",
                    assignedFolderId = null
                ),
                ExistingDuplicateModImportSource(
                    storagePath = "/mods/b-second.jar",
                    fileName = "b-second.jar",
                    assignedFolderId = "folder-b"
                )
            ),
            options = DuplicateModImportReplaceOptions(
                moveToPreviousFolder = true,
                renameToPreviousFileName = false
            )
        )

        assertNull(plan.targetFileName)
        assertEquals("folder-b", plan.assignedFolderId)
        assertTrue(plan.sourceStoragePaths.isNotEmpty())
    }

    @Test
    fun buildDuplicateModImportReusePlan_ignoresEphemeralImportTempNames() {
        val plan = SettingsFileService.buildDuplicateModImportReusePlan(
            existingSources = listOf(
                ExistingDuplicateModImportSource(
                    storagePath = "/mods/.import-213000.tmp.jar",
                    fileName = ".import-213000.tmp.jar",
                    assignedFolderId = null
                ),
                ExistingDuplicateModImportSource(
                    storagePath = "/mods/real-old-name.jar",
                    fileName = "real-old-name.jar",
                    assignedFolderId = "folder-a"
                )
            ),
            options = DuplicateModImportReplaceOptions(
                moveToPreviousFolder = true,
                renameToPreviousFileName = true
            )
        )

        assertEquals("real-old-name.jar", plan.targetFileName)
        assertEquals("folder-a", plan.assignedFolderId)
    }
}
