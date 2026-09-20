package io.stamethyst.backend.diag

import android.content.Context
import android.content.res.Configuration
import io.stamethyst.R
import io.stamethyst.backend.mods.CompatibilitySettings
import io.stamethyst.backend.mods.RuntimeDownscaleMaterialPolicy
import io.stamethyst.backend.mods.RuntimeTextureAtlasDownscaleQuality
import io.stamethyst.backend.render.RendererSelectionMode
import io.stamethyst.backend.render.VirtualResolutionMode
import io.stamethyst.backend.resources.ResourcePackInspection
import io.stamethyst.backend.resources.ResourcePackStore
import io.stamethyst.backend.update.UpdateSource
import io.stamethyst.backend.workshop.BaiduTranslationCredentialsRepository
import io.stamethyst.backend.workshop.SteamLanguagePreference
import io.stamethyst.backend.workshop.WorkshopBrowseSort
import io.stamethyst.config.BackBehavior
import io.stamethyst.config.BootOverlayAnimation
import io.stamethyst.config.BootOverlayImageConfig
import io.stamethyst.config.BootOverlayImageMode
import io.stamethyst.config.BootOverlayStyle
import io.stamethyst.config.CardPlayOptimizationMode
import io.stamethyst.config.GpuResourceGuardianMode
import io.stamethyst.config.LauncherConfig
import io.stamethyst.config.LauncherIconMode
import io.stamethyst.config.LauncherThemeColor
import io.stamethyst.config.LauncherThemeMode
import io.stamethyst.config.RenderSurfaceBackend
import io.stamethyst.config.RichPresenceDisplayPreferences
import io.stamethyst.config.RichPresencePrefix
import io.stamethyst.config.SpecialKeyInputMode
import io.stamethyst.config.SteamCloudSaveMode
import io.stamethyst.config.TouchMouseInteractionMode
import io.stamethyst.config.TouchscreenInputMode
import java.util.Locale

/**
 * Resolves every user-facing launcher setting once, producing both the stable English
 * machine key/value view and a Simplified-Chinese, human-readable view. Building both
 * languages in a single pass keeps the diagnostics export cheap: both files are rendered
 * from the same snapshot instead of re-reading preferences twice.
 */
internal fun captureLauncherSettingsSections(
    context: Context,
    resourcePackInspection: ResourcePackInspection? = null,
): List<LauncherSettingsDiagnosticsSection> {
    val zh = chineseContext(context)
    return listOf(
        appearanceSection(context, zh),
        updateSection(context, zh),
        resourcePackSection(context, resourcePackInspection),
        playerSection(context, zh),
        renderingSection(context, zh),
        inputSection(context, zh),
        steamServicesSection(context, zh),
        developerRuntimeSection(context, zh),
        advancedRenderSection(context, zh),
        mobileGluesSection(context, zh),
        statusLogsSection(context, zh),
        compatibilitySection(context, zh),
    )
}

internal fun chineseContext(context: Context): Context {
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocale(Locale.SIMPLIFIED_CHINESE)
    return context.createConfigurationContext(configuration)
}

private fun section(
    titleEn: String,
    titleZh: String,
    fields: List<LauncherSettingsDiagnosticsField>,
): LauncherSettingsDiagnosticsSection =
    LauncherSettingsDiagnosticsSection(titleEn = titleEn, titleZh = titleZh, fields = fields)

private fun field(
    key: String,
    labelZh: String,
    enValue: String,
    zhValue: String,
): LauncherSettingsDiagnosticsField =
    LauncherSettingsDiagnosticsField(
        key = key,
        labelZh = labelZh,
        enValue = normalizeFieldValue(enValue),
        zhValue = normalizeFieldValue(zhValue),
    )

private fun boolField(key: String, labelRes: Int, value: Boolean, zh: Context): LauncherSettingsDiagnosticsField =
    field(key, zh.getString(labelRes), value.toString(), if (value) "是" else "否")

private fun boolField(key: String, labelZh: String, value: Boolean): LauncherSettingsDiagnosticsField =
    field(key, labelZh, value.toString(), if (value) "是" else "否")

private fun textField(
    key: String,
    labelRes: Int,
    enValue: String,
    zhValue: String,
    zh: Context,
): LauncherSettingsDiagnosticsField =
    field(key, zh.getString(labelRes), enValue, zhValue)

private fun textField(key: String, labelZh: String, value: String): LauncherSettingsDiagnosticsField =
    field(key, labelZh, value, value)

private fun persisted(name: String, persistedValue: Any): String = "$name ($persistedValue)"

private fun appearanceSection(
    context: Context,
    zh: Context,
): LauncherSettingsDiagnosticsSection {
    val themeMode = LauncherConfig.readThemeMode(context)
    val themeColor = LauncherConfig.readThemeColor(context)
    val iconMode = LauncherConfig.readLauncherIconMode(context)
    val overlayStyle = LauncherConfig.readBootOverlayStyle(context)
    val animation = LauncherConfig.readBootOverlayAnimation(context)
    val imageConfig = LauncherConfig.readBootOverlayImageConfig(context)
    val opacity = LauncherConfig.readChromeBackgroundOpacity(context)
    val opacityPercent = "${(opacity * 100f).toInt()}%"

    return section(
        "Launcher / Appearance",
        "启动器 / 外观",
        listOf(
            field(
                "theme.mode",
                zh.getString(R.string.settings_theme_mode_title),
                persisted(themeMode.name, themeMode.persistedValue),
                themeModeZh(zh, themeMode),
            ),
            field(
                "theme.color",
                zh.getString(R.string.settings_theme_color_title),
                persisted(themeColor.name, themeColor.persistedValue),
                themeColorZh(zh, themeColor),
            ),
            field(
                "launcher.iconMode",
                zh.getString(R.string.settings_app_icon_title),
                persisted(iconMode.name, iconMode.persistedValue),
                iconModeZh(zh, iconMode),
            ),
            textField(
                "chrome.backgroundOpacity",
                R.string.settings_chrome_background_opacity_title,
                opacityPercent,
                opacityPercent,
                zh,
            ),
            field(
                "bootOverlayStyle",
                zh.getString(R.string.settings_boot_overlay_style_title),
                overlayStyle.persistedValue,
                bootOverlayStyleZh(zh, overlayStyle),
            ),
            field(
                "bootOverlayAnimation",
                zh.getString(R.string.settings_loading_animation_title),
                animation.persistedValue,
                bootOverlayAnimationZh(zh, animation),
            ),
            textField(
                "bootOverlayCustomImage",
                R.string.settings_boot_overlay_custom_image_title,
                bootOverlayImageModeEn(imageConfig),
                bootOverlayImageModeZh(imageConfig),
                zh,
            ),
            boolField("showModFileName", "显示 Mod 文件名", LauncherConfig.readShowModFileName(context)),
            boolField("firstRunSetupCompleted", "已完成首次引导", LauncherConfig.isFirstRunSetupCompleted(context)),
        )
    )
}

private fun updateSection(
    context: Context,
    zh: Context,
): LauncherSettingsDiagnosticsSection {
    val mirrorId = LauncherConfig.readPreferredUpdateMirrorId(context)
    return section(
        "Launcher / Updates",
        "启动器 / 更新",
        listOf(
            boolField(
                "autoCheckUpdatesEnabled",
                R.string.update_auto_check_enabled,
                LauncherConfig.isAutoCheckUpdatesEnabled(context),
                zh,
            ),
            field(
                "preferredUpdateMirrorId",
                zh.getString(R.string.update_mirror_title),
                mirrorId,
                UpdateSource.fromPersistedValue(mirrorId)?.displayName ?: mirrorId,
            ),
        )
    )
}

private fun resourcePackSection(
    context: Context,
    resourcePackInspection: ResourcePackInspection?,
): LauncherSettingsDiagnosticsSection {
    val resourcePack = resourcePackInspection ?: ResourcePackStore.inspect(context)
    return section(
        "Launcher / Resource pack",
        "启动器 / 资源包",
        listOf(
            textField("resourcePack.ready", "资源包就绪", resourcePack.ready.toString()),
            textField("resourcePack.packId", "资源包 ID", resourcePack.packId ?: "none"),
            textField("resourcePack.version", "资源包版本", resourcePack.version ?: "none"),
            textField(
                "resourcePack.generation",
                "资源包生成目录",
                resourcePack.generationDir?.absolutePath ?: "none",
            ),
            textField("resourcePack.state", "资源包状态", resourcePack.state ?: "none"),
            textField("resourcePack.issues", "资源包问题", resourcePack.issues.joinToString("; ")),
            textField(
                "resourcePack.legacyPaths",
                "资源包旧路径",
                resourcePack.legacyPaths.joinToString("|"),
            ),
        )
    )
}

private fun playerSection(
    context: Context,
    zh: Context,
): LauncherSettingsDiagnosticsSection {
    val playerName = LauncherConfig.readPlayerName(context)
    return section(
        "Game / Player name",
        "游戏 / 玩家名",
        listOf(
            textField(
                "player.name",
                R.string.settings_player_name_dialog_title,
                playerName,
                playerName,
                zh,
            ),
        )
    )
}

private fun renderingSection(
    context: Context,
    zh: Context,
): LauncherSettingsDiagnosticsSection {
    val targetFps = LauncherConfig.readTargetFps(context)
    val automaticFps = LauncherConfig.isTargetFpsAutomatic(context)
    val renderScale = LauncherConfig.readRenderScale(context)
    val fontScale = LauncherConfig.readGameplayFontScale(context)
    val virtualResolution = LauncherConfig.readVirtualResolutionMode(context)
    val timeoutMinutes = LauncherConfig.readKeepScreenOnTimeoutMinutes(context)

    return section(
        "Game / Rendering",
        "游戏 / 渲染与显示",
        listOf(
            field(
                "targetFps",
                zh.getString(R.string.settings_target_fps_title),
                targetFps.toString(),
                if (automaticFps) "${targetFps}（自动）" else targetFps.toString(),
            ),
            boolField(
                "nonRecommendedFpsEnabled",
                R.string.settings_non_recommended_fps_enabled,
                LauncherConfig.isNonRecommendedFpsEnabled(context),
                zh,
            ),
            field(
                "framePacingMode",
                zh.getString(R.string.settings_frame_pacing_mode_title),
                LauncherConfig.readFramePacingMode(context).persistedValue,
                zh.getString(R.string.settings_frame_pacing_mode_title) + ": " +
                    LauncherConfig.readFramePacingMode(context).persistedValue,
            ),
            textField(
                "render.scale",
                R.string.settings_render_scale_title,
                LauncherConfig.formatRenderScale(renderScale),
                LauncherConfig.formatRenderScale(renderScale),
                zh,
            ),
            field(
                "virtualResolutionMode",
                zh.getString(R.string.settings_virtual_resolution_mode_title),
                persisted(virtualResolution.name, virtualResolution.persistedValue),
                virtualResolutionModeZh(zh, virtualResolution),
            ),
            textField(
                "gameplayFontScale",
                R.string.settings_gameplay_font_scale_title,
                LauncherConfig.formatGameplayFontScale(fontScale),
                LauncherConfig.formatGameplayFontScale(fontScale),
                zh,
            ),
            boolField(
                "largerUiEnabled",
                R.string.settings_gameplay_larger_ui_enabled,
                LauncherConfig.readGameplayLargerUiEnabled(context),
                zh,
            ),
            boolField(
                "avoidDisplayCutout",
                R.string.settings_display_cutout_enabled,
                LauncherConfig.isDisplayCutoutAvoidanceEnabled(context),
                zh,
            ),
            boolField(
                "cropScreenBottom",
                R.string.settings_crop_screen_bottom_enabled,
                LauncherConfig.isScreenBottomCropEnabled(context),
                zh,
            ),
            boolField(
                "ramSaverEnabled",
                R.string.settings_ram_saver_title,
                LauncherConfig.isRamSaverEnabled(context),
                zh,
            ),
            boolField(
                "mtsPatchCacheEnabled",
                R.string.settings_mts_patch_cache_title,
                LauncherConfig.isMtsPatchCacheEnabled(context),
                zh,
            ),
            boolField(
                "showGamePerformanceOverlay",
                R.string.settings_performance_overlay_enabled,
                LauncherConfig.isGamePerformanceOverlayEnabled(context),
                zh,
            ),
            field(
                "keepScreenOnTimeoutMinutes",
                zh.getString(R.string.settings_keep_screen_on_timeout_title),
                timeoutMinutes.toString(),
                keepScreenOnTimeoutZh(zh, timeoutMinutes),
            ),
        )
    )
}

private fun inputSection(
    context: Context,
    zh: Context,
): LauncherSettingsDiagnosticsSection {
    val backBehavior = LauncherConfig.readBackBehavior(context)
    val touchscreenMode = LauncherConfig.readTouchscreenInputMode(context)
    val specialKeyMode = LauncherConfig.readSpecialKeyInputMode(context)
    val touchMouseMode = LauncherConfig.readTouchMouseInteractionMode(context)
    val cardPlayMode = LauncherConfig.readCardPlayOptimizationMode(context)
    val floatingButtons = LauncherConfig.readFloatingToolButtons(context).sorted()
    val floatingButtonsText = floatingButtons.joinToString(",").ifBlank { "none" }

    return section(
        "Game / Input and interaction",
        "游戏 / 输入与交互",
        listOf(
            field(
                "back.behavior",
                zh.getString(R.string.settings_back_behavior_title),
                persisted(backBehavior.name, backBehavior.persistedValue),
                backBehaviorZh(zh, backBehavior),
            ),
            boolField("back.immediateExit", "返回键立即退出", LauncherConfig.readBackImmediateExit(context)),
            field(
                "specialKeyInputMode",
                zh.getString(R.string.settings_special_key_input_mode_title),
                specialKeyMode.persistedValue,
                specialKeyInputModeZh(zh, specialKeyMode),
            ),
            boolField(
                "showFloatingMouseWindow",
                "显示悬浮鼠标窗",
                LauncherConfig.readShowFloatingMouseWindow(context),
            ),
            field(
                "touchMouseInteractionMode",
                zh.getString(R.string.settings_touch_mouse_interaction_label),
                touchMouseMode.persistedValue,
                touchMouseInteractionModeZh(zh, touchMouseMode),
            ),
            field(
                "cardPlayOptimizationMode",
                zh.getString(R.string.settings_card_play_optimization_title),
                persisted(cardPlayMode.name, cardPlayMode.persistedValue),
                cardPlayOptimizationModeZh(zh, cardPlayMode),
            ),
            boolField(
                "builtInSoftKeyboardEnabled",
                R.string.settings_built_in_soft_keyboard_enabled,
                LauncherConfig.isBuiltInSoftKeyboardEnabled(context),
                zh,
            ),
            textField(
                "floatingToolButtons",
                R.string.settings_floating_tool_buttons_title,
                floatingButtonsText,
                floatingButtonsText,
                zh,
            ),
            boolField(
                "hapticFeedbackEnabled",
                R.string.settings_haptic_feedback_enabled,
                LauncherConfig.isHapticFeedbackEnabled(context),
                zh,
            ),
            boolField(
                "autoSwitchLeftAfterRightClick",
                R.string.settings_auto_switch_left_enabled,
                LauncherConfig.readAutoSwitchLeftAfterRightClick(context),
                zh,
            ),
            boolField(
                "touchDoubleClickAsRightClick",
                R.string.settings_touch_double_click_as_right_click_enabled,
                LauncherConfig.readTouchDoubleClickAsRightClick(context),
                zh,
            ),
            boolField(
                "ignoreLongPressRightClickWhilePlayingCard",
                R.string.settings_ignore_long_press_right_click_while_playing_card_enabled,
                LauncherConfig.readIgnoreLongPressRightClickWhilePlayingCard(context),
                zh,
            ),
            field(
                "touchscreenInputMode",
                zh.getString(R.string.settings_touchscreen_mode_title),
                touchscreenMode.persistedValue,
                touchscreenInputModeZh(zh, touchscreenMode),
            ),
            boolField("touchscreenEnabled", "触屏输入启用", touchscreenMode.touchscreenEnabled),
            boolField("nativeTouchscreenEnabled", "原生触屏输入启用", touchscreenMode.touchscreenEnabled),
            boolField(
                "touchIndicatorEnabled",
                R.string.settings_touch_indicator_enabled,
                touchscreenMode.touchscreenEnabled && LauncherConfig.readTouchIndicatorEnabled(context),
                zh,
            ),
            field(
                "touchscreenPolicy",
                "触屏策略",
                if (touchscreenMode.nativeTouchscreenAllowlistEnabled) "vanilla_allowlist" else "global",
                if (touchscreenMode.nativeTouchscreenAllowlistEnabled) "原版白名单" else "全局",
            ),
            boolField("mobileHudEnabled", "移动端 UI", LauncherConfig.readMobileHudEnabled(context)),
            boolField(
                "compendiumUpgradeTouchFixEnabled",
                R.string.settings_compendium_upgrade_touch_fix_enabled,
                LauncherConfig.readCompendiumUpgradeTouchFixEnabled(context),
                zh,
            ),
        )
    )
}

private fun steamServicesSection(
    context: Context,
    zh: Context,
): LauncherSettingsDiagnosticsSection {
    val saveMode = LauncherConfig.readSteamCloudSaveMode(context)
    val language = SteamLanguagePreference.fromStorageValue(
        LauncherConfig.readWorkshopSteamLanguage(context)
    )
    val defaultSort = WorkshopBrowseSort.fromStorageValue(
        LauncherConfig.readWorkshopDefaultSort(context)
    )
    val blacklist = LauncherConfig.readSteamCloudSyncBlacklistPaths(context).sorted()
    val richPresence = LauncherConfig.readRichPresenceDisplayPreferences(context)
    val atlasDownscaleEnabled = LauncherConfig.isWorkshopAutoImportAtlasDownscaleEnabled(context)
    val maxEdgePx = LauncherConfig.readWorkshopAutoImportAtlasDownscaleMaxEdgePx(context)
    val baiduConfigured = BaiduTranslationCredentialsRepository(context).hasConfiguredCredentials()

    return section(
        "Steam services / Cloud, market and workshop",
        "Steam 服务 / 云存档、市场与创意工坊",
        listOf(
            boolField(
                "steamCloudWattAccelerationEnabled",
                R.string.settings_steam_cloud_watt_acceleration_enabled_title,
                LauncherConfig.isSteamCloudWattAccelerationEnabled(context),
                zh,
            ),
            boolField(
                "steamCloudAutoLaunchAfterSyncEnabled",
                R.string.settings_steam_cloud_auto_launch_after_sync_title,
                LauncherConfig.isSteamCloudAutoLaunchAfterSyncEnabled(context),
                zh,
            ),
            field(
                "steamCloudSaveMode",
                zh.getString(R.string.settings_steam_cloud_save_settings_title),
                persisted(saveMode.name, saveMode.persistedValue),
                steamCloudSaveModeZh(zh, saveMode),
            ),
            textField(
                "steamCloudSyncBlacklistPaths",
                R.string.settings_steam_cloud_sync_blacklist_title,
                blacklist.joinToString(",").ifBlank { "none" },
                blacklist.joinToString(",").ifBlank { "none" },
                zh,
            ),
            boolField(
                "steamGamePresenceEnabled",
                R.string.settings_steam_services_presence_enabled_title,
                LauncherConfig.isSteamGamePresenceEnabled(context),
                zh,
            ),
            textField(
                "richPresenceDisplay",
                R.string.settings_steam_services_rich_presence_display_title,
                richPresenceEn(richPresence),
                richPresenceZh(zh, richPresence),
                zh,
            ),
            boolField(
                "steamAchievementSyncEnabled",
                R.string.settings_steam_services_achievement_enabled_title,
                LauncherConfig.isSteamAchievementSyncEnabled(context),
                zh,
            ),
            boolField(
                "achievementUnlockNotificationEnabled",
                R.string.settings_steam_services_achievement_notification_enabled_title,
                LauncherConfig.isAchievementUnlockNotificationEnabled(context),
                zh,
            ),
            textField(
                "workshopMaxConcurrentDownloads",
                R.string.settings_market_concurrent_downloads_title,
                LauncherConfig.readWorkshopMaxConcurrentDownloads(context).toString(),
                LauncherConfig.readWorkshopMaxConcurrentDownloads(context).toString(),
                zh,
            ),
            textField(
                "workshopDownloadThreads",
                R.string.settings_market_download_threads_title,
                LauncherConfig.readWorkshopDownloadThreads(context).toString(),
                LauncherConfig.readWorkshopDownloadThreads(context).toString(),
                zh,
            ),
            boolField(
                "workshopWattAccelerationEnabled",
                R.string.settings_market_workshop_acceleration_enabled_title,
                LauncherConfig.isWorkshopWattAccelerationEnabled(context),
                zh,
            ),
            field(
                "workshopSteamLanguage",
                zh.getString(R.string.settings_market_workshop_language_title),
                language.storageValue,
                language.displayName,
            ),
            field(
                "workshopDefaultSort",
                zh.getString(R.string.settings_market_workshop_default_sort_title),
                defaultSort.browseSortValue,
                defaultSort.displayName,
            ),
            boolField(
                "workshopAutoImportEnabled",
                R.string.settings_market_workshop_auto_import_enabled_title,
                LauncherConfig.isWorkshopAutoImportEnabled(context),
                zh,
            ),
            boolField(
                "workshopAutoImportAtlasDownscaleEnabled",
                R.string.settings_workshop_auto_import_defaults_atlas_enabled_title,
                atlasDownscaleEnabled,
                zh,
            ),
            textField(
                "workshopAutoImportAtlasDownscaleMaxEdgePx",
                R.string.settings_workshop_auto_import_defaults_atlas_level_title,
                maxEdgePx.toString(),
                maxEdgePx.toString(),
                zh,
            ),
            field(
                "baiduTranslationCredentialsConfigured",
                zh.getString(R.string.settings_baidu_translation_credentials_title),
                baiduConfigured.toString(),
                if (baiduConfigured) {
                    zh.getString(R.string.settings_baidu_translation_credentials_configured)
                } else {
                    zh.getString(R.string.settings_baidu_translation_credentials_not_configured)
                },
            ),
        )
    )
}

private fun developerRuntimeSection(
    context: Context,
    zh: Context,
): LauncherSettingsDiagnosticsSection {
    return section(
        "Developer / Runtime",
        "开发者 / 运行控制",
        listOf(
            boolField(
                "manualDismissBootOverlay",
                R.string.settings_boot_overlay_manual_enabled,
                LauncherConfig.readManualDismissBootOverlay(context),
                zh,
            ),
            boolField(
                "sustainedPerformanceModeEnabled",
                R.string.settings_sustained_performance_enabled,
                LauncherConfig.isSustainedPerformanceModeEnabled(context),
                zh,
            ),
            boolField(
                "togetherInSpireRouteLockEnabled",
                R.string.settings_together_in_spire_route_lock_enabled,
                LauncherConfig.isTogetherInSpireRouteLockEnabled(context),
                zh,
            ),
            boolField(
                "togetherInSpireEasyTierAutofillEnabled",
                R.string.settings_together_in_spire_autofill_enabled,
                LauncherConfig.isTogetherInSpireEasyTierAutofillEnabled(context),
                zh,
            ),
            boolField(
                "localTestCloudControlEnabled",
                R.string.settings_cloud_control_test_enabled,
                LauncherConfig.isLocalTestCloudControlEnabled(context),
                zh,
            ),
            boolField(
                "steamAchievementDebugModeEnabled",
                R.string.settings_steam_achievement_debug_mode_enabled,
                LauncherConfig.isSteamAchievementDebugModeEnabled(context),
                zh,
            ),
            textField(
                "localTest.onlineServiceBaseUrl",
                R.string.settings_cloud_control_test_online_service_label,
                LauncherConfig.readLocalTestOnlineServiceBaseUrl(context),
                LauncherConfig.readLocalTestOnlineServiceBaseUrl(context),
                zh,
            ),
            textField(
                "localTest.configServerUrl",
                R.string.settings_cloud_control_test_config_server_label,
                LauncherConfig.readLocalTestConfigServerUrl(context),
                LauncherConfig.readLocalTestConfigServerUrl(context),
                zh,
            ),
            textField(
                "localTest.entryNodeUrl",
                R.string.settings_cloud_control_test_entry_node_label,
                LauncherConfig.readLocalTestEntryNodeUrl(context),
                LauncherConfig.readLocalTestEntryNodeUrl(context),
                zh,
            ),
        )
    )
}

private fun advancedRenderSection(
    context: Context,
    zh: Context,
): LauncherSettingsDiagnosticsSection {
    val surfaceBackend = LauncherConfig.readRenderSurfaceBackend(context)
    val selectionMode = LauncherConfig.readRendererSelectionMode(context)
    val manualBackend = LauncherConfig.readManualRendererBackend(context)
    val guardianMode = LauncherConfig.readGpuResourceGuardianMode(context)
    val heapMaxMb = LauncherConfig.readJvmHeapMaxMb(context)
    val heapStartMb = LauncherConfig.resolveJvmHeapStartMb(heapMaxMb)

    return section(
        "Developer / Advanced rendering",
        "开发者 / 高级渲染",
        listOf(
            field(
                "render.surfaceBackend",
                zh.getString(R.string.settings_render_surface_backend_title),
                persisted(surfaceBackend.name, surfaceBackend.persistedValue),
                surfaceBackendZh(zh, surfaceBackend),
            ),
            field(
                "render.selectionMode",
                zh.getString(R.string.settings_renderer_auto_enabled),
                persisted(selectionMode.name, selectionMode.persistedValue),
                rendererSelectionModeZh(zh, selectionMode),
            ),
            field(
                "render.manualBackend",
                zh.getString(R.string.settings_renderer_manual_label),
                persisted(manualBackend.displayName, manualBackend.rendererId()),
                manualBackend.displayName,
            ),
            field(
                "gpuResourceGuardianMode",
                zh.getString(R.string.settings_gpu_resource_guardian_title),
                persisted(guardianMode.name, guardianMode.persistedValue),
                gpuResourceGuardianModeZh(zh, guardianMode),
            ),
            boolField(
                "gpuResourceGuardianPressureDownscale",
                R.string.settings_gpu_resource_guardian_pressure_downscale_enabled,
                LauncherConfig.isGpuResourceGuardianPressureDownscaleEnabled(context),
                zh,
            ),
            textField(
                "jvm.heapStartMb",
                R.string.settings_jvm_heap_title,
                heapStartMb.toString(),
                heapStartMb.toString(),
                zh,
            ),
            textField(
                "jvm.heapMaxMb",
                R.string.settings_jvm_heap_title,
                heapMaxMb.toString(),
                heapMaxMb.toString(),
                zh,
            ),
            boolField(
                "jvm.compressedPointersEnabled",
                R.string.settings_jvm_compressed_pointers_enabled,
                LauncherConfig.isJvmCompressedPointersEnabled(context),
                zh,
            ),
            boolField(
                "jvm.stringDeduplicationEnabled",
                R.string.settings_jvm_string_dedup_enabled,
                LauncherConfig.isJvmStringDeduplicationEnabled(context),
                zh,
            ),
        )
    )
}

private fun mobileGluesSection(
    context: Context,
    zh: Context,
): LauncherSettingsDiagnosticsSection {
    val settings = LauncherConfig.readMobileGluesSettings(context)
    return section(
        "Developer / MobileGlues",
        "开发者 / MobileGlues",
        listOf(
            field(
                "anglePolicy",
                zh.getString(R.string.mobileglues_field_angle_policy_label),
                persisted(settings.anglePolicy.name, settings.anglePolicy.persistedValue),
                settings.anglePolicy.displayName(zh),
            ),
            field(
                "noErrorPolicy",
                zh.getString(R.string.mobileglues_field_no_error_label),
                persisted(settings.noErrorPolicy.name, settings.noErrorPolicy.persistedValue),
                settings.noErrorPolicy.displayName(zh),
            ),
            field(
                "multidrawMode",
                zh.getString(R.string.mobileglues_field_multidraw_label),
                persisted(settings.multidrawMode.name, settings.multidrawMode.persistedValue),
                settings.multidrawMode.displayName(zh),
            ),
            boolField(
                "extComputeShaderEnabled",
                R.string.mobileglues_compute_shader_enabled,
                settings.extComputeShaderEnabled,
                zh,
            ),
            boolField(
                "extTimerQueryEnabled",
                R.string.mobileglues_timer_query_enabled,
                settings.extTimerQueryEnabled,
                zh,
            ),
            boolField(
                "extDirectStateAccessEnabled",
                R.string.mobileglues_direct_state_access_enabled,
                settings.extDirectStateAccessEnabled,
                zh,
            ),
            field(
                "glslCacheSize",
                zh.getString(R.string.mobileglues_field_glsl_cache_label),
                persisted(
                    settings.glslCacheSizePreset.name,
                    settings.glslCacheSizePreset.persistedValue,
                ),
                settings.glslCacheSizePreset.displayName(zh),
            ),
            field(
                "angleDepthClearFixMode",
                zh.getString(R.string.mobileglues_field_angle_depth_clear_label),
                persisted(
                    settings.angleDepthClearFixMode.name,
                    settings.angleDepthClearFixMode.persistedValue,
                ),
                settings.angleDepthClearFixMode.displayName(zh),
            ),
            field(
                "customGlVersion",
                zh.getString(R.string.mobileglues_field_custom_gl_label),
                persisted(settings.customGlVersion.name, settings.customGlVersion.persistedValue),
                settings.customGlVersion.displayName(zh),
            ),
            field(
                "fsr1QualityPreset",
                zh.getString(R.string.mobileglues_field_fsr1_label),
                persisted(
                    settings.fsr1QualityPreset.name,
                    settings.fsr1QualityPreset.persistedValue,
                ),
                settings.fsr1QualityPreset.displayName(zh),
            ),
        )
    )
}

private fun statusLogsSection(
    context: Context,
    zh: Context,
): LauncherSettingsDiagnosticsSection {
    return section(
        "Developer / Status and logs",
        "开发者 / 状态与日志",
        listOf(
            boolField(
                "diag.lwjglDebugEnabled",
                R.string.settings_lwjgl_debug_enabled,
                LauncherConfig.isLwjglDebugEnabled(context),
                zh,
            ),
            boolField(
                "diag.preloadAllJreLibrariesEnabled",
                R.string.settings_preload_all_jre_enabled,
                LauncherConfig.isPreloadAllJreLibrariesEnabled(context),
                zh,
            ),
            boolField(
                "diag.logcatCaptureEnabled",
                R.string.settings_logcat_capture_enabled,
                LauncherConfig.isLogcatCaptureEnabled(context),
                zh,
            ),
            boolField(
                "diag.launcherLogcatCaptureEnabled",
                R.string.settings_launcher_logcat_capture_enabled,
                LauncherConfig.isLauncherLogcatCaptureEnabled(context),
                zh,
            ),
            boolField(
                "diag.jvmLogcatMirrorEnabled",
                R.string.settings_jvm_logcat_mirror_enabled,
                LauncherConfig.isJvmLogcatMirrorEnabled(context),
                zh,
            ),
            boolField(
                "diag.gpuResourceDiagEnabled",
                R.string.settings_gpu_resource_diag_enabled,
                LauncherConfig.isGpuResourceDiagEnabled(context),
                zh,
            ),
            boolField(
                "diag.arthasAnalysisEnabled",
                R.string.settings_arthas_analysis_enabled,
                LauncherConfig.isArthasAnalysisEnabled(context),
                zh,
            ),
            boolField(
                "diag.gdxPadCursorDebugEnabled",
                R.string.settings_gdx_pad_cursor_debug_enabled,
                LauncherConfig.isGdxPadCursorDebugEnabled(context),
                zh,
            ),
            boolField(
                "diag.glBridgeSwapHeartbeatDebugEnabled",
                R.string.settings_glbridge_swap_heartbeat_enabled,
                LauncherConfig.isGlBridgeSwapHeartbeatDebugEnabled(context),
                zh,
            ),
        )
    )
}

private fun compatibilitySection(
    context: Context,
    zh: Context,
): LauncherSettingsDiagnosticsSection {
    val downscale = CompatibilitySettings.readRuntimeDownscaleMaterialPolicy(context)
    val importDownscale = CompatibilitySettings.readImportDownscaleMaterialPolicy(context)
    val divisor = CompatibilitySettings.readTexturePressureDownscaleDivisor(context)
    return section(
        "Developer / Compatibility settings",
        "开发者 / 兼容性设置",
        listOf(
            boolField(
                "globalAtlasFilterCompat",
                R.string.compat_global_atlas_filter_compat_title,
                CompatibilitySettings.isGlobalAtlasFilterCompatEnabled(context),
                zh,
            ),
            boolField(
                "modManifestRootCompat",
                R.string.compat_mod_manifest_root_compat_title,
                CompatibilitySettings.isModManifestRootCompatEnabled(context),
                zh,
            ),
            boolField(
                "frierenModCompat",
                R.string.compat_frieren_mod_compat_title,
                CompatibilitySettings.isFrierenModCompatEnabled(context),
                zh,
            ),
            boolField(
                "downfallImportCompat",
                R.string.compat_downfall_import_compat_title,
                CompatibilitySettings.isDownfallImportCompatEnabled(context),
                zh,
            ),
            boolField(
                "vupShionModCompat",
                R.string.compat_vupshion_mod_compat_title,
                CompatibilitySettings.isVupShionModCompatEnabled(context),
                zh,
            ),
            boolField(
                "chaofanModCompat",
                R.string.compat_chaofanmod_compat_title,
                CompatibilitySettings.isChaofanModCompatEnabled(context),
                zh,
            ),
            boolField(
                "jacketNoAnoKoModCompat",
                "Jacket No Ano Ko 模组兼容",
                CompatibilitySettings.isJacketNoAnoKoModCompatEnabled(context),
            ),
            boolField(
                "fragmentShaderPrecisionCompat",
                R.string.compat_fragment_shader_precision_compat_title,
                CompatibilitySettings.isFragmentShaderPrecisionCompatEnabled(context),
                zh,
            ),
            boolField(
                "runtimeTextureCompat",
                R.string.compat_runtime_texture_compat_title,
                CompatibilitySettings.isRuntimeTextureCompatEnabled(context),
                zh,
            ),
            boolField(
                "mainMenuPreviewReuseCompat",
                R.string.compat_main_menu_preview_reuse_title,
                CompatibilitySettings.isMainMenuPreviewReuseCompatEnabled(context),
                zh,
            ),
            boolField(
                "roomContextHandLayoutRescueCompat",
                R.string.compat_room_context_hand_layout_rescue_title,
                CompatibilitySettings.isRoomContextHandLayoutRescueCompatEnabled(context),
                zh,
            ),
            boolField(
                "roomTransitionRescueCompat",
                R.string.compat_room_transition_rescue_title,
                CompatibilitySettings.isRoomTransitionRescueCompatEnabled(context),
                zh,
            ),
            boolField(
                "eventRoomRescueCompat",
                R.string.compat_event_room_rescue_title,
                CompatibilitySettings.isEventRoomRescueCompatEnabled(context),
                zh,
            ),
            boolField(
                "shopRoomRescueCompat",
                R.string.compat_shop_room_rescue_title,
                CompatibilitySettings.isShopRoomRescueCompatEnabled(context),
                zh,
            ),
            boolField(
                "baseModSaveLoadRescueCompat",
                R.string.compat_basemod_save_load_rescue_title,
                CompatibilitySettings.isBaseModSaveLoadRescueCompatEnabled(context),
                zh,
            ),
            boolField(
                "relicEnterRoomRescueCompat",
                R.string.compat_relic_enter_room_rescue_title,
                CompatibilitySettings.isRelicEnterRoomRescueCompatEnabled(context),
                zh,
            ),
            boolField(
                "dungeonRenderRoomContextRescueCompat",
                R.string.compat_dungeon_render_room_context_rescue_title,
                CompatibilitySettings.isDungeonRenderRoomContextRescueCompatEnabled(context),
                zh,
            ),
            boolField(
                "powerIconRenderRescueCompat",
                R.string.compat_power_icon_render_rescue_title,
                CompatibilitySettings.isPowerIconRenderRescueCompatEnabled(context),
                zh,
            ),
            boolField(
                "baseModCustomMonsterRenderRescueCompat",
                R.string.compat_basemod_custom_monster_render_rescue_title,
                CompatibilitySettings.isBaseModCustomMonsterRenderRescueCompatEnabled(context),
                zh,
            ),
            boolField(
                "nonCombatPlayerRenderRescueCompat",
                R.string.compat_non_combat_player_render_rescue_title,
                CompatibilitySettings.isNonCombatPlayerRenderRescueCompatEnabled(context),
                zh,
            ),
            boolField(
                "cardTooltipKeywordRescueCompat",
                R.string.compat_card_tooltip_keyword_rescue_title,
                CompatibilitySettings.isCardTooltipKeywordRescueCompatEnabled(context),
                zh,
            ),
            boolField(
                "nativeTouchscreenAllowlistCompat",
                R.string.compat_native_touchscreen_allowlist_title,
                CompatibilitySettings.isNativeTouchscreenAllowlistCompatEnabled(context),
                zh,
            ),
            boolField(
                "largeTextureDownscaleCompat",
                R.string.compat_large_texture_downscale_title,
                CompatibilitySettings.isLargeTextureDownscaleCompatEnabled(context),
                zh,
            ),
            boolField(
                "textureResidencyManagerCompat",
                R.string.compat_texture_residency_manager_title,
                CompatibilitySettings.isTextureResidencyManagerCompatEnabled(context),
                zh,
            ),
            field(
                "texturePressureDownscaleDivisor",
                zh.getString(R.string.compat_texture_pressure_downscale_divisor_title),
                "x$divisor",
                "x$divisor",
            ),
            boolField(
                "forceLinearMipmapFilter",
                R.string.compat_force_linear_mipmap_filter_title,
                CompatibilitySettings.isForceLinearMipmapFilterEnabled(context),
                zh,
            ),
            boolField(
                "hinaCharacterRenderCompat",
                R.string.compat_hina_character_render_title,
                CompatibilitySettings.isHinaCharacterRenderCompatEnabled(context),
                zh,
            ),
            boolField(
                "nonRenderableFboFormatCompat",
                R.string.compat_non_renderable_fbo_format_compat_title,
                CompatibilitySettings.isNonRenderableFboFormatCompatEnabled(context),
                zh,
            ),
            boolField(
                "fboManagerCompat",
                R.string.compat_fbo_manager_title,
                CompatibilitySettings.isFboManagerCompatEnabled(context),
                zh,
            ),
            boolField(
                "fboIdleReclaimCompat",
                R.string.compat_fbo_idle_reclaim_title,
                CompatibilitySettings.isFboIdleReclaimCompatEnabled(context),
                zh,
            ),
            boolField(
                "fboPressureDownscaleCompat",
                R.string.compat_fbo_pressure_downscale_title,
                CompatibilitySettings.isFboPressureDownscaleCompatEnabled(context),
                zh,
            ),
            boolField(
                "runtimeDownscaleOrdinaryTextures",
                R.string.compat_runtime_downscale_ordinary_texture_title,
                downscale.ordinaryTextures,
                zh,
            ),
            field(
                "runtimeDownscaleTextureAtlasPages",
                zh.getString(R.string.compat_runtime_downscale_texture_atlas_title),
                downscale.textureAtlasPages.prefValue,
                textureAtlasDownscaleZh(zh, downscale),
            ),
            boolField(
                "runtimeDownscaleSpineTextures",
                R.string.compat_runtime_downscale_spine_title,
                downscale.spineTextures,
                zh,
            ),
            boolField(
                "runtimeDownscaleOffscreenFrameBuffers",
                R.string.compat_runtime_downscale_fbo_title,
                downscale.offscreenFrameBuffers,
                zh,
            ),
            boolField(
                "importDownscaleSpineAtlasPages",
                R.string.compat_import_downscale_spine_atlas_title,
                importDownscale.spineAtlasPages,
                zh,
            ),
            boolField(
                "importDownscaleOrdinaryAtlasPages",
                R.string.compat_import_downscale_ordinary_atlas_title,
                importDownscale.ordinaryAtlasPages,
                zh,
            ),
        )
    )
}

private fun normalizeFieldValue(value: String): String {
    val sanitized = value.replace('\r', ' ').replace('\n', ' ').trim()
    return sanitized.ifEmpty { "none" }
}

private fun themeModeZh(zh: Context, mode: LauncherThemeMode): String = when (mode) {
    LauncherThemeMode.FOLLOW_SYSTEM -> zh.getString(R.string.settings_theme_mode_follow_system)
    LauncherThemeMode.LIGHT -> zh.getString(R.string.settings_theme_mode_light)
    LauncherThemeMode.DARK -> zh.getString(R.string.settings_theme_mode_dark)
}

private fun themeColorZh(zh: Context, color: LauncherThemeColor): String = when (color) {
    LauncherThemeColor.ZHANSHIGE -> zh.getString(R.string.settings_theme_color_zhanshige)
    LauncherThemeColor.LIEBAO -> zh.getString(R.string.settings_theme_color_liebao)
    LauncherThemeColor.JIBAO -> zh.getString(R.string.settings_theme_color_jibao)
    LauncherThemeColor.GUANJIE -> zh.getString(R.string.settings_theme_color_guanjie)
    LauncherThemeColor.COLORLESS -> zh.getString(R.string.settings_theme_color_colorless)
}

private fun iconModeZh(zh: Context, mode: LauncherIconMode): String = when (mode) {
    LauncherIconMode.AMETHYST -> zh.getString(R.string.settings_app_icon_amethyst)
    LauncherIconMode.WATCHER -> zh.getString(R.string.settings_app_icon_watcher)
}

private fun bootOverlayStyleZh(zh: Context, style: BootOverlayStyle): String = when (style) {
    BootOverlayStyle.MODERN -> zh.getString(R.string.settings_boot_overlay_style_modern)
    BootOverlayStyle.LEGACY -> zh.getString(R.string.settings_boot_overlay_style_legacy)
    BootOverlayStyle.CLASSIC_LOG -> zh.getString(R.string.settings_boot_overlay_style_classic_log)
    BootOverlayStyle.MATERIAL_LOG -> zh.getString(R.string.settings_boot_overlay_style_material_log)
    BootOverlayStyle.SLING_BREAK -> zh.getString(R.string.settings_boot_overlay_style_sling_break)
}

private fun bootOverlayAnimationZh(zh: Context, animation: BootOverlayAnimation): String =
    when (animation) {
        BootOverlayAnimation.INFINITY_ORBIT ->
            zh.getString(R.string.settings_loading_animation_infinity_orbit)
        BootOverlayAnimation.COMET -> zh.getString(R.string.settings_loading_animation_comet)
        BootOverlayAnimation.WAVE -> zh.getString(R.string.settings_loading_animation_wave)
        BootOverlayAnimation.HALO -> zh.getString(R.string.settings_loading_animation_halo)
        BootOverlayAnimation.ELASTIC_DOTS ->
            zh.getString(R.string.settings_loading_animation_elastic_dots)
        BootOverlayAnimation.SPIRAL -> zh.getString(R.string.settings_loading_animation_spiral)
        BootOverlayAnimation.PULSE_RINGS ->
            zh.getString(R.string.settings_loading_animation_pulse_rings)
        BootOverlayAnimation.ORBITAL_ECLIPSE ->
            zh.getString(R.string.settings_loading_animation_orbital_eclipse)
        BootOverlayAnimation.RUNIC_GATE -> zh.getString(R.string.settings_loading_animation_runic_gate)
        BootOverlayAnimation.CARD_SHUFFLE -> zh.getString(R.string.settings_loading_animation_card_shuffle)
        BootOverlayAnimation.PRISM_SWEEP -> zh.getString(R.string.settings_loading_animation_prism_sweep)
        BootOverlayAnimation.HELIX_LADDER -> zh.getString(R.string.settings_loading_animation_helix_ladder)
        BootOverlayAnimation.LIQUID_ORB -> zh.getString(R.string.settings_loading_animation_liquid_orb)
        BootOverlayAnimation.SIGNAL_STACK -> zh.getString(R.string.settings_loading_animation_signal_stack)
        BootOverlayAnimation.DIAMOND_FLOW -> zh.getString(R.string.settings_loading_animation_diamond_flow)
        BootOverlayAnimation.GRAVITY_WELL -> zh.getString(R.string.settings_loading_animation_gravity_well)
    }

private fun bootOverlayImageModeEn(config: BootOverlayImageConfig): String = when {
    !config.hasCustomImages -> "default"
    config.mode == BootOverlayImageMode.SINGLE -> "single"
    else -> "dual"
}

private fun bootOverlayImageModeZh(config: BootOverlayImageConfig): String = when {
    !config.hasCustomImages -> "默认"
    config.mode == BootOverlayImageMode.SINGLE -> "单张"
    else -> "双张"
}

private fun virtualResolutionModeZh(zh: Context, mode: VirtualResolutionMode): String = when (mode) {
    VirtualResolutionMode.FULLSCREEN_FILL ->
        zh.getString(R.string.settings_virtual_resolution_mode_fullscreen_fill)
    VirtualResolutionMode.RESOLUTION_1080P -> zh.getString(R.string.settings_virtual_resolution_mode_1080p)
    VirtualResolutionMode.RESOLUTION_720P -> zh.getString(R.string.settings_virtual_resolution_mode_720p)
    VirtualResolutionMode.RATIO_4_3 -> zh.getString(R.string.settings_virtual_resolution_mode_4_3)
    VirtualResolutionMode.RATIO_16_9 -> zh.getString(R.string.settings_virtual_resolution_mode_16_9)
}

private fun keepScreenOnTimeoutZh(zh: Context, minutes: Int): String =
    if (minutes <= 0) {
        zh.getString(R.string.settings_keep_screen_on_timeout_always)
    } else {
        zh.getString(R.string.settings_keep_screen_on_timeout_minutes, minutes)
    }

private fun backBehaviorZh(zh: Context, behavior: BackBehavior): String = when (behavior) {
    BackBehavior.EXIT_TO_LAUNCHER -> zh.getString(R.string.settings_back_behavior_exit)
    BackBehavior.SEND_ESCAPE -> zh.getString(R.string.settings_back_behavior_escape)
    BackBehavior.NONE -> zh.getString(R.string.settings_back_behavior_none)
}

private fun specialKeyInputModeZh(zh: Context, mode: SpecialKeyInputMode): String = when (mode) {
    SpecialKeyInputMode.LEGACY_FLOATING_WINDOW ->
        zh.getString(R.string.settings_special_key_input_mode_legacy_floating_window)
    SpecialKeyInputMode.BUILT_IN_MOD ->
        zh.getString(R.string.settings_special_key_input_mode_built_in_mod)
    SpecialKeyInputMode.DISABLED ->
        zh.getString(R.string.settings_special_key_input_mode_disabled)
}

private fun touchMouseInteractionModeZh(zh: Context, mode: TouchMouseInteractionMode): String = when (mode) {
    TouchMouseInteractionMode.OPEN_MENU_ON_TAP ->
        zh.getString(R.string.settings_touch_mouse_interaction_mode_open_menu)
    TouchMouseInteractionMode.TOGGLE_BUTTON_ON_TAP ->
        zh.getString(R.string.settings_touch_mouse_interaction_mode_toggle_button)
}

private fun cardPlayOptimizationModeZh(zh: Context, mode: CardPlayOptimizationMode): String = when (mode) {
    CardPlayOptimizationMode.RELEASE_POP_BACK ->
        zh.getString(R.string.settings_card_play_optimization_release_pop_back)
    CardPlayOptimizationMode.RELEASE_KEEP_OPEN ->
        zh.getString(R.string.settings_card_play_optimization_release_keep_open)
    CardPlayOptimizationMode.TAP_CARD_THEN_TARGET ->
        zh.getString(R.string.settings_card_play_optimization_tap_then_target)
    CardPlayOptimizationMode.VANILLA ->
        zh.getString(R.string.settings_card_play_optimization_vanilla)
}

private fun touchscreenInputModeZh(zh: Context, mode: TouchscreenInputMode): String = when (mode) {
    TouchscreenInputMode.DESKTOP -> zh.getString(R.string.settings_touchscreen_mode_desktop)
    TouchscreenInputMode.HYBRID -> zh.getString(R.string.settings_touchscreen_mode_hybrid)
    TouchscreenInputMode.MOBILE -> zh.getString(R.string.settings_touchscreen_mode_mobile)
}

private fun steamCloudSaveModeZh(zh: Context, mode: SteamCloudSaveMode): String = when (mode) {
    SteamCloudSaveMode.INDEPENDENT ->
        zh.getString(R.string.settings_steam_cloud_save_mode_independent_title)
    SteamCloudSaveMode.STEAM_CLOUD ->
        zh.getString(R.string.settings_steam_cloud_save_mode_cloud_title)
}

private fun surfaceBackendZh(zh: Context, backend: RenderSurfaceBackend): String = when (backend) {
    RenderSurfaceBackend.SURFACE_VIEW ->
        zh.getString(R.string.settings_render_surface_backend_surface_view_short)
    RenderSurfaceBackend.TEXTURE_VIEW ->
        zh.getString(R.string.settings_render_surface_backend_texture_view_short)
}

private fun rendererSelectionModeZh(zh: Context, mode: RendererSelectionMode): String = when (mode) {
    RendererSelectionMode.AUTO -> zh.getString(R.string.settings_renderer_auto_enabled)
    RendererSelectionMode.MANUAL -> zh.getString(R.string.settings_renderer_manual_label)
}

private fun gpuResourceGuardianModeZh(zh: Context, mode: GpuResourceGuardianMode): String = when (mode) {
    GpuResourceGuardianMode.OFF -> zh.getString(R.string.settings_gpu_resource_guardian_mode_off)
    GpuResourceGuardianMode.SAFE -> zh.getString(R.string.settings_gpu_resource_guardian_mode_safe)
    GpuResourceGuardianMode.AGGRESSIVE ->
        zh.getString(R.string.settings_gpu_resource_guardian_mode_aggressive)
    GpuResourceGuardianMode.ULTRA_AGGRESSIVE ->
        zh.getString(R.string.settings_gpu_resource_guardian_mode_ultra_aggressive)
    GpuResourceGuardianMode.LEGACY -> zh.getString(R.string.settings_gpu_resource_guardian_mode_legacy)
}

private fun textureAtlasDownscaleZh(zh: Context, policy: RuntimeDownscaleMaterialPolicy): String =
    when (policy.textureAtlasPages) {
        RuntimeTextureAtlasDownscaleQuality.P720 ->
            zh.getString(R.string.compat_runtime_downscale_texture_atlas_quality_720p)
        RuntimeTextureAtlasDownscaleQuality.P1080 ->
            zh.getString(R.string.compat_runtime_downscale_texture_atlas_quality_1080p)
        RuntimeTextureAtlasDownscaleQuality.P2K ->
            zh.getString(R.string.compat_runtime_downscale_texture_atlas_quality_2k)
        RuntimeTextureAtlasDownscaleQuality.NATIVE ->
            zh.getString(R.string.compat_runtime_downscale_texture_atlas_quality_native)
    }

private fun richPresenceEn(preferences: RichPresenceDisplayPreferences): String {
    val parts = buildList {
        add("prefix=${preferences.prefix.name}")
        if (preferences.showCharacter) add("character")
        if (preferences.showFloor) add("floor")
        if (preferences.showAscension) add("ascension")
        if (preferences.showAct) add("act")
    }
    return parts.joinToString(",")
}

private fun richPresenceZh(zh: Context, preferences: RichPresenceDisplayPreferences): String {
    val parts = buildList {
        add(richPresencePrefixZh(zh, preferences.prefix))
        if (preferences.showCharacter) {
            add(zh.getString(R.string.settings_steam_services_rich_presence_summary_character))
        }
        if (preferences.showFloor) {
            add(zh.getString(R.string.settings_steam_services_rich_presence_summary_floor))
        }
        if (preferences.showAscension) {
            add(zh.getString(R.string.settings_steam_services_rich_presence_summary_ascension))
        }
        if (preferences.showAct) {
            add(zh.getString(R.string.settings_steam_services_rich_presence_summary_act))
        }
    }
    return parts.joinToString(" · ")
}

private fun richPresencePrefixZh(zh: Context, prefix: RichPresencePrefix): String = when (prefix) {
    RichPresencePrefix.GAME ->
        zh.getString(R.string.settings_steam_services_rich_presence_prefix_game)
    RichPresencePrefix.DEVICE ->
        zh.getString(R.string.settings_steam_services_rich_presence_prefix_device)
    RichPresencePrefix.NONE ->
        zh.getString(R.string.settings_steam_services_rich_presence_prefix_none)
}
