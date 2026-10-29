package io.stamethyst.ui.main

import org.junit.Assert.*
import org.junit.Test

class SteamCloudNotificationPermissionTest {
    @Test fun asksOnlyForAvailableCloudSavesOnAndroid13OrLater() {
        assertTrue(shouldRequestSteamCloudNotificationPermission(33, true, false, false))
        assertTrue(shouldRequestSteamCloudNotificationPermission(36, true, false, false))
        assertFalse(shouldRequestSteamCloudNotificationPermission(32, true, false, false))
        assertFalse(shouldRequestSteamCloudNotificationPermission(33, false, false, false))
    }

    @Test fun grantingOrDecliningDoesNotRepeatedlyPromptOnGameReturn() {
        assertFalse(shouldRequestSteamCloudNotificationPermission(33, true, true, false))
        assertFalse(shouldRequestSteamCloudNotificationPermission(33, true, false, true))
        assertFalse(shouldRequestSteamCloudNotificationPermission(33, true, true, true))
    }
}
