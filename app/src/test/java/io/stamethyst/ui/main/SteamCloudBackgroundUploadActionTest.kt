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
                    state = MainScreenViewModel.SteamCloudIndicatorState.CHECKING,
                    syncDirection = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                )
            )
        )
    }
}
