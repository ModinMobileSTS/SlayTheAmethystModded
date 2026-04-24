package io.stamethyst.backend.steamcloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SteamCloudUserWarningTest {
    @Test
    fun parse_recognizesStructuredWarningMessages() {
        assertEquals(
            SteamCloudUserWarning.UnsupportedLocalPath("preferences/STSPlayer"),
            SteamCloudUserWarning.parse(
                SteamCloudUserWarning.UnsupportedLocalPath("preferences/STSPlayer").rawMessage()
            )
        )
        assertEquals(
            SteamCloudUserWarning.FailedToMapLocalFile("saves/IRONCLAD.autosave"),
            SteamCloudUserWarning.parse(
                SteamCloudUserWarning.FailedToMapLocalFile("saves/IRONCLAD.autosave").rawMessage()
            )
        )
        assertEquals(
            SteamCloudUserWarning.BaselineRequired,
            SteamCloudUserWarning.parse(SteamCloudUserWarning.BaselineRequired.rawMessage())
        )
        assertEquals(
            SteamCloudUserWarning.IgnoredLocalDeletions(2),
            SteamCloudUserWarning.parse(
                SteamCloudUserWarning.IgnoredLocalDeletions(2).rawMessage()
            )
        )
        assertEquals(
            SteamCloudUserWarning.UnsupportedRemotePath("%GameInstall%saves/IRONCLAD.autosave"),
            SteamCloudUserWarning.parse(
                SteamCloudUserWarning.UnsupportedRemotePath(
                    "%GameInstall%saves/IRONCLAD.autosave"
                ).rawMessage()
            )
        )
    }

    @Test
    fun parse_returnsNullForUnknownWarnings() {
        assertNull(SteamCloudUserWarning.parse("Something else"))
    }
}
