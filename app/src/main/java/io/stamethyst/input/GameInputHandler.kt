package io.stamethyst.input

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout
import io.stamethyst.FloatingMouseOverlayController
import io.stamethyst.StsGameActivity
import io.stamethyst.backend.bridge.AndroidGamepadGlfwMapper
import io.stamethyst.backend.bridge.AndroidGlfwKeycode
import io.stamethyst.config.TouchMouseInteractionMode
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
        internal fun isGamepadKeyEventSource(
            keyCode: Int,
            eventSource: Int,
            deviceSources: Int?
        ): Boolean {
            if (!AndroidGamepadGlfwMapper.isGamepadKeyCode(keyCode)) return false
            if (isDedicatedGamepadButtonKeyCode(keyCode)) {
                return hasGamepadSource(eventSource) ||
                    (deviceSources != null && hasGamepadSource(deviceSources))
            }
            if (hasKeyboardSource(eventSource)) return false
            if (hasGamepadSource(eventSource)) return true
            return deviceSources != null &&
                hasGamepadSource(deviceSources) &&
                !hasKeyboardSource(deviceSources)
        }

        private fun isDedicatedGamepadButtonKeyCode(keyCode: Int): Boolean {
            return when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_BUTTON_1,
                KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_2,
                KeyEvent.KEYCODE_BUTTON_X,
                KeyEvent.KEYCODE_BUTTON_3,
                KeyEvent.KEYCODE_BUTTON_Y,
                KeyEvent.KEYCODE_BUTTON_4,
                KeyEvent.KEYCODE_BUTTON_L1,
                KeyEvent.KEYCODE_BUTTON_5,
                KeyEvent.KEYCODE_BUTTON_R1,
                KeyEvent.KEYCODE_BUTTON_6,
                KeyEvent.KEYCODE_BUTTON_SELECT,
                KeyEvent.KEYCODE_BUTTON_7,
                KeyEvent.KEYCODE_BUTTON_START,
                KeyEvent.KEYCODE_BUTTON_8,
                KeyEvent.KEYCODE_BUTTON_MODE,
                KeyEvent.KEYCODE_BUTTON_THUMBL,
                KeyEvent.KEYCODE_BUTTON_9,
                KeyEvent.KEYCODE_BUTTON_THUMBR,
                KeyEvent.KEYCODE_BUTTON_10,
                KeyEvent.KEYCODE_BUTTON_L2,
                KeyEvent.KEYCODE_BUTTON_R2 -> true
                else -> false
            }
        }

        private fun hasKeyboardSource(source: Int): Boolean {
            return (source and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD
        }

        private fun hasGamepadSource(source: Int): Boolean {
            return (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
                (source and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
        }
    }

    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var gamepadDirectInputEnableAttempted = false

    private var floatingMouseController: FloatingMouseOverlayController? = null

    fun initFloatingMouseControls(
        host: FrameLayout,
        autoSwitchLeftAfterRightClick: Boolean,
        touchMouseInteractionMode: TouchMouseInteractionMode,
        builtInSoftKeyboardEnabled: Boolean
    ) {
        floatingMouseController = FloatingMouseOverlayController(
            activity = activity,
            isNativeInputDispatchReady = isInputDispatchReady,
            requestRenderViewFocus = requestRenderViewFocus,
            autoSwitchBackToLeftAfterRightClick = autoSwitchLeftAfterRightClick,
            touchMouseInteractionMode = touchMouseInteractionMode,
            builtInSoftKeyboardEnabled = builtInSoftKeyboardEnabled
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

    fun isSoftKeyboardSessionActive(): Boolean {
        return floatingMouseController?.isSoftKeyboardSessionActive() == true
    }

    // ==================== Touch Input ====================

    @SuppressLint("ClickableViewAccessibility")
    fun handleTouchEvent(event: MotionEvent): Boolean {
        if (!isInputDispatchReady()) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                moveCursor(event.getX(0), event.getY(0))
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
                moveCursor(event.getX(downIndex), event.getY(downIndex))
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
                    moveCursor(event.getX(pointerIndex), event.getY(pointerIndex))
                    return true
                }
                return false
            }

            MotionEvent.ACTION_POINTER_UP -> {
                handlePointerUp(event)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
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
                moveCursor(event.getX(i), event.getY(i))
                break
            }
        }
        if (remaining <= 0) {
            releaseTouchButtonIfNeeded()
            resetTouchState()
        }
    }

    private fun moveCursor(x: Float, y: Float) {
        if (!isInputDispatchReady()) return
        mapToWindowCoords(x, y) { mappedX, mappedY, windowHeight ->
            val rawCursorY = (windowHeight - 1f - mappedY).coerceIn(0f, windowHeight - 1f)
            CallbackBridge.sendCursorPos(mappedX, rawCursorY)
        }
    }

    private inline fun mapToWindowCoords(
        viewX: Float,
        viewY: Float,
        onMapped: (x: Float, y: Float, windowHeight: Int) -> Unit
    ) {
        val rawViewWidth = getRenderViewWidth()
        val rawViewHeight = getRenderViewHeight()
        val targetWindowWidth = getTargetWindowWidth()
        val targetWindowHeight = getTargetWindowHeight()
        mapViewToWindowCoords(
            viewX = viewX,
            viewY = viewY,
            rawViewWidth = rawViewWidth,
            rawViewHeight = rawViewHeight,
            windowWidthRaw = targetWindowWidth,
            windowHeightRaw = targetWindowHeight,
            onMapped = onMapped
        )
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
        return isGamepadKeyEventSource(
            keyCode = event.keyCode,
            eventSource = event.source,
            deviceSources = event.device?.sources
        )
    }

    private fun isGamepadMotionEvent(event: MotionEvent?): Boolean {
        if (event == null || event.actionMasked != MotionEvent.ACTION_MOVE) return false
        val source = event.source
        return (source and InputDevice.SOURCE_JOYSTICK) != 0 ||
            (source and InputDevice.SOURCE_GAMEPAD) != 0
    }

    fun handleGamepadKeyEvent(event: KeyEvent): Boolean {
        val action = event.action
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return false
        if (!AndroidGamepadGlfwMapper.isGamepadKeyCode(event.keyCode)) return false
        if (!isInputDispatchReady()) return true

        ensureGamepadDirectInputEnabled()
        return AndroidGamepadGlfwMapper.writeKeyEvent(event.keyCode, action == KeyEvent.ACTION_DOWN)
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
