package io.stamethyst.ui.preferences

import android.content.Context
import io.stamethyst.backend.render.RendererBackend
import io.stamethyst.backend.render.RendererSelectionMode
import io.stamethyst.config.BackBehavior
import io.stamethyst.config.LauncherConfig
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
    val DEFAULT_THEME_MODE: LauncherThemeMode
        get() = LauncherConfig.DEFAULT_THEME_MODE
    val DEFAULT_SHOW_FLOATING_MOUSE_WINDOW: Boolean
        get() = LauncherConfig.DEFAULT_SHOW_FLOATING_MOUSE_WINDOW
    val DEFAULT_LONG_PRESS_MOUSE_SHOWS_KEYBOARD: Boolean
        get() = LauncherConfig.DEFAULT_LONG_PRESS_MOUSE_SHOWS_KEYBOARD
    val DEFAULT_AUTO_SWITCH_LEFT_AFTER_RIGHT_CLICK: Boolean
        get() = LauncherConfig.DEFAULT_AUTO_SWITCH_LEFT_AFTER_RIGHT_CLICK
    val DEFAULT_SHOW_MOD_FILE_NAME: Boolean
        get() = LauncherConfig.DEFAULT_SHOW_MOD_FILE_NAME
    val DEFAULT_MOBILE_HUD_ENABLED: Boolean
        get() = LauncherConfig.DEFAULT_MOBILE_HUD_ENABLED
    val DEFAULT_AVOID_DISPLAY_CUTOUT: Boolean
        get() = LauncherConfig.DEFAULT_AVOID_DISPLAY_CUTOUT
    val DEFAULT_SHOW_GAME_PERFORMANCE_OVERLAY: Boolean
        get() = LauncherConfig.DEFAULT_SHOW_GAME_PERFORMANCE_OVERLAY
    val DEFAULT_LWJGL_DEBUG: Boolean
        get() = LauncherConfig.DEFAULT_LWJGL_DEBUG
    val DEFAULT_JVM_LOGCAT_MIRROR_ENABLED: Boolean
        get() = LauncherConfig.DEFAULT_JVM_LOGCAT_MIRROR_ENABLED
    val DEFAULT_GDX_PAD_CURSOR_DEBUG: Boolean
        get() = LauncherConfig.DEFAULT_GDX_PAD_CURSOR_DEBUG
    val DEFAULT_GLBRIDGE_SWAP_HEARTBEAT_DEBUG: Boolean
        get() = LauncherConfig.DEFAULT_GLBRIDGE_SWAP_HEARTBEAT_DEBUG
    val DEFAULT_AUTO_CHECK_UPDATES_ENABLED: Boolean
        get() = LauncherConfig.DEFAULT_AUTO_CHECK_UPDATES_ENABLED
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

    fun readLongPressMouseShowsKeyboard(context: Context): Boolean {
        return LauncherConfig.readLongPressMouseShowsKeyboard(context)
    }

    fun saveLongPressMouseShowsKeyboard(context: Context, enabled: Boolean) {
        LauncherConfig.saveLongPressMouseShowsKeyboard(context, enabled)
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

    fun readThemeMode(context: Context): LauncherThemeMode {
        return LauncherConfig.readThemeMode(context)
    }

    fun saveThemeMode(context: Context, themeMode: LauncherThemeMode) {
        LauncherConfig.saveThemeMode(context, themeMode)
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

    fun isDisplayCutoutAvoidanceEnabled(context: Context): Boolean {
        return LauncherConfig.isDisplayCutoutAvoidanceEnabled(context)
    }

    fun setDisplayCutoutAvoidanceEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setDisplayCutoutAvoidanceEnabled(context, enabled)
    }

    fun isGamePerformanceOverlayEnabled(context: Context): Boolean {
        return LauncherConfig.isGamePerformanceOverlayEnabled(context)
    }

    fun setGamePerformanceOverlayEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setGamePerformanceOverlayEnabled(context, enabled)
    }

    fun isLwjglDebugEnabled(context: Context): Boolean {
        return LauncherConfig.isLwjglDebugEnabled(context)
    }

    fun setLwjglDebugEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setLwjglDebugEnabled(context, enabled)
    }

    fun isJvmLogcatMirrorEnabled(context: Context): Boolean {
        return LauncherConfig.isJvmLogcatMirrorEnabled(context)
    }

    fun setJvmLogcatMirrorEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setJvmLogcatMirrorEnabled(context, enabled)
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
