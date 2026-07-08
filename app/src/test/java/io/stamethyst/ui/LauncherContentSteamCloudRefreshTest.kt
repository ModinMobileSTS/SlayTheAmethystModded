package io.stamethyst.ui

import io.stamethyst.config.SteamCloudSaveMode
import io.stamethyst.navigation.Route
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherContentSteamCloudRefreshTest {

    @Test
    fun planSteamCloudMainRefresh_defersLoginRefreshUntilMainRoute() {
        val initialState = SteamCloudMainRefreshTrackerState(
            observedRefreshTokenConfigured = false,
            observedSaveMode = SteamCloudSaveMode.STEAM_CLOUD,
        )

        val offMainPlan = planSteamCloudMainRefresh(
            route = Route.SteamCloudLogin,
            state = initialState,
            refreshTokenConfigured = true,
            saveMode = SteamCloudSaveMode.STEAM_CLOUD,
        )

        assertTrue(offMainPlan.nextState.pendingRefreshOnMain)
        assertFalse(offMainPlan.shouldRefreshMain)
        assertFalse(offMainPlan.shouldForceSyncIndicator)

        val mainPlan = planSteamCloudMainRefresh(
            route = Route.Main,
            state = offMainPlan.nextState,
            refreshTokenConfigured = true,
            saveMode = SteamCloudSaveMode.STEAM_CLOUD,
        )

        assertFalse(mainPlan.nextState.pendingRefreshOnMain)
        assertTrue(mainPlan.shouldRefreshMain)
        assertTrue(mainPlan.shouldForceSyncIndicator)
    }

    @Test
    fun planSteamCloudMainRefresh_forceSyncsWhenSaveModeSwitchesToSteamCloudOnMain() {
        val initialState = SteamCloudMainRefreshTrackerState(
            observedRefreshTokenConfigured = true,
            observedSaveMode = SteamCloudSaveMode.INDEPENDENT,
        )

        val plan = planSteamCloudMainRefresh(
            route = Route.Main,
            state = initialState,
            refreshTokenConfigured = true,
            saveMode = SteamCloudSaveMode.STEAM_CLOUD,
        )

        assertFalse(plan.nextState.pendingRefreshOnMain)
        assertTrue(plan.shouldRefreshMain)
        assertTrue(plan.shouldForceSyncIndicator)
    }

    @Test
    fun planSteamCloudMainRefresh_refreshesWithoutForceSyncAfterLogout() {
        val initialState = SteamCloudMainRefreshTrackerState(
            observedRefreshTokenConfigured = true,
            observedSaveMode = SteamCloudSaveMode.STEAM_CLOUD,
        )

        val offMainPlan = planSteamCloudMainRefresh(
            route = Route.Settings,
            state = initialState,
            refreshTokenConfigured = false,
            saveMode = SteamCloudSaveMode.STEAM_CLOUD,
        )

        assertFalse(offMainPlan.shouldRefreshMain)
        assertFalse(offMainPlan.shouldForceSyncIndicator)
        assertTrue(offMainPlan.nextState.pendingRefreshOnMain)

        val mainPlan = planSteamCloudMainRefresh(
            route = Route.Main,
            state = offMainPlan.nextState,
            refreshTokenConfigured = false,
            saveMode = SteamCloudSaveMode.STEAM_CLOUD,
        )

        assertTrue(mainPlan.shouldRefreshMain)
        assertFalse(mainPlan.shouldForceSyncIndicator)
        assertFalse(mainPlan.nextState.pendingRefreshOnMain)
    }
}
