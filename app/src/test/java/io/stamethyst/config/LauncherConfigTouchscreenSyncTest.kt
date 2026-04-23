package io.stamethyst.config

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherConfigTouchscreenSyncTest {
    @Test
    fun syncTouchscreenEnabledToGameplaySettingsFile_overridesExistingPreferenceValue() {
        val tempDir = Files.createTempDirectory("launcher-config-touchscreen-sync").toFile()
        try {
            val gameplaySettingsFile = File(tempDir, "STSGameplaySettings")
            gameplaySettingsFile.writeText(
                """
                {
                  "Touchscreen Enabled": "true",
                  "LANGUAGE": "ENG"
                }
                """.trimIndent()
            )

            LauncherConfig.syncTouchscreenEnabledToGameplaySettingsFile(
                file = gameplaySettingsFile,
                enabled = false,
                bundledDefaults = JSONObject("""{"Bigger Text":"true"}""")
            )

            val root = JSONObject(gameplaySettingsFile.readText())
            assertEquals("false", root.getString("Touchscreen Enabled"))
            assertEquals("ENG", root.getString("LANGUAGE"))
            assertEquals("true", root.getString("Bigger Text"))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
