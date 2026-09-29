package io.stamethyst.ui.settings.core

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsScreenViewModelLocalSettingsFeedbackTest {
    @Test
    fun steamAndMarketSettingsUpdateLocalUiStateBeforeRefreshing() {
        val source = repositorySource(SETTINGS_VIEW_MODEL_PATH)

        listOf(
            HandlerExpectation(
                name = "onSteamCloudWattAccelerationChanged",
                snippets = listOf(
                    "steamCloudWattAccelerationEnabled = effectiveEnabled",
                    "workshopWattAccelerationEnabled = effectiveEnabled",
                ),
            ),
            HandlerExpectation(
                name = "onSteamCloudAutoLaunchAfterSyncChanged",
                snippets = listOf("steamCloudAutoLaunchAfterSyncEnabled = enabled"),
            ),
            HandlerExpectation(
                name = "onSteamGamePresenceChanged",
                snippets = listOf("steamGamePresenceEnabled = enabled"),
            ),
            HandlerExpectation(
                name = "onRichPresenceDisplayPreferencesChanged",
                snippets = listOf("richPresenceDisplayPreferences = settings"),
            ),
            HandlerExpectation(
                name = "onSteamAchievementSyncChanged",
                snippets = listOf("steamAchievementSyncEnabled = enabled"),
            ),
            HandlerExpectation(
                name = "onAchievementUnlockNotificationChanged",
                snippets = listOf("achievementUnlockNotificationEnabled = enabled"),
            ),
            HandlerExpectation(
                name = "onWorkshopMaxConcurrentDownloadsChanged",
                snippets = listOf("workshopMaxConcurrentDownloads ="),
            ),
            HandlerExpectation(
                name = "onWorkshopDownloadThreadsChanged",
                snippets = listOf("workshopDownloadThreads ="),
            ),
            HandlerExpectation(
                name = "onWorkshopWattAccelerationChanged",
                snippets = listOf(
                    "steamCloudWattAccelerationEnabled = effectiveEnabled",
                    "workshopWattAccelerationEnabled = effectiveEnabled",
                ),
            ),
            HandlerExpectation(
                name = "onWorkshopSteamLanguageChanged",
                snippets = listOf("workshopSteamLanguage = language"),
            ),
            HandlerExpectation(
                name = "onWorkshopDefaultSortChanged",
                snippets = listOf("workshopDefaultSort = sort"),
            ),
            HandlerExpectation(
                name = "onWorkshopAutoImportChanged",
                snippets = listOf("workshopAutoImportEnabled = enabled"),
            ),
            HandlerExpectation(
                name = "onWorkshopAutoImportAtlasDownscaleChanged",
                snippets = listOf("workshopAutoImportAtlasDownscaleEnabled = enabled"),
            ),
            HandlerExpectation(
                name = "onWorkshopAutoImportAtlasDownscaleMaxEdgeChanged",
                snippets = listOf("workshopAutoImportAtlasDownscaleMaxEdgePx ="),
            ),
        ).forEach { expectation ->
            val body = functionBody(source, expectation.name)
            assertTrue(
                "${expectation.name} should optimistically copy uiState before refresh",
                body.contains("uiState = uiState.copy("),
            )
            expectation.snippets.forEach { snippet ->
                assertTrue(
                    "${expectation.name} should update local UI state with: $snippet",
                    body.contains(snippet),
                )
            }
            assertTrue(
                "${expectation.name} should not clear unrelated busy operations",
                body.contains("refreshStatus(host, clearBusy = false)"),
            )
        }
    }

    private data class HandlerExpectation(
        val name: String,
        val snippets: List<String>,
    )

    private fun functionBody(source: String, functionName: String): String {
        val functionStart = source.indexOf("fun $functionName(")
        assertTrue("Missing function $functionName", functionStart >= 0)
        val bodyStart = source.indexOf('{', functionStart)
        assertTrue("Missing body for $functionName", bodyStart >= 0)

        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return source.substring(bodyStart, index + 1)
                    }
                }
            }
        }
        throw AssertionError("Unclosed body for $functionName")
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
        throw AssertionError("Could not locate $path from $workingDirectory")
    }

    private companion object {
        const val SETTINGS_VIEW_MODEL_PATH =
            "app/src/main/java/io/stamethyst/ui/settings/core/SettingsScreenViewModel.kt"
    }
}
