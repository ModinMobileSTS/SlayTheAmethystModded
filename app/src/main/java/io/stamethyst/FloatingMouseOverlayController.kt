package io.stamethyst

import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.stamethyst.backend.bridge.AndroidGlfwKeycode
import net.kdt.pojavlaunch.LwjglGlfwKeycode
import org.lwjgl.glfw.CallbackBridge
import kotlin.math.abs
import kotlin.math.roundToInt

internal class FloatingMouseOverlayController(
    private val activity: AppCompatActivity,
    private val isNativeInputDispatchReady: () -> Boolean,
    private val requestRenderViewFocus: () -> Unit,
    private val autoSwitchBackToLeftAfterRightClick: Boolean,
    private val longPressMouseShowsKeyboard: Boolean,
) {
    private enum class TouchMouseMode {
        LEFT,
        RIGHT
    }

    companion object {
        private const val FLOATING_MOUSE_IDLE_ALPHA = 0.2f
        private const val FLOATING_MOUSE_ACTIVE_ALPHA = 1.0f
        private const val FLOATING_MOUSE_ACTIVE_KEEP_MS = 1500L
        private const val FLOATING_MOUSE_ALPHA_ANIM_DURATION_MS = 180L
        private const val FLOATING_MOUSE_SIDE_INSET_DP = 18
        private const val SPECIAL_KEYS_BAR_BOTTOM_MARGIN_DP = 8
        private const val SPECIAL_KEYS_BAR_PADDING_HORIZONTAL_DP = 8
        private const val SPECIAL_KEYS_BAR_PADDING_VERTICAL_DP = 6
        private const val SPECIAL_KEYS_BUTTON_HEIGHT_DP = 38
        private const val SPECIAL_KEYS_BUTTON_TEXT_SIZE_SP = 12f
        private const val SPECIAL_KEYS_BUTTON_MIN_WIDTH_DP = 46
        private const val SPECIAL_KEYS_BUTTON_SPACING_DP = 6
    }

    private var hostView: FrameLayout? = null
    private var touchMouseMode = TouchMouseMode.LEFT
    private var touchPressedButton = -1
    private var floatingMouseButton: FrameLayout? = null
    private var floatingMouseMainIcon: ImageView? = null
    private var imeProxyView: View? = null
    private var specialKeysBar: LinearLayout? = null
    private var floatingMouseTouchSlop = 0
    private var floatingMouseDragging = false
    private var floatingMouseLongPressTriggered = false
    private var floatingMousePressRunnable: Runnable? = null
    private var floatingMouseIdleRunnable: Runnable? = null
    private var floatingMouseDownRawX = 0f
    private var floatingMouseDownRawY = 0f
    private var floatingMouseDownLeft = 0
    private var floatingMouseDownTop = 0

    fun attachToHost(host: FrameLayout) {
        detachViews()
        hostView = host
        floatingMouseTouchSlop = ViewConfiguration.get(activity).scaledTouchSlop

        val imeView = GameImeProxyView(activity).apply {
            alpha = 0f
        }
        host.addView(
            imeView,
            FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.START)
        )
        imeProxyView = imeView

        val keysBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            alpha = 0.95f
            setPadding(
                dpToPx(SPECIAL_KEYS_BAR_PADDING_HORIZONTAL_DP),
                dpToPx(SPECIAL_KEYS_BAR_PADDING_VERTICAL_DP),
                dpToPx(SPECIAL_KEYS_BAR_PADDING_HORIZONTAL_DP),
                dpToPx(SPECIAL_KEYS_BAR_PADDING_VERTICAL_DP)
            )
            // Keep toolbar taps from stealing IME focus.
            isFocusable = false
            isFocusableInTouchMode = false
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(12).toFloat()
                setColor(0xCC121212.toInt())
            }
        }
        host.addView(
            keysBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                leftMargin = 0
                rightMargin = 0
                bottomMargin = dpToPx(SPECIAL_KEYS_BAR_BOTTOM_MARGIN_DP)
            }
        )
        specialKeysBar = keysBar
        populateSpecialKeysBar(keysBar)
        ViewCompat.setOnApplyWindowInsetsListener(keysBar) { _, insets ->
            updateSpecialKeysBarForInsets(insets)
            insets
        }
        ViewCompat.requestApplyInsets(keysBar)

        val buttonSize = dpToPx(56)
        val iconSize = dpToPx(30)
        val button = FrameLayout(activity).apply {
            setBackgroundResource(R.drawable.bg_touch_mouse_floating)
            visibility = View.GONE
            alpha = FLOATING_MOUSE_IDLE_ALPHA
            isClickable = true
            isFocusable = false
            elevation = dpToPx(8).toFloat()
        }
        val icon = ImageView(activity).apply {
            setImageResource(R.drawable.ic_touch_mouse_mode_left)
            setColorFilter(0xFFFFFFFF.toInt())
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        button.addView(
            icon,
            FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
        )
        host.addView(
            button,
            FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.TOP or Gravity.START).apply {
                leftMargin = 0
                topMargin = 0
            }
        )
        placeFloatingButtonAtRightCenter(host, button, buttonSize)

        floatingMouseMainIcon = icon
        floatingMouseButton = button
        button.setOnTouchListener { _, event -> handleFloatingMouseTouch(event) }
        updateTouchMouseModeUi()
    }

    fun onDestroy() {
        hideSoftKeyboard()
        cancelFloatingMouseLongPress()
        clearIdleRunnable()
        floatingMouseButton?.animate()?.cancel()
        releaseTouchButtonIfNeeded()
        detachViews()
        hostView = null
    }

    fun updateVisibility(shouldShow: Boolean) {
        val button = floatingMouseButton ?: return
        button.visibility = if (shouldShow) View.VISIBLE else View.GONE
        if (shouldShow) {
            button.animate().cancel()
            button.alpha = FLOATING_MOUSE_IDLE_ALPHA
        } else {
            clearIdleRunnable()
            button.animate().cancel()
        }
    }

    fun pressTouchButtonIfNeeded() {
        if (!isNativeInputDispatchReady.invoke()) {
            return
        }
        if (touchPressedButton >= 0) {
            return
        }
        val button = resolveTouchButton()
        CallbackBridge.sendMouseButton(button, true)
        touchPressedButton = button
        if (autoSwitchBackToLeftAfterRightClick && button == LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT.toInt()) {
            touchMouseMode = TouchMouseMode.LEFT
            updateTouchMouseModeUi()
        }
    }

    fun releaseTouchButtonIfNeeded() {
        if (!isNativeInputDispatchReady.invoke()) {
            touchPressedButton = -1
            return
        }
        if (touchPressedButton < 0) {
            return
        }
        CallbackBridge.sendMouseButton(touchPressedButton, false)
        touchPressedButton = -1
    }

    fun hideSoftKeyboard() {
        specialKeysBar?.visibility = View.GONE
        val inputView = imeProxyView ?: return
        val imm = activity.getSystemService(AppCompatActivity.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(inputView.windowToken, 0)
        requestRenderViewFocus.invoke()
    }

    private fun detachViews() {
        floatingMouseButton?.let { button ->
            (button.parent as? FrameLayout)?.removeView(button)
        }
        imeProxyView?.let { ime ->
            (ime.parent as? FrameLayout)?.removeView(ime)
        }
        specialKeysBar?.let { bar ->
            ViewCompat.setOnApplyWindowInsetsListener(bar, null)
            (bar.parent as? FrameLayout)?.removeView(bar)
        }
        floatingMouseButton = null
        floatingMouseMainIcon = null
        imeProxyView = null
        specialKeysBar = null
    }

    private fun handleFloatingMouseTouch(event: MotionEvent): Boolean {
        val button = floatingMouseButton ?: return false
        val params = button.layoutParams as? FrameLayout.LayoutParams ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                highlightFloatingMouse()
                floatingMouseDragging = false
                floatingMouseLongPressTriggered = false
                floatingMouseDownRawX = event.rawX
                floatingMouseDownRawY = event.rawY
                floatingMouseDownLeft = params.leftMargin
                floatingMouseDownTop = params.topMargin

                if (longPressMouseShowsKeyboard) {
                    val longPressRunnable = Runnable {
                        if (!floatingMouseDragging && !floatingMouseLongPressTriggered) {
                            floatingMouseLongPressTriggered = true
                            button.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            showSoftKeyboard()
                        }
                    }
                    floatingMousePressRunnable = longPressRunnable
                    button.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                } else {
                    floatingMousePressRunnable = null
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - floatingMouseDownRawX).toInt()
                val dy = (event.rawY - floatingMouseDownRawY).toInt()
                if (!floatingMouseDragging &&
                    (abs(dx) > floatingMouseTouchSlop || abs(dy) > floatingMouseTouchSlop)
                ) {
                    floatingMouseDragging = true
                    cancelFloatingMouseLongPress()
                }
                if (floatingMouseDragging) {
                    val parentView = button.parent as? View
                    val maxLeft = ((parentView?.width ?: 0) - button.width).coerceAtLeast(0)
                    val maxTop = ((parentView?.height ?: 0) - button.height).coerceAtLeast(0)
                    params.leftMargin = (floatingMouseDownLeft + dx).coerceIn(0, maxLeft)
                    params.topMargin = (floatingMouseDownTop + dy).coerceIn(0, maxTop)
                    button.layoutParams = params
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                cancelFloatingMouseLongPress()
                if (!floatingMouseDragging && !floatingMouseLongPressTriggered) {
                    button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    toggleTouchMouseMode()
                }
                floatingMouseDragging = false
                floatingMouseLongPressTriggered = false
                scheduleFloatingMouseIdle()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelFloatingMouseLongPress()
                floatingMouseDragging = false
                floatingMouseLongPressTriggered = false
                scheduleFloatingMouseIdle()
                return true
            }

            else -> return false
        }
    }

    private fun cancelFloatingMouseLongPress() {
        val button = floatingMouseButton ?: return
        floatingMousePressRunnable?.let { button.removeCallbacks(it) }
        floatingMousePressRunnable = null
    }

    private fun highlightFloatingMouse() {
        val button = floatingMouseButton ?: return
        clearIdleRunnable()
        button.animate().cancel()
        button.animate()
            .alpha(FLOATING_MOUSE_ACTIVE_ALPHA)
            .setDuration(FLOATING_MOUSE_ALPHA_ANIM_DURATION_MS)
            .start()
    }

    private fun clearIdleRunnable() {
        val button = floatingMouseButton ?: return
        floatingMouseIdleRunnable?.let { button.removeCallbacks(it) }
        floatingMouseIdleRunnable = null
    }

    private fun scheduleFloatingMouseIdle() {
        val button = floatingMouseButton ?: return
        clearIdleRunnable()
        val idleRunnable = Runnable {
            if (button.visibility != View.VISIBLE) {
                return@Runnable
            }
            button.animate().cancel()
            button.animate()
                .alpha(FLOATING_MOUSE_IDLE_ALPHA)
                .setDuration(FLOATING_MOUSE_ALPHA_ANIM_DURATION_MS)
                .start()
        }
        floatingMouseIdleRunnable = idleRunnable
        button.postDelayed(idleRunnable, FLOATING_MOUSE_ACTIVE_KEEP_MS)
    }

    private fun toggleTouchMouseMode() {
        releaseTouchButtonIfNeeded()
        touchMouseMode = if (touchMouseMode == TouchMouseMode.LEFT) {
            TouchMouseMode.RIGHT
        } else {
            TouchMouseMode.LEFT
        }
        updateTouchMouseModeUi()
    }

    private fun updateTouchMouseModeUi() {
        val leftMode = touchMouseMode == TouchMouseMode.LEFT
        val modeIconRes = if (leftMode) {
            R.drawable.ic_touch_mouse_mode_left
        } else {
            R.drawable.ic_touch_mouse_mode_right
        }
        floatingMouseMainIcon?.setImageResource(modeIconRes)
    }

    private fun resolveTouchButton(): Int {
        return if (touchMouseMode == TouchMouseMode.LEFT) {
            LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT.toInt()
        } else {
            LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT.toInt()
        }
    }

    private fun showSoftKeyboard() {
        val inputView = imeProxyView ?: return
        val imm = activity.getSystemService(AppCompatActivity.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        inputView.requestFocus()
        imm.restartInput(inputView)
        inputView.post {
            imm.showSoftInput(inputView, InputMethodManager.SHOW_IMPLICIT)
            specialKeysBar?.let { ViewCompat.requestApplyInsets(it) }
        }
    }

    private fun populateSpecialKeysBar(bar: LinearLayout) {
        val keys = listOf(
            "Esc" to KeyEvent.KEYCODE_ESCAPE,
            "Tab" to KeyEvent.KEYCODE_TAB,
            "↑" to KeyEvent.KEYCODE_DPAD_UP,
            "↓" to KeyEvent.KEYCODE_DPAD_DOWN,
            "←" to KeyEvent.KEYCODE_DPAD_LEFT,
            "→" to KeyEvent.KEYCODE_DPAD_RIGHT
        )
        val spacing = dpToPx(SPECIAL_KEYS_BUTTON_SPACING_DP)
        keys.forEachIndexed { index, (label, keyCode) ->
            val button = createSpecialKeyButton(label, keyCode)
            bar.addView(
                button,
                LinearLayout.LayoutParams(
                    0,
                    dpToPx(SPECIAL_KEYS_BUTTON_HEIGHT_DP),
                    1f
                ).apply {
                    if (index > 0) {
                        leftMargin = spacing
                    }
                }
            )
        }
    }

    private fun createSpecialKeyButton(label: String, keyCode: Int): TextView {
        return TextView(activity).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            textSize = SPECIAL_KEYS_BUTTON_TEXT_SIZE_SP
            minWidth = dpToPx(SPECIAL_KEYS_BUTTON_MIN_WIDTH_DP)
            isAllCaps = false
            isFocusable = false
            isFocusableInTouchMode = false
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(10).toFloat()
                setColor(0xFF2B2B2B.toInt())
            }
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                sendSyntheticSoftKey(keyCode)
            }
        }
    }

    private fun updateSpecialKeysBarForInsets(insets: WindowInsetsCompat) {
        val bar = specialKeysBar ?: return
        val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
        val imeInsetsBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        val navInsetsBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        val keyboardBottomInset = (imeInsetsBottom - navInsetsBottom).coerceAtLeast(0)

        val params = bar.layoutParams as? FrameLayout.LayoutParams ?: return
        params.bottomMargin = keyboardBottomInset + dpToPx(SPECIAL_KEYS_BAR_BOTTOM_MARGIN_DP)
        bar.layoutParams = params
        bar.visibility = if (imeVisible) View.VISIBLE else View.GONE
    }

    private fun sendSoftKeyboardText(text: CharSequence?): Boolean {
        if (text.isNullOrEmpty() || !isNativeInputDispatchReady.invoke()) {
            return false
        }
        var handled = false
        for (ch in text) {
            when (ch) {
                '\n', '\r' -> {
                    sendSyntheticSoftKey(KeyEvent.KEYCODE_ENTER)
                    handled = true
                }

                '\b' -> {
                    sendSyntheticSoftKey(KeyEvent.KEYCODE_DEL)
                    handled = true
                }

                else -> {
                    if (!Character.isISOControl(ch)) {
                        CallbackBridge.sendChar(ch, CallbackBridge.getCurrentMods())
                        handled = true
                    }
                }
            }
        }
        return handled
    }

    private fun sendSyntheticSoftKey(androidKeyCode: Int) {
        dispatchKeyboardEventToGame(KeyEvent(KeyEvent.ACTION_DOWN, androidKeyCode))
        dispatchKeyboardEventToGame(KeyEvent(KeyEvent.ACTION_UP, androidKeyCode))
    }

    private fun dispatchSoftKeyboardKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            return false
        }
        if (!isNativeInputDispatchReady.invoke()) {
            return true
        }
        return dispatchKeyboardEventToGame(event)
    }

    @Suppress("DEPRECATION")
    private fun dispatchKeyboardEventToGame(event: KeyEvent): Boolean {
        if (!isNativeInputDispatchReady.invoke()) {
            return false
        }
        if (event.action == KeyEvent.ACTION_MULTIPLE) {
            val chars = event.characters
            return if (!chars.isNullOrEmpty()) {
                sendSoftKeyboardText(chars)
            } else {
                true
            }
        }
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) {
            return false
        }

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

    private fun dpToPx(dp: Int): Int {
        return (activity.resources.displayMetrics.density * dp).roundToInt()
    }

    private fun placeFloatingButtonAtRightCenter(host: FrameLayout, button: FrameLayout, buttonSize: Int) {
        val inset = dpToPx(FLOATING_MOUSE_SIDE_INSET_DP)
        host.post {
            val params = button.layoutParams as? FrameLayout.LayoutParams ?: return@post
            val maxLeft = (host.width - buttonSize).coerceAtLeast(0)
            val maxTop = (host.height - buttonSize).coerceAtLeast(0)
            params.leftMargin = (maxLeft - inset).coerceAtLeast(0)
            params.topMargin = (maxTop / 2).coerceAtLeast(0)
            button.layoutParams = params
        }
    }

    private inner class GameImeProxyView(context: android.content.Context) : View(context) {
        init {
            isFocusable = true
            isFocusableInTouchMode = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            }
        }

        override fun onCheckIsTextEditor(): Boolean {
            return true
        }

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            return object : BaseInputConnection(this, false) {
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    sendSoftKeyboardText(text)
                    return true
                }

                override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    // Keep composition internal to IME and only forward committed text.
                    return true
                }

                override fun finishComposingText(): Boolean {
                    return true
                }

                override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                    val count = if (beforeLength > 0) beforeLength else 1
                    repeat(count) {
                        sendSyntheticSoftKey(KeyEvent.KEYCODE_DEL)
                    }
                    return true
                }

                override fun sendKeyEvent(event: KeyEvent): Boolean {
                    return dispatchSoftKeyboardKeyEvent(event)
                }

                override fun performEditorAction(actionCode: Int): Boolean {
                    sendSyntheticSoftKey(KeyEvent.KEYCODE_ENTER)
                    return true
                }
            }
        }
    }
}
