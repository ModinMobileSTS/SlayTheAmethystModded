package io.stamethyst

import android.util.Log
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.os.SystemClock
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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.stamethyst.backend.bridge.AndroidGlfwKeycode
import net.kdt.pojavlaunch.AWTInputBridge
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
    private val doubleTapLocksClicksEnabled: Boolean,
) {
    private enum class TouchMouseMode {
        LEFT,
        RIGHT
    }

    private enum class SoftKeyboardTarget {
        GLFW,
        AWT
    }

    private data class SpecialKeySpec(
        val label: String,
        val keyCode: Int,
        val toggleable: Boolean = false,
    )

    companion object {
        private const val IME_LOG_TAG = "STS-IME"
        private const val TEST_LOG_TAG = "STS-TEST"
        private const val FLOATING_MOUSE_IDLE_ALPHA = 0.2f
        private const val FLOATING_MOUSE_ACTIVE_ALPHA = 1.0f
        private const val FLOATING_MOUSE_ACTIVE_KEEP_MS = 1500L
        private const val FLOATING_MOUSE_ALPHA_ANIM_DURATION_MS = 180L
        private const val FLOATING_MOUSE_SIDE_INSET_DP = 18
        private const val FLOATING_MENU_ANCHOR_GAP_DP = 8
        private const val SPECIAL_KEYS_BAR_PADDING_HORIZONTAL_DP = 8
        private const val SPECIAL_KEYS_BAR_PADDING_VERTICAL_DP = 6
        private const val SPECIAL_KEYS_BUTTON_HEIGHT_DP = 38
        private const val SPECIAL_KEYS_BUTTON_TEXT_SIZE_SP = 12f
        private const val SPECIAL_KEYS_BUTTON_MIN_WIDTH_DP = 46
        private const val SPECIAL_KEYS_BUTTON_SPACING_DP = 6
        private const val SOFT_KEY_MIN_PRESS_MS = 70L
        private const val SOFT_TEXT_DUPLICATE_CROSS_SOURCE_WINDOW_MS = 150L
        private const val SOFT_TEXT_DUPLICATE_SAME_SOURCE_WINDOW_MS = 16L
        private const val AWT_VK_ENTER = 10
        private const val AWT_VK_BACK_SPACE = 8
        private const val AWT_VK_TAB = 9
        private const val AWT_VK_SHIFT = 16
        private const val AWT_VK_CONTROL = 17
        private const val AWT_VK_ALT = 18
        private const val AWT_VK_CAPS_LOCK = 20
        private const val AWT_VK_ESCAPE = 27
        private const val AWT_VK_LEFT = 37
        private const val AWT_VK_UP = 38
        private const val AWT_VK_RIGHT = 39
        private const val AWT_VK_DOWN = 40
        private const val AWT_VK_DELETE = 127
    }

    private var hostView: FrameLayout? = null
    private var touchMouseMode = TouchMouseMode.LEFT
    private var touchPressedButton = -1
    private var floatingMouseButton: FrameLayout? = null
    private var floatingMouseMainIcon: ImageView? = null
    private var imeProxyView: View? = null
    private var floatingMouseExpandedMenu: LinearLayout? = null
    private var floatingMouseTouchSlop = 0
    private var floatingMouseDoubleTapSlop = 0
    private var floatingMouseDragging = false
    private var floatingMouseLongPressTriggered = false
    private var floatingMousePressRunnable: Runnable? = null
    private var floatingMouseSingleTapRunnable: Runnable? = null
    private var floatingMouseIdleRunnable: Runnable? = null
    private var floatingMouseDownRawX = 0f
    private var floatingMouseDownRawY = 0f
    private var floatingMouseDownLeft = 0
    private var floatingMouseDownTop = 0
    private var floatingMouseLastTapUpAtMs = 0L
    private var floatingMouseLastTapUpRawX = 0f
    private var floatingMouseLastTapUpRawY = 0f
    private var touchMouseLockEnabled = false
    private val pendingSoftKeyReleaseRunnables = mutableMapOf<Int, Runnable>()
    private val floatingMouseDoubleTapTimeoutMs = ViewConfiguration.getDoubleTapTimeout().toLong()

    private val toggleSpecialKeyButtons = mutableMapOf<Int, View>()
    private val activeToggleSoftKeys = mutableMapOf<Int, SoftKeyboardTarget>()
    private var lastSoftTextPayload = ""
    private var lastSoftTextSource = ""
    private var lastSoftTextAtMs = 0L

    fun attachToHost(host: FrameLayout) {
        flushPendingSoftKeyReleases()
        releaseActiveToggleSoftKeys()
        detachViews()
        hostView = host
        val viewConfiguration = ViewConfiguration.get(activity)
        floatingMouseTouchSlop = viewConfiguration.scaledTouchSlop
        floatingMouseDoubleTapSlop = viewConfiguration.scaledDoubleTapSlop

        val imeView = GameImeProxyView(activity).apply {
            alpha = 0f
        }
        host.addView(
            imeView,
            FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.START)
        )
        imeProxyView = imeView

        val expandedMenu = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
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
            expandedMenu,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply {
                leftMargin = 0
                topMargin = 0
            }
        )
        floatingMouseExpandedMenu = expandedMenu
        populateFloatingMouseExpandedMenu(expandedMenu)

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
        flushPendingSoftKeyReleases()
        hideSoftKeyboard()
        cancelFloatingMouseLongPress()
        clearFloatingMouseSingleTap()
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
            hideFloatingMouseExpandedMenu()
            clearFloatingMouseSingleTap()
            clearIdleRunnable()
            button.animate().cancel()
        }
    }

    fun isTouchMouseLockEnabled(): Boolean {
        return touchMouseLockEnabled
    }

    fun pressTouchButtonIfNeeded() {
        if (!isNativeInputDispatchReady.invoke()) {
            logTest("FloatingMouse.press ignored native input not ready")
            return
        }
        if (touchPressedButton >= 0) {
            logTest("FloatingMouse.press ignored alreadyPressed button=$touchPressedButton")
            return
        }
        val button = resolveTouchButton()
        logTest(
            "FloatingMouse.press button=$button mode=$touchMouseMode " +
                "lockEnabled=$touchMouseLockEnabled autoLeftAfterRight=$autoSwitchBackToLeftAfterRightClick"
        )
        CallbackBridge.sendMouseButton(button, true)
        touchPressedButton = button
        if (autoSwitchBackToLeftAfterRightClick && button == LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT.toInt()) {
            touchMouseMode = TouchMouseMode.LEFT
            updateTouchMouseModeUi()
            logTest("FloatingMouse.autoSwitchedToLeftAfterRightClick")
        }
    }

    fun releaseTouchButtonIfNeeded() {
        if (!isNativeInputDispatchReady.invoke()) {
            logTest("FloatingMouse.release reset because native input not ready")
            touchPressedButton = -1
            return
        }
        if (touchPressedButton < 0) {
            logTest("FloatingMouse.release ignored because no pressed button")
            return
        }
        logTest("FloatingMouse.release button=$touchPressedButton")
        CallbackBridge.sendMouseButton(touchPressedButton, false)
        touchPressedButton = -1
    }

    fun hideSoftKeyboard() {
        flushPendingSoftKeyReleases()
        releaseActiveToggleSoftKeys()
        hideFloatingMouseExpandedMenu()
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
        floatingMouseExpandedMenu?.let { menu ->
            (menu.parent as? FrameLayout)?.removeView(menu)
        }
        floatingMouseButton = null
        floatingMouseMainIcon = null
        imeProxyView = null
        floatingMouseExpandedMenu = null
        toggleSpecialKeyButtons.clear()
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
                if (hasPendingFloatingMouseSingleTap() &&
                    !canCurrentTapBecomeDoubleTap(event.rawX, event.rawY)
                ) {
                    flushPendingFloatingMouseSingleTap()
                }

                if (longPressMouseShowsKeyboard) {
                    val longPressRunnable = Runnable {
                        if (!floatingMouseDragging && !floatingMouseLongPressTriggered) {
                            floatingMouseLongPressTriggered = true
                            flushPendingFloatingMouseSingleTap()
                            button.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            showFloatingMouseExpandedMenu()
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
                    flushPendingFloatingMouseSingleTap()
                }
                if (floatingMouseDragging) {
                    val parentView = button.parent as? View
                    val maxLeft = ((parentView?.width ?: 0) - button.width).coerceAtLeast(0)
                    val maxTop = ((parentView?.height ?: 0) - button.height).coerceAtLeast(0)
                    params.leftMargin = (floatingMouseDownLeft + dx).coerceIn(0, maxLeft)
                    params.topMargin = (floatingMouseDownTop + dy).coerceIn(0, maxTop)
                    button.layoutParams = params
                    updateFloatingMouseExpandedMenuPosition()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                cancelFloatingMouseLongPress()
                if (!floatingMouseDragging && !floatingMouseLongPressTriggered) {
                    handleFloatingMouseTap(button, event.rawX, event.rawY)
                }
                floatingMouseDragging = false
                floatingMouseLongPressTriggered = false
                scheduleFloatingMouseIdle()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelFloatingMouseLongPress()
                flushPendingFloatingMouseSingleTap()
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

    private fun handleFloatingMouseTap(button: View, rawX: Float, rawY: Float) {
        if (!doubleTapLocksClicksEnabled) {
            clearFloatingMouseSingleTap()
            floatingMouseLastTapUpAtMs = 0L
            button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            toggleTouchMouseMode()
            return
        }
        val now = SystemClock.uptimeMillis()
        if (canCurrentTapBecomeDoubleTap(rawX, rawY, now)) {
            clearFloatingMouseSingleTap()
            floatingMouseLastTapUpAtMs = 0L
            button.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            toggleTouchMouseLock()
            return
        }
        if (hasPendingFloatingMouseSingleTap()) {
            flushPendingFloatingMouseSingleTap()
        }
        scheduleFloatingMouseSingleTap(button, rawX, rawY, now)
    }

    private fun hasPendingFloatingMouseSingleTap(): Boolean {
        return floatingMouseSingleTapRunnable != null
    }

    private fun canCurrentTapBecomeDoubleTap(
        rawX: Float,
        rawY: Float,
        now: Long = SystemClock.uptimeMillis()
    ): Boolean {
        if (!hasPendingFloatingMouseSingleTap()) {
            return false
        }
        if (now - floatingMouseLastTapUpAtMs > floatingMouseDoubleTapTimeoutMs) {
            return false
        }
        return abs(rawX - floatingMouseLastTapUpRawX) <= floatingMouseDoubleTapSlop &&
            abs(rawY - floatingMouseLastTapUpRawY) <= floatingMouseDoubleTapSlop
    }

    private fun scheduleFloatingMouseSingleTap(
        button: View,
        rawX: Float,
        rawY: Float,
        timestampMs: Long
    ) {
        clearFloatingMouseSingleTap()
        floatingMouseLastTapUpAtMs = timestampMs
        floatingMouseLastTapUpRawX = rawX
        floatingMouseLastTapUpRawY = rawY
        val singleTapRunnable = Runnable {
            floatingMouseSingleTapRunnable = null
            floatingMouseLastTapUpAtMs = 0L
            button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            toggleTouchMouseMode()
        }
        floatingMouseSingleTapRunnable = singleTapRunnable
        button.postDelayed(singleTapRunnable, floatingMouseDoubleTapTimeoutMs)
    }

    private fun clearFloatingMouseSingleTap() {
        val singleTapRunnable = floatingMouseSingleTapRunnable ?: return
        floatingMouseButton?.removeCallbacks(singleTapRunnable)
        floatingMouseSingleTapRunnable = null
    }

    private fun flushPendingFloatingMouseSingleTap() {
        val singleTapRunnable = floatingMouseSingleTapRunnable ?: return
        floatingMouseButton?.removeCallbacks(singleTapRunnable)
        singleTapRunnable.run()
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
        logTest("FloatingMouse.toggleMode newMode=$touchMouseMode")
        updateTouchMouseModeUi()
    }

    private fun toggleTouchMouseLock() {
        if (!doubleTapLocksClicksEnabled) {
            return
        }
        releaseTouchButtonIfNeeded()
        touchMouseLockEnabled = !touchMouseLockEnabled
        logTest("FloatingMouse.toggleLock enabled=$touchMouseLockEnabled")
        updateTouchMouseModeUi()
        val messageRes = if (touchMouseLockEnabled) {
            R.string.touch_mouse_lock_enabled_toast
        } else {
            R.string.touch_mouse_lock_disabled_toast
        }
        Toast.makeText(activity, messageRes, Toast.LENGTH_SHORT).show()
    }

    private fun updateTouchMouseModeUi() {
        val leftMode = touchMouseMode == TouchMouseMode.LEFT
        val modeIconRes = if (leftMode) {
            R.drawable.ic_touch_mouse_mode_left
        } else {
            R.drawable.ic_touch_mouse_mode_right
        }
        floatingMouseMainIcon?.setImageResource(modeIconRes)
        floatingMouseMainIcon?.setColorFilter(
            if (touchMouseLockEnabled) 0xFF98D96A.toInt() else 0xFFFFFFFF.toInt()
        )
        floatingMouseButton?.setBackgroundResource(
            if (touchMouseLockEnabled) {
                R.drawable.bg_touch_mouse_floating_locked
            } else {
                R.drawable.bg_touch_mouse_floating
            }
        )
    }

    private fun resolveTouchButton(): Int {
        return if (touchMouseMode == TouchMouseMode.LEFT) {
            LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT.toInt()
        } else {
            LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT.toInt()
        }
    }

    private fun showSoftKeyboard() {
        hideFloatingMouseExpandedMenu()
        val inputView = imeProxyView ?: return
        val imm = activity.getSystemService(AppCompatActivity.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        inputView.requestFocus()
        imm.restartInput(inputView)
        inputView.post {
            imm.showSoftInput(inputView, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun populateFloatingMouseExpandedMenu(menu: LinearLayout) {
        toggleSpecialKeyButtons.clear()
        menu.removeAllViews()
        val firstRow = listOf(
            SpecialKeySpec("Ctrl", KeyEvent.KEYCODE_CTRL_LEFT, toggleable = true),
            SpecialKeySpec("Shift", KeyEvent.KEYCODE_SHIFT_LEFT, toggleable = true),
            SpecialKeySpec("Tab", KeyEvent.KEYCODE_TAB)
        )
        addFloatingMouseExpandedMenuRow(menu, firstRow.map(::createFloatingMouseTextButton))
        addFloatingMouseExpandedMenuRow(
            menu,
            listOf(
                createFloatingMouseTextButton(SpecialKeySpec("Alt", KeyEvent.KEYCODE_ALT_LEFT, toggleable = true)),
                createFloatingMouseTextActionButton(activity.getString(R.string.touch_mouse_floating_menu_collapse)) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    hideFloatingMouseExpandedMenu()
                },
                createFloatingMouseKeyboardButton()
            ),
            addTopMargin = true
        )
    }

    private fun createFloatingMouseTextButton(spec: SpecialKeySpec): TextView {
        return TextView(activity).apply {
            text = spec.label
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            textSize = SPECIAL_KEYS_BUTTON_TEXT_SIZE_SP
            minWidth = dpToPx(SPECIAL_KEYS_BUTTON_MIN_WIDTH_DP)
            setPadding(
                dpToPx(12),
                0,
                dpToPx(12),
                0
            )
            isAllCaps = false
            isFocusable = false
            isFocusableInTouchMode = false
            updateFloatingMouseMenuButtonAppearance(this, false)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (spec.toggleable) {
                    toggleSpecialKey(spec.keyCode)
                } else {
                    sendSyntheticSoftKey(spec.keyCode)
                }
            }
            if (spec.toggleable) {
                toggleSpecialKeyButtons[spec.keyCode] = this
                updateFloatingMouseMenuButtonAppearance(this, activeToggleSoftKeys.containsKey(spec.keyCode))
            }
        }
    }

    private fun createFloatingMouseTextActionButton(label: String, onClick: View.() -> Unit): TextView {
        return TextView(activity).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            textSize = SPECIAL_KEYS_BUTTON_TEXT_SIZE_SP
            minWidth = dpToPx(SPECIAL_KEYS_BUTTON_MIN_WIDTH_DP)
            setPadding(
                dpToPx(12),
                0,
                dpToPx(12),
                0
            )
            isAllCaps = false
            isFocusable = false
            isFocusableInTouchMode = false
            updateFloatingMouseMenuButtonAppearance(this, false)
            setOnClickListener(onClick)
        }
    }

    private fun createFloatingMouseKeyboardButton(): FrameLayout {
        val iconSize = dpToPx(20)
        return FrameLayout(activity).apply {
            minimumWidth = dpToPx(SPECIAL_KEYS_BUTTON_MIN_WIDTH_DP)
            updateFloatingMouseMenuButtonAppearance(this, false)
            contentDescription = activity.getString(R.string.touch_mouse_floating_menu_keyboard)
            isFocusable = false
            isFocusableInTouchMode = false
            addView(
                ImageView(activity).apply {
                    setImageResource(R.drawable.ic_keyboard)
                    setColorFilter(0xFFFFFFFF.toInt())
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = null
                },
                FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
            )
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                showSoftKeyboard()
            }
        }
    }

    private fun addFloatingMouseExpandedMenuRow(
        menu: LinearLayout,
        buttons: List<View>,
        addTopMargin: Boolean = false,
    ) {
        val spacing = dpToPx(SPECIAL_KEYS_BUTTON_SPACING_DP)
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        buttons.forEachIndexed { index, button ->
            row.addView(
                button,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dpToPx(SPECIAL_KEYS_BUTTON_HEIGHT_DP)
                ).apply {
                    if (index > 0) {
                        leftMargin = spacing
                    }
                }
            )
        }
        menu.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (addTopMargin) {
                    topMargin = spacing
                }
            }
        )
    }

    private fun updateFloatingMouseMenuButtonAppearance(button: View, active: Boolean) {
        button.isSelected = active
        button.alpha = if (active) 1.0f else 0.96f
        button.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(10).toFloat()
            setColor(if (active) 0xFF355B2E.toInt() else 0xFF2B2B2B.toInt())
            setStroke(dpToPx(1), if (active) 0xFF98D96A.toInt() else 0xFF454545.toInt())
        }
    }

    private fun updateToggleSpecialKeyUi(androidKeyCode: Int, active: Boolean) {
        toggleSpecialKeyButtons[androidKeyCode]?.let { button ->
            updateFloatingMouseMenuButtonAppearance(button, active)
        }
    }

    private fun showFloatingMouseExpandedMenu() {
        if (!longPressMouseShowsKeyboard || isSoftKeyboardVisible()) {
            return
        }
        val menu = floatingMouseExpandedMenu ?: return
        menu.visibility = View.VISIBLE
        menu.bringToFront()
        floatingMouseButton?.bringToFront()
        updateFloatingMouseExpandedMenuPosition()
    }

    private fun hideFloatingMouseExpandedMenu() {
        floatingMouseExpandedMenu?.visibility = View.GONE
    }

    private fun updateFloatingMouseExpandedMenuPosition() {
        val host = hostView ?: return
        val menu = floatingMouseExpandedMenu ?: return
        val button = floatingMouseButton ?: return
        if (menu.visibility != View.VISIBLE) {
            return
        }
        if (host.width == 0 || host.height == 0 || button.width == 0 || button.height == 0) {
            host.post { updateFloatingMouseExpandedMenuPosition() }
            return
        }
        menu.measure(
            View.MeasureSpec.makeMeasureSpec(host.width, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(host.height, View.MeasureSpec.AT_MOST)
        )
        val menuWidth = menu.measuredWidth
        val menuHeight = menu.measuredHeight
        val buttonParams = button.layoutParams as? FrameLayout.LayoutParams ?: return
        val menuParams = menu.layoutParams as? FrameLayout.LayoutParams ?: return
        val gap = dpToPx(FLOATING_MENU_ANCHOR_GAP_DP)
        val maxLeft = (host.width - menuWidth).coerceAtLeast(0)
        val preferredLeft = buttonParams.leftMargin - menuWidth - gap
        val fallbackLeft = buttonParams.leftMargin + button.width + gap
        menuParams.leftMargin = when {
            preferredLeft >= 0 -> preferredLeft
            fallbackLeft <= maxLeft -> fallbackLeft
            else -> maxLeft
        }
        val maxTop = (host.height - menuHeight).coerceAtLeast(0)
        val preferredTop = buttonParams.topMargin + (button.height - menuHeight) / 2
        menuParams.topMargin = preferredTop.coerceIn(0, maxTop)
        menu.layoutParams = menuParams
    }

    private fun isSoftKeyboardVisible(): Boolean {
        val anchorView = imeProxyView ?: floatingMouseButton ?: return false
        return ViewCompat.getRootWindowInsets(anchorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }

    private fun toggleSpecialKey(androidKeyCode: Int) {
        val activeTarget = activeToggleSoftKeys[androidKeyCode]
        if (activeTarget != null) {
            logIme(
                "toggleSpecialKey release " +
                    "android=${KeyEvent.keyCodeToString(androidKeyCode)} target=$activeTarget"
            )
            dispatchKeyboardEvent(KeyEvent(KeyEvent.ACTION_UP, androidKeyCode), activeTarget)
            activeToggleSoftKeys.remove(androidKeyCode)
            updateToggleSpecialKeyUi(androidKeyCode, false)
            return
        }

        val target = resolveSoftKeyboardTarget()
        logIme(
            "toggleSpecialKey press " +
                "android=${KeyEvent.keyCodeToString(androidKeyCode)} target=$target"
        )
        if (dispatchKeyboardEvent(KeyEvent(KeyEvent.ACTION_DOWN, androidKeyCode), target)) {
            activeToggleSoftKeys[androidKeyCode] = target
            updateToggleSpecialKeyUi(androidKeyCode, true)
        }
    }

    private fun releaseActiveToggleSoftKeys() {
        val activeKeys = activeToggleSoftKeys.toMap()
        if (activeKeys.isEmpty()) {
            return
        }
        activeKeys.forEach { (androidKeyCode, target) ->
            logIme(
                "releaseActiveToggleSoftKey " +
                    "android=${KeyEvent.keyCodeToString(androidKeyCode)} target=$target"
            )
            dispatchKeyboardEvent(KeyEvent(KeyEvent.ACTION_UP, androidKeyCode), target)
            activeToggleSoftKeys.remove(androidKeyCode)
            updateToggleSpecialKeyUi(androidKeyCode, false)
        }
    }

    private fun syncActiveToggleSoftKeys(target: SoftKeyboardTarget) {
        val activeKeys = activeToggleSoftKeys.toMap()
        if (activeKeys.isEmpty()) {
            return
        }
        activeKeys.forEach { (androidKeyCode, previousTarget) ->
            if (previousTarget == target) {
                return@forEach
            }
            logIme(
                "syncActiveToggleSoftKey " +
                    "android=${KeyEvent.keyCodeToString(androidKeyCode)} from=$previousTarget to=$target"
            )
            dispatchKeyboardEvent(KeyEvent(KeyEvent.ACTION_UP, androidKeyCode), previousTarget)
            if (dispatchKeyboardEvent(KeyEvent(KeyEvent.ACTION_DOWN, androidKeyCode), target)) {
                activeToggleSoftKeys[androidKeyCode] = target
            } else {
                activeToggleSoftKeys.remove(androidKeyCode)
                updateToggleSpecialKeyUi(androidKeyCode, false)
            }
        }
    }

    private fun sendSoftKeyboardText(text: CharSequence?, source: String): Boolean {
        val ready = isNativeInputDispatchReady.invoke()
        if (text.isNullOrEmpty() || !ready) {
            logIme("sendSoftKeyboardText source=$source payload=${describeText(text)} ready=$ready")
            return false
        }
        if (shouldSuppressDuplicateSoftKeyboardText(text, source)) {
            return true
        }
        val target = resolveSoftKeyboardTarget()
        logIme("sendSoftKeyboardText source=$source payload=${describeText(text)} ready=$ready target=$target")
        return when (target) {
            SoftKeyboardTarget.GLFW -> sendSoftKeyboardTextToGame(text)
            SoftKeyboardTarget.AWT -> sendSoftKeyboardTextToAwt(text)
        }
    }

    private fun shouldSuppressDuplicateSoftKeyboardText(text: CharSequence, source: String): Boolean {
        val payload = text.toString()
        val shouldTrackForDedup = payload.length > 1 || payload.any { it.code > 0x7F }
        if (!shouldTrackForDedup) {
            return false
        }

        val now = SystemClock.uptimeMillis()
        val deltaMs = now - lastSoftTextAtMs
        val samePayload = payload == lastSoftTextPayload
        val sameSource = source == lastSoftTextSource
        val withinWindow = if (sameSource) {
            deltaMs in 0..SOFT_TEXT_DUPLICATE_SAME_SOURCE_WINDOW_MS
        } else {
            deltaMs in 0..SOFT_TEXT_DUPLICATE_CROSS_SOURCE_WINDOW_MS
        }
        if (samePayload && withinWindow) {
            logIme(
                "sendSoftKeyboardText suppressed duplicate " +
                    "source=$source previousSource=$lastSoftTextSource " +
                    "deltaMs=$deltaMs payload=${describeText(text)}"
            )
            lastSoftTextSource = source
            lastSoftTextAtMs = now
            return true
        }

        lastSoftTextPayload = payload
        lastSoftTextSource = source
        lastSoftTextAtMs = now
        return false
    }

    private fun sendSoftKeyboardTextToGame(text: CharSequence): Boolean {
        var handled = false
        for (ch in text) {
            when (ch) {
                '\n', '\r' -> {
                    logIme("commitText[GLFW] mapped newline -> KEYCODE_ENTER")
                    sendSyntheticSoftKey(KeyEvent.KEYCODE_ENTER, SoftKeyboardTarget.GLFW)
                    handled = true
                }

                '\b' -> {
                    logIme("commitText[GLFW] mapped backspace -> KEYCODE_DEL")
                    sendSyntheticSoftKey(KeyEvent.KEYCODE_DEL, SoftKeyboardTarget.GLFW)
                    handled = true
                }

                else -> {
                    if (!Character.isISOControl(ch)) {
                        logIme("commitText[GLFW] sendChar char=${describeChar(ch)} mods=${CallbackBridge.getCurrentMods()}")
                        CallbackBridge.sendChar(ch, CallbackBridge.getCurrentMods())
                        handled = true
                    }
                }
            }
        }
        return handled
    }

    private fun sendSoftKeyboardTextToAwt(text: CharSequence): Boolean {
        var handled = false
        for (ch in text) {
            when (ch) {
                '\n', '\r' -> {
                    logIme("commitText[AWT] mapped newline -> KEYCODE_ENTER")
                    sendSyntheticSoftKey(KeyEvent.KEYCODE_ENTER, SoftKeyboardTarget.AWT)
                    handled = true
                }

                '\b' -> {
                    logIme("commitText[AWT] mapped backspace -> KEYCODE_DEL")
                    sendSyntheticSoftKey(KeyEvent.KEYCODE_DEL, SoftKeyboardTarget.AWT)
                    handled = true
                }

                '\t' -> {
                    logIme("commitText[AWT] mapped tab -> KEYCODE_TAB")
                    sendSyntheticSoftKey(KeyEvent.KEYCODE_TAB, SoftKeyboardTarget.AWT)
                    handled = true
                }

                else -> {
                    if (ch.code == AWT_VK_DELETE) {
                        logIme("commitText[AWT] mapped delete -> KEYCODE_FORWARD_DEL")
                        sendSyntheticSoftKey(KeyEvent.KEYCODE_FORWARD_DEL, SoftKeyboardTarget.AWT)
                        handled = true
                    } else if (!Character.isISOControl(ch)) {
                        logIme("commitText[AWT] sendChar char=${describeChar(ch)}")
                        AWTInputBridge.sendChar(ch)
                        handled = true
                    }
                }
            }
        }
        return handled
    }

    private fun sendSyntheticSoftKey(
        androidKeyCode: Int,
        target: SoftKeyboardTarget = resolveSoftKeyboardTarget()
    ) {
        logIme("sendSyntheticSoftKey androidKey=${KeyEvent.keyCodeToString(androidKeyCode)} target=$target")
        dispatchSoftKeyboardKeyEventToTarget(KeyEvent(KeyEvent.ACTION_DOWN, androidKeyCode), target)
        if (!shouldDelaySoftKeyRelease(androidKeyCode, target)) {
            dispatchSoftKeyboardKeyEventToTarget(KeyEvent(KeyEvent.ACTION_UP, androidKeyCode), target)
        }
    }

    private fun dispatchSoftKeyboardKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            return false
        }
        if (!isNativeInputDispatchReady.invoke()) {
            logIme("dispatchSoftKeyboardKeyEvent dropped: native input not ready")
            return true
        }
        if (shouldIgnorePrintableSoftKeyEvent(event)) {
            logIme(
                "dispatchSoftKeyboardKeyEvent ignored printable key event " +
                    "event=${describeKeyEvent(event)}; waiting for commitText"
            )
            return true
        }
        val target = resolveSoftKeyboardTarget()
        logIme("dispatchSoftKeyboardKeyEvent event=${describeKeyEvent(event)} target=$target")
        return dispatchSoftKeyboardKeyEventToTarget(event, target)
    }

    private fun dispatchKeyboardEvent(event: KeyEvent, target: SoftKeyboardTarget): Boolean {
        return when (target) {
            SoftKeyboardTarget.GLFW -> dispatchKeyboardEventToGame(event)
            SoftKeyboardTarget.AWT -> dispatchKeyboardEventToAwt(event)
        }
    }

    private fun dispatchSoftKeyboardKeyEventToTarget(event: KeyEvent, target: SoftKeyboardTarget): Boolean {
        if (!shouldDelaySoftKeyRelease(event.keyCode, target)) {
            return dispatchKeyboardEvent(event, target)
        }

        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                flushPendingSoftKeyRelease(event.keyCode)
                val handled = dispatchKeyboardEvent(event, target)
                scheduleSoftKeyRelease(event.keyCode, target)
                handled
            }

            KeyEvent.ACTION_UP -> {
                logIme(
                    "dispatchSoftKeyboardKeyEvent delayed release " +
                        "android=${KeyEvent.keyCodeToString(event.keyCode)} target=$target"
                )
                true
            }

            else -> dispatchKeyboardEvent(event, target)
        }
    }

    private fun shouldIgnorePrintableSoftKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) {
            return false
        }
        if (event.isPrintingKey) {
            return true
        }
        val unicode = event.unicodeChar
        return unicode > 0 && !Character.isISOControl(unicode)
    }

    private fun shouldDelaySoftKeyRelease(androidKeyCode: Int, target: SoftKeyboardTarget): Boolean {
        return target == SoftKeyboardTarget.GLFW &&
            (androidKeyCode == KeyEvent.KEYCODE_DEL || androidKeyCode == KeyEvent.KEYCODE_FORWARD_DEL)
    }

    private fun scheduleSoftKeyRelease(androidKeyCode: Int, target: SoftKeyboardTarget) {
        val inputView = imeProxyView
        if (inputView == null) {
            logIme(
                "scheduleSoftKeyRelease fallback immediate " +
                    "android=${KeyEvent.keyCodeToString(androidKeyCode)} target=$target"
            )
            dispatchKeyboardEvent(KeyEvent(KeyEvent.ACTION_UP, androidKeyCode), target)
            return
        }

        val downAt = SystemClock.uptimeMillis()
        val releaseRunnable = Runnable {
            pendingSoftKeyReleaseRunnables.remove(androidKeyCode)
            val heldFor = SystemClock.uptimeMillis() - downAt
            logIme(
                "scheduleSoftKeyRelease dispatch " +
                    "android=${KeyEvent.keyCodeToString(androidKeyCode)} target=$target heldMs=$heldFor"
            )
            dispatchKeyboardEvent(KeyEvent(KeyEvent.ACTION_UP, androidKeyCode), target)
        }
        pendingSoftKeyReleaseRunnables[androidKeyCode] = releaseRunnable
        inputView.postDelayed(releaseRunnable, SOFT_KEY_MIN_PRESS_MS)
        logIme(
            "scheduleSoftKeyRelease queued " +
                "android=${KeyEvent.keyCodeToString(androidKeyCode)} target=$target delayMs=$SOFT_KEY_MIN_PRESS_MS"
        )
    }

    private fun flushPendingSoftKeyRelease(androidKeyCode: Int) {
        val releaseRunnable = pendingSoftKeyReleaseRunnables.remove(androidKeyCode) ?: return
        imeProxyView?.removeCallbacks(releaseRunnable)
        logIme("flushPendingSoftKeyRelease android=${KeyEvent.keyCodeToString(androidKeyCode)}")
        releaseRunnable.run()
    }

    private fun flushPendingSoftKeyReleases() {
        val pendingKeys = pendingSoftKeyReleaseRunnables.keys.toList()
        pendingKeys.forEach(::flushPendingSoftKeyRelease)
    }

    private fun dispatchKeyboardEventToAwt(event: KeyEvent): Boolean {
        if (!isNativeInputDispatchReady.invoke()) {
            logIme("dispatchKeyboardEventToAwt ignored: native input not ready event=${describeKeyEvent(event)}")
            return false
        }
        if (event.action == KeyEvent.ACTION_MULTIPLE) {
            val chars = event.characters
            logIme("dispatchKeyboardEventToAwt ACTION_MULTIPLE chars=${describeText(chars)}")
            return if (!chars.isNullOrEmpty()) {
                sendSoftKeyboardText(chars, "action_multiple_awt")
            } else {
                true
            }
        }
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) {
            return false
        }

        val awtKeyCode = toAwtKeyCode(event.keyCode)
        if (awtKeyCode != null) {
            val isDown = event.action == KeyEvent.ACTION_DOWN
            logIme(
                "dispatchKeyboardEventToAwt key " +
                    "android=${KeyEvent.keyCodeToString(event.keyCode)} " +
                    "awt=$awtKeyCode action=${describeAction(event.action)}"
            )
            AWTInputBridge.sendKey(Char.MIN_VALUE, awtKeyCode, if (isDown) 1 else 0)
            return true
        }

        val unicode = event.unicodeChar
        if (event.action == KeyEvent.ACTION_DOWN && unicode > 0 && !Character.isISOControl(unicode)) {
            logIme(
                "dispatchKeyboardEventToAwt ignored printable key event " +
                    "char=${describeChar(unicode.toChar())}; waiting for commitText"
            )
            return true
        }

        logIme("dispatchKeyboardEventToAwt handled=false event=${describeKeyEvent(event)}")
        return false
    }

    private fun toAwtKeyCode(androidKeyCode: Int): Int? {
        return when (androidKeyCode) {
            KeyEvent.KEYCODE_DEL -> AWT_VK_BACK_SPACE
            KeyEvent.KEYCODE_FORWARD_DEL -> AWT_VK_DELETE
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> AWT_VK_ENTER
            KeyEvent.KEYCODE_TAB -> AWT_VK_TAB
            KeyEvent.KEYCODE_ESCAPE -> AWT_VK_ESCAPE
            KeyEvent.KEYCODE_DPAD_LEFT -> AWT_VK_LEFT
            KeyEvent.KEYCODE_DPAD_UP -> AWT_VK_UP
            KeyEvent.KEYCODE_DPAD_RIGHT -> AWT_VK_RIGHT
            KeyEvent.KEYCODE_DPAD_DOWN -> AWT_VK_DOWN
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> AWT_VK_SHIFT
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> AWT_VK_CONTROL
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> AWT_VK_ALT
            KeyEvent.KEYCODE_CAPS_LOCK -> AWT_VK_CAPS_LOCK
            else -> null
        }
    }

    private fun resolveSoftKeyboardTarget(): SoftKeyboardTarget {
        val awtTextFocused = AWTInputBridge.isTextInputFocused()
        val target = if (awtTextFocused) {
            SoftKeyboardTarget.AWT
        } else {
            SoftKeyboardTarget.GLFW
        }
        syncActiveToggleSoftKeys(target)
        logIme("resolveSoftKeyboardTarget awtTextFocused=$awtTextFocused target=$target")
        return target
    }

    @Suppress("DEPRECATION")
    private fun dispatchKeyboardEventToGame(event: KeyEvent): Boolean {
        if (!isNativeInputDispatchReady.invoke()) {
            logIme("dispatchKeyboardEventToGame ignored: native input not ready event=${describeKeyEvent(event)}")
            return false
        }
        if (event.action == KeyEvent.ACTION_MULTIPLE) {
            val chars = event.characters
            logIme("dispatchKeyboardEventToGame ACTION_MULTIPLE chars=${describeText(chars)}")
            return if (!chars.isNullOrEmpty()) {
                sendSoftKeyboardText(chars, "action_multiple_glfw")
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
            logIme(
                "dispatchKeyboardEventToGame key " +
                    "android=${KeyEvent.keyCodeToString(event.keyCode)} " +
                    "glfw=$glfwKey action=${describeAction(event.action)} modsBefore=${CallbackBridge.getCurrentMods()}"
            )
            CallbackBridge.setModifiers(glfwKey, isDown)
            CallbackBridge.sendKeyPress(glfwKey, 0, CallbackBridge.getCurrentMods(), isDown)
            handled = true
        }

        val unicode = event.unicodeChar
        val typedChar = when {
            event.action != KeyEvent.ACTION_DOWN -> null
            event.keyCode == KeyEvent.KEYCODE_DEL -> '\b'
            event.keyCode == KeyEvent.KEYCODE_FORWARD_DEL -> 127.toChar()
            unicode > 0 && !Character.isISOControl(unicode) -> unicode.toChar()
            else -> null
        }
        if (typedChar != null) {
            logIme("dispatchKeyboardEventToGame typed char=${describeChar(typedChar)} mods=${CallbackBridge.getCurrentMods()}")
            CallbackBridge.sendChar(typedChar, CallbackBridge.getCurrentMods())
            handled = true
        }
        logIme("dispatchKeyboardEventToGame handled=$handled event=${describeKeyEvent(event)}")
        return handled
    }

    private fun logIme(message: String) {
        Log.d(IME_LOG_TAG, message)
    }

    private fun logTest(message: String) {
        Log.d(TEST_LOG_TAG, "[test] $message")
    }

    private fun describeAction(action: Int): String {
        return when (action) {
            KeyEvent.ACTION_DOWN -> "DOWN"
            KeyEvent.ACTION_UP -> "UP"
            KeyEvent.ACTION_MULTIPLE -> "MULTIPLE"
            else -> action.toString()
        }
    }

    private fun describeKeyEvent(event: KeyEvent): String {
        return buildString {
            append(describeAction(event.action))
            append('/')
            append(KeyEvent.keyCodeToString(event.keyCode))
            append(" repeat=").append(event.repeatCount)
            append(" unicode=").append(event.unicodeChar)
            if (!event.characters.isNullOrEmpty()) {
                append(" chars=").append(describeText(event.characters))
            }
        }
    }

    private fun describeText(text: CharSequence?): String {
        if (text == null) {
            return "<null>"
        }
        return buildString {
            append('"')
            text.forEach { append(describeChar(it)) }
            append('"')
            append(" len=").append(text.length)
        }
    }

    private fun describeChar(ch: Char): String {
        return when (ch) {
            '\b' -> "\\b"
            '\n' -> "\\n"
            '\r' -> "\\r"
            '\t' -> "\\t"
            else -> if (Character.isISOControl(ch)) {
                "\\u" + ch.code.toString(16).padStart(4, '0')
            } else {
                ch.toString()
            }
        }
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
            updateFloatingMouseExpandedMenuPosition()
        }
    }

    private inner class GameImeProxyView(context: android.content.Context) : View(context) {
        init {
            isFocusable = true
            isFocusableInTouchMode = true
            importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
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
                // Mirror committed text locally so IMEs keep delete/backspace enabled.
                private val editable = Editable.Factory.getInstance().newEditable("").also {
                    Selection.setSelection(it, 0)
                }

                override fun getEditable(): Editable {
                    logIme("getEditable state=${describeEditableState(editable)}")
                    return editable
                }

                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    val result = super.commitText(text, newCursorPosition)
                    logIme(
                        "InputConnection.commitText text=${describeText(text)} " +
                            "cursor=$newCursorPosition result=$result state=${describeEditableState(editable)}"
                    )
                    sendSoftKeyboardText(text, "commit_text")
                    return true
                }

                override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    // Keep composition internal to IME and only forward committed text.
                    val result = super.setComposingText(text, newCursorPosition)
                    logIme(
                        "InputConnection.setComposingText text=${describeText(text)} " +
                            "cursor=$newCursorPosition result=$result state=${describeEditableState(editable)}"
                    )
                    return result
                }

                override fun finishComposingText(): Boolean {
                    val result = super.finishComposingText()
                    logIme("InputConnection.finishComposingText result=$result state=${describeEditableState(editable)}")
                    return result
                }

                override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                    val beforeState = describeEditableState(editable)
                    val result = super.deleteSurroundingText(beforeLength, afterLength)
                    logIme(
                        "InputConnection.deleteSurroundingText before=$beforeLength after=$afterLength " +
                            "result=$result stateBefore=$beforeState stateAfter=${describeEditableState(editable)}"
                    )
                    repeat(beforeLength.coerceAtLeast(0)) {
                        sendSyntheticSoftKey(KeyEvent.KEYCODE_DEL)
                    }
                    repeat(afterLength.coerceAtLeast(0)) {
                        sendSyntheticSoftKey(KeyEvent.KEYCODE_FORWARD_DEL)
                    }
                    if (beforeLength <= 0 && afterLength <= 0) {
                        sendSyntheticSoftKey(KeyEvent.KEYCODE_DEL)
                    }
                    return true
                }

                override fun sendKeyEvent(event: KeyEvent): Boolean {
                    logIme("InputConnection.sendKeyEvent event=${describeKeyEvent(event)} state=${describeEditableState(editable)}")
                    return dispatchSoftKeyboardKeyEvent(event)
                }

                override fun performEditorAction(actionCode: Int): Boolean {
                    logIme("InputConnection.performEditorAction actionCode=$actionCode")
                    sendSyntheticSoftKey(KeyEvent.KEYCODE_ENTER)
                    return true
                }

                private fun describeEditableState(value: Editable): String {
                    return "text=${describeText(value)} sel=${Selection.getSelectionStart(value)}..${Selection.getSelectionEnd(value)}"
                }
            }
        }
    }
}
