package io.stamethyst.ui.preferences

import android.content.Context
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
import io.stamethyst.config.LauncherConfig
import io.stamethyst.config.LauncherThemeColor
import io.stamethyst.config.LauncherThemeMode
import io.stamethyst.config.RenderSurfaceBackend

object LauncherPreferences {
    val DEFAULT_BACK_BEHAVIOR: BackBehavior
        get() = LauncherConfig.DEFAULT_BACK_BEHAVIOR
    val DEFAULT_BACK_IMMEDIATE_EXIT: Boolean
        get() = LauncherConfig.DEFAULT_BACK_IMMEDIATE_EXIT
    val DEFAULT_MANUAL_DISMISS_BOOT_OVERLAY: Boolean
        get() = LauncherConfig.DEFAULT_MANUAL_DISMISS_BOOT_OVERLAY
    val DEFAULT_TARGET_FPS: Int
        get() = LauncherConfig.DEFAULT_TARGET_FPS
    val TARGET_FPS_OPTIONS: IntArray
        get() = LauncherConfig.TARGET_FPS_OPTIONS.copyOf()
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
    val DEFAULT_SHOW_FLOATING_MOUSE_WINDOW: Boolean
        get() = LauncherConfig.DEFAULT_SHOW_FLOATING_MOUSE_WINDOW
    val DEFAULT_TOUCH_MOUSE_NEW_INTERACTION: Boolean
        get() = LauncherConfig.DEFAULT_TOUCH_MOUSE_NEW_INTERACTION
    val DEFAULT_LONG_PRESS_MOUSE_SHOWS_KEYBOARD: Boolean
        get() = LauncherConfig.DEFAULT_LONG_PRESS_MOUSE_SHOWS_KEYBOARD
    val DEFAULT_BUILT_IN_SOFT_KEYBOARD_ENABLED: Boolean
        get() = LauncherConfig.DEFAULT_BUILT_IN_SOFT_KEYBOARD_ENABLED
    val DEFAULT_AUTO_SWITCH_LEFT_AFTER_RIGHT_CLICK: Boolean
        get() = LauncherConfig.DEFAULT_AUTO_SWITCH_LEFT_AFTER_RIGHT_CLICK
    val DEFAULT_SHOW_MOD_FILE_NAME: Boolean
        get() = LauncherConfig.DEFAULT_SHOW_MOD_FILE_NAME
    val DEFAULT_MOBILE_HUD_ENABLED: Boolean
        get() = LauncherConfig.DEFAULT_MOBILE_HUD_ENABLED
    val DEFAULT_COMPENDIUM_UPGRADE_TOUCH_FIX_ENABLED: Boolean
        get() = LauncherConfig.DEFAULT_COMPENDIUM_UPGRADE_TOUCH_FIX_ENABLED
    val DEFAULT_VIRTUAL_RESOLUTION_MODE: VirtualResolutionMode
        get() = LauncherConfig.DEFAULT_VIRTUAL_RESOLUTION_MODE
    val DEFAULT_AVOID_DISPLAY_CUTOUT: Boolean
        get() = LauncherConfig.DEFAULT_AVOID_DISPLAY_CUTOUT
    val DEFAULT_CROP_SCREEN_BOTTOM: Boolean
        get() = LauncherConfig.DEFAULT_CROP_SCREEN_BOTTOM
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
    val DEFAULT_GDX_PAD_CURSOR_DEBUG: Boolean
        get() = LauncherConfig.DEFAULT_GDX_PAD_CURSOR_DEBUG
    val DEFAULT_GLBRIDGE_SWAP_HEARTBEAT_DEBUG: Boolean
        get() = LauncherConfig.DEFAULT_GLBRIDGE_SWAP_HEARTBEAT_DEBUG
    val DEFAULT_AUTO_CHECK_UPDATES_ENABLED: Boolean
        get() = LauncherConfig.DEFAULT_AUTO_CHECK_UPDATES_ENABLED
    val DEFAULT_STEAM_CLOUD_AUTO_PULL_BEFORE_LAUNCH_ENABLED: Boolean
        get() = LauncherConfig.DEFAULT_STEAM_CLOUD_AUTO_PULL_BEFORE_LAUNCH_ENABLED
    val DEFAULT_STEAM_CLOUD_AUTO_PUSH_AFTER_CLEAN_SHUTDOWN_ENABLED: Boolean
        get() = LauncherConfig.DEFAULT_STEAM_CLOUD_AUTO_PUSH_AFTER_CLEAN_SHUTDOWN_ENABLED
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

    fun readShowFloatingMouseWindow(context: Context): Boolean {
        return LauncherConfig.readShowFloatingMouseWindow(context)
    }

    fun saveShowFloatingMouseWindow(context: Context, enabled: Boolean) {
        LauncherConfig.saveShowFloatingMouseWindow(context, enabled)
    }

    fun readTouchMouseNewInteraction(context: Context): Boolean {
        return LauncherConfig.readTouchMouseNewInteraction(context)
    }

    fun saveTouchMouseNewInteraction(context: Context, enabled: Boolean) {
        LauncherConfig.saveTouchMouseNewInteraction(context, enabled)
    }

    fun readLongPressMouseShowsKeyboard(context: Context): Boolean {
        return LauncherConfig.readLongPressMouseShowsKeyboard(context)
    }

    fun saveLongPressMouseShowsKeyboard(context: Context, enabled: Boolean) {
        LauncherConfig.saveLongPressMouseShowsKeyboard(context, enabled)
    }

    fun isBuiltInSoftKeyboardEnabled(context: Context): Boolean {
        return LauncherConfig.isBuiltInSoftKeyboardEnabled(context)
    }

    fun setBuiltInSoftKeyboardEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setBuiltInSoftKeyboardEnabled(context, enabled)
    }

    fun readAutoSwitchLeftAfterRightClick(context: Context): Boolean {
        return LauncherConfig.readAutoSwitchLeftAfterRightClick(context)
    }

    fun saveAutoSwitchLeftAfterRightClick(context: Context, enabled: Boolean) {
        LauncherConfig.saveAutoSwitchLeftAfterRightClick(context, enabled)
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

    fun isSteamCloudAutoPullBeforeLaunchEnabled(context: Context): Boolean {
        return LauncherConfig.isSteamCloudAutoPullBeforeLaunchEnabled(context)
    }

    fun setSteamCloudAutoPullBeforeLaunchEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setSteamCloudAutoPullBeforeLaunchEnabled(context, enabled)
    }

    fun isSteamCloudAutoPushAfterCleanShutdownEnabled(context: Context): Boolean {
        return LauncherConfig.isSteamCloudAutoPushAfterCleanShutdownEnabled(context)
    }

    fun setSteamCloudAutoPushAfterCleanShutdownEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setSteamCloudAutoPushAfterCleanShutdownEnabled(context, enabled)
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

    fun normalizeTargetFps(targetFps: Int): Int {
        return LauncherConfig.normalizeTargetFps(targetFps)
    }

    fun readTargetFps(context: Context): Int {
        return LauncherConfig.readTargetFps(context)
    }

    fun saveTargetFps(context: Context, targetFps: Int) {
        LauncherConfig.saveTargetFps(context, targetFps)
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
