package io.stamethyst.backend.steamcloud

import io.stamethyst.backend.notify.ForegroundNotificationIds
import org.junit.Assert.*
import org.junit.Test

class SteamCloudSyncResultNotificationTest {
    private val noChanges = SteamCloudUploadPlan(1, baselineConfigured = true,
        uploadCandidates = emptyList(), conflicts = emptyList(), remoteOnlyChanges = emptyList(),
        remoteDeleteCandidates = emptyList(), warnings = emptyList())

    @Test fun allCompletedOperationsReportSuccessIncludingAutomaticSync() {
        listOf(SteamCloudSyncProcessService.RESULT_AUTO_SYNC_COMPLETED,
            SteamCloudSyncProcessService.RESULT_LOCAL_OVERRIDE_COMPLETED,
            SteamCloudSyncProcessService.RESULT_CLOUD_OVERRIDE_COMPLETED).forEach { code ->
            val notice = steamCloudSyncResultNotice(code)!!
            assertEquals(SteamCloudSyncResultNotice.COMPLETED, notice)
            // An automatic worker stays automatic when the user reuses it for background launch.
            assertTrue(shouldShowSteamCloudSyncResultNotice(notice, false, true))
            assertTrue(shouldShowSteamCloudSyncResultNotice(notice, false, false))
        }
    }

    @Test fun noChangeResultIsNotLostWhenBackgroundLaunchReusesAnAutomaticCheck() {
        val notice = steamCloudSyncResultNotice(SteamCloudSyncProcessService.RESULT_PLAN_READY, noChanges)!!
        assertEquals(SteamCloudSyncResultNotice.UP_TO_DATE, notice)
        assertTrue(shouldShowSteamCloudSyncResultNotice(notice, false, true))
        assertTrue(shouldShowSteamCloudSyncResultNotice(notice, true, false))
        assertFalse(shouldShowSteamCloudSyncResultNotice(notice, false, false))
        assertEquals(notice, steamCloudSyncResultNotice(SteamCloudSyncProcessService.RESULT_UP_TO_DATE))
    }

    @Test fun conflictIsReportedWithoutClaimingThePlanWasSynchronized() {
        val conflict = SteamCloudConflict("saves/IRONCLAD.autosave", SteamCloudRootKind.SAVES,
            SteamCloudConflictKind.BOTH_CHANGED, null, null, null, null)
        assertEquals(SteamCloudSyncResultNotice.CONFLICT,
            steamCloudSyncResultNotice(SteamCloudSyncProcessService.RESULT_PLAN_READY,
                noChanges.copy(conflicts = listOf(conflict))))
    }

    @Test fun failuresRecoveryDeferralAndCancellationHaveDistinctResults() {
        assertEquals(SteamCloudSyncResultNotice.FAILED, steamCloudSyncResultNotice(SteamCloudSyncProcessService.RESULT_FAILURE))
        assertEquals(SteamCloudSyncResultNotice.RECOVERY_REQUIRED,
            steamCloudSyncResultNotice(SteamCloudSyncProcessService.RESULT_FAILURE, requiresResolution = true))
        assertEquals(SteamCloudSyncResultNotice.DEFERRED, steamCloudSyncResultNotice(SteamCloudSyncProcessService.RESULT_DEFERRED))
        assertEquals(SteamCloudSyncResultNotice.CANCELLED, steamCloudSyncResultNotice(SteamCloudSyncProcessService.RESULT_CANCELLED))
        listOf(SteamCloudSyncResultNotice.FAILED, SteamCloudSyncResultNotice.CONFLICT,
            SteamCloudSyncResultNotice.RECOVERY_REQUIRED, SteamCloudSyncResultNotice.DEFERRED,
            SteamCloudSyncResultNotice.CANCELLED).forEach { notice ->
            assertTrue(shouldShowSteamCloudSyncResultNotice(notice, false, false))
        }
    }

    @Test fun progressAndUnverifiedPlansNeverGenerateCompletionNotices() {
        listOf(SteamCloudSyncProcessService.RESULT_CHECKING, SteamCloudSyncProcessService.RESULT_SYNC_STARTED,
            SteamCloudSyncProcessService.RESULT_PROGRESS, -1).forEach {
            assertNull(steamCloudSyncResultNotice(it, noChanges))
        }
        assertNull(steamCloudSyncResultNotice(SteamCloudSyncProcessService.RESULT_PLAN_READY))
        val remoteDeletion = SteamCloudRemoteOnlyChange("saves/IRONCLAD.autosave", SteamCloudRootKind.SAVES,
            SteamCloudRemoteOnlyChangeKind.REMOTE_FILE_DELETED, null, null)
        assertNull(steamCloudSyncResultNotice(SteamCloudSyncProcessService.RESULT_PLAN_READY,
            noChanges.copy(remoteOnlyChanges = listOf(remoteDeletion))))
    }

    @Test fun removingForegroundNotificationCannotRemoveTheResult() {
        assertNotEquals(ForegroundNotificationIds.STEAM_CLOUD_SYNC, SteamCloudSyncResultNotification.NOTIFICATION_ID)
        assertNotEquals("steam_cloud_sync", SteamCloudSyncResultNotification.CHANNEL_ID)
    }
}
