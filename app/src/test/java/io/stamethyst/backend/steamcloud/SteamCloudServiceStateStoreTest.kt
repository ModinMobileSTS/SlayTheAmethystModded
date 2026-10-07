package io.stamethyst.backend.steamcloud

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SteamCloudServiceStateStoreTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun newStoreInstanceRestoresLatestOperationAndSequence() {
        val file = File(temp.root, "state.json")
        val record = SteamCloudServiceStateStore.Record(strings = mapOf("operation" to "a", "direction" to "PULL"),
            longs = mapOf("sequence" to 12), ints = mapOf("code" to 4), booleans = mapOf("user" to true), active = true)
        SteamCloudServiceStateStore(file).write(record)
        assertEquals(record, SteamCloudServiceStateStore(file).read())
    }
    @Test fun terminalSnapshotReplacesActiveSnapshotWithoutOldBackup() {
        val file = File(temp.root, "state.json")
        val store = SteamCloudServiceStateStore(file)
        store.write(SteamCloudServiceStateStore.Record(active = true))
        store.write(SteamCloudServiceStateStore.Record(strings = mapOf("operation" to "b"), active = false))
        assertFalse(store.read()!!.active); assertEquals("b", store.read()!!.strings["operation"])
        assertFalse(SteamCloudAtomicFileStore.backupFile(file).exists())
    }
    @Test fun conflictPlanRoundTripsForReattachedUi() {
        val file = File(temp.root, "state.json")
        val plan = SteamCloudSyncPlanner.buildUploadPlan(1, emptyList(),
            SteamCloudManifestSnapshot(1, 0, 0, 0, emptyList(), emptyList(), steamId64 = "1"), null)
        val record = SteamCloudServiceStateStore.Record(plan = plan, active = false)
        SteamCloudServiceStateStore(file).write(record)
        assertEquals(plan, SteamCloudServiceStateStore(file).read()!!.plan)
    }
    @Test fun stageWarningsAndRecoveryReasonSurviveReattachment() {
        val file = File(temp.root, "state.json")
        val record = SteamCloudServiceStateStore.Record(
            strings = mapOf(SteamCloudSyncProcessService.EXTRA_OPERATION_ID to "operation",
                SteamCloudSyncProcessService.EXTRA_PROGRESS_PHASE to SteamCloudSyncPhase.VERIFYING_REMOTE.name,
                SteamCloudSyncProcessService.EXTRA_WARNINGS to "warning one\nwarning two"),
            longs = mapOf(SteamCloudSyncProcessService.EXTRA_EVENT_SEQUENCE to 9),
            ints = mapOf(SteamCloudSyncProcessService.EXTRA_PROGRESS_COMPLETED_FILES to 3,
                SteamCloudSyncProcessService.EXTRA_PROGRESS_TOTAL_FILES to 3),
            booleans = mapOf(SteamCloudSyncProcessService.EXTRA_REQUIRES_RESOLUTION to true), active = false)
        SteamCloudServiceStateStore(file).write(record)
        assertEquals(record, SteamCloudServiceStateStore(file).read())
    }
    @Test fun activeProgressRetainsInspectedPlanAndBackgroundReadiness() {
        val file = File(temp.root, "state.json")
        val plan = SteamCloudUploadPlan(1, baselineConfigured = true, uploadCandidates = emptyList(),
            conflicts = emptyList(), remoteOnlyChanges = emptyList(), remoteDeleteCandidates = emptyList(), warnings = emptyList())
        val record = SteamCloudServiceStateStore.Record(
            strings = mapOf(SteamCloudSyncProcessService.EXTRA_PROGRESS_PHASE to SteamCloudSyncPhase.UPLOADING.name),
            ints = mapOf(SteamCloudSyncProcessService.EXTRA_EVENT_RESULT_CODE to SteamCloudSyncProcessService.RESULT_PROGRESS),
            booleans = mapOf(SteamCloudSyncProcessService.EXTRA_BACKGROUND_UPLOAD_READY to true),
            plan = plan, active = true)
        SteamCloudServiceStateStore(file).write(record)
        assertEquals(record, SteamCloudServiceStateStore(file).read())
    }
}
