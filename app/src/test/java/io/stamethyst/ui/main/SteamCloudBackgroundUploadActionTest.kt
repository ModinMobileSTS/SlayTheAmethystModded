package io.stamethyst.ui.main

import io.stamethyst.backend.steamcloud.SteamCloudSyncDirection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamCloudBackgroundUploadActionTest {

    @Test
    fun shouldShowSteamCloudBackgroundUploadAction_onlyShowsForUploadSyncing() {
        assertTrue(
            shouldShowSteamCloudBackgroundUploadAction(
                MainScreenViewModel.SteamCloudIndicatorUi(
                    visible = true,
                    state = MainScreenViewModel.SteamCloudIndicatorState.SYNCING,
                    syncDirection = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                    backgroundUploadReady = true,
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
        )
        val notReady = ready.copy(backgroundUploadReady = false)

        assertTrue(shouldShowSteamCloudBackgroundUploadAction(ready))
        assertTrue(shouldAutoLaunchAfterSteamCloudUpdate(ready))
        assertFalse(shouldShowSteamCloudBackgroundUploadAction(notReady))
        assertFalse(shouldAutoLaunchAfterSteamCloudUpdate(notReady))
    }

    @Test
    fun backgroundLaunchDuringCheck_requiresFrozenSnapshot() {
        val checking = MainScreenViewModel.SteamCloudIndicatorUi(
            visible = true,
            state = MainScreenViewModel.SteamCloudIndicatorState.CHECKING,
        )

        assertFalse(shouldShowSteamCloudBackgroundLaunchDuringCheck(checking))
        assertTrue(shouldShowSteamCloudBackgroundLaunchDuringCheck(checking.copy(backgroundUploadReady = true)))
    }
}
