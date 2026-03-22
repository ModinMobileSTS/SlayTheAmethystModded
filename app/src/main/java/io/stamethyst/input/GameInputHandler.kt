package io.stamethyst.input

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout
import io.stamethyst.FloatingMouseOverlayController
import io.stamethyst.StsGameActivity
import io.stamethyst.backend.bridge.AndroidGamepadGlfwMapper
import io.stamethyst.backend.bridge.AndroidGlfwKeycode
import net.kdt.pojavlaunch.LwjglGlfwKeycode
import org.lwjgl.glfw.CallbackBridge
import kotlin.text.iterator

/**
 * Handles all input events: touch, mouse, gamepad, keyboard, and volume keys.
 * Bridges Android input events to GLFW callbacks.
 */
class GameInputHandler(
    private val activity: StsGameActivity,
    private val isInputDispatchReady: () -> Boolean,
    private val requestRenderViewFocus: () -> Unit,
    private val getRenderViewWidth: () -> Int,
    private val getRenderViewHeight: () -> Int,
    private val getTargetWindowWidth: () -> Int,
    private val getTargetWindowHeight: () -> Int
) {
    companion object {
        private const val INPUT_DIAG_TAG = "STS-InputDiag"
        private const val MOVE_DIAG_THROTTLE_MS = 750L
    }

    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var gamepadDirectInputEnableAttempted = false
    private var lastMoveDiagLoggedAtMs = 0L

    private var floatingMouseController: FloatingMouseOverlayController? = null

    fun initFloatingMouseControls(
        host: FrameLayout,
        autoSwitchLeftAfterRightClick: Boolean,
        longPressMouseShowsKeyboard: Boolean,
        doubleTapLocksClicksEnabled: Boolean
    ) {
        floatingMouseController = FloatingMouseOverlayController(
            activity = activity,
            isNativeInputDispatchReady = isInputDispatchReady,
            requestRenderViewFocus = requestRenderViewFocus,
            autoSwitchBackToLeftAfterRightClick = autoSwitchLeftAfterRightClick,
            longPressMouseShowsKeyboard = longPressMouseShowsKeyboard,
            doubleTapLocksClicksEnabled = doubleTapLocksClicksEnabled
        ).also { controller ->
            controller.attachToHost(host)
        }
    }

    fun onDestroy() {
        resetGamepadState()
        hideSoftKeyboard()
        floatingMouseController?.onDestroy()
        floatingMouseController = null
    }

    fun updateFloatingMouseVisibility(
        showFloatingMouseWindow: Boolean,
        runtimeLifecycleReady: Boolean,
        bootOverlayDismissed: Boolean,
        backExitRequested: Boolean
    ) {
        val shouldShow = showFloatingMouseWindow && runtimeLifecycleReady && bootOverlayDismissed && !backExitRequested
        floatingMouseController?.updateVisibility(shouldShow)
    }

    fun hideSoftKeyboard() {
        floatingMouseController?.hideSoftKeyboard()
    }

    // ==================== Touch Input ====================

    @SuppressLint("ClickableViewAccessibility")
    fun handleTouchEvent(event: MotionEvent): Boolean {
        if (!isInputDispatchReady()) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                moveCursor(event.getX(0), event.getY(0), "touch_down")
                maybeLogTouchButtonRouting("touch_down")
                if (shouldDispatchTouchButtons()) {
                    pressTouchButtonIfNeeded()
                } else {
                    releaseTouchButtonIfNeeded()
                }
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val downIndex = event.actionIndex
                activePointerId = event.getPointerId(downIndex)
                moveCursor(event.getX(downIndex), event.getY(downIndex), "touch_pointer_down")
                maybeLogTouchButtonRouting("touch_pointer_down")
                if (shouldDispatchTouchButtons()) {
                    pressTouchButtonIfNeeded()
                } else {
                    releaseTouchButtonIfNeeded()
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                var pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex < 0 && event.pointerCount > 0) {
                    activePointerId = event.getPointerId(0)
                    pointerIndex = 0
                }
                if (pointerIndex >= 0) {
                    moveCursor(event.getX(pointerIndex), event.getY(pointerIndex), "touch_move")
                    return true
                }
                return false
            }

            MotionEvent.ACTION_POINTER_UP -> {
                handlePointerUp(event)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                maybeLogTouchButtonRouting(
                    if (event.actionMasked == MotionEvent.ACTION_UP) "touch_up" else "touch_cancel"
                )
                releaseTouchButtonIfNeeded()
                resetTouchState()
                return true
            }

            else -> return false
        }
    }

    private fun handlePointerUp(event: MotionEvent) {
        val actionIndex = event.actionIndex
        val pointerId = event.getPointerId(actionIndex)
        val remaining = event.pointerCount - 1

        if (pointerId == activePointerId) {
            activePointerId = MotionEvent.INVALID_POINTER_ID
            for (i in 0 until event.pointerCount) {
                if (i == actionIndex) continue
                activePointerId = event.getPointerId(i)
                moveCursor(event.getX(i), event.getY(i), "touch_pointer_promote")
                break
            }
        }
        if (remaining <= 0) {
            releaseTouchButtonIfNeeded()
            resetTouchState()
        }
    }

    private fun moveCursor(x: Float, y: Float, diagReason: String? = null) {
        if (!isInputDispatchReady()) return
        val mapped = mapToWindowCoords(x, y, diagReason)
        val targetWindowHeight = getTargetWindowHeight().coerceAtLeast(1)
        val rawCursorY = (targetWindowHeight - 1f - mapped[1]).coerceIn(0f, targetWindowHeight - 1f)
        maybeLogCursorDispatch(diagReason, mapped[0], mapped[1], rawCursorY, targetWindowHeight)
        CallbackBridge.sendCursorPos(mapped[0], rawCursorY)
    }

    private fun mapToWindowCoords(viewX: Float, viewY: Float, diagReason: String? = null): FloatArray {
        val rawViewWidth = getRenderViewWidth()
        val rawViewHeight = getRenderViewHeight()
        val targetWindowWidth = getTargetWindowWidth()
        val targetWindowHeight = getTargetWindowHeight()
        val mapped = mapViewToWindowCoords(
            viewX = viewX,
            viewY = viewY,
            rawViewWidth = rawViewWidth,
            rawViewHeight = rawViewHeight,
            windowWidthRaw = targetWindowWidth,
            windowHeightRaw = targetWindowHeight
        )
        maybeLogInputMapping(
            reason = diagReason,
            viewX = viewX,
            viewY = viewY,
            rawViewWidth = rawViewWidth,
            rawViewHeight = rawViewHeight,
            targetWindowWidth = targetWindowWidth,
            targetWindowHeight = targetWindowHeight,
            mapped = mapped
        )
        return mapped
    }

    private fun maybeLogInputMapping(
        reason: String?,
        viewX: Float,
        viewY: Float,
        rawViewWidth: Int,
        rawViewHeight: Int,
        targetWindowWidth: Int,
        targetWindowHeight: Int,
        mapped: FloatArray
    ) {
        if (reason == null) return
        if (reason == "touch_move") {
            val now = SystemClock.uptimeMillis()
            if (now - lastMoveDiagLoggedAtMs < MOVE_DIAG_THROTTLE_MS) {
                return
            }
            lastMoveDiagLoggedAtMs = now
        }
        val message =
            "InputCoordDiag: reason=$reason " +
                "view=${rawViewWidth}x${rawViewHeight} " +
                "target=${targetWindowWidth}x${targetWindowHeight} " +
                "bridgeWindow=${CallbackBridge.windowWidth}x${CallbackBridge.windowHeight} " +
                "bridgePhysical=${CallbackBridge.physicalWidth}x${CallbackBridge.physicalHeight} " +
                "point=${"%.1f".format(viewX)},${"%.1f".format(viewY)} " +
                "mapped=${"%.1f".format(mapped[0])},${"%.1f".format(mapped[1])}"
        println(message)
        Log.i(INPUT_DIAG_TAG, message)
    }

    private fun maybeLogCursorDispatch(
        reason: String?,
        mappedX: Float,
        mappedTopLeftY: Float,
        rawCursorY: Float,
        targetWindowHeight: Int
    ) {
        if (reason == null) return
        if (reason == "touch_move") {
            val now = SystemClock.uptimeMillis()
            if (now - lastMoveDiagLoggedAtMs < MOVE_DIAG_THROTTLE_MS) {
                return
            }
        }
        val message =
            "InputCoordDiag: reason=$reason " +
                "dispatch=mappedTopLeft=${"%.1f".format(mappedX)},${"%.1f".format(mappedTopLeftY)} " +
                "rawCursor=${"%.1f".format(mappedX)},${"%.1f".format(rawCursorY)} " +
                "targetHeight=$targetWindowHeight"
        println(message)
        Log.i(INPUT_DIAG_TAG, message)
    }

    private fun pressTouchButtonIfNeeded() {
        floatingMouseController?.pressTouchButtonIfNeeded()
    }

    private fun releaseTouchButtonIfNeeded() {
        floatingMouseController?.releaseTouchButtonIfNeeded()
    }

    private fun shouldDispatchTouchButtons(): Boolean {
        return floatingMouseController?.isTouchMouseLockEnabled() != true
    }

    private fun maybeLogTouchButtonRouting(reason: String) {
        val lockEnabled = floatingMouseController?.isTouchMouseLockEnabled() == true
        val dispatchButtons = !lockEnabled
        val message =
            "InputCoordDiag: reason=$reason " +
                "touchButtons dispatch=$dispatchButtons " +
                "lock=$lockEnabled " +
                "floatingMouse=${floatingMouseController != null}"
        println(message)
        Log.i(INPUT_DIAG_TAG, message)
    }

    private fun resetTouchState() {
        activePointerId = MotionEvent.INVALID_POINTER_ID
    }

    // ==================== Mouse/Generic Motion ====================

    fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        if (isGamepadMotionEvent(event)) {
            return handleGamepadMotionEvent(event)
        }

        val source = event.source
        if ((source and InputDevice.SOURCE_MOUSE) != 0 || (source and InputDevice.SOURCE_TOUCHPAD) != 0) {
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_MOVE -> {
                    moveCursor(event.x, event.y)
                    return true
                }

                MotionEvent.ACTION_SCROLL -> {
                    CallbackBridge.sendScroll(
                        event.getAxisValue(MotionEvent.AXIS_HSCROLL).toDouble(),
                        event.getAxisValue(MotionEvent.AXIS_VSCROLL).toDouble()
                    )
                    return true
                }

                MotionEvent.ACTION_BUTTON_PRESS -> return sendMouseButton(event.actionButton, true)
                MotionEvent.ACTION_BUTTON_RELEASE -> return sendMouseButton(event.actionButton, false)
            }
        }
        return false
    }

    private fun sendMouseButton(androidButton: Int, down: Boolean): Boolean {
        if (!isInputDispatchReady()) return true

        val glfwButton = when (androidButton) {
            MotionEvent.BUTTON_PRIMARY -> LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT.toInt()
            MotionEvent.BUTTON_SECONDARY -> LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT.toInt()
            MotionEvent.BUTTON_TERTIARY -> LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_MIDDLE.toInt()
            else -> return false
        }
        CallbackBridge.sendMouseButton(glfwButton, down)
        return true
    }

    // ==================== Gamepad Input ====================

    fun isGamepadKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false

        val source = event.source
        if ((source and InputDevice.SOURCE_GAMEPAD) != 0 || (source and InputDevice.SOURCE_JOYSTICK) != 0) {
            return true
        }
        val device = event.device ?: return false
        val deviceSources = device.sources
        return (deviceSources and InputDevice.SOURCE_GAMEPAD) != 0 ||
            (deviceSources and InputDevice.SOURCE_JOYSTICK) != 0
    }

    private fun isGamepadMotionEvent(event: MotionEvent?): Boolean {
        if (event == null || event.actionMasked != MotionEvent.ACTION_MOVE) return false
        val source = event.source
        return (source and InputDevice.SOURCE_JOYSTICK) != 0 ||
            (source and InputDevice.SOURCE_GAMEPAD) != 0
    }

    fun handleGamepadKeyEvent(event: KeyEvent): Boolean {
        if (!isInputDispatchReady()) return true

        ensureGamepadDirectInputEnabled()
        val action = event.action
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return true

        AndroidGamepadGlfwMapper.writeKeyEvent(event.keyCode, action == KeyEvent.ACTION_DOWN)
        return true
    }

    private fun handleGamepadMotionEvent(event: MotionEvent): Boolean {
        if (!isInputDispatchReady()) return true

        ensureGamepadDirectInputEnabled()
        AndroidGamepadGlfwMapper.writeMotionEvent(event)
        return true
    }

    private fun ensureGamepadDirectInputEnabled() {
        if (gamepadDirectInputEnableAttempted) return
        gamepadDirectInputEnableAttempted = true

        try {
            CallbackBridge.nativeEnableGamepadDirectInput()
        } catch (_: Throwable) {}
    }

    fun resetGamepadState() {
        try {
            AndroidGamepadGlfwMapper.resetState()
        } catch (_: Throwable) {}
    }

    // ==================== Keyboard Input ====================

    @Suppress("DEPRECATION")
    fun dispatchKeyboardEventToGame(event: KeyEvent): Boolean {
        if (!isInputDispatchReady()) return false

        if (event.action == KeyEvent.ACTION_MULTIPLE) {
            val chars = event.characters
            if (chars.isNullOrEmpty()) return true

            var handled = false
            for (ch in chars) {
                if (!Character.isISOControl(ch)) {
                    CallbackBridge.sendChar(ch, CallbackBridge.getCurrentMods())
                    handled = true
                }
            }
            return handled
        }

        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false

        val glfwKey = AndroidGlfwKeycode.toGlfw(event.keyCode)
        var handled = false

        if (glfwKey != AndroidGlfwKeycode.GLFW_KEY_UNKNOWN) {
            val isDown = event.action == KeyEvent.ACTION_DOWN
            CallbackBridge.setModifiers(glfwKey, isDown)
            CallbackBridge.sendKeyPress(glfwKey, 0, CallbackBridge.getCurrentMods(), isDown)
            handled = true
        }

        val unicode = event.unicodeChar
        if (event.action == KeyEvent.ACTION_DOWN && unicode > 0 && !Character.isISOControl(unicode)) {
            CallbackBridge.sendChar(unicode.toChar(), CallbackBridge.getCurrentMods())
            handled = true
        }
        return handled
    }

    // ==================== Volume Keys ====================

    fun handleVolumeKeyEvent(event: KeyEvent): Boolean {
        val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false

        if (event.action == KeyEvent.ACTION_DOWN) {
            val direction = when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> AudioManager.ADJUST_RAISE
                KeyEvent.KEYCODE_VOLUME_DOWN -> AudioManager.ADJUST_LOWER
                KeyEvent.KEYCODE_VOLUME_MUTE -> AudioManager.ADJUST_TOGGLE_MUTE
                else -> return false
            }
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                direction,
                AudioManager.FLAG_SHOW_UI
            )
        }
        return true
    }
}
