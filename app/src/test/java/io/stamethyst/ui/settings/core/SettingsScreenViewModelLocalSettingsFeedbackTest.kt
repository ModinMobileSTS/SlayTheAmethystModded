package io.stamethyst.ui.settings.core

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class SettingsScreenViewModelLocalSettingsFeedbackTest {
    @Test
    fun launcherBlurChangesUpdateLocallyAndRestoreWithConfiguration() {
        val source = repositorySource(SETTINGS_VIEW_MODEL_PATH)
        val body = functionBody(source, "onLauncherBlurDisabledChanged")
        assertTrue(body.contains("uiState.busy || uiState.launcherBlurDisabled == disabled"))
        assertTrue(body.indexOf("uiState = uiState.copy(launcherBlurDisabled = disabled)") <
            body.indexOf("LauncherPreferences.saveLauncherBlurDisabled(host, disabled)"))
        assertTrue(body.contains("refreshConfiguration(host)"))
        assertFalse(body.contains("refreshStatus("))
        assertTrue(functionBody(source, "applySnapshot").contains(
            "launcherBlurDisabled = snapshot.launcherBlurDisabled",
        ))
        val repository = repositorySource("app/src/main/java/io/stamethyst/ui/settings/core/SettingsRepository.kt")
        assertTrue(functionBody(repository, "resetLauncherSettingsToDefaults").contains(
            "LauncherPreferences.DEFAULT_LAUNCHER_BLUR_DISABLED",
        ))
    }

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
                body.contains("refreshConfiguration(host, clearBusy = false)"),
            )
            assertFalse("${expectation.name} must not rescan mods or Steam", body.contains("refreshStatus("))
        }
    }

    @Test
    fun ordinaryConfigurationLoadHasNoEnvironmentOrEncryptedCredentialsChecks() {
        val source = repositorySource("app/src/main/java/io/stamethyst/ui/settings/core/SettingsRepository.kt")
        val body = functionBody(source, "loadSettingsSnapshot")
        listOf("RendererBackendResolver", "AndroidGameModeSupport", "BaiduTranslationCredentialsRepository",
            "ResourcePackStore", "SteamCloudAuthStore", "ModManager").forEach {
            assertFalse("Configuration loading must not depend on $it", body.contains(it))
        }
    }

    @Test
    fun ordinarySettingChangesNeverTriggerFullStatusRefresh() {
        val source = repositorySource(SETTINGS_VIEW_MODEL_PATH)
        listOf("onTargetFpsSelected", "onJvmHeapMaxSelected", "onRamSaverEnabledChanged",
            "onTouchMouseInteractionModeChanged", "onMobileGluesAnglePolicyChanged").forEach {
            val body = functionBody(source, it)
            assertTrue(body.contains("refreshConfiguration(host)"))
            assertFalse(body.contains("refreshStatus("))
        }
    }

    @Test
    fun accountChangesInvalidateAccountSaveAndProfileRefreshesBeforeStartingWork() {
        val source = repositorySource(SETTINGS_VIEW_MODEL_PATH)
        val invalidation = functionBody(source, "beginSteamIdentityChange")
        assertTrue(invalidation.contains("steamProfileTask.cancel()"))
        assertTrue(invalidation.contains("SettingsLoadDomain.STEAM_ACCOUNT, SettingsLoadDomain.STEAM_SAVES"))
        assertTrue(invalidation.contains("refreshTasks.getValue(domain).cancel()"))
        listOf("onStartSteamCloudLogin", "onClearSteamCloudCredentials").forEach { name ->
            val body = functionBody(source, name)
            assertTrue("$name must invalidate old results", body.contains("beginSteamIdentityChange()"))
            val workStart = body.indexOf("executor.execute")
            assertTrue("$name must queue work after invalidation", workStart > body.indexOf("beginSteamIdentityChange()"))
            assertFalse("$name must not rescan mods or resources", body.contains("refreshStatus("))
        }
    }

    @Test
    fun pendingSteamControlWriteCannotBeOverwrittenByConfigurationRefresh() {
        val source = repositorySource(SETTINGS_VIEW_MODEL_PATH)
        assertTrue(functionBody(source, "applySnapshot").contains(
            "steamCloudSyncDisabled = pendingSteamCloudSyncDisabled ?: snapshot.steam.syncDisabled",
        ))
        val body = functionBody(source, "onSteamCloudSyncDisabledChanged")
        assertTrue(body.contains("pendingSteamCloudSyncDisabled = disabled"))
        val completion = body.substring(body.indexOf("host.runOnUiThread"))
        assertTrue(completion.indexOf("refreshTasks.getValue(SettingsLoadDomain.CONFIGURATION).cancel()") <
            completion.indexOf("pendingSteamCloudSyncDisabled = null"))
    }

    @Test
    fun composingStatusDoesNotReadConfigurationAgainOnMainThread() {
        val source = repositorySource(SETTINGS_VIEW_MODEL_PATH)
        assertFalse(functionBody(source, "buildStatusText").contains("LauncherPreferences."))
        assertFalse(functionBody(source, "applySnapshot").contains("LauncherPreferences."))
    }

    @Test
    fun cosmeticProfileHttpNeverHoldsAccountLoadingOrLoginCompletion() {
        val source = repositorySource(SETTINGS_VIEW_MODEL_PATH)
        assertFalse(functionBody(source, "onStartSteamCloudLogin").contains("SteamCloudProfileService.fetchProfile"))
        val refresh = functionBody(source, "refreshSteamAccount")
        assertFalse(refresh.contains("SteamCloudProfileService.fetchProfile"))
        assertTrue(refresh.contains("steamProfileTask.submit("))
        assertTrue(functionBody(source, "maybeBackfillSteamCloudProfile").contains("checkCurrent()"))
    }

    @Test
    fun accountCardNeverWaitsForTheSaveTransactionLock() {
        val source = repositorySource(SETTINGS_VIEW_MODEL_PATH)
        val body = functionBody(source, "refreshSteamAccount")
        listOf("SteamCloudManifestStore", "SteamCloudBaselineStore", "SteamCloudOperationMutex").forEach {
            assertFalse("Account state must not depend on $it", body.contains(it))
        }
    }

    @Test
    fun dependencyLoadingKeepsContentAndDisablesControlsWithoutAPlaceholder() {
        val source = repositorySource("app/src/main/java/io/stamethyst/ui/settings/common/SettingsDependencyContent.kt")
        val body = functionBody(source, "SettingsDependencyContent")
        assertTrue(body.contains("LocalSettingsControlsEnabled provides settingsControlEnabled(loadState == SettingsLoadState.READY)"))
        assertTrue(body.contains("content = content"))
        assertTrue(body.contains("loadState == SettingsLoadState.ERROR"))
        assertFalse(body.contains("settings_value_loading"))
        assertFalse(body.contains("CircularProgressIndicator"))
        assertTrue(source.contains("LocalSettingsControlsEnabled.current && enabled"))
    }

    @Test
    fun sharedSettingsControlsRespectTheDependencyGate() {
        val source = repositorySource("app/src/main/java/io/stamethyst/ui/settings/common/SettingsCommonUi.kt")
        listOf("SwitchSettingRow", "SettingsRadioOptionRow", "HapticIconButton", "HapticTextButton").forEach {
            assertTrue("$it must disable the actual control", functionBody(source, it).contains("settingsControlEnabled(enabled)"))
        }
        listOf("SettingsActionListItem", "SettingsDangerActionListItem").forEach {
            val body = functionBody(source, it)
            assertTrue(body.contains("val controlEnabled = settingsControlEnabled(enabled)"))
            assertTrue(body.contains("enabled = controlEnabled"))
        }
        assertTrue(source.contains("clickable(\n        enabled = settingsControlEnabled(enabled)"))
        assertTrue(source.contains("toggleable(\n        value = value,\n        enabled = settingsControlEnabled(enabled)"))
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
