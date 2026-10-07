package io.stamethyst.ui.main

import io.stamethyst.backend.steamcloud.SteamCloudSyncPhase
import io.stamethyst.backend.steamcloud.SteamCloudSyncDirection
import io.stamethyst.backend.steamcloud.SteamCloudRemoteOnlyChange
import io.stamethyst.backend.steamcloud.SteamCloudRemoteOnlyChangeKind
import io.stamethyst.backend.steamcloud.SteamCloudRootKind
import io.stamethyst.backend.steamcloud.SteamCloudConflict
import io.stamethyst.backend.steamcloud.SteamCloudConflictKind
import io.stamethyst.backend.steamcloud.SteamCloudUploadPlan
import io.stamethyst.ui.main.MainScreenViewModel.SteamCloudIndicatorState as Status
import io.stamethyst.ui.main.MainScreenViewModel.SteamCloudIndicatorUi as State
import org.junit.Assert.*
import org.junit.Test

class SteamCloudCardPolicyTest {
    @Test fun webDesignBackgroundButtonVisibilityCoversEveryState() {
        val visible = setOf(Status.HIDDEN, Status.CHECKING, Status.SYNCING, Status.CANCELLING, Status.CANCELLED)
        Status.entries.forEach { status ->
            assertEquals(status.name, status in visible, shouldShowSteamCloudBackgroundSyncAction(State(state = status)))
        }
    }

    @Test fun presentationDoesNotDiscardSafetyStateOrWarnings() {
        val recovery = State(state = Status.RECOVERY_REQUIRED, errorSummary = "Unverified remote write")
        assertEquals(Status.CONFLICT, steamCloudCardDisplayState(recovery))
        assertTrue(canResolveSteamCloudCard(recovery))
        assertFalse(canLaunchSteamCloudCard(recovery))
        assertFalse(shouldShowSteamCloudBackgroundSyncAction(recovery))
        assertEquals("Unverified remote write", recovery.errorSummary)
        val completed = State(state = Status.UP_TO_DATE, warnings = listOf("excluded path"))
        assertEquals(Status.UP_TO_DATE, steamCloudCardDisplayState(completed))
        assertEquals(listOf("excluded path"), completed.warnings)
        assertEquals(Status.HIDDEN, steamCloudCardDisplayState(State(state = Status.DEFERRED)))
    }

    @Test fun directLaunchStatesHideBackgroundSync() {
        Status.entries.forEach { status ->
            val state = State(state = status)
            val launchable = status in setOf(Status.UP_TO_DATE, Status.DISABLED, Status.INDEPENDENT, Status.SIGNED_OUT)
            assertEquals(status.name, launchable, canLaunchSteamCloudCard(state))
            if (launchable) assertFalse(shouldShowSteamCloudBackgroundSyncAction(state))
        }
    }

    @Test fun explicitBackgroundLaunchDuringDownloadDoesNotWaitButAutomaticLaunchDoes() {
        val downloading = State(state = Status.SYNCING, phase = SteamCloudSyncPhase.DOWNLOADING)
        assertTrue(shouldShowSteamCloudBackgroundSyncAction(downloading))
        assertFalse(shouldShowSteamCloudBackgroundUploadAction(downloading))
        assertFalse(shouldAutoLaunchAfterSteamCloudUpdate(downloading))
        assertEquals(SteamCloudBackgroundLaunchAction.LAUNCH, steamCloudBackgroundLaunchAction(downloading))
    }

    @Test fun backgroundLaunchRoutesEveryStateWithoutSkippingSafety() {
        Status.entries.forEach { status ->
            val expected = when (status) {
                Status.HIDDEN, Status.CANCELLED -> SteamCloudBackgroundLaunchAction.START_SYNC
                Status.CHECKING, Status.SYNCING -> SteamCloudBackgroundLaunchAction.LAUNCH
                else -> SteamCloudBackgroundLaunchAction.REJECT
            }
            assertEquals(status.name, expected, steamCloudBackgroundLaunchAction(State(state = status)))
        }
    }

    @Test fun explicitBackgroundLaunchOverridesPreferenceButNeverRevivesCancelledIntent() {
        assertTrue(shouldHonorSteamCloudLaunchRequest(true, true, false))
        assertTrue(shouldHonorSteamCloudLaunchRequest(true, true, true))
        assertTrue(shouldHonorSteamCloudLaunchRequest(true, false, true))
        assertFalse(shouldHonorSteamCloudLaunchRequest(true, false, false))
        assertFalse(shouldHonorSteamCloudLaunchRequest(false, true, true))
        assertFalse(shouldHonorSteamCloudLaunchRequest(false, false, true))
    }

    @Test fun immediateBackgroundLaunchDoesNotRequirePlanButAutomaticLaunchStillDoes() {
        val plan = SteamCloudUploadPlan(1, baselineConfigured = true, uploadCandidates = emptyList(),
            conflicts = emptyList(), remoteOnlyChanges = emptyList(), remoteDeleteCandidates = emptyList(), warnings = emptyList())
        val ready = State(state = Status.SYNCING, syncDirection = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
            backgroundUploadReady = true, plan = plan)
        assertEquals(SteamCloudBackgroundLaunchAction.LAUNCH, steamCloudBackgroundLaunchAction(ready))
        assertEquals(SteamCloudBackgroundLaunchAction.LAUNCH, steamCloudBackgroundLaunchAction(ready.copy(plan = null)))
        assertFalse(shouldAutoLaunchAfterSteamCloudUpdate(ready.copy(plan = null)))
        val remoteDeletion = SteamCloudRemoteOnlyChange("saves/IRONCLAD.autosave", SteamCloudRootKind.SAVES,
            SteamCloudRemoteOnlyChangeKind.REMOTE_FILE_DELETED, null, null)
        val mixed = ready.copy(plan = plan.copy(remoteOnlyChanges = listOf(remoteDeletion)))
        assertEquals(SteamCloudBackgroundLaunchAction.LAUNCH, steamCloudBackgroundLaunchAction(mixed))
        assertFalse(shouldAutoLaunchAfterSteamCloudUpdate(mixed))
        val conflict = SteamCloudConflict("saves/IRONCLAD.autosave", SteamCloudRootKind.SAVES,
            SteamCloudConflictKind.BOTH_CHANGED, null, null, null, null)
        assertFalse(shouldAutoLaunchAfterSteamCloudUpdate(ready.copy(plan = plan.copy(conflicts = listOf(conflict)))))
        assertFalse(shouldAutoLaunchAfterSteamCloudUpdate(ready.copy(syncDirection = SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL)))
    }

    @Test fun immediateLaunchRunsWithoutReceivingAnyCloudCompletionEvent() {
        val events = mutableListOf<String>()
        assertTrue(performSteamCloudBackgroundLaunch(SteamCloudBackgroundLaunchAction.START_SYNC,
            startSync = { events += "worker-enqueued" }, launch = { events += "launch"; true }))
        assertEquals(listOf("worker-enqueued", "launch"), events)
        events.clear()
        assertTrue(performSteamCloudBackgroundLaunch(SteamCloudBackgroundLaunchAction.LAUNCH,
            startSync = { fail("Must reuse checking/downloading/uploading worker") }, launch = { events += "launch"; true }))
        assertEquals(listOf("launch"), events)
        assertFalse(performSteamCloudBackgroundLaunch(SteamCloudBackgroundLaunchAction.REJECT,
            startSync = { fail() }, launch = { fail(); true }))
    }

    @Test fun backgroundWorkerStartRejectionDoesNotCancelImmediateLaunch() {
        var started = false
        var launched = false
        assertTrue(performSteamCloudBackgroundLaunch(SteamCloudBackgroundLaunchAction.START_SYNC,
            startSync = { started = false }, launch = { launched = true; true }))
        assertFalse(started)
        assertTrue(launched)
    }

    @Test fun allRunningStatesBlockRefreshAndResolution() {
        listOf(Status.CHECKING, Status.SYNCING, Status.CANCELLING).forEach { status ->
            val state = State(state = status)
            assertTrue(state.operationInFlight)
            assertFalse(canRefreshSteamCloudCard(state))
            assertFalse(canResolveSteamCloudCard(state))
        }
    }

    @Test fun cancellationAndRecoveryAreNotConnectionFailures() {
        assertEquals(Status.CANCELLED, steamCloudFailureState(true, false))
        assertEquals(Status.RECOVERY_REQUIRED, steamCloudFailureState(false, true))
        assertEquals(Status.CONNECTION_FAILED, steamCloudFailureState(false, false))
        assertTrue(canResolveSteamCloudCard(State(state = Status.RECOVERY_REQUIRED)))
        assertTrue(canRefreshSteamCloudCard(State(state = Status.CANCELLED)))
        assertFalse(shouldAutoLaunchAfterSteamCloudUpdate(State(state = Status.CANCELLED)))
    }

    @Test fun aConflictWithoutAPlanCanOnlyBeRechecked() {
        val state = State(state = Status.CONFLICT)
        assertFalse(canResolveSteamCloudCard(state))
        assertTrue(canRefreshSteamCloudCard(state))
        val plan = SteamCloudUploadPlan(1, baselineConfigured = false, uploadCandidates = emptyList(),
            conflicts = emptyList(), remoteOnlyChanges = emptyList(), remoteDeleteCandidates = emptyList(), warnings = emptyList())
        assertTrue(canResolveSteamCloudCard(state.copy(plan = plan)))
    }

    @Test fun unavailableStatesExplainWhyThereIsNoSyncAction() {
        listOf(Status.DISABLED, Status.INDEPENDENT, Status.SIGNED_OUT, Status.DEFERRED).forEach {
            val state = State(state = it)
            assertFalse(state.operationInFlight)
            assertFalse(canRefreshSteamCloudCard(state))
            assertFalse(canResolveSteamCloudCard(state))
        }
    }

    @Test fun mixedSyncUsesStageCountersNotOverallPercent() {
        val download = State(state = Status.SYNCING, phase = SteamCloudSyncPhase.DOWNLOADING,
            completedFiles = 3, totalFiles = 3)
        assertEquals(1f, steamCloudStageFraction(download)!!, 0f)
        assertEquals(0f, steamCloudStageFraction(download.copy(phase = SteamCloudSyncPhase.UPLOADING, completedFiles = 0))!!, 0f)
        assertNull(steamCloudStageFraction(download.copy(phase = SteamCloudSyncPhase.VERIFYING_REMOTE)))
        assertNull(steamCloudStageFraction(download.copy(phase = SteamCloudSyncPhase.APPLYING_TO_LOCAL)))
        assertNull(steamCloudStageFraction(download.copy(phase = SteamCloudSyncPhase.FINALIZING)))
        assertNull(steamCloudStageFraction(download.copy(state = Status.CANCELLING)))
        assertNull(steamCloudStageFraction(download.copy(totalFiles = 0)))
    }

    @Test fun cancellingNeverOffersBackgroundLaunchEvenWithOldReadiness() {
        assertFalse(shouldShowSteamCloudBackgroundUploadAction(State(state = Status.CANCELLING, backgroundUploadReady = true)))
        assertFalse(shouldAutoLaunchAfterSteamCloudUpdate(State(state = Status.CANCELLING, backgroundUploadReady = true)))
    }
    @Test fun republishingOtherGameUiPreservesProgressAndWarnings() {
        val current = State(visible = true, state = Status.SYNCING, phase = SteamCloudSyncPhase.DOWNLOADING,
            completedFiles = 4, totalFiles = 6, warnings = listOf("warning"))
        assertEquals(current, steamCloudCardAvailability(current, false, true, true))
        val disabled = steamCloudCardAvailability(current, true, true, true)
        assertEquals(Status.DISABLED, disabled.state)
        assertEquals(Status.HIDDEN, steamCloudCardAvailability(disabled, false, true, true).state)
        assertEquals(Status.SIGNED_OUT, steamCloudCardAvailability(current, false, true, false).state)
        assertEquals(Status.INDEPENDENT, steamCloudCardAvailability(current, false, false, true).state)
    }
}
