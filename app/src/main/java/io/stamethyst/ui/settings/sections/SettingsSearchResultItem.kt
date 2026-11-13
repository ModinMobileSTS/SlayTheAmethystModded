package io.stamethyst.ui.settings.sections

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.stamethyst.R
import io.stamethyst.config.RamSaverOptimization
import io.stamethyst.config.MtsCacheStartupOptimization
import io.stamethyst.config.SteamCloudSaveMode
import io.stamethyst.navigation.Route
import io.stamethyst.ui.settings.common.SettingsActionListItem
import io.stamethyst.ui.settings.common.SettingsSearchEntry
import io.stamethyst.ui.settings.common.SettingsDependencyContent
import io.stamethyst.ui.settings.common.LocalSettingsControlsEnabled
import io.stamethyst.ui.settings.common.settingsControlEnabled
import io.stamethyst.ui.settings.core.SettingsLoadDomain
import io.stamethyst.ui.settings.core.SettingsScreenViewModel
import io.stamethyst.ui.settings.core.SettingsMtsCacheOptimizationControls
import io.stamethyst.ui.settings.steamcloud.SteamCloudSaveSettingsContent
import io.stamethyst.ui.settings.steamcloud.SteamCloudSyncBlacklistSettingsContent

internal data class SettingsSearchActions(
    val details: SettingsSearchDetailContent,
    val appearance: AppearanceSettingsActions,
    val update: UpdateSettingsActions,
    val input: InputSettingsActions,
    val display: GameplayDisplaySettingsActions,
    val performance: PerformanceSettingsActions,
    val market: MarketSettingsActions,
    val acceleration: AccelerationSettingsActions,
    val advancedRender: AdvancedRenderSettingsActions,
    val steamCloud: SteamCloudSettingsActions,
    val developerRuntime: DeveloperRuntimeSettingsActions,
    val togetherInSpire: TogetherInSpireSettingsActions,
    val status: StatusSettingsActions,
    val onRamSaverOptimizationChanged: (RamSaverOptimization, Boolean) -> Unit,
    val onLocalTestCloudControlEnabledChanged: (Boolean) -> Unit,
    val onOpenBasicTutorial: () -> Unit,
    val onPlayerNameChanged: (String) -> Boolean,
    val onApplyModFileNameAliases: () -> Unit,
    val onResetLauncherSettingsToDefaults: () -> Unit,
    val onImportMods: () -> Unit,
    val onExportMods: () -> Unit,
    val onImportSaves: () -> Unit,
    val onExportSaves: () -> Unit,
    val onExportLogs: () -> Unit,
    val onExportLogsToFile: () -> Unit,
    val onImportJar: () -> Unit,
    val onWorkshopAutoImportAtlasDownscaleChanged: (Boolean) -> Unit,
    val onWorkshopAutoImportAtlasDownscaleMaxEdgeChanged: (Int) -> Unit,
    val onSteamCloudSaveModeChanged: (SteamCloudSaveMode) -> Unit,
    val onSteamCloudSyncBlacklistPathChanged: (String, Boolean) -> Unit,
    val onMtsCacheOptimizationChanged: (MtsCacheStartupOptimization, Boolean) -> Unit,
)

internal data class SettingsSearchDetailContent(
    val baiduTranslation: @Composable () -> Unit,
    val mobileGlues: @Composable () -> Unit,
    val compatibility: @Composable () -> Unit,
    val llm: @Composable () -> Unit,
)

/** Reuse category controls so search shares their values, persistence, dialogs and validation. */
@Composable
internal fun SettingsSearchResultItem(
    entry: SettingsSearchEntry,
    uiState: SettingsScreenViewModel.UiState,
    actions: SettingsSearchActions?,
    onOpenSettingsRoute: (Route) -> Unit,
) {
    CompositionLocalProvider(
        LocalSettingsControlsEnabled provides settingsControlEnabled(!uiState.busy),
    ) {
        SettingsSearchResultControl(entry, uiState, actions, onOpenSettingsRoute)
    }
}

@Composable
private fun SettingsSearchResultControl(
    entry: SettingsSearchEntry,
    uiState: SettingsScreenViewModel.UiState,
    actions: SettingsSearchActions?,
    onOpenSettingsRoute: (Route) -> Unit,
) {
    val title = entry.titleResId
    var showSyncBlacklist by rememberSaveable { mutableStateOf(false) }
    when {
        actions == null -> SettingsActionListItem(
            title = stringResource(title),
            supportingText = entry.subtitleResId?.let { stringResource(it) },
            enabled = !uiState.busyOperation.locksInteraction(uiState.busy),
            onClick = { onOpenSettingsRoute(entry.route) },
        )
        title in appearanceTitles -> SettingsAppearanceSection(uiState, actions.appearance, title)
        title == R.string.update_section_title -> SettingsUpdateSection(uiState, actions.update, title)
        title == R.string.update_auto_check_enabled -> SettingsUpdateSection(uiState, actions.update, title)
        title == R.string.update_mirror_title -> SettingsUpdateMirrorSetting(uiState, actions.update)
        title == R.string.settings_mod_alias_apply_file_names_action -> SettingsApplyModFileNameAliasesAction(
            uiState.busy, actions.onApplyModFileNameAliases,
        )
        title == R.string.settings_player_name_title -> SettingsPlayerNameAction(
            uiState, actions.onPlayerNameChanged,
        )
        title in displayTitles -> SettingsGameplayDisplaySection(uiState, actions.display, title)
        entry.route == Route.SettingsGame -> SettingsInputSection(uiState, actions.input, title)
        title == R.string.settings_mts_cache_optimizations_title -> SettingsMtsCacheOptimizationControls(
            uiState, actions.onMtsCacheOptimizationChanged,
        )
        entry.route == Route.SettingsPerformance -> SettingsPerformanceSection(uiState, actions.performance, title)
        title in steamCloudTitles -> SettingsSteamCloudSection(uiState, actions.steamCloud, title)
        title in steamNetworkTitles -> SteamNetworkSection(uiState, actions.steamCloud, title)
        title == R.string.settings_steam_cloud_save_settings_title ->
            SettingsDependencyContent(uiState, SettingsLoadDomain.STEAM_ACCOUNT, SettingsLoadDomain.STEAM_SAVES) {
                SteamCloudSaveSettingsContent(
                    uiState = uiState,
                    onSteamCloudSaveModeChanged = actions.onSteamCloudSaveModeChanged,
                    onOpenSteamCloudSyncBlacklistSettings = { showSyncBlacklist = !showSyncBlacklist },
                )
                if (showSyncBlacklist) SteamCloudSyncBlacklistSettingsContent(
                    uiState, actions.onSteamCloudSyncBlacklistPathChanged,
                )
            }
        title == R.string.settings_baidu_translation_credentials_title ->
            SettingsDependencyContent(uiState, SettingsLoadDomain.TRANSLATION) {
                actions.details.baiduTranslation()
            }
        title == R.string.settings_mobileglues_entry_title -> actions.details.mobileGlues()
        title == R.string.compat_settings_title -> actions.details.compatibility()
        title == R.string.settings_llm_title -> actions.details.llm()
        title == R.string.settings_steam_cloud_title -> SteamAccountSection(uiState, actions.steamCloud)
        title in marketTitles -> SettingsMarketSection(uiState, actions.market, title)
        title == R.string.settings_market_workshop_auto_import_defaults_title -> SettingsWorkshopAutoImportDefaultsSection(
            uiState,
            actions.onWorkshopAutoImportAtlasDownscaleChanged,
            actions.onWorkshopAutoImportAtlasDownscaleMaxEdgeChanged,
        )
        title in developerRuntimeTitles -> SettingsDeveloperRuntimeSection(uiState, actions.developerRuntime, title)
        title == R.string.settings_together_in_spire_title -> SettingsTogetherInSpireSection(uiState, actions.togetherInSpire)
        title == R.string.settings_easytier_title -> SettingsEasyTierSection(uiState)
        title == R.string.settings_cloud_control_test_enabled -> SettingsLocalTestCloudControlSetting(
            uiState, actions.onLocalTestCloudControlEnabledChanged,
        )
        title in ramSaverOptimizationTitles -> SettingsRamSaverOptimizationSetting(
            uiState, ramSaverOptimizationTitles.getValue(title), actions.onRamSaverOptimizationChanged,
        )
        title == R.string.settings_developer_acceleration_strategy_title ->
            SettingsAccelerationSection(uiState, actions.acceleration)
        title in advancedRenderTitles -> SettingsAdvancedRenderSection(uiState, actions.advancedRender, title)
        title == R.string.settings_render_surface_backend_title ->
            SettingsDependencyContent(uiState, SettingsLoadDomain.RENDERER) {
                SettingsRenderSurfaceBackendSetting(uiState, actions.advancedRender)
            }
        title in statusTitles -> SettingsStatusSection(uiState, actions.status, title)
        title == R.string.settings_reset_defaults_title -> SettingsResetDefaultsSection(
            uiState.busy, actions.onResetLauncherSettingsToDefaults,
        )
        title in resourceDialogTitles -> SettingsImportSection(
            busy = uiState.busy,
            onImportJar = actions.onImportJar,
            onReinstallResourcePack = {},
            onImportMods = actions.onImportMods,
            onExportMods = actions.onExportMods,
            onImportSaves = actions.onImportSaves,
            onExportSaves = actions.onExportSaves,
            onExportLogs = actions.onExportLogs,
            onExportLogsToFile = actions.onExportLogsToFile,
            settingTitleResId = title,
        )
        title == R.string.settings_author_info_title -> SettingsAuthorInfoSection()
        else -> SettingsActionListItem(
            title = stringResource(title),
            supportingText = entry.subtitleResId?.let { stringResource(it) },
            enabled = !uiState.busy,
            onClick = {
                when (title) {
                    R.string.settings_basic_tutorial_action -> actions.onOpenBasicTutorial()
                    R.string.settings_first_run_reopen_action -> onOpenSettingsRoute(Route.FirstRunSetup)
                    R.string.settings_feedback_entry_new -> onOpenSettingsRoute(Route.Feedback)
                    R.string.settings_feedback_entry_subscriptions -> onOpenSettingsRoute(Route.FeedbackSubscriptions)
                    R.string.settings_feedback_entry_issue_browser -> onOpenSettingsRoute(Route.FeedbackIssueBrowser)
                    R.string.settings_reimport_sts_jar_title -> actions.onImportJar()
                    R.string.settings_export_all_mods -> actions.onExportMods()
                    R.string.settings_import_saves -> actions.onImportSaves()
                    R.string.settings_export_saves -> actions.onExportSaves()
                    R.string.settings_export_error_logs -> actions.onExportLogsToFile()
                    else -> onOpenSettingsRoute(entry.route)
                }
            },
        )
    }
}

private val appearanceTitles = setOf(
    R.string.settings_theme_mode_title,
    R.string.settings_app_icon_title,
    R.string.settings_theme_color_title,
    R.string.settings_boot_overlay_style_title,
    R.string.settings_boot_overlay_custom_image_title,
    R.string.settings_loading_animation_title,
    R.string.settings_chrome_background_opacity_title,
    R.string.settings_launcher_blur_disabled_title,
)

private val displayTitles = setOf(
    R.string.settings_game_display_size_title,
    R.string.settings_gameplay_larger_ui_enabled,
    R.string.settings_hide_enemy_panel_while_targeting,
    R.string.settings_gameplay_font_scale_title,
    R.string.settings_keep_screen_on_timeout_title,
)

private val steamCloudTitles = setOf(
    R.string.settings_steam_cloud_sync_disabled_title,
    R.string.settings_steam_cloud_auto_launch_after_sync_title,
)

private val steamNetworkTitles = setOf(
    R.string.settings_steam_cloud_watt_acceleration_enabled_title,
    R.string.settings_steam_cloud_clear_network_cache_title,
)

private val marketTitles = setOf(
    R.string.settings_market_concurrent_downloads_title,
    R.string.settings_market_download_threads_title,
    R.string.settings_market_workshop_acceleration_enabled_title,
    R.string.settings_market_workshop_language_title,
    R.string.settings_market_workshop_auto_import_enabled_title,
    R.string.settings_market_clear_preview_cache_title,
)

private val developerRuntimeTitles = setOf(
    R.string.settings_sustained_performance_enabled,
    R.string.settings_system_game_mode_title,
    R.string.settings_boot_overlay_manual_enabled,
    R.string.settings_compendium_upgrade_touch_fix_enabled,
    R.string.settings_gpu_resource_diag_enabled,
)

private val advancedRenderTitles = setOf(
    R.string.settings_renderer_backend_title,
    R.string.settings_gpu_resource_guardian_title,
    R.string.settings_jvm_tiered_stop_at_level_title,
    R.string.settings_jvm_compressed_pointers_enabled,
    R.string.settings_jvm_string_dedup_enabled,
)

private val statusTitles = setOf(
    R.string.refresh_diagnostics_title,
    R.string.refresh_diagnostics_latest,
    R.string.settings_view_full_status,
    R.string.settings_view_log_paths,
    R.string.settings_unplayable_mods_entry_title,
    R.string.settings_lwjgl_debug_enabled,
    R.string.settings_preload_all_jre_enabled,
    R.string.settings_logcat_capture_enabled,
    R.string.settings_launcher_logcat_capture_enabled,
    R.string.settings_jvm_logcat_mirror_enabled,
    R.string.settings_developer_clear_junk_files_title,
    R.string.settings_agent_workspace_export_title,
)

private val ramSaverOptimizationTitles = mapOf(
    R.string.settings_ram_saver_shared_release to RamSaverOptimization.SHARED_RELEASE_BUDGET,
    R.string.settings_ram_saver_background_decode to RamSaverOptimization.BACKGROUND_DECODE,
    R.string.settings_ram_saver_visual_debug to RamSaverOptimization.VISUAL_RELEASE_DEBUG,
)

private val resourceDialogTitles = setOf(
    R.string.settings_mod_operations,
    R.string.settings_save_operations,
    R.string.settings_log_operations,
)
