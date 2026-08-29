package io.stamethyst.config

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherConfigFloatingToolButtonsDefaultsTest {
    @Test
    fun optionalFloatingToolButtonsAreDisabledByDefault() {
        assertTrue(LauncherConfig.DEFAULT_FLOATING_TOOL_BUTTONS.isEmpty())
    }

    @Test
    fun supportedOptionalFloatingToolButtonsMatchTheDrawerActions() {
        assertEquals(
            listOf("ctrl", "shift", "tab", "alt", "lock", "wheel"),
            LauncherConfig.FLOATING_TOOL_BUTTON_IDS,
        )
    }

    /**
     * The launcher hands these ids to the mod over a JVM property, so a rename on either side
     * would turn the matching toggle into a no-op rather than break the build.
     */
    @Test
    fun everySupportedButtonIdIsRecognisedByTheMod() {
        val body = floatingToolPanelSource()
            .substringAfter("private static Action optionalActionForId(")
            .substringBefore("\n    }")
        val recognised = QUOTED_LITERAL.findAll(body).map { it.groupValues[1] }.toSet()

        assertEquals(LauncherConfig.FLOATING_TOOL_BUTTON_IDS.toSet(), recognised)
    }

    @Test
    fun launcherAndDrawerShareAutoSwitchLeftProperty() {
        val property = "amethyst.floating_tools.auto_switch_left_after_right_click"

        assertTrue(stsLaunchSpecSource().contains("\"-D$property=\""))
        assertTrue(floatingToolPanelSource().contains("\"$property\""))
    }

    @Test
    fun primaryDrawerActionsKeepTheirRequestedHoverTooltips() {
        val panel = floatingToolPanelSource()

        assertTrue(panel.contains("\"切换鼠标左右键\""))
        assertTrue(panel.contains("\"新增按键\""))
        assertTrue(panel.contains("\"打开键盘\""))
        assertTrue(panel.contains("\"打开虚拟局域网菜单\""))
        assertTrue(panel.contains("renderHoverTooltip"))
        assertTrue(panel.contains("FontHelper.renderFontCentered"))
    }

    private fun floatingToolPanelSource(): String {
        return repositorySource(MOD_PANEL_PATH)
    }

    private fun stsLaunchSpecSource(): String {
        return repositorySource(LAUNCH_SPEC_PATH)
    }

    private fun repositorySource(path: String): String {
        val workingDirectory = System.getProperty("user.dir") ?: "."
        var dir: File? = File(workingDirectory).absoluteFile
        while (dir != null) {
            val currentDir = dir
            val candidate = File(currentDir, path)
            if (candidate.isFile) {
                return candidate.readText()
            }
            dir = currentDir.parentFile
        }
        throw AssertionError(
            "Could not locate $path from ${System.getProperty("user.dir")}"
        )
    }

    private companion object {
        const val MOD_PANEL_PATH =
            "mods/amethyst-floating-tools/src/main/java/io/stamethyst/floatingtools/" +
                "FloatingToolPanel.java"
        const val LAUNCH_SPEC_PATH =
            "app/src/main/java/io/stamethyst/backend/launch/StsLaunchSpec.kt"
        val QUOTED_LITERAL = Regex("\"([^\"]+)\"")
    }
}
