package io.stamethyst.ui.main

import io.stamethyst.backend.steamcloud.SteamCloudSyncDirection
import io.stamethyst.backend.steamcloud.SteamCloudUploadPlan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamCloudBackgroundUploadActionTest {
    private val uploadPlan = SteamCloudUploadPlan(1, baselineConfigured = true,
        uploadCandidates = emptyList(), conflicts = emptyList(), remoteOnlyChanges = emptyList(),
        remoteDeleteCandidates = emptyList(), warnings = emptyList())

    @Test
    fun shouldShowSteamCloudBackgroundUploadAction_onlyShowsForUploadSyncing() {
        assertTrue(
            shouldShowSteamCloudBackgroundUploadAction(
                MainScreenViewModel.SteamCloudIndicatorUi(
                    visible = true,
                    state = MainScreenViewModel.SteamCloudIndicatorState.SYNCING,
                    syncDirection = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                    backgroundUploadReady = true,
                    plan = uploadPlan,
                )
            )
        )

        assertFalse(
            shouldShowSteamCloudBackgroundUploadAction(
                MainScreenViewModel.SteamCloudIndicatorUi(
                    visible = true,
                    state = MainScreenViewModel.SteamCloudIndicatorState.SYNCING,
                    syncDirection = SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL,
                )
            )
        )

        assertFalse(
            shouldShowSteamCloudBackgroundUploadAction(
                MainScreenViewModel.SteamCloudIndicatorUi(
                    visible = true,
                    state = MainScreenViewModel.SteamCloudIndicatorState.SYNCING,
                    syncDirection = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                    backgroundUploadReady = false,
                )
            )
        )

        assertFalse(
            shouldShowSteamCloudBackgroundUploadAction(
                MainScreenViewModel.SteamCloudIndicatorUi(
                    visible = true,
                    state = MainScreenViewModel.SteamCloudIndicatorState.CHECKING,
                    syncDirection = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                )
            )
        )
    }

    @Test
    fun shouldAutoLaunchAfterSteamCloudUpdate_onlyForBackgroundUploadOrUpToDate() {
        assertTrue(
            shouldAutoLaunchAfterSteamCloudUpdate(
                MainScreenViewModel.SteamCloudIndicatorUi(
                    visible = true,
                    state = MainScreenViewModel.SteamCloudIndicatorState.UP_TO_DATE,
                )
            )
        )

        assertTrue(
            shouldAutoLaunchAfterSteamCloudUpdate(
                MainScreenViewModel.SteamCloudIndicatorUi(
                    visible = true,
                    state = MainScreenViewModel.SteamCloudIndicatorState.SYNCING,
                    syncDirection = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                    backgroundUploadReady = true,
                    plan = uploadPlan,
                )
            )
        )

        assertFalse(
            shouldAutoLaunchAfterSteamCloudUpdate(
                MainScreenViewModel.SteamCloudIndicatorUi(
                    visible = true,
                    state = MainScreenViewModel.SteamCloudIndicatorState.SYNCING,
                    syncDirection = SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL,
                )
            )
        )

        assertFalse(
            shouldAutoLaunchAfterSteamCloudUpdate(
                MainScreenViewModel.SteamCloudIndicatorUi(
                    visible = true,
                    state = MainScreenViewModel.SteamCloudIndicatorState.SYNCING,
                    syncDirection = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                    backgroundUploadReady = false,
                )
            )
        )

        assertFalse(
            shouldAutoLaunchAfterSteamCloudUpdate(
                MainScreenViewModel.SteamCloudIndicatorUi(
                    visible = true,
                    state = MainScreenViewModel.SteamCloudIndicatorState.CONFLICT,
                )
            )
        )
    }

    @Test
    fun readyBackgroundUpload_isTheOnlySyncingStateThatCanLaunch() {
        val ready = MainScreenViewModel.SteamCloudIndicatorUi(
            visible = true,
            state = MainScreenViewModel.SteamCloudIndicatorState.SYNCING,
            syncDirection = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
            backgroundUploadReady = true,
            plan = uploadPlan,
        )
        val notReady = ready.copy(backgroundUploadReady = false)

        assertTrue(shouldShowSteamCloudBackgroundUploadAction(ready))
        assertTrue(shouldAutoLaunchAfterSteamCloudUpdate(ready))
        assertFalse(shouldShowSteamCloudBackgroundUploadAction(notReady))
        assertFalse(shouldAutoLaunchAfterSteamCloudUpdate(notReady))
    }

    @Test
    fun backgroundLaunchDuringCheck_waitsForPlanEvenWithFrozenSnapshot() {
        val checking = MainScreenViewModel.SteamCloudIndicatorUi(
            visible = true,
            state = MainScreenViewModel.SteamCloudIndicatorState.CHECKING,
        )

        assertFalse(shouldAutoLaunchAfterSteamCloudUpdate(checking))
        assertFalse(shouldAutoLaunchAfterSteamCloudUpdate(checking.copy(backgroundUploadReady = true)))
        assertFalse(shouldShowSteamCloudBackgroundUploadAction(checking.copy(backgroundUploadReady = true)))
    }
}
