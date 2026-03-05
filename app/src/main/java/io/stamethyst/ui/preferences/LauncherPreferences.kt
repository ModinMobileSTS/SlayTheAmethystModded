package io.stamethyst.ui.preferences

import android.content.Context
import io.stamethyst.config.BackBehavior
import io.stamethyst.config.LauncherConfig

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
    val DEFAULT_SHOW_FLOATING_MOUSE_WINDOW: Boolean
        get() = LauncherConfig.DEFAULT_SHOW_FLOATING_MOUSE_WINDOW
    val DEFAULT_LONG_PRESS_MOUSE_SHOWS_KEYBOARD: Boolean
        get() = LauncherConfig.DEFAULT_LONG_PRESS_MOUSE_SHOWS_KEYBOARD
    val DEFAULT_AUTO_SWITCH_LEFT_AFTER_RIGHT_CLICK: Boolean
        get() = LauncherConfig.DEFAULT_AUTO_SWITCH_LEFT_AFTER_RIGHT_CLICK
    val DEFAULT_MOBILE_HUD_ENABLED: Boolean
        get() = LauncherConfig.DEFAULT_MOBILE_HUD_ENABLED
    val DEFAULT_LWJGL_DEBUG: Boolean
        get() = LauncherConfig.DEFAULT_LWJGL_DEBUG
    val DEFAULT_GDX_PAD_CURSOR_DEBUG: Boolean
        get() = LauncherConfig.DEFAULT_GDX_PAD_CURSOR_DEBUG
    val DEFAULT_GLBRIDGE_SWAP_HEARTBEAT_DEBUG: Boolean
        get() = LauncherConfig.DEFAULT_GLBRIDGE_SWAP_HEARTBEAT_DEBUG

    val DEFAULT_JVM_HEAP_MAX_MB: Int
        get() = LauncherConfig.DEFAULT_JVM_HEAP_MAX_MB
    val MIN_JVM_HEAP_MAX_MB: Int
        get() = LauncherConfig.MIN_JVM_HEAP_MAX_MB
    val MAX_JVM_HEAP_MAX_MB: Int
        get() = LauncherConfig.MAX_JVM_HEAP_MAX_MB
    val JVM_HEAP_STEP_MB: Int
        get() = LauncherConfig.JVM_HEAP_STEP_MB

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

    fun readMobileHudEnabled(context: Context): Boolean {
        return LauncherConfig.readMobileHudEnabled(context)
    }

    fun saveMobileHudEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.saveMobileHudEnabled(context, enabled)
    }

    fun isLwjglDebugEnabled(context: Context): Boolean {
        return LauncherConfig.isLwjglDebugEnabled(context)
    }

    fun setLwjglDebugEnabled(context: Context, enabled: Boolean) {
        LauncherConfig.setLwjglDebugEnabled(context, enabled)
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
}
