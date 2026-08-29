package io.stamethyst.ui.preferences

import android.content.Context
import io.stamethyst.backend.mods.AtlasOfflineDownscaleStrategy
import io.stamethyst.backend.network.NetworkAccelerationPolicy
import io.stamethyst.backend.render.MobileGluesAnglePolicy
import io.stamethyst.backend.render.MobileGluesAngleDepthClearFixMode
import io.stamethyst.backend.render.MobileGluesCustomGlVersion
import io.stamethyst.backend.render.MobileGluesFsr1QualityPreset
import io.stamethyst.backend.render.MobileGluesGlslCacheSizePreset
import io.stamethyst.backend.render.MobileGluesMultidrawMode
import io.stamethyst.backend.render.MobileGluesNoErrorPolicy
import io.stamethyst.backend.render.MobileGluesSettings
import io.stamethyst.backend.render.RendererBackend
import io.stamethyst.backend.render.RendererSelectionMode
import io.stamethyst.backend.render.VirtualResolutionMode
import io.stamethyst.config.BackBehavior
import io.stamethyst.config.BootOverlayImageConfig
import io.stamethyst.config.BootOverlayImageMode
import io.stamethyst.config.BootOverlayAnimation
import io.stamethyst.config.BootOverlayStyle
import io.stamethyst.config.CardPlayOptimizationMode
import io.stamethyst.config.GpuResourceGuardianMode
import io.stamethyst.config.LauncherConfig
import io.stamethyst.config.LauncherIconMode
import io.stamethyst.config.LauncherThemeColor
import io.stamethyst.config.LauncherThemeMode
import io.stamethyst.config.RenderSurfaceBackend
import io.stamethyst.config.RichPresenceDisplayPreferences
import io.stamethyst.config.SpecialKeyInputMode
import io.stamethyst.config.SteamCloudSaveMode
import io.stamethyst.config.TouchMouseInteractionMode
import io.stamethyst.backend.workshop.SteamLanguagePreference

object LauncherPreferences {
    val DEFAULT_BACK_BEHAVIOR: BackBehavior
        get() = LauncherConfig.DEFAULT_BACK_BEHAVIOR
    val DEFAULT_BACK_IMMEDIATE_EXIT: Boolean
        get() = LauncherConfig.DEFAULT_BACK_IMMEDIATE_EXIT
    val DEFAULT_MANUAL_DISMISS_BOOT_OVERLAY: Boolean
        get() = LauncherConfig.DEFAULT_MANUAL_DISMISS_BOOT_OVERLAY
    val DEFAULT_SPECIAL_KEY_INPUT_MODE: SpecialKeyInputMode
        get() = LauncherConfig.DEFAULT_SPECIAL_KEY_INPUT_MODE
    val DEFAULT_BOOT_OVERLAY_ANIMATION: BootOverlayAnimation
        get() = LauncherConfig.DEFAULT_BOOT_OVERLAY_ANIMATION
    val DEFAULT_BOOT_OVERLAY_STYLE: BootOverlayStyle
        get() = LauncherConfig.DEFAULT_BOOT_OVERLAY_STYLE
    val DEFAULT_BOOT_OVERLAY_IMAGE_MODE: BootOverlayImageMode
        get() = LauncherConfig.DEFAULT_BOOT_OVERLAY_IMAGE_MODE
    val DEFAULT_TARGET_FPS: Int
        get() = LauncherConfig.DEFAULT_TARGET_FPS
    val TARGET_FPS_OPTIONS: IntArray
        get() = LauncherConfig.TARGET_FPS_OPTIONS.copyOf()
    val DEFAULT_KEEP_SCREEN_ON_TIMEOUT_MINUTES: Int
        get() = LauncherConfig.DEFAULT_KEEP_SCREEN_ON_TIMEOUT_MINUTES
    val KEEP_SCREEN_ON_TIMEOUT_ALWAYS_MINUTES: Int
        get() = LauncherConfig.KEEP_SCREEN_ON_TIMEOUT_ALWAYS_MINUTES
    val KEEP_SCREEN_ON_TIMEOUT_MINUTE_OPTIONS: IntArray
        get() = LauncherConfig.KEEP_SCREEN_ON_TIMEOUT_MINUTE_OPTIONS.copyOf()
    val DEFAULT_RENDER_SURFACE_BACKEND: RenderSurfaceBackend
        get() = LauncherConfig.DEFAULT_RENDER_SURFACE_BACKEND
    val DEFAULT_RENDERER_SELECTION_MODE: RendererSelectionMode
        get() = LauncherConfig.DEFAULT_RENDERER_SELECTION_MODE
    val DEFAULT_MANUAL_RENDERER_BACKEND: RendererBackend
        get() = LauncherConfig.DEFAULT_MANUAL_RENDERER_BACKEND
    val DEFAULT_MOBILEGLUES_ANGLE_POLICY: MobileGluesAnglePolicy
        get() = LauncherConfig.DEFAULT_MOBILEGLUES_ANGLE_POLICY
    val DEFAULT_MOBILEGLUES_NO_ERROR_POLICY: MobileGluesNoErrorPolicy
        get() = LauncherConfig.DEFAULT_MOBILEGLUES_NO_ERROR_POLICY
    val DEFAULT_MOBILEGLUES_MULTIDRAW_MODE: MobileGluesMultidrawMode
        get() = LauncherConfig.DEFAULT_MOBILEGLUES_MULTIDRAW_MODE
    val DEFAULT_MOBILEGLUES_GLSL_CACHE_SIZE_PRESET: MobileGluesGlslCacheSizePreset
        get() = LauncherConfig.DEFAULT_MOBILEGLUES_GLSL_CACHE_SIZE_PRESET
    val DEFAULT_MOBILEGLUES_ANGLE_DEPTH_CLEAR_FIX_MODE: MobileGluesAngleDepthClearFixMode
        get() = LauncherConfig.DEFAULT_MOBILEGLUES_ANGLE_DEPTH_CLEAR_FIX_MODE
    val DEFAULT_MOBILEGLUES_CUSTOM_GL_VERSION: MobileGluesCustomGlVersion
        get() = LauncherConfig.DEFAULT_MOBILEGLUES_CUSTOM_GL_VERSION
    val DEFAULT_MOBILEGLUES_FSR1_QUALITY_PRESET: MobileGluesFsr1QualityPreset
        get() = LauncherConfig.DEFAULT_MOBILEGLUES_FSR1_QUALITY_PRESET
    val DEFAULT_MOBILEGLUES_EXT_COMPUTE_SHADER_ENABLED: Boolean
        get() = LauncherConfig.DEFAULT_MOBILEGLUES_EXT_COMPUTE_SHADER_ENABLED
    val DEFAULT_MOBILEGLUES_EXT_TIMER_QUERY_ENABLED: Boolean
        get() = LauncherConfig.DEFAULT_MOBILEGLUES_EXT_TIMER_QUERY_ENABLED
    val DEFAULT_MOBILEGLUES_EXT_DIRECT_STATE_ACCESS_ENABLED: Boolean
        get() = LauncherConfig.DEFAULT_MOBILEGLUES_EXT_DIRECT_STATE_ACCESS_ENABLED
    val DEFAULT_THEME_MODE: LauncherThemeMode
        get() = LauncherConfig.DEFAULT_THEME_MODE
    val DEFAULT_THEME_COLOR: LauncherThemeColor
        get() = LauncherConfig.DEFAULT_THEME_COLOR
    val DEFAULT_LAUNCHER_ICON_MODE: LauncherIconMode
        get() = LauncherConfig.DEFAULT_LAUNCHER_ICON_MODE
    val DEFAULT_CHROME_BACKGROUND_OPACITY: Float
        get() = LauncherConfig.DEFAULT_CHROME_BACKGROUND_OPACITY
    val MIN_CHROME_BACKGROUND_OPACITY: Float
        get() = LauncherConfig.MIN_CHROME_BACKGROUND_OPACITY
    val MAX_CHROME_BACKGROUND_OPACITY: Float
        get() = LauncherConfig.MAX_CHROME_BACKGROUND_OPACITY
    val DEFAULT_SHOW_FLOATING_MOUSE_WINDOW: Boolean
        get() = LauncherConfig.DEFAULT_SHOW_FLOATING_MOUSE_WINDOW
    val DEFAULT_TOUCH_MOUSE_INTERACTION_MODE: TouchMouseInteractionMode
        get() = LauncherConfig.DEFAULT_TOUCH_MOUSE_INTERACTION_MODE
    val DEFAULT_CARD_PLAY_OPTIMIZATION_MODE: CardPlayOptimizationMode
        get() = LauncherConfig.DEFAULT_CARD_PLAY_OPTIMIZATION_MODE
    val DEFAULT_BUILT_IN_SOFT_KEYBOARD_ENABLED: Boolean
        get() = LauncherConfig.DEFAULT_BUILT_IN_SOFT_KEYBOARD_ENABLED
    val DEFAULT_FLOATING_TOOL_BUTTONS: Set<String>
        get() = LauncherConfig.DEFAULT_FLOATING_TOOL_BUTTONS
    val FLOATING_TOOL_BUTTON_IDS: List<String>
        get() = LauncherConfig.FLOATING_TOOL_BUTTON_IDS
    val DEFAULT_HAPTIC_FEEDBACK_ENABLED: Boolean
        get() = LauncherConfig.DEFAULT_HAPTIC_FEEDBACK_ENABLED
    val DEFAULT_AUTO_SWITCH_LEFT_AFTER_RIGHT_CLICK: Boolean
        get() = LauncherConfig.DEFAULT_AUTO_SWITCH_LEFT_AFTER_RIGHT_CLICK
    val DEFAULT_TOUCH_DOUBLE_CLICK_AS_RIGHT_CLICK: Boolean
        get() = LauncherConfig.DEFAULT_TOUCH_DOUBLE_CLICK_AS_RIGHT_CLICK
    val DEFAULT_IGNORE_LONG_PRESS_RIGHT_CLICK_WHILE_PLAYING_CARD: Boolean
        get() = LauncherConfig.DEFAULT_IGNORE_LONG_PRESS_RIGHT_CLICK_WHILE_PLAYING_CARD
    val DEFAULT_SHOW_MOD_FILE_NAME: Boolean
        get() = LauncherConfig.DEFAULT_SHOW_MOD_FILE_NAME
    val DEFAULT_MOBILE_HUD_ENABLED: Boolean
        get() = LauncherConfig.DEFAULT_MOBILE_HUD_ENABLED
    val DEFAULT_COMPENDIUM_UPGRADE_TOUCH_FIX_ENABLED: Boolean
        get() = LauncherConfig.DEFAULT_COMPENDIUM_UPGRADE_TOUCH_FIX_ENABLED
    val DEFAULT_TOGETHER_IN_SPIRE_ROUTE_LOCK_ENABLED: Boolean
        get() = LauncherConfig.DEFAULT_TOGETHER_IN_SPIRE_ROUTE_LOCK_ENABLED
    val DEFAULT_TOGETHER_IN_SPIRE_EASYTIER_AUTOFILL_ENABLED: Boolean
        get() = LauncherConfig.DEFAULT_TOGETHER_IN_SPIRE_EASYTIER_AUTOFILL_ENABLED
    val DEFAULT_VIRTUAL_RESOLUTION_MODE: VirtualResolutionMode
        get() = LauncherConfig.DEFAULT_VIRTUAL_RESOLUTION_MODE
    val DEFAULT_AVOID_DISPLAY_CUTOUT: Boolean
        get() = LauncherConfig.DEFAULT_AVOID_DISPLAY_CUTOUT
    val DEFAULT_CROP_SCREEN_BOTTOM: Boolean
        get() = LauncherConfig.DEFAULT_CROP_SCREEN_BOTTOM
    val DEFAULT_RAM_SAVER_ENABLED: Boolean
        get() = LauncherConfig.DEFAULT_RAM_SAVER_ENABLED
    val DEFAULT_MTS_PATCH_CACHE_ENABLED: Boolean
        get() = LauncherConfig.DEFAULT_MTS_PATCH_CACHE_ENABLED
    val DEFAULT_SHOW_GAME_PERFORMANCE_OVERLAY: Boolean
        get() = LauncherConfig.DEFAULT_SHOW_GAME_PERFORMANCE_OVERLAY
    val DEFAULT_SUSTAINED_PERFORMANCE_MODE_ENABLED: Boolean
        get() = LauncherConfig.DEFAULT_SUSTAINED_PERFORMANCE_MODE_ENABLED
    val DEFAULT_LWJGL_DEBUG: Boolean
        get() = LauncherConfig.DEFAULT_LWJGL_DEBUG
    val DEFAULT_PRELOAD_ALL_JRE_LIBRARIES: Boolean
        get() = LauncherConfig.DEFAULT_PRELOAD_ALL_JRE_LIBRARIES
    val DEFAULT_LOGCAT_CAPTURE_ENABLED: Boolean
        get() = LauncherConfig.DEFAULT_LOGCAT_CAPTURE_ENABLED
    val DEFAULT_LAUNCHER_LOGCAT_CAPTURE_ENABLED: Boolean
        get() = LauncherConfig.DEFAULT_LAUNCHER_LOGCAT_CAPTURE_ENABLED
    val DEFAULT_JVM_LOGCAT_MIRROR_ENABLED: Boolean
        get() = LauncherConfig.DEFAULT_JVM_LOGCAT_MIRROR_ENABLED
    val DEFAULT_GPU_RESOURCE_DIAG_ENABLED: Boolean
        get() = LauncherConfig.DEFAULT_GPU_RESOURCE_DIAG_ENABLED
    val DEFAULT_GPU_RESOURCE_GUARDIAN_MODE: GpuResourceGuardianMode
        get() = LauncherConfig.DEFAULT_GPU_RESOURCE_GUARDIAN_MODE
    val DEFAULT_GPU_RESOURCE_GUARDIAN_PRESSURE_DOWNSCALE_ENABLED: Boolean
        get() = LauncherConfig.DEFAULT_GPU_RESOURCE_GUARDIAN_PRESSURE_DOWNSCALE_ENABLED
    val DEFAULT_GDX_PAD_CURSOR_DEBUG: Boolean
        get() = LauncherConfig.DEFAULT_GDX_PAD_CURSOR_DEBUG
    val DEFAULT_GLBRIDGE_SWAP_HEARTBEAT_DEBUG: Boolean
        get() = LauncherConfig.DEFAULT_GLBRIDGE_SWAP_HEARTBEAT_DEBUG
    val DEFAULT_AUTO_CHECK_UPDATES_ENABLED: Boolean
        get() = LauncherConfig.DEFAULT_AUTO_CHECK_UPDATES_ENABLED
    val DEFAULT_STEAM_CLOUD_WATT_ACCELERATION_ENABLED: Boolean
        get() = LauncherConfig.DEFAULT_STEAM_CLOUD_WATT_ACCELERATION_ENABLED
    val DEFAULT_STEAM_CLOUD_AUTO_LAUNCH_AFTER_SYNC_ENABLED: Boolean
        get() = LauncherConfig.DEFAULT_STEAM_CLOUD_AUTO_LAUNCH_AFTER_SYNC_ENABLED
    val DEFAULT_STEAM_GAME_PRESENCE_ENABLED: Boolean
        get() = LauncherConfig.DEFAULT_STEAM_GAME_PRESENCE_ENABLED
    val DEFAULT_ACHIEVEMENT_UNLOCK_NOTIFICATION_ENABLED: Boolean
        get() = LauncherConfig.DEFAULT_ACHIEVEMENT_UNLOCK_NOTIFICATION_ENABLED
    val DEFAULT_WORKSHOP_MAX_CONCURRENT_DOWNLOADS: Int
        get() = LauncherConfig.DEFAULT_WORKSHOP_MAX_CONCURRENT_DOWNLOADS
    val MIN_WORKSHOP_MAX_CONCURRENT_DOWNLOADS: Int
        get() = LauncherConfig.MIN_WORKSHOP_MAX_CONCURRENT_DOWNLOADS
    val MAX_WORKSHOP_MAX_CONCURRENT_DOWNLOADS: Int
        get() = LauncherConfig.MAX_WORKSHOP_MAX_CONCURRENT_DOWNLOADS
    val DEFAULT_WORKSHOP_DOWNLOAD_THREADS: Int
        get() = LauncherConfig.DEFAULT_WORKSHOP_DOWNLOAD_THREADS
    val MIN_WORKSHOP_DOWNLOAD_THREADS: Int
        get() = LauncherConfig.MIN_WORKSHOP_DOWNLOAD_THREADS
    val MAX_WORKSHOP_DOWNLOAD_THREADS: Int
        get() = LauncherConfig.MAX_WORKSHOP_DOWNLOAD_THREADS
    val DEFAULT_WORKSHOP_WATT_ACCELERATION_ENABLED: Boolean
        get() = LauncherConfig.DEFAULT_WORKSHOP_WATT_ACCELERATION_ENABLED
    val DEFAULT_WORKSHOP_STEAM_LANGUAGE: SteamLanguagePreference
        get() = SteamLanguagePreference.fromStorageValue(LauncherConfig.DEFAULT_WORKSHOP_STEAM_LANGUAGE)
    val DEFAULT_WORKSHOP_AUTO_IMPORT_ENABLED: Boolean
        get() = LauncherConfig.DEFAULT_WORKSHOP_AUTO_IMPORT_ENABLED
    val DEFAULT_WORKSHOP_AUTO_IMPORT_ATLAS_DOWNSCALE_ENABLED: Boolean
        get() = LauncherConfig.DEFAULT_WORKSHOP_AUTO_IMPORT_ATLAS_DOWNSCALE_ENABLED
    val DEFAULT_WORKSHOP_AUTO_IMPORT_ATLAS_DOWNSCALE_MAX_EDGE_PX: Int
        get() = LauncherConfig.DEFAULT_WORKSHOP_AUTO_IMPORT_ATLAS_DOWNSCALE_MAX_EDGE_PX
    val WORKSHOP_AUTO_IMPORT_ATLAS_DOWNSCALE_MAX_EDGE_OPTIONS: IntArray
        get() = AtlasOfflineDownscaleStrategy.maxEdgeOptions()
    val DEFAULT_STEAM_CLOUD_SAVE_MODE: SteamCloudSaveMode
        get() = LauncherConfig.DEFAULT_STEAM_CLOUD_SAVE_MODE
    val DEFAULT_STEAM_CLOUD_SYNC_BLACKLIST_PATHS: Set<String>
        get() = LauncherConfig.DEFAULT_STEAM_CLOUD_SYNC_BLACKLIST_PATHS
    val DEFAULT_PREFERRED_UPDATE_MIRROR_ID: String
        get() = LauncherConfig.DEFAULT_PREFERRED_UPDATE_MIRROR_ID
    val DEFAULT_PLAYER_NAME: String
        get() = LauncherConfig.DEFAULT_PLAYER_NAME

    val DEFAULT_JVM_HEAP_MAX_MB: Int
        get() = LauncherConfig.DEFAULT_JVM_HEAP_MAX_MB
    val MIN_JVM_HEAP_MAX_MB: Int
        get() = LauncherConfig.MIN_JVM_HEAP_MAX_MB
    val MAX_JVM_HEAP_MAX_MB: Int
        get() = LauncherConfig.MAX_JVM_HEAP_MAX_MB
    val JVM_HEAP_STEP_MB: Int
        get() = LauncherConfig.JVM_HEAP_STEP_MB
    val DEFAULT_JVM_COMPRESSED_POINTERS_ENABLED: Boolean
        get() = LauncherConfig.DEFAULT_JVM_COMPRESSED_POINTERS_ENABLED
    val DEFAULT_JVM_STRING_DEDUPLICATION_ENABLED: Boolean
        get() = LauncherConfig.DEFAULT_JVM_STRING_DEDUPLICATION_ENABLED

    fun readBackBehavior(context: Context): BackBehavior {
        return LauncherConfig.readBackBehavior(context)
    }

    fun saveBackBehavior(context: Context, behavior: BackBehavior) {
        LauncherConfig.saveBackBehavior(context, behavior)
    }

    fun readBackImmediateExit(context: Context): Boolean {
        return LauncherConfig.readBackImmediateExit(context)
    }

    fun saveBackImmediateExit(context: Context, enabled: Boolean) {
        LauncherConfig.saveBackImmediateExit(context, enabled)
    }

    fun readManualDismissBootOverlay(context: Context): Boolean {
        return LauncherConfig.readManualDismissBootOverlay(context)
    }

    fun saveManualDismissBootOverlay(context: Context, enabled: Boolean) {
        LauncherConfig.saveManualDismissBootOverlay(context, enabled)
    }

    fun readBootOverlayStyle(context: Context): BootOverlayStyle {
        return LauncherConfig.readBootOverlayStyle(context)
    }

    fun saveBootOverlayStyle(context: Context, style: BootOverlayStyle) {
        LauncherConfig.saveBootOverlayStyle(context, style)
    }

    fun readBootOverlayAnimation(context: Context): BootOverlayAnimation {
        return LauncherConfig.readBootOverlayAnimation(context)
    }

    fun saveBootOverlayAnimation(context: Context, animation: BootOverlayAnimation) {
        LauncherConfig.saveBootOverlayAnimation(context, animation)
    }

    fun readBootOverlayImageConfig(context: Context): BootOverlayImageConfig {
        return LauncherConfig.readBootOverlayImageConfig(context)
    }

    fun saveBootOverlayImageConfig(context: Context, config: BootOverlayImageConfig) {
        LauncherConfig.saveBootOverlayImageConfig(context, config)
    }

    fun clearBootOverlayImageConfig(context: Context) {
        LauncherConfig.clearBootOverlayImageConfig(context)
    }

    fun isBasicTutorialNoticeDismissed(context: Context): Boolean {
        return LauncherConfig.isBasicTutorialNoticeDismissed(context)
    }

    fun setBasicTutorialNoticeDismissed(context: Context, dismissed: Boolean) {
        LauncherConfig.setBasicTutorialNoticeDismissed(context, dismissed)
    }

    fun isDeveloperSettingsWarningDismissed(context: Context): Boolean {
        return LauncherConfig.isDeveloperSettingsWarningDismissed(context)
    }

    fun setDeveloperSettingsWarningDismissed(context: Context, dismissed: Boolean) {
        LauncherConfig.setDeveloperSettingsWarningDismissed(context, dismissed)
    }

    fun isSteamAchievementDebugModeEnabled(context: Context): Boolean {
        return LauncherConfig.isSteamAchievementDebugModeEnabled(context)
    }

    fun setSteamAchievementDebugModeEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setSteamAchievementDebugModeEnabled(context, enabled)
    }

    fun isSteamAchievementSyncEnabled(context: Context): Boolean {
        return LauncherConfig.isSteamAchievementSyncEnabled(context)
    }

    fun setSteamAchievementSyncEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setSteamAchievementSyncEnabled(context, enabled)
    }

    fun isAchievementUnlockNotificationEnabled(context: Context): Boolean {
        return LauncherConfig.isAchievementUnlockNotificationEnabled(context)
    }

    fun setAchievementUnlockNotificationEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setAchievementUnlockNotificationEnabled(context, enabled)
    }

    fun isLocalTestCloudControlEnabled(context: Context): Boolean {
        return LauncherConfig.isLocalTestCloudControlEnabled(context)
    }

    fun setLocalTestCloudControlEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setLocalTestCloudControlEnabled(context, enabled)
    }

    fun readLocalTestOnlineServiceBaseUrl(context: Context): String =
        LauncherConfig.readLocalTestOnlineServiceBaseUrl(context)

    fun saveLocalTestOnlineServiceBaseUrl(context: Context, value: String) {
        LauncherConfig.saveLocalTestOnlineServiceBaseUrl(context, value)
    }

    fun readLocalTestConfigServerUrl(context: Context): String =
        LauncherConfig.readLocalTestConfigServerUrl(context)

    fun saveLocalTestConfigServerUrl(context: Context, value: String) {
        LauncherConfig.saveLocalTestConfigServerUrl(context, value)
    }

    fun readLocalTestEntryNodeUrl(context: Context): String =
        LauncherConfig.readLocalTestEntryNodeUrl(context)

    fun saveLocalTestEntryNodeUrl(context: Context, value: String) {
        LauncherConfig.saveLocalTestEntryNodeUrl(context, value)
    }

    fun readSpecialKeyInputMode(context: Context): SpecialKeyInputMode {
        return LauncherConfig.readSpecialKeyInputMode(context)
    }

    fun saveSpecialKeyInputMode(context: Context, mode: SpecialKeyInputMode) {
        LauncherConfig.saveSpecialKeyInputMode(context, mode)
    }

    fun readShowFloatingMouseWindow(context: Context): Boolean {
        return LauncherConfig.readShowFloatingMouseWindow(context)
    }

    fun saveShowFloatingMouseWindow(context: Context, enabled: Boolean) {
        LauncherConfig.saveShowFloatingMouseWindow(context, enabled)
    }

    fun readTouchMouseInteractionMode(context: Context): TouchMouseInteractionMode {
        return LauncherConfig.readTouchMouseInteractionMode(context)
    }

    fun saveTouchMouseInteractionMode(context: Context, mode: TouchMouseInteractionMode) {
        LauncherConfig.saveTouchMouseInteractionMode(context, mode)
    }

    fun readCardPlayOptimizationMode(context: Context): CardPlayOptimizationMode {
        return LauncherConfig.readCardPlayOptimizationMode(context)
    }

    fun saveCardPlayOptimizationMode(context: Context, mode: CardPlayOptimizationMode) {
        LauncherConfig.saveCardPlayOptimizationMode(context, mode)
    }

    fun isBuiltInSoftKeyboardEnabled(context: Context): Boolean {
        return LauncherConfig.isBuiltInSoftKeyboardEnabled(context)
    }

    fun setBuiltInSoftKeyboardEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setBuiltInSoftKeyboardEnabled(context, enabled)
    }

    fun readFloatingToolButtons(context: Context): Set<String> {
        return LauncherConfig.readFloatingToolButtons(context)
    }

    fun saveFloatingToolButtons(context: Context, buttons: Set<String>) {
        LauncherConfig.saveFloatingToolButtons(context, buttons)
    }

    fun isHapticFeedbackEnabled(context: Context): Boolean {
        return LauncherConfig.isHapticFeedbackEnabled(context)
    }

    fun setHapticFeedbackEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setHapticFeedbackEnabled(context, enabled)
    }

    fun readAutoSwitchLeftAfterRightClick(context: Context): Boolean {
        return LauncherConfig.readAutoSwitchLeftAfterRightClick(context)
    }

    fun saveAutoSwitchLeftAfterRightClick(context: Context, enabled: Boolean) {
        LauncherConfig.saveAutoSwitchLeftAfterRightClick(context, enabled)
    }

    fun readTouchDoubleClickAsRightClick(context: Context): Boolean {
        return LauncherConfig.readTouchDoubleClickAsRightClick(context)
    }

    fun saveTouchDoubleClickAsRightClick(context: Context, enabled: Boolean) {
        LauncherConfig.saveTouchDoubleClickAsRightClick(context, enabled)
    }

    fun readIgnoreLongPressRightClickWhilePlayingCard(context: Context): Boolean {
        return LauncherConfig.readIgnoreLongPressRightClickWhilePlayingCard(context)
    }

    fun saveIgnoreLongPressRightClickWhilePlayingCard(context: Context, enabled: Boolean) {
        LauncherConfig.saveIgnoreLongPressRightClickWhilePlayingCard(context, enabled)
    }

    fun readRenderSurfaceBackend(context: Context): RenderSurfaceBackend {
        return LauncherConfig.readRenderSurfaceBackend(context)
    }

    fun saveRenderSurfaceBackend(context: Context, backend: RenderSurfaceBackend) {
        LauncherConfig.saveRenderSurfaceBackend(context, backend)
    }

    fun readRendererSelectionMode(context: Context): RendererSelectionMode {
        return LauncherConfig.readRendererSelectionMode(context)
    }

    fun saveRendererSelectionMode(context: Context, mode: RendererSelectionMode) {
        LauncherConfig.saveRendererSelectionMode(context, mode)
    }

    fun readManualRendererBackend(context: Context): RendererBackend {
        return LauncherConfig.readManualRendererBackend(context)
    }

    fun saveManualRendererBackend(context: Context, backend: RendererBackend) {
        LauncherConfig.saveManualRendererBackend(context, backend)
    }

    fun readMobileGluesAnglePolicy(context: Context): MobileGluesAnglePolicy {
        return LauncherConfig.readMobileGluesAnglePolicy(context)
    }

    fun saveMobileGluesAnglePolicy(context: Context, policy: MobileGluesAnglePolicy) {
        LauncherConfig.saveMobileGluesAnglePolicy(context, policy)
    }

    fun readMobileGluesSettings(context: Context): MobileGluesSettings {
        return LauncherConfig.readMobileGluesSettings(context)
    }

    fun saveMobileGluesSettings(context: Context, settings: MobileGluesSettings) {
        LauncherConfig.saveMobileGluesSettings(context, settings)
    }

    fun readThemeMode(context: Context): LauncherThemeMode {
        return LauncherConfig.readThemeMode(context)
    }

    fun saveThemeMode(context: Context, themeMode: LauncherThemeMode) {
        LauncherConfig.saveThemeMode(context, themeMode)
    }

    fun readThemeColor(context: Context): LauncherThemeColor {
        return LauncherConfig.readThemeColor(context)
    }

    fun saveThemeColor(context: Context, themeColor: LauncherThemeColor) {
        LauncherConfig.saveThemeColor(context, themeColor)
    }

    fun readLauncherIconMode(context: Context): LauncherIconMode {
        return LauncherConfig.readLauncherIconMode(context)
    }

    fun saveLauncherIconMode(context: Context, iconMode: LauncherIconMode) {
        LauncherConfig.saveLauncherIconMode(context, iconMode)
    }

    fun readChromeBackgroundOpacity(context: Context): Float {
        return LauncherConfig.readChromeBackgroundOpacity(context)
    }

    fun saveChromeBackgroundOpacity(context: Context, opacity: Float) {
        LauncherConfig.saveChromeBackgroundOpacity(context, opacity)
    }

    fun normalizeChromeBackgroundOpacity(opacity: Float): Float {
        return LauncherConfig.normalizeChromeBackgroundOpacity(opacity)
    }

    fun readShowModFileName(context: Context): Boolean {
        return LauncherConfig.readShowModFileName(context)
    }

    fun saveShowModFileName(context: Context, enabled: Boolean) {
        LauncherConfig.saveShowModFileName(context, enabled)
    }

    fun readMobileHudEnabled(context: Context): Boolean {
        return LauncherConfig.readMobileHudEnabled(context)
    }

    fun saveMobileHudEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.saveMobileHudEnabled(context, enabled)
    }

    fun readCompendiumUpgradeTouchFixEnabled(context: Context): Boolean {
        return LauncherConfig.readCompendiumUpgradeTouchFixEnabled(context)
    }

    fun saveCompendiumUpgradeTouchFixEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.saveCompendiumUpgradeTouchFixEnabled(context, enabled)
    }

    fun isTogetherInSpireRouteLockEnabled(context: Context): Boolean {
        return LauncherConfig.isTogetherInSpireRouteLockEnabled(context)
    }

    fun setTogetherInSpireRouteLockEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setTogetherInSpireRouteLockEnabled(context, enabled)
    }

    fun isTogetherInSpireEasyTierAutofillEnabled(context: Context): Boolean {
        return LauncherConfig.isTogetherInSpireEasyTierAutofillEnabled(context)
    }

    fun setTogetherInSpireEasyTierAutofillEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setTogetherInSpireEasyTierAutofillEnabled(context, enabled)
    }

    fun readVirtualResolutionMode(context: Context): VirtualResolutionMode {
        return LauncherConfig.readVirtualResolutionMode(context)
    }

    fun saveVirtualResolutionMode(context: Context, mode: VirtualResolutionMode) {
        LauncherConfig.saveVirtualResolutionMode(context, mode)
    }

    fun isDisplayCutoutAvoidanceEnabled(context: Context): Boolean {
        return LauncherConfig.isDisplayCutoutAvoidanceEnabled(context)
    }

    fun setDisplayCutoutAvoidanceEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setDisplayCutoutAvoidanceEnabled(context, enabled)
    }

    fun isScreenBottomCropEnabled(context: Context): Boolean {
        return LauncherConfig.isScreenBottomCropEnabled(context)
    }

    fun setScreenBottomCropEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setScreenBottomCropEnabled(context, enabled)
    }

    fun isRamSaverEnabled(context: Context): Boolean {
        return LauncherConfig.isRamSaverEnabled(context)
    }

    fun setRamSaverEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setRamSaverEnabled(context, enabled)
    }

    fun isMtsPatchCacheEnabled(context: Context): Boolean {
        return LauncherConfig.isMtsPatchCacheEnabled(context)
    }

    fun setMtsPatchCacheEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setMtsPatchCacheEnabled(context, enabled)
    }

    fun isGamePerformanceOverlayEnabled(context: Context): Boolean {
        return LauncherConfig.isGamePerformanceOverlayEnabled(context)
    }

    fun setGamePerformanceOverlayEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setGamePerformanceOverlayEnabled(context, enabled)
    }

    fun isSustainedPerformanceModeEnabled(context: Context): Boolean {
        return LauncherConfig.isSustainedPerformanceModeEnabled(context)
    }

    fun setSustainedPerformanceModeEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setSustainedPerformanceModeEnabled(context, enabled)
    }

    fun isLwjglDebugEnabled(context: Context): Boolean {
        return LauncherConfig.isLwjglDebugEnabled(context)
    }

    fun setLwjglDebugEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setLwjglDebugEnabled(context, enabled)
    }

    @JvmStatic
    fun isPreloadAllJreLibrariesEnabled(context: Context): Boolean {
        return LauncherConfig.isPreloadAllJreLibrariesEnabled(context)
    }

    @JvmStatic
    fun setPreloadAllJreLibrariesEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setPreloadAllJreLibrariesEnabled(context, enabled)
    }

    fun isLogcatCaptureEnabled(context: Context): Boolean {
        return LauncherConfig.isLogcatCaptureEnabled(context)
    }

    fun setLogcatCaptureEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setLogcatCaptureEnabled(context, enabled)
    }

    fun isLauncherLogcatCaptureEnabled(context: Context): Boolean {
        return LauncherConfig.isLauncherLogcatCaptureEnabled(context)
    }

    fun setLauncherLogcatCaptureEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setLauncherLogcatCaptureEnabled(context, enabled)
    }

    fun isJvmLogcatMirrorEnabled(context: Context): Boolean {
        return LauncherConfig.isJvmLogcatMirrorEnabled(context)
    }

    fun setJvmLogcatMirrorEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setJvmLogcatMirrorEnabled(context, enabled)
    }

    fun isGpuResourceDiagEnabled(context: Context): Boolean {
        return LauncherConfig.isGpuResourceDiagEnabled(context)
    }

    fun setGpuResourceDiagEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setGpuResourceDiagEnabled(context, enabled)
    }

    fun readGpuResourceGuardianMode(context: Context): GpuResourceGuardianMode {
        return LauncherConfig.readGpuResourceGuardianMode(context)
    }

    fun saveGpuResourceGuardianMode(context: Context, mode: GpuResourceGuardianMode) {
        LauncherConfig.saveGpuResourceGuardianMode(context, mode)
    }

    fun isGpuResourceGuardianPressureDownscaleEnabled(context: Context): Boolean {
        return LauncherConfig.isGpuResourceGuardianPressureDownscaleEnabled(context)
    }

    fun setGpuResourceGuardianPressureDownscaleEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setGpuResourceGuardianPressureDownscaleEnabled(context, enabled)
    }

    fun resetGpuResourceGuardianMode(context: Context) {
        LauncherConfig.resetGpuResourceGuardianMode(context)
    }

    fun isGdxPadCursorDebugEnabled(context: Context): Boolean {
        return LauncherConfig.isGdxPadCursorDebugEnabled(context)
    }

    fun setGdxPadCursorDebugEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setGdxPadCursorDebugEnabled(context, enabled)
    }

    fun isGlBridgeSwapHeartbeatDebugEnabled(context: Context): Boolean {
        return LauncherConfig.isGlBridgeSwapHeartbeatDebugEnabled(context)
    }

    fun setGlBridgeSwapHeartbeatDebugEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setGlBridgeSwapHeartbeatDebugEnabled(context, enabled)
    }

    fun isAutoCheckUpdatesEnabled(context: Context): Boolean {
        return LauncherConfig.isAutoCheckUpdatesEnabled(context)
    }

    fun setAutoCheckUpdatesEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setAutoCheckUpdatesEnabled(context, enabled)
    }

    fun isSteamCloudWattAccelerationEnabled(context: Context): Boolean {
        return NetworkAccelerationPolicy.shouldUseAcceleratedLinks(
            context = context,
            configuredEnabled = LauncherConfig.isSteamCloudWattAccelerationEnabled(context),
        )
    }

    fun setSteamCloudWattAccelerationEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setSteamCloudWattAccelerationEnabled(context, enabled)
    }

    fun isSteamCloudAutoLaunchAfterSyncEnabled(context: Context): Boolean {
        return LauncherConfig.isSteamCloudAutoLaunchAfterSyncEnabled(context)
    }

    fun setSteamCloudAutoLaunchAfterSyncEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setSteamCloudAutoLaunchAfterSyncEnabled(context, enabled)
    }

    fun isSteamGamePresenceEnabled(context: Context): Boolean {
        return LauncherConfig.isSteamGamePresenceEnabled(context)
    }

    fun setSteamGamePresenceEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setSteamGamePresenceEnabled(context, enabled)
    }

    fun readRichPresenceDisplayPreferences(context: Context): RichPresenceDisplayPreferences {
        return LauncherConfig.readRichPresenceDisplayPreferences(context)
    }

    fun saveRichPresenceDisplayPreferences(
        context: Context,
        settings: RichPresenceDisplayPreferences,
    ) {
        LauncherConfig.saveRichPresenceDisplayPreferences(context, settings)
    }

    fun readWorkshopMaxConcurrentDownloads(context: Context): Int {
        return LauncherConfig.readWorkshopMaxConcurrentDownloads(context)
    }

    fun saveWorkshopMaxConcurrentDownloads(context: Context, value: Int) {
        LauncherConfig.saveWorkshopMaxConcurrentDownloads(context, value)
    }

    fun readWorkshopDownloadThreads(context: Context): Int {
        return LauncherConfig.readWorkshopDownloadThreads(context)
    }

    fun saveWorkshopDownloadThreads(context: Context, value: Int) {
        LauncherConfig.saveWorkshopDownloadThreads(context, value)
    }

    fun isWorkshopWattAccelerationEnabled(context: Context): Boolean {
        return NetworkAccelerationPolicy.shouldUseAcceleratedLinks(
            context = context,
            configuredEnabled = LauncherConfig.isWorkshopWattAccelerationEnabled(context),
        )
    }

    fun setWorkshopWattAccelerationEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setWorkshopWattAccelerationEnabled(context, enabled)
    }

    fun readWorkshopSteamLanguage(context: Context): SteamLanguagePreference {
        return SteamLanguagePreference.fromStorageValue(LauncherConfig.readWorkshopSteamLanguage(context))
    }

    fun saveWorkshopSteamLanguage(context: Context, value: SteamLanguagePreference) {
        LauncherConfig.saveWorkshopSteamLanguage(context, value.storageValue)
    }

    fun isWorkshopAutoImportEnabled(context: Context): Boolean {
        return LauncherConfig.isWorkshopAutoImportEnabled(context)
    }

    fun setWorkshopAutoImportEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setWorkshopAutoImportEnabled(context, enabled)
    }

    fun isWorkshopAutoImportAtlasDownscaleEnabled(context: Context): Boolean {
        return LauncherConfig.isWorkshopAutoImportAtlasDownscaleEnabled(context)
    }

    fun setWorkshopAutoImportAtlasDownscaleEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setWorkshopAutoImportAtlasDownscaleEnabled(context, enabled)
    }

    fun readWorkshopAutoImportAtlasDownscaleMaxEdgePx(context: Context): Int {
        return LauncherConfig.readWorkshopAutoImportAtlasDownscaleMaxEdgePx(context)
    }

    fun saveWorkshopAutoImportAtlasDownscaleMaxEdgePx(context: Context, maxEdgePx: Int) {
        LauncherConfig.saveWorkshopAutoImportAtlasDownscaleMaxEdgePx(context, maxEdgePx)
    }

    fun isWorkshopSubscribeWarningDismissed(context: Context): Boolean {
        return LauncherConfig.isWorkshopSubscribeWarningDismissed(context)
    }

    fun setWorkshopSubscribeWarningDismissed(context: Context, dismissed: Boolean) {
        LauncherConfig.setWorkshopSubscribeWarningDismissed(context, dismissed)
    }

    fun isEnabledModSizeWarningDismissed(context: Context): Boolean {
        return LauncherConfig.isEnabledModSizeWarningDismissed(context)
    }

    fun setEnabledModSizeWarningDismissed(context: Context, dismissed: Boolean) {
        LauncherConfig.setEnabledModSizeWarningDismissed(context, dismissed)
    }

    fun readLastWorkshopUpdateCheckAtMs(context: Context): Long {
        return LauncherConfig.readLastWorkshopUpdateCheckAtMs(context)
    }

    fun saveLastWorkshopUpdateCheckAtMs(context: Context, timestampMs: Long) {
        LauncherConfig.saveLastWorkshopUpdateCheckAtMs(context, timestampMs)
    }

    fun readSteamCloudSaveMode(context: Context): SteamCloudSaveMode {
        return LauncherConfig.readSteamCloudSaveMode(context)
    }

    fun saveSteamCloudSaveMode(context: Context, mode: SteamCloudSaveMode) {
        LauncherConfig.saveSteamCloudSaveMode(context, mode)
    }

    fun readSteamCloudSyncBlacklistPaths(context: Context): Set<String> {
        return LauncherConfig.readSteamCloudSyncBlacklistPaths(context)
    }

    fun saveSteamCloudSyncBlacklistPaths(context: Context, localRelativePaths: Set<String>) {
        LauncherConfig.saveSteamCloudSyncBlacklistPaths(context, localRelativePaths)
    }

    fun readPreferredUpdateMirrorId(context: Context): String {
        return LauncherConfig.readPreferredUpdateMirrorId(context)
    }

    fun savePreferredUpdateMirrorId(context: Context, mirrorId: String) {
        LauncherConfig.savePreferredUpdateMirrorId(context, mirrorId)
    }

    fun readLastUpdateCheckAtMs(context: Context): Long {
        return LauncherConfig.readLastUpdateCheckAtMs(context)
    }

    fun saveLastUpdateCheckAtMs(context: Context, timestampMs: Long) {
        LauncherConfig.saveLastUpdateCheckAtMs(context, timestampMs)
    }

    fun readLastKnownRemoteTag(context: Context): String? {
        return LauncherConfig.readLastKnownRemoteTag(context)
    }

    fun saveLastKnownRemoteTag(context: Context, tag: String?) {
        LauncherConfig.saveLastKnownRemoteTag(context, tag)
    }

    fun readLastSuccessfulMetadataSourceId(context: Context): String? {
        return LauncherConfig.readLastSuccessfulMetadataSourceId(context)
    }

    fun saveLastSuccessfulMetadataSourceId(context: Context, sourceId: String?) {
        LauncherConfig.saveLastSuccessfulMetadataSourceId(context, sourceId)
    }

    fun readLastSuccessfulDownloadSourceId(context: Context): String? {
        return LauncherConfig.readLastSuccessfulDownloadSourceId(context)
    }

    fun saveLastSuccessfulDownloadSourceId(context: Context, sourceId: String?) {
        LauncherConfig.saveLastSuccessfulDownloadSourceId(context, sourceId)
    }

    fun readLastUpdateErrorSummary(context: Context): String? {
        return LauncherConfig.readLastUpdateErrorSummary(context)
    }

    fun saveLastUpdateErrorSummary(context: Context, summary: String?) {
        LauncherConfig.saveLastUpdateErrorSummary(context, summary)
    }

    fun isFirstRunSetupCompleted(context: Context): Boolean {
        return LauncherConfig.isFirstRunSetupCompleted(context)
    }

    fun setFirstRunSetupCompleted(context: Context, completed: Boolean) {
        LauncherConfig.setFirstRunSetupCompleted(context, completed)
    }

    fun normalizeTargetFps(targetFps: Int): Int {
        return LauncherConfig.normalizeTargetFps(targetFps)
    }

    fun readTargetFps(context: Context): Int {
        return LauncherConfig.readTargetFps(context)
    }

    fun saveTargetFps(context: Context, targetFps: Int) {
        LauncherConfig.saveTargetFps(context, targetFps)
    }

    fun normalizeKeepScreenOnTimeoutMinutes(timeoutMinutes: Int): Int {
        return LauncherConfig.normalizeKeepScreenOnTimeoutMinutes(timeoutMinutes)
    }

    fun readKeepScreenOnTimeoutMinutes(context: Context): Int {
        return LauncherConfig.readKeepScreenOnTimeoutMinutes(context)
    }

    fun saveKeepScreenOnTimeoutMinutes(context: Context, timeoutMinutes: Int) {
        LauncherConfig.saveKeepScreenOnTimeoutMinutes(context, timeoutMinutes)
    }

    fun normalizeJvmHeapMaxMb(heapMaxMb: Int): Int {
        return LauncherConfig.normalizeJvmHeapMaxMb(heapMaxMb)
    }

    fun readJvmHeapMaxMb(context: Context): Int {
        return LauncherConfig.readJvmHeapMaxMb(context)
    }

    fun resolveJvmHeapStartMb(heapMaxMb: Int): Int {
        return LauncherConfig.resolveJvmHeapStartMb(heapMaxMb)
    }

    fun readJvmHeapStartMb(context: Context): Int {
        return LauncherConfig.readJvmHeapStartMb(context)
    }

    fun saveJvmHeapMaxMb(context: Context, heapMaxMb: Int) {
        LauncherConfig.saveJvmHeapMaxMb(context, heapMaxMb)
    }

    fun isJvmCompressedPointersEnabled(context: Context): Boolean {
        return LauncherConfig.isJvmCompressedPointersEnabled(context)
    }

    fun setJvmCompressedPointersEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setJvmCompressedPointersEnabled(context, enabled)
    }

    fun isJvmStringDeduplicationEnabled(context: Context): Boolean {
        return LauncherConfig.isJvmStringDeduplicationEnabled(context)
    }

    fun setJvmStringDeduplicationEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setJvmStringDeduplicationEnabled(context, enabled)
    }

    fun syncLauncherPrefsToDisk(context: Context): Boolean {
        return LauncherConfig.syncLauncherPrefsToDisk(context)
    }

    fun normalizePlayerName(name: String): String {
        return LauncherConfig.normalizePlayerName(name)
    }

    fun readPlayerName(context: Context): String {
        return LauncherConfig.readPlayerName(context)
    }

    fun savePlayerName(context: Context, name: String) {
        LauncherConfig.savePlayerName(context, name)
    }
}
