package io.stamethyst.ui.settings

import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.stamethyst.R
import io.stamethyst.config.BackBehavior
import io.stamethyst.config.BootOverlayStyle
import io.stamethyst.config.MtsCacheStartupOptimization
import io.stamethyst.config.RamSaverOptimization
import io.stamethyst.config.SpecialKeyInputMode
import io.stamethyst.config.SteamCloudSaveMode
import io.stamethyst.navigation.Route
import io.stamethyst.ui.preferences.LauncherPreferences
import io.stamethyst.ui.settings.core.SettingsLoadDomain
import io.stamethyst.ui.settings.core.SettingsLoadState
import io.stamethyst.ui.settings.core.SettingsScreenViewModel
import io.stamethyst.ui.settings.core.settingsSearchActions
import io.stamethyst.ui.settings.sections.LauncherSettingsScreenContent
import io.stamethyst.ui.settings.sections.SettingsSearchActions
import io.stamethyst.ui.settings.sections.SettingsSearchDetailContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI-only callbacks: never change real preferences, import files, or reset user data. */
@RunWith(AndroidJUnit4::class)
class SettingsSearchInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val navigations = mutableListOf<Route>()
    private var previousSearchHistory: String? = null
    private var previousDeveloperWarningDismissed = false

    @Before
    fun saveSearchHistory() {
        previousSearchHistory = context.getSharedPreferences("search_history", Context.MODE_PRIVATE)
            .getString("settings", null)
        previousDeveloperWarningDismissed = LauncherPreferences.isDeveloperSettingsWarningDismissed(context)
    }

    @After
    fun restoreSearchHistory() {
        val editor = context.getSharedPreferences("search_history", Context.MODE_PRIVATE).edit()
        previousSearchHistory?.let { editor.putString("settings", it) } ?: editor.remove("settings")
        editor.commit()
        LauncherPreferences.setDeveloperSettingsWarningDismissed(context, previousDeveloperWarningDismissed)
    }

    @Test
    fun choiceResultOpensDialogAndSavesWithoutNavigating() {
        var selected: BackBehavior? = null
        showSettings { actions ->
            actions.copy(input = actions.input.copy(onBackBehaviorChanged = { selected = it }))
        }
        search(R.string.settings_back_behavior_title)
        clickResult(R.string.settings_back_behavior_title)

        compose.onNode(isDialog()).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.settings_back_behavior_desc)).assertIsDisplayed()
        compose.runOnIdle { assertTrue(navigations.isEmpty()) }
        // Selecting the already selected option must still call the real control's save callback.
        compose.onNode(
            hasText(context.getString(R.string.settings_back_behavior_exit)) and
                hasAnyAncestor(isDialog())
        ).performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(BackBehavior.EXIT_TO_LAUNCHER, selected) }
    }

    @Test
    fun playerNameCanBeEditedFromSearchAndCancelledWithoutSaving() {
        var saves = 0
        showSettings { actions -> actions.copy(onPlayerNameChanged = { saves++; true }) }
        search(R.string.settings_player_name_title)
        clickResult(R.string.settings_player_name_dialog_title)

        compose.onNode(isDialog()).assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement("new player")
        compose.onNodeWithText(context.getString(R.string.main_folder_dialog_cancel)).performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, saves)
            assertTrue(navigations.isEmpty())
        }
    }

    @Test
    fun playerNameValidationAndSaveArePreservedInSearch() {
        var savedName: String? = null
        showSettings { actions ->
            actions.copy(onPlayerNameChanged = { name ->
                if (name.isBlank()) false else {
                    savedName = name
                    true
                }
            })
        }
        search(R.string.settings_player_name_title)
        clickResult(R.string.settings_player_name_dialog_title)
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement("")
        compose.onNodeWithText(context.getString(R.string.main_folder_dialog_confirm)).performClick()
        compose.onNode(isDialog()).assertIsDisplayed()
        compose.runOnIdle { assertEquals(null, savedName) }

        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement("new player")
        compose.onNodeWithText(context.getString(R.string.main_folder_dialog_confirm)).performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("new player", savedName)
            assertTrue(navigations.isEmpty())
        }
    }

    @Test
    fun destructiveResultStillRequiresConfirmation() {
        var applied = 0
        showSettings { actions -> actions.copy(onApplyModFileNameAliases = { applied++ }) }
        search(R.string.settings_mod_alias_apply_file_names_action)
        clickResult(R.string.settings_mod_alias_apply_file_names_action)
        compose.onNode(isDialog()).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, applied) }
        compose.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, applied)
            assertTrue(navigations.isEmpty())
        }
    }

    @Test
    fun resourceOperationResultOpensItsOwnDialog() {
        showSettings()
        search(R.string.settings_save_operations)
        clickResult(R.string.settings_save_operations)

        compose.onNode(isDialog()).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.settings_import_saves)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.settings_export_saves)).assertIsDisplayed()
        compose.runOnIdle { assertTrue(navigations.isEmpty()) }
    }

    @Test
    fun busyStateDisablesDialogResult() {
        showSettings(SettingsScreenViewModel.UiState(busy = true))
        search(R.string.settings_back_behavior_title)
        compose.onNodeWithText(context.getString(R.string.settings_back_behavior_title))
            .performScrollTo().assertIsNotEnabled()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { assertTrue(navigations.isEmpty()) }
    }

    @Test
    fun fontScaleSliderSavesWithoutNavigating() {
        var selected: Float? = null
        showSettings { actions ->
            actions.copy(display = actions.display.copy(onGameplayFontScaleChanged = { selected = it }))
        }
        search(R.string.settings_gameplay_font_scale_title)
        slider().performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(1.5f) }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1.5f, selected)
            assertTrue(navigations.isEmpty())
        }
    }

    @Test
    fun switchSavesAndRefreshesInPlaceWithoutNavigating() {
        var state by mutableStateOf(SettingsScreenViewModel.UiState(hapticFeedbackEnabled = false))
        showSettings(stateProvider = { state }) { actions ->
            actions.copy(input = actions.input.copy(onHapticFeedbackChanged = {
                state = state.copy(hapticFeedbackEnabled = it)
            }))
        }
        search(R.string.settings_haptic_feedback_enabled)
        compose.onNode(isToggleable()).performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.onNode(isToggleable()).performClick().assertIsOff()
        compose.runOnIdle { assertTrue(navigations.isEmpty()) }
    }

    @Test
    fun renderScaleSliderSavesWithoutNavigating() {
        var selected: Float? = null
        showSettings { actions ->
            actions.copy(performance = actions.performance.copy(onRenderScaleSelected = { selected = it }))
        }
        search(R.string.settings_render_scale_title)
        slider().performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(0.75f) }
        compose.runOnIdle {
            assertEquals(0.75f, selected)
            assertTrue(navigations.isEmpty())
        }
    }

    @Test
    fun targetFpsSliderSavesWithoutNavigating() {
        var selected: Float? = null
        showSettings { actions ->
            actions.copy(performance = actions.performance.copy(onTargetFpsSelected = { selected = it }))
        }
        search(R.string.settings_target_fps_title)
        slider().performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(90f) }
        compose.runOnIdle {
            assertTrue(selected != null)
            assertTrue(navigations.isEmpty())
        }
    }

    @Test
    fun opacitySliderSavesWithoutNavigating() {
        var selected: Float? = null
        showSettings { actions ->
            actions.copy(appearance = actions.appearance.copy(onChromeBackgroundOpacityChanged = { selected = it }))
        }
        search(R.string.settings_chrome_background_opacity_title)
        slider().performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        compose.runOnIdle {
            assertEquals(0.5f, selected)
            assertTrue(navigations.isEmpty())
        }
    }

    @Test
    fun launcherBlurCanBeDisabledAndReenabledFromSearchWithoutNavigating() {
        var state by mutableStateOf(SettingsScreenViewModel.UiState())
        showSettings(stateProvider = { state }) { actions ->
            actions.copy(appearance = actions.appearance.copy(onLauncherBlurDisabledChanged = {
                state = state.copy(launcherBlurDisabled = it)
            }))
        }
        search(R.string.settings_launcher_blur_disabled_title)
        compose.onNode(isToggleable()).performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.onNode(isToggleable()).performClick().assertIsOff()
        compose.runOnIdle { assertTrue(navigations.isEmpty()) }
    }

    @Test
    fun launcherBlurToggleRemainsVisibleAndDisabledDuringConfigurationLoading() {
        showSettings(SettingsScreenViewModel.UiState(loadStates = mapOf(
            SettingsLoadDomain.CONFIGURATION to SettingsLoadState.LOADING,
        )))
        search(R.string.settings_launcher_blur_disabled_title)
        compose.onNode(isToggleable()).performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.settings_value_loading)).assertDoesNotExist()
    }

    @Test
    fun downloadStepperSavesAndRefreshesInPlace() {
        var state by mutableStateOf(SettingsScreenViewModel.UiState(workshopMaxConcurrentDownloads = 2))
        showSettings(stateProvider = { state }) { actions ->
            actions.copy(market = actions.market.copy(onWorkshopMaxConcurrentDownloadsChanged = {
                state = state.copy(workshopMaxConcurrentDownloads = it)
            }))
        }
        search(R.string.settings_market_concurrent_downloads_title)
        compose.onNodeWithText("+").performScrollTo().performClick()
        compose.onNodeWithText("3").assertIsDisplayed()
        compose.onNodeWithText("-").performClick()
        compose.onNodeWithText("2").assertIsDisplayed()
        compose.runOnIdle { assertTrue(navigations.isEmpty()) }
    }

    @Test
    fun configurationLoadingDisablesSliderWithoutHidingIt() {
        showSettings(SettingsScreenViewModel.UiState(loadStates = mapOf(
            SettingsLoadDomain.CONFIGURATION to SettingsLoadState.LOADING,
        )))
        search(R.string.settings_gameplay_font_scale_title)
        slider().performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.settings_value_loading)).assertDoesNotExist()
    }

    @Test
    fun busyStateDisablesSwitchWithoutHidingIt() {
        showSettings(SettingsScreenViewModel.UiState(busy = true))
        search(R.string.settings_haptic_feedback_enabled)
        compose.onNode(isToggleable()).performScrollTo().assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun dependentSwitchRemainsDisabledUntilItsParentIsEnabled() {
        showSettings(SettingsScreenViewModel.UiState(steamCloudSyncDisabled = true))
        search(R.string.settings_steam_cloud_auto_launch_after_sync_title)
        compose.onNode(isToggleable()).performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        compose.runOnIdle { assertTrue(navigations.isEmpty()) }
    }

    @Test
    fun unavailableAppearanceSettingStaysInSearchWithoutNavigating() {
        showSettings(SettingsScreenViewModel.UiState(bootOverlayStyle = BootOverlayStyle.CLASSIC_LOG))
        search(R.string.settings_boot_overlay_custom_image_title)
        compose.onNodeWithText(context.getString(R.string.settings_boot_overlay_custom_image_title))
            .performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        compose.runOnIdle { assertTrue(navigations.isEmpty()) }
    }

    @Test
    fun unavailableMouseSettingStaysInSearchWithoutNavigating() {
        showSettings(SettingsScreenViewModel.UiState(specialKeyInputMode = SpecialKeyInputMode.BUILT_IN_MOD))
        search(R.string.settings_touch_mouse_interaction_label)
        compose.onNodeWithText(context.getString(R.string.settings_touch_mouse_interaction_label))
            .performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        compose.runOnIdle { assertTrue(navigations.isEmpty()) }
    }

    @Test
    fun resourceDependentSwitchStaysDisabledWhileLoading() {
        showSettings(SettingsScreenViewModel.UiState(loadStates = mapOf(
            SettingsLoadDomain.RESOURCES to SettingsLoadState.LOADING,
        )))
        search(R.string.settings_gpu_resource_diag_enabled)
        compose.onNode(isToggleable()).performScrollTo().assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun developerSwitchRequiresWarningBeforeSaving() {
        LauncherPreferences.setDeveloperSettingsWarningDismissed(context, false)
        var selected: Pair<RamSaverOptimization, Boolean>? = null
        showSettings { actions ->
            actions.copy(onRamSaverOptimizationChanged = { optimization, enabled -> selected = optimization to enabled })
        }
        search(R.string.settings_ram_saver_shared_release)
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.settings_developer_warning_title)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(null, selected) }
        compose.onNodeWithText(context.getString(R.string.settings_developer_warning_confirm)).assertIsNotEnabled()
        compose.mainClock.advanceTimeBy(6_000)
        compose.onNodeWithText(context.getString(R.string.settings_developer_warning_confirm)).assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(RamSaverOptimization.SHARED_RELEASE_BUDGET to true, selected)
            assertTrue(navigations.isEmpty())
        }
    }

    @Test
    fun workshopImportDefaultsCanBeChangedInSearch() {
        var enabled: Boolean? = null
        showSettings { actions -> actions.copy(onWorkshopAutoImportAtlasDownscaleChanged = { enabled = it }) }
        search(R.string.settings_market_workshop_auto_import_defaults_title)
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(enabled != null)
            assertTrue(navigations.isEmpty())
        }
    }

    @Test
    fun cloudSaveModeCanBeChangedInSearch() {
        var selected: SteamCloudSaveMode? = null
        showSettings(SettingsScreenViewModel.UiState(steamCloudRefreshTokenConfigured = true)) { actions ->
            actions.copy(onSteamCloudSaveModeChanged = { selected = it })
        }
        search(R.string.settings_steam_cloud_save_settings_title)
        clickResult(R.string.settings_steam_cloud_save_mode_cloud_title)
        compose.runOnIdle {
            assertEquals(SteamCloudSaveMode.STEAM_CLOUD, selected)
            assertTrue(navigations.isEmpty())
        }
    }

    @Test
    fun cloudSaveModeRemainsDisabledUntilAccountAndSavesAreReady() {
        showSettings(SettingsScreenViewModel.UiState(
            steamCloudRefreshTokenConfigured = true,
            loadStates = mapOf(SettingsLoadDomain.STEAM_SAVES to SettingsLoadState.LOADING),
        ))
        search(R.string.settings_steam_cloud_save_settings_title)
        compose.onNodeWithText(context.getString(R.string.settings_steam_cloud_save_mode_cloud_title))
            .performScrollTo().assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun dedicatedFeatureResultOpensFeatureDirectlyRatherThanCategory() {
        showSettings()
        search(R.string.settings_feedback_entry_subscriptions)
        clickResult(R.string.settings_feedback_entry_subscriptions)
        compose.runOnIdle { assertEquals(listOf(Route.FeedbackSubscriptions), navigations) }
    }

    @Test
    fun exportLogsResultUsesFileExportRatherThanShareOrNavigation() {
        var exports = 0
        var shares = 0
        showSettings { actions ->
            actions.copy(onExportLogsToFile = { exports++ }, onExportLogs = { shares++ })
        }
        search(R.string.settings_export_error_logs)
        clickResult(R.string.settings_export_error_logs)
        compose.runOnIdle {
            assertEquals(1, exports)
            assertEquals(0, shares)
            assertTrue(navigations.isEmpty())
        }
    }

    @Test
    fun baiduCredentialsAreEmbeddedRatherThanNavigating() {
        assertEmbeddedDetail(R.string.settings_baidu_translation_credentials_title, "baidu editor")
    }

    @Test
    fun mobileGluesSettingsAreEmbeddedRatherThanNavigating() {
        assertEmbeddedDetail(R.string.settings_mobileglues_entry_title, "mobileglues editor")
    }

    @Test
    fun compatibilitySettingsAreEmbeddedRatherThanNavigating() {
        assertEmbeddedDetail(R.string.compat_settings_title, "compatibility editor")
    }

    @Test
    fun llmSettingsAreEmbeddedRatherThanNavigating() {
        assertEmbeddedDetail(R.string.settings_llm_title, "llm editor")
    }

    @Test
    fun mtsCacheOptimizationsCanBeChangedWithoutNavigating() {
        var changed: MtsCacheStartupOptimization? = null
        showSettings { actions ->
            actions.copy(onMtsCacheOptimizationChanged = { optimization, _ -> changed = optimization })
        }
        search(R.string.settings_mts_cache_optimizations_title)
        compose.onAllNodes(isToggleable())[0].performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(MtsCacheStartupOptimization.CLASS_FINDER_SCAN_CACHE, changed)
            assertTrue(navigations.isEmpty())
        }
    }

    @Test
    fun cloudSyncBlacklistExpandsAndSavesWithoutNavigating() {
        var selectedPath: String? = null
        showSettings(SettingsScreenViewModel.UiState(
            steamCloudRefreshTokenConfigured = true,
            steamCloudSaveMode = SteamCloudSaveMode.STEAM_CLOUD,
            steamCloudSyncBlacklistCandidates = listOf("preferences/test.json"),
        )) { actions ->
            actions.copy(onSteamCloudSyncBlacklistPathChanged = { path, _ -> selectedPath = path })
        }
        search(R.string.settings_steam_cloud_save_settings_title)
        clickResult(R.string.settings_steam_cloud_sync_blacklist_title)
        compose.onNodeWithText("preferences/test.json").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("preferences/test.json", selectedPath)
            assertTrue(navigations.isEmpty())
        }
    }

    private fun assertEmbeddedDetail(@StringRes title: Int, editor: String) {
        showSettings { actions ->
            actions.copy(details = SettingsSearchDetailContent(
                baiduTranslation = { Text("baidu editor") },
                mobileGlues = { Text("mobileglues editor") },
                compatibility = { Text("compatibility editor") },
                llm = { Text("llm editor") },
            ))
        }
        search(title)
        compose.onNodeWithText(editor).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertTrue(navigations.isEmpty()) }
    }

    @Test
    fun changingSearchDoesNotCarryOpenDialogToAnotherResult() {
        showSettings()
        search(R.string.settings_theme_mode_title)
        clickResult(R.string.settings_theme_mode_title)
        compose.onNode(isDialog()).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.main_folder_dialog_confirm)).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement(context.getString(R.string.settings_app_icon_title))
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.onNode(isDialog()).assertDoesNotExist()
        clickResult(R.string.settings_app_icon_title)
        compose.onNode(isDialog()).assertIsDisplayed()
        compose.runOnIdle { assertTrue(navigations.isEmpty()) }
    }

    private fun showSettings(
        uiState: SettingsScreenViewModel.UiState = SettingsScreenViewModel.UiState(playerName = "player"),
        stateProvider: () -> SettingsScreenViewModel.UiState = { uiState },
        configure: (SettingsSearchActions) -> SettingsSearchActions = { it },
    ) {
        compose.setContent {
            MaterialTheme {
                SettingsContent(stateProvider(), configure)
            }
        }
    }

    @Composable
    private fun SettingsContent(
        uiState: SettingsScreenViewModel.UiState,
        configure: (SettingsSearchActions) -> SettingsSearchActions,
    ) {
        val viewModel = remember { SettingsScreenViewModel() }
        val actions = settingsSearchActions(viewModel, requireNotNull(LocalActivity.current), navigations::add)
            .copy(details = SettingsSearchDetailContent(
                baiduTranslation = { Text("baidu editor") },
                mobileGlues = { Text("mobileglues editor") },
                compatibility = { Text("compatibility editor") },
                llm = { Text("llm editor") },
            ))
        LauncherSettingsScreenContent(
            uiState = uiState,
            onOpenSettingsRoute = navigations::add,
            searchActions = configure(actions),
        )
    }

    private fun search(@StringRes title: Int) {
        compose.onNode(hasSetTextAction()).performTextInput(context.getString(title))
        compose.onNode(hasSetTextAction()).performImeAction()
    }

    private fun clickResult(@StringRes title: Int) {
        compose.onNodeWithText(context.getString(title)).performScrollTo().performClick()
    }

    private fun slider() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
}
