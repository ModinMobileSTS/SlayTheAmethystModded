package io.stamethyst.backend.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherSettingsDiagnosticsFormatterTest {
    private val snapshot = LauncherSettingsDiagnosticsSnapshot(
        sections = listOf(
            LauncherSettingsDiagnosticsSection(
                titleEn = "Launcher / Appearance",
                titleZh = "启动器 / 外观",
                fields = listOf(
                    LauncherSettingsDiagnosticsField(
                        key = "player.name",
                        labelZh = "玩家名",
                        enValue = "player name",
                        zhValue = "玩家 名",
                    ),
                    LauncherSettingsDiagnosticsField(
                        key = "theme.mode",
                        labelZh = "主题模式",
                        enValue = "FOLLOW_SYSTEM (follow_system)",
                        zhValue = "跟随系统",
                    ),
                )
            ),
            LauncherSettingsDiagnosticsSection(
                titleEn = "Developer / MobileGlues",
                titleZh = "开发者 / MobileGlues",
                fields = listOf(
                    LauncherSettingsDiagnosticsField(
                        key = "anglePolicy",
                        labelZh = "ANGLE 策略",
                        enValue = "PREFER_DISABLED (0)",
                        zhValue = "倾向禁用",
                    ),
                )
            )
        )
    )

    @Test
    fun build_keepsStableEnglishKeysAndValues() {
        val text = LauncherSettingsDiagnosticsFormatter.build(snapshot)

        assertTrue(text.contains("launcherSettings.formatVersion=2"))
        assertTrue(text.contains("[Launcher / Appearance]"))
        assertTrue(text.contains("player.name=player name"))
        assertTrue(text.contains("theme.mode=FOLLOW_SYSTEM (follow_system)"))
        assertTrue(text.contains("[Developer / MobileGlues]"))
        assertTrue(text.contains("anglePolicy=PREFER_DISABLED (0)"))
    }

    @Test
    fun buildChinese_usesChineseLabelsAndValues() {
        val text = LauncherSettingsDiagnosticsFormatter.buildChinese(snapshot)

        assertTrue(text.contains("launcherSettings.locale=zh-CN"))
        assertTrue(text.contains("[启动器 / 外观]"))
        assertTrue(text.contains("玩家名=玩家 名"))
        assertTrue(text.contains("主题模式=跟随系统"))
        assertTrue(text.contains("[开发者 / MobileGlues]"))
        assertTrue(text.contains("ANGLE 策略=倾向禁用"))
        // English machine keys must not leak into the Chinese rendering.
        assertTrue(!text.contains("theme.mode="))
    }

    @Test
    fun buildAndBuildChinese_areRenderedFromTheSameSnapshot() {
        val sections = snapshot.sections
        assertEquals(sections.size, LauncherSettingsDiagnosticsFormatter.build(snapshot).let {
            it.split("\n").count { line -> line.startsWith("[") }
        })
        assertEquals(
            sections.size,
            LauncherSettingsDiagnosticsFormatter.buildChinese(snapshot).split("\n")
                .count { line -> line.startsWith("[") }
        )
    }
}
