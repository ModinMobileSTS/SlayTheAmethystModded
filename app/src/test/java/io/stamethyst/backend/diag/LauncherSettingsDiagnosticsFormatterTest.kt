package io.stamethyst.backend.diag

import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherSettingsDiagnosticsFormatterTest {
    @Test
    fun build_includesSectionsAndSanitizedValues() {
        val text = LauncherSettingsDiagnosticsFormatter.build(
            LauncherSettingsDiagnosticsSnapshot(
                sections = listOf(
                    LauncherSettingsDiagnosticsSection(
                        title = "General",
                        entries = listOf(
                            "player.name" to "player\nname",
                            "theme.mode" to "FOLLOW_SYSTEM (follow_system)"
                        )
                    ),
                    LauncherSettingsDiagnosticsSection(
                        title = "MobileGlues",
                        entries = listOf(
                            "anglePolicy" to "尽量开启 (Prefer Enabled) (1)"
                        )
                    )
                )
            )
        )

        assertTrue(text.contains("launcherSettings.formatVersion=1"))
        assertTrue(text.contains("[General]"))
        assertTrue(text.contains("player.name=player name"))
        assertTrue(text.contains("theme.mode=FOLLOW_SYSTEM (follow_system)"))
        assertTrue(text.contains("[MobileGlues]"))
        assertTrue(text.contains("anglePolicy=尽量开启 (Prefer Enabled) (1)"))
    }
}
