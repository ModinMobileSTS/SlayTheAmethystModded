package io.stamethyst

import android.content.Context
import android.graphics.Typeface
import android.graphics.Rect
import android.util.Log
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.stamethyst.config.TouchMouseInteractionMode
import io.stamethyst.backend.bridge.AndroidGlfwKeycode
import io.stamethyst.ui.GameAndroidUiPalette
import io.stamethyst.ui.LauncherTransientNoticeBus
import io.stamethyst.ui.haptics.LauncherHaptics
import net.kdt.pojavlaunch.AWTInputBridge
import net.kdt.pojavlaunch.LwjglGlfwKeycode
import org.lwjgl.glfw.CallbackBridge
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class FloatingMouseStoredPosition(
    val leftFraction: Float,
    val topFraction: Float,
)

internal data class FloatingMouseResolvedPosition(
    val left: Int,
    val top: Int,
)

internal fun captureFloatingMouseStoredPosition(
    leftMargin: Int,
    topMargin: Int,
    maxLeft: Int,
    maxTop: Int,
): FloatingMouseStoredPosition {
    return FloatingMouseStoredPosition(
        leftFraction = captureFloatingMousePositionFraction(leftMargin, maxLeft),
        topFraction = captureFloatingMousePositionFraction(topMargin, maxTop),
    )
}

internal fun restoreFloatingMouseResolvedPosition(
    leftFraction: Float?,
    topFraction: Float?,
    maxLeft: Int,
    maxTop: Int,
    defaultLeft: Int,
    defaultTop: Int,
): FloatingMouseResolvedPosition {
    return FloatingMouseResolvedPosition(
        left = restoreFloatingMousePositionMargin(leftFraction, maxLeft, defaultLeft),
        top = restoreFloatingMousePositionMargin(topFraction, maxTop, defaultTop),
    )
}

private fun captureFloatingMousePositionFraction(margin: Int, maxMargin: Int): Float {
    if (maxMargin <= 0) {
        return 0f
    }
    return margin.coerceIn(0, maxMargin).toFloat() / maxMargin.toFloat()
}

private fun restoreFloatingMousePositionMargin(
    fraction: Float?,
    maxMargin: Int,
    defaultMargin: Int,
): Int {
    if (maxMargin <= 0) {
        return 0
    }
    val boundedDefault = defaultMargin.coerceIn(0, maxMargin)
    val boundedFraction = fraction?.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: return boundedDefault
    return (maxMargin * boundedFraction).roundToInt().coerceIn(0, maxMargin)
}

internal class FloatingMouseOverlayController(
    private val activity: AppCompatActivity,
    private val isNativeInputDispatchReady: () -> Boolean,
    private val requestRenderViewFocus: () -> Unit,
    private val autoSwitchBackToLeftAfterRightClick: Boolean,
    private val touchMouseInteractionMode: TouchMouseInteractionMode,
    private val builtInSoftKeyboardEnabled: Boolean,
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

    private data class CustomSoftKeySpec(
        val pickerLabel: String,
        val buttonLabel: String,
        val keyCode: Int,
        val toggleable: Boolean = false,
    )

    private data class CustomSoftKeyCategory(
        val label: String,
        val keys: List<CustomSoftKeySpec>,
    )

    private data class CustomSoftKeyButtonState(
        val spec: CustomSoftKeySpec,
        val view: FrameLayout,
        var dragging: Boolean = false,
        var longPressTriggered: Boolean = false,
        var movedBeyondTapSlop: Boolean = false,
        var overDeleteTarget: Boolean = false,
        var longPressRunnable: Runnable? = null,
        var downRawX: Float = 0f,
        var downRawY: Float = 0f,
        var lastRawX: Float = 0f,
        var lastRawY: Float = 0f,
        var downLeft: Int = 0,
        var downTop: Int = 0,
        var dragStartRawX: Float = 0f,
        var dragStartRawY: Float = 0f,
        var dragStartLeft: Int = 0,
        var dragStartTop: Int = 0,
    )

    companion object {
        private const val IME_LOG_TAG = "STS-IME"
        private const val TOGETHER_IN_SPIRE_CHAT_TEXT_SYNC_SOURCE = "together_in_spire_chat"
        private const val TOGETHER_IN_SPIRE_CHAT_TEXT_SYNC_START = '\uE000'
        private const val TOGETHER_IN_SPIRE_CHAT_TEXT_SYNC_END = '\uE001'
        private const val FLOATING_MOUSE_IDLE_ALPHA = 0.2f
        private const val FLOATING_MOUSE_ACTIVE_ALPHA = 1.0f
        private const val FLOATING_MOUSE_ACTIVE_KEEP_MS = 1500L
        private const val FLOATING_MOUSE_ALPHA_ANIM_DURATION_MS = 180L
        private const val FLOATING_MENU_ANIM_DURATION_MS = 180L
        private const val FLOATING_MENU_ANIM_OFFSET_DP = 10
        private const val FLOATING_MOUSE_SIDE_INSET_DP = 18
        private const val FLOATING_MENU_ANCHOR_GAP_DP = 8
        private const val FLOATING_MOUSE_LAYOUT_PREFS_NAME = "floating_mouse_layout"
        private const val FLOATING_MOUSE_LEFT_FRACTION_KEY = "floating_mouse_left_fraction"
        private const val FLOATING_MOUSE_TOP_FRACTION_KEY = "floating_mouse_top_fraction"
        private const val CUSTOM_KEY_PREFS_NAME = "floating_mouse_custom_keys"
        private const val CUSTOM_KEY_TUTORIAL_SHOWN_KEY = "custom_key_tutorial_shown"
        private const val CUSTOM_KEY_DRAG_LONG_PRESS_MS = 1000L
        private const val CUSTOM_KEY_BUTTON_SIZE_DP = 52
        private const val CUSTOM_KEY_BUTTON_TEXT_SIZE_SP = 13f
        private const val CUSTOM_KEY_BUTTON_INITIAL_OFFSET_DP = 24
        private const val CUSTOM_KEY_BUTTON_PLACEMENT_GAP_DP = 8
        private const val CUSTOM_KEY_BUTTON_PRESS_SCALE = 1.12f
        private const val CUSTOM_KEY_BUTTON_TAP_SCALE = 1.18f
        private const val CUSTOM_KEY_BUTTON_DRAG_SCALE = 1.16f
        private const val CUSTOM_KEY_SCALE_ANIM_DURATION_MS = 120L
        private const val CUSTOM_KEY_DELETE_TARGET_SIZE_DP = 72
        private const val CUSTOM_KEY_DELETE_TARGET_BOTTOM_MARGIN_DP = 72
        private const val CUSTOM_KEY_DELETE_TARGET_ANIM_DURATION_MS = 140L
        private const val CUSTOM_KEY_PICKER_DIALOG_MARGIN_DP = 18
        private const val CUSTOM_KEY_PICKER_DIALOG_MAX_WIDTH_DP = 560
        private const val CUSTOM_KEY_PICKER_DIALOG_COMPACT_WIDTH_DP = 420
        private const val CUSTOM_KEY_PICKER_DIALOG_PADDING_DP = 16
        private const val CUSTOM_KEY_PICKER_LIST_HEIGHT_DP = 300
        private const val CUSTOM_KEY_PICKER_CATEGORY_WIDTH_DP = 112
        private const val CUSTOM_KEY_PICKER_KEY_WIDTH_DP = 94
        private const val CUSTOM_KEY_PICKER_KEY_MIN_WIDTH_DP = 76
        private const val CUSTOM_KEY_PICKER_OPTION_HEIGHT_DP = 42
        private const val CUSTOM_KEY_PICKER_OPTION_SPACING_DP = 8
        private const val CUSTOM_KEY_PICKER_OPTION_TEXT_SIZE_SP = 12f
        private const val CUSTOM_KEY_PICKER_SELECTED_TEXT_SIZE_SP = 13f
        private const val CUSTOM_KEY_PICKER_TITLE_TEXT_SIZE_SP = 20f
        private const val SPECIAL_KEYS_BAR_PADDING_HORIZONTAL_DP = 8
        private const val SPECIAL_KEYS_BAR_PADDING_VERTICAL_DP = 6
        private const val SPECIAL_KEYS_BUTTON_HEIGHT_DP = 38
        private const val SPECIAL_KEYS_BUTTON_TEXT_SIZE_SP = 12f
        private const val SPECIAL_KEYS_BUTTON_MIN_WIDTH_DP = 46
        private const val SPECIAL_KEYS_BUTTON_SPACING_DP = 6
        private const val SPECIAL_KEYS_GRID_ROWS = 3
        private const val SPECIAL_KEYS_GRID_COLUMNS = 3
        private const val VIRTUAL_WHEEL_TRACK_WIDTH_DP = 42
        private const val VIRTUAL_WHEEL_TRACK_HEIGHT_DP = 88
        private const val VIRTUAL_WHEEL_TRACK_PADDING_VERTICAL_DP = 10
        private const val VIRTUAL_WHEEL_THUMB_WIDTH_DP = 30
        private const val VIRTUAL_WHEEL_THUMB_HEIGHT_DP = 24
        private const val VIRTUAL_WHEEL_CENTER_MARKER_WIDTH_DP = 16
        private const val VIRTUAL_WHEEL_CENTER_MARKER_HEIGHT_DP = 2
        private const val VIRTUAL_WHEEL_ARROW_TEXT_SIZE_SP = 10f
        private const val VIRTUAL_WHEEL_DEAD_ZONE = 0.16f
        private const val VIRTUAL_WHEEL_MIN_SCROLL_DELTA = 0.40
        private const val VIRTUAL_WHEEL_MAX_SCROLL_DELTA = 1.10
        private const val VIRTUAL_WHEEL_REPEAT_SLOW_MS = 88L
        private const val VIRTUAL_WHEEL_REPEAT_FAST_MS = 42L
        private const val SOFT_KEY_MIN_PRESS_MS = 70L
        private const val SOFT_TEXT_DUPLICATE_CROSS_SOURCE_WINDOW_MS = 150L
        private const val SOFT_TEXT_DUPLICATE_SAME_SOURCE_WINDOW_MS = 16L
        private const val AWT_VK_PAUSE = 19
        private const val AWT_VK_ENTER = 10
        private const val AWT_VK_BACK_SPACE = 8
        private const val AWT_VK_TAB = 9
        private const val AWT_VK_SPACE = 32
        private const val AWT_VK_PAGE_UP = 33
        private const val AWT_VK_PAGE_DOWN = 34
        private const val AWT_VK_END = 35
        private const val AWT_VK_HOME = 36
        private const val AWT_VK_SHIFT = 16
        private const val AWT_VK_CONTROL = 17
        private const val AWT_VK_ALT = 18
        private const val AWT_VK_CAPS_LOCK = 20
        private const val AWT_VK_ESCAPE = 27
        private const val AWT_VK_LEFT = 37
        private const val AWT_VK_UP = 38
        private const val AWT_VK_RIGHT = 39
        private const val AWT_VK_DOWN = 40
        private const val AWT_VK_COMMA = 44
        private const val AWT_VK_MINUS = 45
        private const val AWT_VK_PERIOD = 46
        private const val AWT_VK_SLASH = 47
        private const val AWT_VK_0 = 48
        private const val AWT_VK_A = 65
        private const val AWT_VK_SEMICOLON = 59
        private const val AWT_VK_EQUALS = 61
        private const val AWT_VK_OPEN_BRACKET = 91
        private const val AWT_VK_BACK_SLASH = 92
        private const val AWT_VK_CLOSE_BRACKET = 93
        private const val AWT_VK_NUMPAD0 = 96
        private const val AWT_VK_MULTIPLY = 106
        private const val AWT_VK_ADD = 107
        private const val AWT_VK_SUBTRACT = 109
        private const val AWT_VK_DECIMAL = 110
        private const val AWT_VK_DIVIDE = 111
        private const val AWT_VK_F1 = 112
        private const val AWT_VK_NUM_LOCK = 144
        private const val AWT_VK_SCROLL_LOCK = 145
        private const val AWT_VK_PRINTSCREEN = 154
        private const val AWT_VK_INSERT = 155
        private const val AWT_VK_META = 157
        private const val AWT_VK_DELETE = 127
        private const val AWT_VK_BACK_QUOTE = 192
        private const val AWT_VK_QUOTE = 222
        private const val AWT_VK_CONTEXT_MENU = 525
    }

    private var hostView: FrameLayout? = null
    private var touchMouseMode = TouchMouseMode.LEFT
    private var touchPressedButton = -1
    private var floatingMouseButton: FrameLayout? = null
    private var floatingMouseMainIcon: ImageView? = null
    private var imeController: FloatingMouseImeController? = null
    private var builtInKeyboardController: InGameSoftKeyboardOverlayController? = null
    private var floatingMouseExpandedMenu: LinearLayout? = null
    private var floatingMouseModeButton: TextView? = null
    private var floatingMouseWheelView: VirtualMouseWheelView? = null
    private var floatingMouseLockButton: TextView? = null
    private var floatingMouseTouchSlop = 0
    private var floatingMouseDragging = false
    private var floatingMouseLongPressTriggered = false
    private var floatingMousePressRunnable: Runnable? = null
    private var floatingMouseIdleRunnable: Runnable? = null
    private var floatingMouseDownRawX = 0f
    private var floatingMouseDownRawY = 0f
    private var floatingMouseDownLeft = 0
    private var floatingMouseDownTop = 0
    private var touchMouseLockEnabled = false
    private var floatingMouseMenuExpanded = false
    private val uiPalette: GameAndroidUiPalette by lazy { GameAndroidUiPalette.from(activity) }
    private val pendingSoftKeyReleaseRunnables = mutableMapOf<Int, Runnable>()

    private val toggleSpecialKeyButtons = mutableMapOf<Int, View>()
    private val activeToggleSoftKeys = mutableMapOf<Int, SoftKeyboardTarget>()
    private val customSoftKeyButtons = mutableListOf<CustomSoftKeyButtonState>()
    private val floatingMouseLayoutPrefs by lazy {
        activity.getSharedPreferences(FLOATING_MOUSE_LAYOUT_PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val customSoftKeyPrefs by lazy {
        activity.getSharedPreferences(CUSTOM_KEY_PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val customSoftKeyCategories = buildCustomSoftKeyCategories()
    private var customSoftKeyDeleteTarget: FrameLayout? = null
    private var customSoftKeyPickerDialog: AlertDialog? = null
    private var customSoftKeyButtonsVisible = true
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

        val controller = FloatingMouseImeController(
            activity = activity,
            requestRenderViewFocus = requestRenderViewFocus,
            debugLogger = ::logImeState,
            callbacks = object : FloatingMouseImeController.InputCallbacks {
                override fun onCommitText(text: CharSequence?, source: String): Boolean {
                    return sendSoftKeyboardText(text, source)
                }

                override fun onPreviewTextChanged(
                    text: CharSequence,
                    source: String
                ): Boolean {
                    return sendPreviewTextSync(text, source)
                }

                override fun onDeleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                    return handleDeleteSurroundingText(beforeLength, afterLength)
                }

                override fun onSendKeyEvent(event: KeyEvent): Boolean {
                    return dispatchSoftKeyboardKeyEvent(event)
                }

                override fun onPerformEditorAction(actionCode: Int): Boolean {
                    return handlePerformEditorAction(actionCode)
                }

                override fun onKeyboardVisibilityChanged(visible: Boolean) {
                    handleKeyboardVisibilityChanged(visible)
                }
            }
        )
        controller.attachToHost(host)
        imeController = controller

        val builtInController = InGameSoftKeyboardOverlayController(
            activity = activity,
            requestRenderViewFocus = requestRenderViewFocus,
            callbacks = object : InGameSoftKeyboardOverlayController.Callbacks {
                override fun onCommitText(text: CharSequence): Boolean {
                    return sendSoftKeyboardText(text, source = "builtin_keyboard")
                }

                override fun onBackspace(): Boolean {
                    sendSyntheticSoftKey(KeyEvent.KEYCODE_DEL)
                    return true
                }

                override fun onEnter(): Boolean {
                    sendSyntheticSoftKey(KeyEvent.KEYCODE_ENTER)
                    return true
                }

                override fun onTab(): Boolean {
                    sendSyntheticSoftKey(KeyEvent.KEYCODE_TAB)
                    return true
                }

                override fun onKey(androidKeyCode: Int): Boolean {
                    return sendSyntheticSoftKey(androidKeyCode)
                }

                override fun onToggleKey(androidKeyCode: Int, active: Boolean): Boolean {
                    return setToggleSpecialKey(androidKeyCode, active)
                }

                override fun onSystemKeyboardRequested() {
                    imeController?.requestShow(
                        reason = "builtin_keyboard_system_key",
                        keepVisible = false
                    )
                }

                override fun onVisibilityChanged(visible: Boolean) {
                    handleKeyboardVisibilityChanged(visible)
                }
            }
        )
        builtInController.attachToHost(host)
        builtInKeyboardController = builtInController

        customSoftKeyDeleteTarget = createCustomSoftKeyDeleteTarget().also { deleteTarget ->
            host.addView(
                deleteTarget,
                FrameLayout.LayoutParams(
                    dpToPx(CUSTOM_KEY_DELETE_TARGET_SIZE_DP),
                    dpToPx(CUSTOM_KEY_DELETE_TARGET_SIZE_DP),
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                ).apply {
                    bottomMargin = dpToPx(CUSTOM_KEY_DELETE_TARGET_BOTTOM_MARGIN_DP)
                }
            )
        }

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
                setColor(uiPalette.surfaceScrim)
                setStroke(dpToPx(1), uiPalette.outline)
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
            background = floatingMouseButtonBackground(locked = false)
            visibility = View.GONE
            alpha = FLOATING_MOUSE_IDLE_ALPHA
            isClickable = true
            isFocusable = false
            elevation = dpToPx(8).toFloat()
        }
        val icon = ImageView(activity).apply {
            setImageResource(R.drawable.ic_touch_mouse_mode_left)
            setColorFilter(uiPalette.onSurface)
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
        placeFloatingButtonAtSavedOrDefaultPosition(host, button, buttonSize)

        floatingMouseMainIcon = icon
        floatingMouseButton = button
        button.setOnTouchListener { _, event -> handleFloatingMouseTouch(event) }
        updateTouchMouseModeUi()
    }

    fun onDestroy() {
        flushPendingSoftKeyReleases()
        hideSoftKeyboard()
        cancelFloatingMouseLongPress()
        clearIdleRunnable()
        floatingMouseButton?.animate()?.cancel()
        releaseTouchButtonIfNeeded()
        detachViews()
        hostView = null
    }

    fun updateVisibility(
        shouldShowFloatingMouseButton: Boolean,
        shouldShowCustomSoftKeyButtons: Boolean,
    ) {
        val button = floatingMouseButton ?: return
        button.visibility = if (shouldShowFloatingMouseButton) View.VISIBLE else View.GONE
        customSoftKeyButtonsVisible = shouldShowCustomSoftKeyButtons
        customSoftKeyButtons.forEach { state ->
            state.view.visibility = if (shouldShowCustomSoftKeyButtons) View.VISIBLE else View.GONE
        }
        if (!shouldShowFloatingMouseButton && !shouldShowCustomSoftKeyButtons) {
            hideSoftKeyboard()
        }
        if (shouldShowFloatingMouseButton) {
            button.animate().cancel()
            button.alpha = if (floatingMouseMenuExpanded) {
                FLOATING_MOUSE_ACTIVE_ALPHA
            } else {
                FLOATING_MOUSE_IDLE_ALPHA
            }
            if (floatingMouseMenuExpanded) {
                updateFloatingMouseExpandedMenuPosition()
            }
        } else {
            hideFloatingMouseExpandedMenu(animate = false)
            clearIdleRunnable()
            button.animate().cancel()
        }
        if (!shouldShowCustomSoftKeyButtons) {
            hideCustomSoftKeyDeleteTarget(animate = false)
            customSoftKeyButtons.forEach { state ->
                cancelCustomSoftKeyLongPress(state)
                state.dragging = false
                state.longPressTriggered = false
                state.movedBeyondTapSlop = false
                animateCustomSoftKeyScale(state.view, 1f)
            }
        }
    }

    fun isTouchMouseLockEnabled(): Boolean {
        return touchMouseLockEnabled
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

    fun clearTouchButtonState() {
        touchPressedButton = -1
    }

    fun hideSoftKeyboard() {
        val hadKeyboardSession =
            builtInKeyboardController?.isVisible() == true ||
                imeController?.isVisible() == true ||
                imeController?.shouldHoldRenderSurfaceStable() == true
        flushPendingSoftKeyReleases()
        releaseActiveToggleSoftKeys()
        hideFloatingMouseExpandedMenu()
        builtInKeyboardController?.hide(refocusRenderView = false)
        imeController?.requestHide(reason = "overlay_hide", refocusRenderView = false)
        if (hadKeyboardSession) {
            requestRenderViewFocus.invoke()
        }
    }

    fun requestSoftKeyboard(reason: String) {
        showSoftKeyboard(reason)
    }

    fun requestSystemSoftKeyboard(
        reason: String,
        previewConfig: FloatingMouseImeController.PreviewConfig? = null,
    ) {
        hideFloatingMouseExpandedMenu()
        builtInKeyboardController?.hide(refocusRenderView = false)
        imeController?.requestShow(
            reason = reason,
            keepVisible = previewConfig == null,
            previewConfig = previewConfig,
        )
    }

    fun requestCustomSoftKeyButton(reason: String) {
        logIme("requestCustomSoftKeyButton reason=$reason")
        hideFloatingMouseExpandedMenu()
        if (activity.isFinishing || activity.isDestroyed) {
            return
        }
        val categories = customSoftKeyCategories.filter { it.keys.isNotEmpty() }
        if (categories.isEmpty()) {
            return
        }
        customSoftKeyPickerDialog?.dismiss()

        val displayMetrics = activity.resources.displayMetrics
        val dialogMargin = dpToPx(CUSTOM_KEY_PICKER_DIALOG_MARGIN_DP)
        val screenMaxDialogWidth = (displayMetrics.widthPixels - dpToPx(8)).coerceAtLeast(1)
        val minDialogWidth = dpToPx(240)
            .coerceAtMost(screenMaxDialogWidth)
            .coerceAtLeast(1)
        val dialogWidth = (displayMetrics.widthPixels - dialogMargin * 2)
            .coerceAtMost(dpToPx(CUSTOM_KEY_PICKER_DIALOG_MAX_WIDTH_DP))
            .coerceAtLeast(minDialogWidth)
            .coerceAtMost(screenMaxDialogWidth)
        val panelPadding = dpToPx(CUSTOM_KEY_PICKER_DIALOG_PADDING_DP)
        val spacing = dpToPx(CUSTOM_KEY_PICKER_OPTION_SPACING_DP)
        val bodyWidth = (dialogWidth - panelPadding * 2).coerceAtLeast(1)
        val compactLayout = dialogWidth < dpToPx(CUSTOM_KEY_PICKER_DIALOG_COMPACT_WIDTH_DP)
        val maxDialogHeight = (displayMetrics.heightPixels * 0.86f).roundToInt()
        val reservedHeight = panelPadding * 2 + dpToPx(if (compactLayout) 156 else 116)
        val listHeight = (maxDialogHeight - reservedHeight)
            .coerceAtLeast(dpToPx(148))
            .coerceAtMost(dpToPx(CUSTOM_KEY_PICKER_LIST_HEIGHT_DP))
        val categoryWidth = if (compactLayout) {
            bodyWidth
        } else {
            dpToPx(CUSTOM_KEY_PICKER_CATEGORY_WIDTH_DP)
                .coerceAtMost((bodyWidth * 0.34f).roundToInt())
        }
        val keyAreaWidth = if (compactLayout) {
            bodyWidth
        } else {
            (bodyWidth - categoryWidth - spacing).coerceAtLeast(dpToPx(180))
        }
        val preferredKeyWidth = dpToPx(CUSTOM_KEY_PICKER_KEY_WIDTH_DP)
        val keyColumnCount = ((keyAreaWidth + spacing) / (preferredKeyWidth + spacing))
            .coerceIn(2, if (compactLayout) 3 else 4)
        val keyWidth = ((keyAreaWidth - spacing * (keyColumnCount - 1)) / keyColumnCount)
            .coerceAtLeast(1)

        var selectedSpec: CustomSoftKeySpec? = null
        var selectedKeyView: TextView? = null
        val categoryViews = mutableListOf<TextView>()
        val titleView = TextView(activity).apply {
            text = activity.getString(R.string.touch_mouse_custom_key_picker_title)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            setTextColor(uiPalette.onSurface)
            textSize = CUSTOM_KEY_PICKER_TITLE_TEXT_SIZE_SP
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val selectedLabel = TextView(activity).apply {
            text = activity.getString(R.string.touch_mouse_custom_key_picker_none)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            setTextColor(uiPalette.onSurfaceMuted)
            textSize = CUSTOM_KEY_PICKER_SELECTED_TEXT_SIZE_SP
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dpToPx(12), dpToPx(7), dpToPx(12), dpToPx(7))
            background = customSoftKeyPickerSelectedBackground()
        }
        val keyGrid = GridLayout(activity).apply {
            columnCount = keyColumnCount
            useDefaultMargins = false
            alignmentMode = GridLayout.ALIGN_BOUNDS
        }
        val cancelButton = Button(activity).apply {
            text = activity.getString(android.R.string.cancel)
            isAllCaps = false
            textSize = CUSTOM_KEY_PICKER_SELECTED_TEXT_SIZE_SP
            setTextColor(uiPalette.onSurfaceMuted)
            setPadding(dpToPx(14), 0, dpToPx(14), 0)
            backgroundTintList = null
            background = customSoftKeyPickerActionBackground(accent = false, enabled = true)
        }
        val confirmButton = Button(activity).apply {
            text = activity.getString(android.R.string.ok)
            isAllCaps = false
            textSize = CUSTOM_KEY_PICKER_SELECTED_TEXT_SIZE_SP
            setPadding(dpToPx(16), 0, dpToPx(16), 0)
            backgroundTintList = null
        }

        fun updateConfirmButtonEnabled(enabled: Boolean) {
            confirmButton.apply {
                isEnabled = enabled
                alpha = if (enabled) 1.0f else 0.58f
                setTextColor(if (enabled) uiPalette.onPrimary else uiPalette.onSurfaceMuted)
                backgroundTintList = null
                background = customSoftKeyPickerActionBackground(accent = true, enabled = enabled)
            }
        }
        updateConfirmButtonEnabled(false)

        fun updateSelectedKey(spec: CustomSoftKeySpec?, view: TextView?) {
            selectedKeyView?.let { updateCustomSoftKeyPickerOptionAppearance(it, false) }
            selectedSpec = spec
            selectedKeyView = view
            selectedKeyView?.let { updateCustomSoftKeyPickerOptionAppearance(it, true) }
            selectedLabel.text = if (spec == null) {
                activity.getString(R.string.touch_mouse_custom_key_picker_none)
            } else {
                activity.getString(R.string.touch_mouse_custom_key_picker_selected_format, spec.pickerLabel)
            }
            updateConfirmButtonEnabled(spec != null)
        }

        fun populateKeys(categoryIndex: Int) {
            categoryViews.forEachIndexed { index, view ->
                updateCustomSoftKeyPickerOptionAppearance(view, index == categoryIndex)
            }
            updateSelectedKey(null, null)
            keyGrid.removeAllViews()
            categories[categoryIndex].keys.forEachIndexed { optionIndex, spec ->
                val keyView = createCustomSoftKeyPickerOption(
                    label = spec.pickerLabel,
                    minWidthDp = CUSTOM_KEY_PICKER_KEY_MIN_WIDTH_DP
                ).apply {
                    setOnClickListener {
                        LauncherHaptics.perform(this, HapticFeedbackConstants.KEYBOARD_TAP)
                        updateSelectedKey(spec, this)
                    }
                }
                val column = optionIndex % keyColumnCount
                keyGrid.addView(
                    keyView,
                    GridLayout.LayoutParams().apply {
                        width = keyWidth
                        height = dpToPx(CUSTOM_KEY_PICKER_OPTION_HEIGHT_DP)
                        setMargins(0, 0, if (column == keyColumnCount - 1) 0 else spacing, spacing)
                    }
                )
            }
        }

        val categoryContainer = LinearLayout(activity).apply {
            orientation = if (compactLayout) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        categories.forEachIndexed { index, category ->
            val categoryView = createCustomSoftKeyPickerOption(
                label = category.label,
                minWidthDp = if (compactLayout) 92 else CUSTOM_KEY_PICKER_CATEGORY_WIDTH_DP
            ).apply {
                setOnClickListener {
                    LauncherHaptics.perform(this, HapticFeedbackConstants.KEYBOARD_TAP)
                    populateKeys(index)
                }
            }
            categoryViews += categoryView
            categoryContainer.addView(
                categoryView,
                if (compactLayout) {
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        dpToPx(CUSTOM_KEY_PICKER_OPTION_HEIGHT_DP)
                    ).apply {
                        rightMargin = spacing
                    }
                } else {
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dpToPx(CUSTOM_KEY_PICKER_OPTION_HEIGHT_DP)
                    ).apply {
                        bottomMargin = spacing
                    }
                }
            )
        }

        val keyScrollView = ScrollView(activity).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            addView(
                keyGrid,
                FrameLayout.LayoutParams(
                    keyAreaWidth,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val pickerView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = customSoftKeyPickerPanelBackground()
            layoutParams = ViewGroup.LayoutParams(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(panelPadding, panelPadding, panelPadding, panelPadding)
            addView(
                LinearLayout(activity).apply {
                    orientation = if (compactLayout) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        titleView,
                        LinearLayout.LayoutParams(
                            if (compactLayout) {
                                LinearLayout.LayoutParams.MATCH_PARENT
                            } else {
                                0
                            },
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            if (compactLayout) 0f else 1f
                        )
                    )
                    addView(
                        selectedLabel,
                        LinearLayout.LayoutParams(
                            if (compactLayout) {
                                LinearLayout.LayoutParams.MATCH_PARENT
                            } else {
                                (bodyWidth * 0.46f).roundToInt()
                            },
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            if (compactLayout) {
                                topMargin = dpToPx(10)
                            } else {
                                leftMargin = spacing
                            }
                        }
                    )
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dpToPx(14)
                }
            )
            if (compactLayout) {
                addView(
                    HorizontalScrollView(activity).apply {
                        isHorizontalScrollBarEnabled = false
                        addView(
                            categoryContainer,
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.WRAP_CONTENT,
                                FrameLayout.LayoutParams.MATCH_PARENT
                            )
                        )
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dpToPx(CUSTOM_KEY_PICKER_OPTION_HEIGHT_DP)
                    ).apply {
                        bottomMargin = spacing
                    }
                )
                addView(
                    keyScrollView,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        listHeight
                    )
                )
            } else {
                addView(
                    LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.TOP
                        addView(
                            ScrollView(activity).apply {
                                isFillViewport = false
                                isVerticalScrollBarEnabled = true
                                addView(
                                    categoryContainer,
                                    FrameLayout.LayoutParams(
                                        FrameLayout.LayoutParams.MATCH_PARENT,
                                        FrameLayout.LayoutParams.WRAP_CONTENT
                                    )
                                )
                            },
                            LinearLayout.LayoutParams(
                                categoryWidth,
                                listHeight
                            )
                        )
                        addView(
                            keyScrollView,
                            LinearLayout.LayoutParams(
                                keyAreaWidth,
                                listHeight
                            ).apply {
                                leftMargin = spacing
                            }
                        )
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            addView(
                LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    setPadding(0, dpToPx(14), 0, 0)
                    addView(
                        cancelButton,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            dpToPx(40)
                        )
                    )
                    addView(
                        confirmButton,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            dpToPx(40)
                        ).apply {
                            leftMargin = spacing
                        }
                    )
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        populateKeys(0)

        val dialog = AlertDialog.Builder(activity).create()
        dialog.setView(pickerView, 0, 0, 0, 0)
        customSoftKeyPickerDialog = dialog
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(false)
        cancelButton.setOnClickListener {
            dialog.dismiss()
        }
        confirmButton.setOnClickListener {
            selectedSpec?.let { spec ->
                addCustomSoftKeyButton(spec)
                dialog.dismiss()
            }
        }
        dialog.setOnDismissListener {
            if (customSoftKeyPickerDialog === dialog) {
                customSoftKeyPickerDialog = null
            }
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    fun isSoftKeyboardSessionActive(): Boolean {
        return builtInKeyboardController?.isVisible() == true ||
            imeController?.shouldHoldRenderSurfaceStable() == true
    }

    private fun detachViews() {
        customSoftKeyPickerDialog?.dismiss()
        customSoftKeyPickerDialog = null
        customSoftKeyButtons.toList().forEach { state ->
            cancelCustomSoftKeyLongPress(state)
            state.view.animate().cancel()
            (state.view.parent as? FrameLayout)?.removeView(state.view)
        }
        customSoftKeyButtons.clear()
        customSoftKeyDeleteTarget?.let { target ->
            target.animate().cancel()
            (target.parent as? FrameLayout)?.removeView(target)
        }
        customSoftKeyDeleteTarget = null
        floatingMouseButton?.let { button ->
            (button.parent as? FrameLayout)?.removeView(button)
        }
        imeController?.detach()
        builtInKeyboardController?.detach()
        floatingMouseExpandedMenu?.let { menu ->
            (menu.parent as? FrameLayout)?.removeView(menu)
        }
        floatingMouseButton = null
        floatingMouseModeButton = null
        floatingMouseMainIcon = null
        imeController = null
        builtInKeyboardController = null
        floatingMouseExpandedMenu = null
        floatingMouseWheelView = null
        floatingMouseLockButton = null
        floatingMouseMenuExpanded = false
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
                if (touchMouseInteractionMode == TouchMouseInteractionMode.TOGGLE_BUTTON_ON_TAP) {
                    val longPressRunnable = Runnable {
                        if (!floatingMouseDragging && !floatingMouseLongPressTriggered) {
                            floatingMouseLongPressTriggered = true
                            LauncherHaptics.perform(button, HapticFeedbackConstants.LONG_PRESS)
                            if (floatingMouseMenuExpanded) {
                                hideFloatingMouseExpandedMenu()
                            } else {
                                showFloatingMouseExpandedMenu()
                            }
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
                    updateFloatingMouseExpandedMenuPosition()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                cancelFloatingMouseLongPress()
                if (floatingMouseDragging) {
                    saveFloatingMouseButtonPosition()
                }
                if (!floatingMouseDragging && !floatingMouseLongPressTriggered) {
                    handleFloatingMouseTap(button)
                }
                floatingMouseDragging = false
                floatingMouseLongPressTriggered = false
                scheduleFloatingMouseIdle()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelFloatingMouseLongPress()
                if (floatingMouseDragging) {
                    saveFloatingMouseButtonPosition()
                }
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

    private fun handleFloatingMouseTap(button: View) {
        LauncherHaptics.perform(button, HapticFeedbackConstants.KEYBOARD_TAP)
        if (touchMouseInteractionMode == TouchMouseInteractionMode.OPEN_MENU_ON_TAP) {
            if (floatingMouseMenuExpanded) {
                hideFloatingMouseExpandedMenu()
            } else {
                showFloatingMouseExpandedMenu()
            }
            return
        }
        toggleTouchMouseMode()
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
        if (floatingMouseMenuExpanded) {
            button.animate().cancel()
            button.alpha = FLOATING_MOUSE_ACTIVE_ALPHA
            return
        }
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

    private fun toggleTouchMouseLock() {
        releaseTouchButtonIfNeeded()
        touchMouseLockEnabled = !touchMouseLockEnabled
        updateTouchMouseModeUi()
        val messageRes = if (touchMouseLockEnabled) {
            R.string.touch_mouse_lock_enabled_toast
        } else {
            R.string.touch_mouse_lock_disabled_toast
        }
        LauncherTransientNoticeBus.show(activity, messageRes, Toast.LENGTH_SHORT)
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
            if (touchMouseLockEnabled) {
                uiPalette.primaryStrong
            } else {
                uiPalette.onSurface
            }
        )
        floatingMouseButton?.background = floatingMouseButtonBackground(touchMouseLockEnabled)
        updateFloatingMouseModeButtonUi()
        updateFloatingMouseLockButtonUi()
    }

    private fun floatingMouseButtonBackground(locked: Boolean): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(if (locked) uiPalette.primaryContainerHigh else uiPalette.surfaceScrim)
            setStroke(
                dpToPx(if (locked) 2 else 1),
                if (locked) uiPalette.primaryStrong else uiPalette.outlineStrong
            )
        }
    }

    private fun resolveTouchButton(): Int {
        return if (touchMouseMode == TouchMouseMode.LEFT) {
            LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT.toInt()
        } else {
            LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT.toInt()
        }
    }

    private fun showSoftKeyboard(reason: String = "floating_menu_keyboard") {
        hideFloatingMouseExpandedMenu()
        if (builtInSoftKeyboardEnabled) {
            imeController?.requestHide(
                reason = "${reason}_builtin",
                refocusRenderView = false
            )
            builtInKeyboardController?.show()
        } else {
            builtInKeyboardController?.hide(refocusRenderView = false)
            imeController?.requestShow(reason = reason)
        }
    }

    private fun populateFloatingMouseExpandedMenu(menu: LinearLayout) {
        toggleSpecialKeyButtons.clear()
        floatingMouseModeButton = null
        floatingMouseLockButton = null
        floatingMouseWheelView = null
        menu.removeAllViews()
        val grid = GridLayout(activity).apply {
            rowCount = SPECIAL_KEYS_GRID_ROWS
            columnCount = SPECIAL_KEYS_GRID_COLUMNS
            useDefaultMargins = false
            alignmentMode = GridLayout.ALIGN_BOUNDS
        }

        addFloatingMouseExpandedMenuGridItem(
            grid = grid,
            item = createFloatingMouseTextButton(SpecialKeySpec("Ctrl", KeyEvent.KEYCODE_CTRL_LEFT, toggleable = true)),
            row = 0,
            column = 0
        )
        addFloatingMouseExpandedMenuGridItem(
            grid = grid,
            item = createFloatingMouseTextButton(SpecialKeySpec("Shift", KeyEvent.KEYCODE_SHIFT_LEFT, toggleable = true)),
            row = 0,
            column = 1
        )
        addFloatingMouseExpandedMenuGridItem(
            grid = grid,
            item = createFloatingMouseTextButton(SpecialKeySpec("Tab", KeyEvent.KEYCODE_TAB)),
            row = 0,
            column = 2
        )
        addFloatingMouseExpandedMenuGridItem(
            grid = grid,
            item = createFloatingMouseTextButton(SpecialKeySpec("Alt", KeyEvent.KEYCODE_ALT_LEFT, toggleable = true)),
            row = 1,
            column = 0
        )
        addFloatingMouseExpandedMenuGridItem(
            grid = grid,
            item = createFloatingMouseLockButton(),
            row = 1,
            column = 1
        )
        addFloatingMouseExpandedMenuGridItem(
            grid = grid,
            item = createFloatingMouseWheelPanel(),
            row = 1,
            column = 2,
            rowSpan = 2
        )
        addFloatingMouseExpandedMenuGridItem(
            grid = grid,
            item = createFloatingMouseModeButton(),
            row = 2,
            column = 0
        )
        addFloatingMouseExpandedMenuGridItem(
            grid = grid,
            item = createFloatingMouseKeyboardButton(),
            row = 2,
            column = 1
        )
        menu.addView(
            grid,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun createFloatingMouseWheelPanel(): View {
        val wheelView = VirtualMouseWheelView(activity).apply {
            contentDescription = activity.getString(R.string.touch_mouse_floating_menu_wheel)
        }
        floatingMouseWheelView = wheelView

        return FrameLayout(activity).apply {
            updateFloatingMouseMenuButtonAppearance(this, false)
            minimumWidth = dpToPx(SPECIAL_KEYS_BUTTON_MIN_WIDTH_DP)
            contentDescription = activity.getString(R.string.touch_mouse_floating_menu_wheel)
            isFocusable = false
            isFocusableInTouchMode = false
            setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
            addView(
                wheelView,
                FrameLayout.LayoutParams(
                    dpToPx(VIRTUAL_WHEEL_TRACK_WIDTH_DP),
                    dpToPx(VIRTUAL_WHEEL_TRACK_HEIGHT_DP),
                    Gravity.CENTER
                )
            )
        }
    }

    private fun createFloatingMouseTextButton(spec: SpecialKeySpec): TextView {
        return TextView(activity).apply {
            text = spec.label
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(uiPalette.onSurface)
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
                LauncherHaptics.perform(this, HapticFeedbackConstants.KEYBOARD_TAP)
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
            includeFontPadding = false
            setTextColor(uiPalette.onSurface)
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

    private fun createFloatingMouseModeButton(): TextView {
        return createFloatingMouseTextActionButton("") {
            LauncherHaptics.perform(this, HapticFeedbackConstants.KEYBOARD_TAP)
            toggleTouchMouseMode()
        }.apply {
            floatingMouseModeButton = this
            updateFloatingMouseModeButtonUi()
        }
    }

    private fun createFloatingMouseLockButton(): TextView {
        return TextView(activity).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(uiPalette.onSurface)
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
            floatingMouseLockButton = this
            updateFloatingMouseLockButtonUi()
            setOnClickListener {
                LauncherHaptics.perform(this, HapticFeedbackConstants.KEYBOARD_TAP)
                toggleTouchMouseLock()
            }
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
                    setColorFilter(uiPalette.onSurface)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = null
                },
                FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
            )
            setOnClickListener {
                LauncherHaptics.perform(this, HapticFeedbackConstants.KEYBOARD_TAP)
                showSoftKeyboard()
            }
        }
    }

    private fun addFloatingMouseExpandedMenuGridItem(
        grid: GridLayout,
        item: View,
        row: Int,
        column: Int,
        rowSpan: Int = 1,
    ) {
        val spacing = dpToPx(SPECIAL_KEYS_BUTTON_SPACING_DP)
        val buttonHeight = dpToPx(SPECIAL_KEYS_BUTTON_HEIGHT_DP)
        grid.addView(
            item,
            GridLayout.LayoutParams(
                GridLayout.spec(row, rowSpan),
                GridLayout.spec(column, 1)
            ).apply {
                width = GridLayout.LayoutParams.WRAP_CONTENT
                height = buttonHeight * rowSpan + spacing * (rowSpan - 1)
                if (column > 0) {
                    leftMargin = spacing
                }
                if (row > 0) {
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
            setColor(if (active) uiPalette.primaryContainer else uiPalette.surfaceHigh)
            setStroke(dpToPx(1), if (active) uiPalette.primaryStrong else uiPalette.outline)
        }
    }

    private fun updateToggleSpecialKeyUi(androidKeyCode: Int, active: Boolean) {
        toggleSpecialKeyButtons[androidKeyCode]?.let { button ->
            updateFloatingMouseMenuButtonAppearance(button, active)
        }
    }

    private fun updateFloatingMouseLockButtonUi() {
        val lockButton = floatingMouseLockButton ?: return
        lockButton.text = activity.getString(
            if (touchMouseLockEnabled) {
                R.string.touch_mouse_floating_menu_unlock
            } else {
                R.string.touch_mouse_floating_menu_lock
            }
        )
        updateFloatingMouseMenuButtonAppearance(lockButton, touchMouseLockEnabled)
    }

    private fun updateFloatingMouseModeButtonUi() {
        val modeButton = floatingMouseModeButton ?: return
        val nextModeLabelRes = if (touchMouseMode == TouchMouseMode.LEFT) {
            R.string.touch_mouse_mode_right
        } else {
            R.string.touch_mouse_mode_left
        }
        val modeLabel = activity.getString(nextModeLabelRes)
        modeButton.text = modeLabel
        modeButton.contentDescription = activity.getString(R.string.touch_mouse_mode_toast, modeLabel)
        updateFloatingMouseMenuButtonAppearance(modeButton, false)
    }

    private fun showFloatingMouseExpandedMenu() {
        if (isSoftKeyboardVisible()) {
            return
        }
        val menu = floatingMouseExpandedMenu ?: return
        if (floatingMouseMenuExpanded) {
            return
        }
        floatingMouseMenuExpanded = true
        clearIdleRunnable()
        highlightFloatingMouse()
        menu.animate().cancel()
        menu.visibility = View.VISIBLE
        updateFloatingMouseExpandedMenuPosition()
        menu.alpha = 0f
        menu.scaleX = 0.92f
        menu.scaleY = 0.92f
        menu.translationY = dpToPx(FLOATING_MENU_ANIM_OFFSET_DP).toFloat()
        menu.bringToFront()
        floatingMouseButton?.bringToFront()
        menu.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(FLOATING_MENU_ANIM_DURATION_MS)
            .start()
    }

    private fun hideFloatingMouseExpandedMenu(animate: Boolean = true) {
        floatingMouseWheelView?.resetToCenter()
        val menu = floatingMouseExpandedMenu
        floatingMouseMenuExpanded = false
        if (menu == null) {
            scheduleFloatingMouseIdle()
            return
        }
        menu.animate().cancel()
        if (!animate || menu.visibility != View.VISIBLE) {
            menu.visibility = View.GONE
            menu.alpha = 1f
            menu.scaleX = 1f
            menu.scaleY = 1f
            menu.translationY = 0f
            scheduleFloatingMouseIdle()
            return
        }
        menu.animate()
            .alpha(0f)
            .scaleX(0.96f)
            .scaleY(0.96f)
            .translationY(dpToPx(FLOATING_MENU_ANIM_OFFSET_DP).toFloat())
            .setDuration(FLOATING_MENU_ANIM_DURATION_MS)
            .withEndAction {
                menu.visibility = View.GONE
                menu.alpha = 1f
                menu.scaleX = 1f
                menu.scaleY = 1f
                menu.translationY = 0f
                scheduleFloatingMouseIdle()
            }
            .start()
    }

    private fun updateFloatingMouseExpandedMenuPosition() {
        val host = hostView ?: return
        val menu = floatingMouseExpandedMenu ?: return
        val button = floatingMouseButton ?: return
        if (!floatingMouseMenuExpanded && menu.visibility != View.VISIBLE) {
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
        return builtInKeyboardController?.isVisible() == true ||
            imeController?.isVisible() == true
    }

    private fun dispatchVirtualMouseScroll(verticalOffset: Double) {
        if (!isNativeInputDispatchReady.invoke()) {
            return
        }
        if (verticalOffset == 0.0) {
            return
        }
        CallbackBridge.sendScroll(0.0, verticalOffset)
    }

    private fun createCustomSoftKeyPickerOption(label: String, minWidthDp: Int): TextView {
        return TextView(activity).apply {
            text = label
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(uiPalette.onSurface)
            textSize = CUSTOM_KEY_PICKER_OPTION_TEXT_SIZE_SP
            minWidth = dpToPx(minWidthDp)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            isAllCaps = false
            isFocusable = false
            isFocusableInTouchMode = false
            contentDescription = label
            setPadding(dpToPx(8), 0, dpToPx(8), 0)
            updateCustomSoftKeyPickerOptionAppearance(this, false)
        }
    }

    private fun updateCustomSoftKeyPickerOptionAppearance(view: TextView, selected: Boolean) {
        view.isSelected = selected
        view.alpha = if (selected) 1.0f else 0.96f
        view.setTextColor(if (selected) uiPalette.onSurface else uiPalette.onSurfaceMuted)
        view.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(8).toFloat()
            setColor(if (selected) uiPalette.primaryContainer else uiPalette.surfaceHigh)
            setStroke(dpToPx(1), if (selected) uiPalette.primaryStrong else uiPalette.outline)
        }
    }

    private fun customSoftKeyPickerPanelBackground(): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(18).toFloat()
            setColor(uiPalette.surface)
            setStroke(dpToPx(1), uiPalette.outlineStrong)
        }
    }

    private fun customSoftKeyPickerSelectedBackground(): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(8).toFloat()
            setColor(uiPalette.surfaceHigh)
            setStroke(dpToPx(1), uiPalette.outline)
        }
    }

    private fun customSoftKeyPickerActionBackground(
        accent: Boolean,
        enabled: Boolean
    ): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(8).toFloat()
            setColor(
                when {
                    accent && enabled -> uiPalette.primaryStrong
                    accent -> uiPalette.surfaceHighest
                    else -> uiPalette.surfaceHigh
                }
            )
            setStroke(
                dpToPx(1),
                when {
                    accent && enabled -> uiPalette.primaryStrong
                    accent -> uiPalette.outline
                    else -> uiPalette.outline
                }
            )
        }
    }

    private fun buildCustomSoftKeyCategories(): List<CustomSoftKeyCategory> {
        return listOf(
            CustomSoftKeyCategory(
                activity.getString(R.string.touch_mouse_custom_key_category_common),
                listOf(
                    CustomSoftKeySpec("Ctrl", "Ctrl", KeyEvent.KEYCODE_CTRL_LEFT, true),
                    CustomSoftKeySpec("Shift", "Shift", KeyEvent.KEYCODE_SHIFT_LEFT, true),
                    CustomSoftKeySpec("Alt", "Alt", KeyEvent.KEYCODE_ALT_LEFT, true),
                    CustomSoftKeySpec("Tab", "Tab", KeyEvent.KEYCODE_TAB),
                    CustomSoftKeySpec("Esc", "Esc", KeyEvent.KEYCODE_ESCAPE),
                    CustomSoftKeySpec("Enter", "Enter", KeyEvent.KEYCODE_ENTER),
                    CustomSoftKeySpec("Space", "Space", KeyEvent.KEYCODE_SPACE),
                    CustomSoftKeySpec("Backspace", "Bksp", KeyEvent.KEYCODE_DEL)
                )
            ),
            CustomSoftKeyCategory(
                activity.getString(R.string.touch_mouse_custom_key_category_function),
                buildList {
                    add(CustomSoftKeySpec("Esc", "Esc", KeyEvent.KEYCODE_ESCAPE))
                    for (index in 1..12) {
                        val keyCode = KeyEvent.KEYCODE_F1 + (index - 1)
                        add(CustomSoftKeySpec("F$index", "F$index", keyCode))
                    }
                    add(CustomSoftKeySpec("Print Screen", "PrtSc", KeyEvent.KEYCODE_SYSRQ))
                    add(CustomSoftKeySpec("Scroll Lock", "ScrLk", KeyEvent.KEYCODE_SCROLL_LOCK))
                    add(CustomSoftKeySpec("Pause / Break", "Pause", KeyEvent.KEYCODE_BREAK))
                }
            ),
            CustomSoftKeyCategory(
                activity.getString(R.string.touch_mouse_custom_key_category_letters),
                ('A'..'Z').map { letter ->
                    val keyCode = KeyEvent.KEYCODE_A + (letter - 'A')
                    CustomSoftKeySpec(letter.toString(), letter.toString(), keyCode)
                }
            ),
            CustomSoftKeyCategory(
                activity.getString(R.string.touch_mouse_custom_key_category_number_row),
                ('1'..'9').map { digit ->
                    val keyCode = KeyEvent.KEYCODE_1 + (digit - '1')
                    CustomSoftKeySpec(digit.toString(), digit.toString(), keyCode)
                } + CustomSoftKeySpec("0", "0", KeyEvent.KEYCODE_0)
            ),
            CustomSoftKeyCategory(
                activity.getString(R.string.touch_mouse_custom_key_category_punctuation),
                listOf(
                    CustomSoftKeySpec("` / ~", "`", KeyEvent.KEYCODE_GRAVE),
                    CustomSoftKeySpec("- / _", "-", KeyEvent.KEYCODE_MINUS),
                    CustomSoftKeySpec("= / +", "=", KeyEvent.KEYCODE_EQUALS),
                    CustomSoftKeySpec("[ / {", "[", KeyEvent.KEYCODE_LEFT_BRACKET),
                    CustomSoftKeySpec("] / }", "]", KeyEvent.KEYCODE_RIGHT_BRACKET),
                    CustomSoftKeySpec("\\ / |", "\\", KeyEvent.KEYCODE_BACKSLASH),
                    CustomSoftKeySpec("; / :", ";", KeyEvent.KEYCODE_SEMICOLON),
                    CustomSoftKeySpec("' / \"", "'", KeyEvent.KEYCODE_APOSTROPHE),
                    CustomSoftKeySpec(", / <", ",", KeyEvent.KEYCODE_COMMA),
                    CustomSoftKeySpec(". / >", ".", KeyEvent.KEYCODE_PERIOD),
                    CustomSoftKeySpec("/ / ?", "/", KeyEvent.KEYCODE_SLASH)
                )
            ),
            CustomSoftKeyCategory(
                activity.getString(R.string.touch_mouse_custom_key_category_editing),
                listOf(
                    CustomSoftKeySpec("Tab", "Tab", KeyEvent.KEYCODE_TAB),
                    CustomSoftKeySpec("Caps Lock", "Caps", KeyEvent.KEYCODE_CAPS_LOCK),
                    CustomSoftKeySpec("Enter", "Enter", KeyEvent.KEYCODE_ENTER),
                    CustomSoftKeySpec("Space", "Space", KeyEvent.KEYCODE_SPACE),
                    CustomSoftKeySpec("Backspace", "Bksp", KeyEvent.KEYCODE_DEL),
                    CustomSoftKeySpec("Insert", "Ins", KeyEvent.KEYCODE_INSERT),
                    CustomSoftKeySpec("Delete", "Del", KeyEvent.KEYCODE_FORWARD_DEL),
                    CustomSoftKeySpec("Home", "Home", KeyEvent.KEYCODE_MOVE_HOME),
                    CustomSoftKeySpec("End", "End", KeyEvent.KEYCODE_MOVE_END),
                    CustomSoftKeySpec("Page Up", "PgUp", KeyEvent.KEYCODE_PAGE_UP),
                    CustomSoftKeySpec("Page Down", "PgDn", KeyEvent.KEYCODE_PAGE_DOWN)
                )
            ),
            CustomSoftKeyCategory(
                activity.getString(R.string.touch_mouse_custom_key_category_arrows),
                listOf(
                    CustomSoftKeySpec("Left", "Left", KeyEvent.KEYCODE_DPAD_LEFT),
                    CustomSoftKeySpec("Up", "Up", KeyEvent.KEYCODE_DPAD_UP),
                    CustomSoftKeySpec("Right", "Right", KeyEvent.KEYCODE_DPAD_RIGHT),
                    CustomSoftKeySpec("Down", "Down", KeyEvent.KEYCODE_DPAD_DOWN)
                )
            ),
            CustomSoftKeyCategory(
                activity.getString(R.string.touch_mouse_custom_key_category_modifiers),
                listOf(
                    CustomSoftKeySpec("Left Shift", "LShift", KeyEvent.KEYCODE_SHIFT_LEFT, true),
                    CustomSoftKeySpec("Right Shift", "RShift", KeyEvent.KEYCODE_SHIFT_RIGHT, true),
                    CustomSoftKeySpec("Left Ctrl", "LCtrl", KeyEvent.KEYCODE_CTRL_LEFT, true),
                    CustomSoftKeySpec("Right Ctrl", "RCtrl", KeyEvent.KEYCODE_CTRL_RIGHT, true),
                    CustomSoftKeySpec("Left Alt", "LAlt", KeyEvent.KEYCODE_ALT_LEFT, true),
                    CustomSoftKeySpec("Right Alt", "RAlt", KeyEvent.KEYCODE_ALT_RIGHT, true),
                    CustomSoftKeySpec("Left Meta", "LMeta", KeyEvent.KEYCODE_META_LEFT, true),
                    CustomSoftKeySpec("Right Meta", "RMeta", KeyEvent.KEYCODE_META_RIGHT, true),
                    CustomSoftKeySpec("Menu", "Menu", KeyEvent.KEYCODE_MENU, true)
                )
            ),
            CustomSoftKeyCategory(
                activity.getString(R.string.touch_mouse_custom_key_category_numpad),
                buildList {
                    add(CustomSoftKeySpec("Num Lock", "Num", KeyEvent.KEYCODE_NUM_LOCK))
                    add(CustomSoftKeySpec("Numpad /", "Num/", KeyEvent.KEYCODE_NUMPAD_DIVIDE))
                    add(CustomSoftKeySpec("Numpad *", "Num*", KeyEvent.KEYCODE_NUMPAD_MULTIPLY))
                    add(CustomSoftKeySpec("Numpad -", "Num-", KeyEvent.KEYCODE_NUMPAD_SUBTRACT))
                    add(CustomSoftKeySpec("Numpad +", "Num+", KeyEvent.KEYCODE_NUMPAD_ADD))
                    add(CustomSoftKeySpec("Num Enter", "NEnter", KeyEvent.KEYCODE_NUMPAD_ENTER))
                    add(CustomSoftKeySpec("Numpad =", "Num=", KeyEvent.KEYCODE_NUMPAD_EQUALS))
                    for (digit in listOf(7, 8, 9, 4, 5, 6, 1, 2, 3, 0)) {
                        val keyCode = KeyEvent.KEYCODE_NUMPAD_0 + digit
                        add(CustomSoftKeySpec("Numpad $digit", "Num$digit", keyCode))
                    }
                    add(CustomSoftKeySpec("Numpad .", "Num.", KeyEvent.KEYCODE_NUMPAD_DOT))
                }
            )
        )
    }

    private fun addCustomSoftKeyButton(spec: CustomSoftKeySpec) {
        val host = hostView ?: return
        val buttonSize = dpToPx(CUSTOM_KEY_BUTTON_SIZE_DP)
        val labelView = TextView(activity).apply {
            text = spec.buttonLabel
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(uiPalette.onSurface)
            textSize = CUSTOM_KEY_BUTTON_TEXT_SIZE_SP
            maxLines = 1
            isAllCaps = false
            contentDescription = spec.pickerLabel
        }
        val button = FrameLayout(activity).apply {
            background = customSoftKeyButtonBackground(active = false)
            alpha = FLOATING_MOUSE_ACTIVE_ALPHA
            elevation = dpToPx(8).toFloat()
            isClickable = true
            isFocusable = false
            isFocusableInTouchMode = false
            contentDescription = spec.pickerLabel
            visibility = if (customSoftKeyButtonsVisible) View.VISIBLE else View.GONE
            addView(
                labelView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
            )
        }
        val state = CustomSoftKeyButtonState(spec = spec, view = button)
        customSoftKeyButtons += state
        host.addView(
            button,
            FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.TOP or Gravity.START).apply {
                leftMargin = 0
                topMargin = 0
            }
        )
        placeCustomSoftKeyButton(host, button, buttonSize, customSoftKeyButtons.size - 1)
        button.setOnTouchListener { _, event -> handleCustomSoftKeyTouch(state, event) }
        button.bringToFront()
        customSoftKeyDeleteTarget?.bringToFront()
        button.scaleX = 0.82f
        button.scaleY = 0.82f
        button.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(CUSTOM_KEY_SCALE_ANIM_DURATION_MS)
            .start()
        maybeShowCustomSoftKeyTutorial()
    }

    private fun handleCustomSoftKeyTouch(state: CustomSoftKeyButtonState, event: MotionEvent): Boolean {
        val button = state.view
        val params = button.layoutParams as? FrameLayout.LayoutParams ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parentDisallowIntercept(button, disallow = true)
                state.dragging = false
                state.longPressTriggered = false
                state.movedBeyondTapSlop = false
                state.overDeleteTarget = false
                state.downRawX = event.rawX
                state.downRawY = event.rawY
                state.lastRawX = event.rawX
                state.lastRawY = event.rawY
                state.downLeft = params.leftMargin
                state.downTop = params.topMargin
                button.background = customSoftKeyButtonBackground(active = true)
                animateCustomSoftKeyScale(button, CUSTOM_KEY_BUTTON_PRESS_SCALE)
                scheduleCustomSoftKeyLongPress(state)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dxFromDown = event.rawX - state.downRawX
                val dyFromDown = event.rawY - state.downRawY
                if (!state.movedBeyondTapSlop &&
                    dxFromDown * dxFromDown + dyFromDown * dyFromDown >
                    floatingMouseTouchSlop * floatingMouseTouchSlop
                ) {
                    state.movedBeyondTapSlop = true
                }
                state.lastRawX = event.rawX
                state.lastRawY = event.rawY
                if (state.dragging) {
                    moveCustomSoftKeyButton(state, event.rawX, event.rawY)
                    updateCustomSoftKeyDeleteTargetHighlight(state)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                parentDisallowIntercept(button, disallow = false)
                cancelCustomSoftKeyLongPress(state)
                val wasDragging = state.dragging
                if (wasDragging) {
                    if (state.overDeleteTarget) {
                        removeCustomSoftKeyButton(state)
                    } else {
                        finishCustomSoftKeyInteraction(state)
                    }
                    hideCustomSoftKeyDeleteTarget()
                    return true
                }
                if (!state.longPressTriggered && !state.movedBeyondTapSlop) {
                    performCustomSoftKeyClick(state)
                } else {
                    finishCustomSoftKeyInteraction(state)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                parentDisallowIntercept(button, disallow = false)
                cancelCustomSoftKeyLongPress(state)
                finishCustomSoftKeyInteraction(state)
                hideCustomSoftKeyDeleteTarget()
                return true
            }

            else -> return false
        }
    }

    private fun scheduleCustomSoftKeyLongPress(state: CustomSoftKeyButtonState) {
        cancelCustomSoftKeyLongPress(state)
        val runnable = Runnable {
            state.longPressRunnable = null
            if (state.view.parent == null || state.dragging) {
                return@Runnable
            }
            state.longPressTriggered = true
            state.dragging = true
            state.dragStartRawX = state.lastRawX
            state.dragStartRawY = state.lastRawY
            val params = state.view.layoutParams as? FrameLayout.LayoutParams ?: return@Runnable
            state.dragStartLeft = params.leftMargin
            state.dragStartTop = params.topMargin
            LauncherHaptics.perform(state.view, HapticFeedbackConstants.LONG_PRESS)
            animateCustomSoftKeyScale(state.view, CUSTOM_KEY_BUTTON_DRAG_SCALE)
            showCustomSoftKeyDeleteTarget()
            moveCustomSoftKeyButton(state, state.lastRawX, state.lastRawY)
            updateCustomSoftKeyDeleteTargetHighlight(state)
        }
        state.longPressRunnable = runnable
        state.view.postDelayed(runnable, CUSTOM_KEY_DRAG_LONG_PRESS_MS)
    }

    private fun cancelCustomSoftKeyLongPress(state: CustomSoftKeyButtonState) {
        state.longPressRunnable?.let { state.view.removeCallbacks(it) }
        state.longPressRunnable = null
    }

    private fun performCustomSoftKeyClick(state: CustomSoftKeyButtonState) {
        LauncherHaptics.perform(state.view, HapticFeedbackConstants.KEYBOARD_TAP)
        if (state.spec.toggleable) {
            toggleSpecialKey(state.spec.keyCode)
        } else {
            sendSyntheticSoftKey(state.spec.keyCode)
        }
        state.view.animate().cancel()
        state.view.animate()
            .scaleX(CUSTOM_KEY_BUTTON_TAP_SCALE)
            .scaleY(CUSTOM_KEY_BUTTON_TAP_SCALE)
            .setDuration(CUSTOM_KEY_SCALE_ANIM_DURATION_MS / 2)
            .withEndAction {
                finishCustomSoftKeyInteraction(state)
            }
            .start()
    }

    private fun finishCustomSoftKeyInteraction(state: CustomSoftKeyButtonState) {
        state.dragging = false
        state.longPressTriggered = false
        state.movedBeyondTapSlop = false
        state.overDeleteTarget = false
        state.view.background = customSoftKeyButtonBackground(
            active = state.spec.toggleable && activeToggleSoftKeys.containsKey(state.spec.keyCode)
        )
        animateCustomSoftKeyScale(state.view, 1f)
        updateCustomSoftKeyDeleteTargetAppearance(active = false)
    }

    private fun moveCustomSoftKeyButton(state: CustomSoftKeyButtonState, rawX: Float, rawY: Float) {
        val host = hostView ?: return
        val params = state.view.layoutParams as? FrameLayout.LayoutParams ?: return
        val dx = (rawX - state.dragStartRawX).roundToInt()
        val dy = (rawY - state.dragStartRawY).roundToInt()
        val maxLeft = (host.width - state.view.width).coerceAtLeast(0)
        val maxTop = (host.height - state.view.height).coerceAtLeast(0)
        params.leftMargin = (state.dragStartLeft + dx).coerceIn(0, maxLeft)
        params.topMargin = (state.dragStartTop + dy).coerceIn(0, maxTop)
        state.view.layoutParams = params
    }

    private fun removeCustomSoftKeyButton(state: CustomSoftKeyButtonState) {
        cancelCustomSoftKeyLongPress(state)
        customSoftKeyButtons.remove(state)
        state.view.animate().cancel()
        state.view.animate()
            .alpha(0f)
            .scaleX(0.72f)
            .scaleY(0.72f)
            .setDuration(CUSTOM_KEY_DELETE_TARGET_ANIM_DURATION_MS)
            .withEndAction {
                (state.view.parent as? FrameLayout)?.removeView(state.view)
            }
            .start()
    }

    private fun createCustomSoftKeyDeleteTarget(): FrameLayout {
        val iconSize = dpToPx(30)
        return FrameLayout(activity).apply {
            visibility = View.GONE
            alpha = 0f
            scaleX = 0.88f
            scaleY = 0.88f
            elevation = dpToPx(12).toFloat()
            isClickable = false
            isFocusable = false
            background = customSoftKeyDeleteTargetBackground(active = false)
            addView(
                ImageView(activity).apply {
                    setImageResource(android.R.drawable.ic_menu_delete)
                    setColorFilter(uiPalette.onSurface)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                },
                FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
            )
        }
    }

    private fun showCustomSoftKeyDeleteTarget() {
        val target = customSoftKeyDeleteTarget ?: return
        target.animate().cancel()
        target.visibility = View.VISIBLE
        target.bringToFront()
        target.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(CUSTOM_KEY_DELETE_TARGET_ANIM_DURATION_MS)
            .start()
    }

    private fun hideCustomSoftKeyDeleteTarget(animate: Boolean = true) {
        val target = customSoftKeyDeleteTarget ?: return
        target.animate().cancel()
        updateCustomSoftKeyDeleteTargetAppearance(active = false)
        if (!animate || target.visibility != View.VISIBLE) {
            target.visibility = View.GONE
            target.alpha = 0f
            target.scaleX = 0.88f
            target.scaleY = 0.88f
            return
        }
        target.animate()
            .alpha(0f)
            .scaleX(0.88f)
            .scaleY(0.88f)
            .setDuration(CUSTOM_KEY_DELETE_TARGET_ANIM_DURATION_MS)
            .withEndAction {
                target.visibility = View.GONE
            }
            .start()
    }

    private fun updateCustomSoftKeyDeleteTargetHighlight(state: CustomSoftKeyButtonState) {
        val target = customSoftKeyDeleteTarget ?: return
        val buttonRect = Rect()
        val targetRect = Rect()
        state.view.getGlobalVisibleRect(buttonRect)
        target.getGlobalVisibleRect(targetRect)
        val over = Rect.intersects(buttonRect, targetRect)
        if (state.overDeleteTarget != over) {
            state.overDeleteTarget = over
            updateCustomSoftKeyDeleteTargetAppearance(over)
        }
    }

    private fun updateCustomSoftKeyDeleteTargetAppearance(active: Boolean) {
        val target = customSoftKeyDeleteTarget ?: return
        target.background = customSoftKeyDeleteTargetBackground(active)
        val scale = if (active) 1.12f else 1f
        target.animate().cancel()
        target.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(CUSTOM_KEY_DELETE_TARGET_ANIM_DURATION_MS)
            .start()
    }

    private fun customSoftKeyButtonBackground(active: Boolean): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(14).toFloat()
            setColor(if (active) uiPalette.primaryContainerHigh else uiPalette.surfaceScrim)
            setStroke(dpToPx(1), if (active) uiPalette.primaryStrong else uiPalette.outlineStrong)
        }
    }

    private fun customSoftKeyDeleteTargetBackground(active: Boolean): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(if (active) uiPalette.dangerContainer else uiPalette.surfaceScrim)
            setStroke(dpToPx(2), if (active) uiPalette.dangerOutline else uiPalette.outlineStrong)
        }
    }

    private fun animateCustomSoftKeyScale(view: View, scale: Float) {
        view.animate().cancel()
        view.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(CUSTOM_KEY_SCALE_ANIM_DURATION_MS)
            .start()
    }

    private fun placeCustomSoftKeyButton(host: FrameLayout, button: FrameLayout, buttonSize: Int, index: Int) {
        val offset = dpToPx(CUSTOM_KEY_BUTTON_INITIAL_OFFSET_DP)
        val gap = dpToPx(CUSTOM_KEY_BUTTON_PLACEMENT_GAP_DP)
        host.post {
            val params = button.layoutParams as? FrameLayout.LayoutParams ?: return@post
            val maxLeft = (host.width - buttonSize).coerceAtLeast(0)
            val maxTop = (host.height - buttonSize).coerceAtLeast(0)
            params.leftMargin = (offset + index * (buttonSize + gap)).coerceIn(0, maxLeft)
            params.topMargin = (host.height / 2 - buttonSize / 2 + index * gap).coerceIn(0, maxTop)
            button.layoutParams = params
        }
    }

    private fun parentDisallowIntercept(view: View, disallow: Boolean) {
        view.parent?.requestDisallowInterceptTouchEvent(disallow)
    }

    private fun maybeShowCustomSoftKeyTutorial() {
        if (customSoftKeyPrefs.getBoolean(CUSTOM_KEY_TUTORIAL_SHOWN_KEY, false)) {
            return
        }
        customSoftKeyPrefs.edit()
            .putBoolean(CUSTOM_KEY_TUTORIAL_SHOWN_KEY, true)
            .apply()
        if (activity.isFinishing || activity.isDestroyed) {
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.touch_mouse_custom_key_tutorial_title)
            .setMessage(R.string.touch_mouse_custom_key_tutorial_message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun toggleSpecialKey(androidKeyCode: Int): Boolean {
        return setToggleSpecialKey(
            androidKeyCode = androidKeyCode,
            active = !activeToggleSoftKeys.containsKey(androidKeyCode)
        )
    }

    private fun setToggleSpecialKey(androidKeyCode: Int, active: Boolean): Boolean {
        val activeTarget = activeToggleSoftKeys[androidKeyCode]
        if (!active) {
            if (activeTarget == null) {
                updateToggleSpecialKeyUi(androidKeyCode, false)
                return true
            }
            logIme(
                "toggleSpecialKey release " +
                    "android=${KeyEvent.keyCodeToString(androidKeyCode)} target=$activeTarget"
            )
            dispatchKeyboardEvent(KeyEvent(KeyEvent.ACTION_UP, androidKeyCode), activeTarget)
            activeToggleSoftKeys.remove(androidKeyCode)
            updateToggleSpecialKeyUi(androidKeyCode, false)
            return true
        }

        if (activeTarget != null) {
            updateToggleSpecialKeyUi(androidKeyCode, true)
            return true
        }
        val target = resolveSoftKeyboardTarget()
        logIme(
            "toggleSpecialKey press " +
                "android=${KeyEvent.keyCodeToString(androidKeyCode)} target=$target"
        )
        return if (dispatchKeyboardEvent(KeyEvent(KeyEvent.ACTION_DOWN, androidKeyCode), target)) {
            activeToggleSoftKeys[androidKeyCode] = target
            updateToggleSpecialKeyUi(androidKeyCode, true)
            true
        } else {
            false
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

    private fun sendPreviewTextSync(text: CharSequence, source: String): Boolean {
        if (source != TOGETHER_IN_SPIRE_CHAT_TEXT_SYNC_SOURCE ||
            !isNativeInputDispatchReady.invoke() ||
            resolveSoftKeyboardTarget() != SoftKeyboardTarget.GLFW
        ) {
            return false
        }
        // ChatConsole cannot represent cursor edits, so replace its text atomically.
        val encodedText = Base64.getEncoder().encodeToString(
            text.toString().toByteArray(StandardCharsets.UTF_8)
        )
        CallbackBridge.sendChar(
            TOGETHER_IN_SPIRE_CHAT_TEXT_SYNC_START,
            CallbackBridge.getCurrentMods()
        )
        for (character in encodedText) {
            CallbackBridge.sendChar(character, CallbackBridge.getCurrentMods())
        }
        CallbackBridge.sendChar(
            TOGETHER_IN_SPIRE_CHAT_TEXT_SYNC_END,
            CallbackBridge.getCurrentMods()
        )
        return true
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
    ): Boolean {
        logIme("sendSyntheticSoftKey androidKey=${KeyEvent.keyCodeToString(androidKeyCode)} target=$target")
        val downHandled = dispatchSoftKeyboardKeyEventToTarget(KeyEvent(KeyEvent.ACTION_DOWN, androidKeyCode), target)
        if (!shouldDelaySoftKeyRelease(androidKeyCode, target)) {
            val upHandled = dispatchSoftKeyboardKeyEventToTarget(KeyEvent(KeyEvent.ACTION_UP, androidKeyCode), target)
            return downHandled || upHandled
        }
        return downHandled
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
        val controller = imeController
        if (controller == null) {
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
        if (!controller.postOnEditor(releaseRunnable, delayMs = SOFT_KEY_MIN_PRESS_MS)) {
            pendingSoftKeyReleaseRunnables.remove(androidKeyCode)
            logIme(
                "scheduleSoftKeyRelease fallback no_editor " +
                    "android=${KeyEvent.keyCodeToString(androidKeyCode)} target=$target"
            )
            releaseRunnable.run()
            return
        }
        logIme(
            "scheduleSoftKeyRelease queued " +
                "android=${KeyEvent.keyCodeToString(androidKeyCode)} target=$target delayMs=$SOFT_KEY_MIN_PRESS_MS"
        )
    }

    private fun flushPendingSoftKeyRelease(androidKeyCode: Int) {
        val releaseRunnable = pendingSoftKeyReleaseRunnables.remove(androidKeyCode) ?: return
        imeController?.removeEditorCallback(releaseRunnable)
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
            KeyEvent.KEYCODE_INSERT -> AWT_VK_INSERT
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> AWT_VK_ENTER
            KeyEvent.KEYCODE_TAB -> AWT_VK_TAB
            KeyEvent.KEYCODE_SPACE -> AWT_VK_SPACE
            KeyEvent.KEYCODE_ESCAPE -> AWT_VK_ESCAPE
            KeyEvent.KEYCODE_DPAD_LEFT -> AWT_VK_LEFT
            KeyEvent.KEYCODE_DPAD_UP -> AWT_VK_UP
            KeyEvent.KEYCODE_DPAD_RIGHT -> AWT_VK_RIGHT
            KeyEvent.KEYCODE_DPAD_DOWN -> AWT_VK_DOWN
            KeyEvent.KEYCODE_PAGE_UP -> AWT_VK_PAGE_UP
            KeyEvent.KEYCODE_PAGE_DOWN -> AWT_VK_PAGE_DOWN
            KeyEvent.KEYCODE_MOVE_HOME -> AWT_VK_HOME
            KeyEvent.KEYCODE_MOVE_END -> AWT_VK_END
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> AWT_VK_SHIFT
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> AWT_VK_CONTROL
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> AWT_VK_ALT
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> AWT_VK_META
            KeyEvent.KEYCODE_MENU -> AWT_VK_CONTEXT_MENU
            KeyEvent.KEYCODE_CAPS_LOCK -> AWT_VK_CAPS_LOCK
            KeyEvent.KEYCODE_SCROLL_LOCK -> AWT_VK_SCROLL_LOCK
            KeyEvent.KEYCODE_NUM_LOCK -> AWT_VK_NUM_LOCK
            KeyEvent.KEYCODE_SYSRQ -> AWT_VK_PRINTSCREEN
            KeyEvent.KEYCODE_BREAK -> AWT_VK_PAUSE
            KeyEvent.KEYCODE_GRAVE -> AWT_VK_BACK_QUOTE
            KeyEvent.KEYCODE_APOSTROPHE -> AWT_VK_QUOTE
            KeyEvent.KEYCODE_COMMA -> AWT_VK_COMMA
            KeyEvent.KEYCODE_MINUS -> AWT_VK_MINUS
            KeyEvent.KEYCODE_PERIOD -> AWT_VK_PERIOD
            KeyEvent.KEYCODE_SLASH -> AWT_VK_SLASH
            KeyEvent.KEYCODE_SEMICOLON -> AWT_VK_SEMICOLON
            KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_NUMPAD_EQUALS -> AWT_VK_EQUALS
            KeyEvent.KEYCODE_LEFT_BRACKET -> AWT_VK_OPEN_BRACKET
            KeyEvent.KEYCODE_BACKSLASH -> AWT_VK_BACK_SLASH
            KeyEvent.KEYCODE_RIGHT_BRACKET -> AWT_VK_CLOSE_BRACKET
            KeyEvent.KEYCODE_NUMPAD_DOT -> AWT_VK_DECIMAL
            KeyEvent.KEYCODE_NUMPAD_DIVIDE -> AWT_VK_DIVIDE
            KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> AWT_VK_MULTIPLY
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> AWT_VK_SUBTRACT
            KeyEvent.KEYCODE_NUMPAD_ADD -> AWT_VK_ADD
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
                AWT_VK_0 + (androidKeyCode - KeyEvent.KEYCODE_0)
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
                AWT_VK_A + (androidKeyCode - KeyEvent.KEYCODE_A)
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 ->
                AWT_VK_NUMPAD0 + (androidKeyCode - KeyEvent.KEYCODE_NUMPAD_0)
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ->
                AWT_VK_F1 + (androidKeyCode - KeyEvent.KEYCODE_F1)
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

    private fun handleDeleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        logIme("handleDeleteSurroundingText before=$beforeLength after=$afterLength")
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

    private fun handlePerformEditorAction(actionCode: Int): Boolean {
        logIme("handlePerformEditorAction actionCode=$actionCode")
        sendSyntheticSoftKey(KeyEvent.KEYCODE_ENTER)
        if (imeController?.isPreviewActive() == true) {
            imeController?.requestHide(reason = "preview_editor_action")
        }
        return true
    }

    private fun handleKeyboardVisibilityChanged(visible: Boolean) {
        logIme("handleKeyboardVisibilityChanged visible=$visible")
        if (visible) {
            return
        }
        flushPendingSoftKeyReleases()
        releaseActiveToggleSoftKeys()
    }

    private fun logIme(message: String) {
        Log.d(IME_LOG_TAG, message)
    }

    private fun logImeState(message: String) {
        Log.i(IME_LOG_TAG, message)
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

    private fun readFloatingMousePositionFraction(key: String): Float? {
        if (!floatingMouseLayoutPrefs.contains(key)) {
            return null
        }
        return floatingMouseLayoutPrefs.getFloat(key, 0f)
    }

    private fun saveFloatingMouseButtonPosition() {
        val host = hostView ?: return
        val button = floatingMouseButton ?: return
        val params = button.layoutParams as? FrameLayout.LayoutParams ?: return
        val maxLeft = (host.width - button.width).coerceAtLeast(0)
        val maxTop = (host.height - button.height).coerceAtLeast(0)
        val storedPosition = captureFloatingMouseStoredPosition(
            leftMargin = params.leftMargin,
            topMargin = params.topMargin,
            maxLeft = maxLeft,
            maxTop = maxTop
        )
        floatingMouseLayoutPrefs.edit()
            .putFloat(FLOATING_MOUSE_LEFT_FRACTION_KEY, storedPosition.leftFraction)
            .putFloat(FLOATING_MOUSE_TOP_FRACTION_KEY, storedPosition.topFraction)
            .apply()
    }

    private fun placeFloatingButtonAtSavedOrDefaultPosition(
        host: FrameLayout,
        button: FrameLayout,
        buttonSize: Int
    ) {
        val inset = dpToPx(FLOATING_MOUSE_SIDE_INSET_DP)
        host.post {
            val params = button.layoutParams as? FrameLayout.LayoutParams ?: return@post
            val maxLeft = (host.width - buttonSize).coerceAtLeast(0)
            val maxTop = (host.height - buttonSize).coerceAtLeast(0)
            val defaultLeft = (maxLeft - inset).coerceAtLeast(0)
            val defaultTop = (maxTop / 2).coerceAtLeast(0)
            val restoredPosition = restoreFloatingMouseResolvedPosition(
                leftFraction = readFloatingMousePositionFraction(FLOATING_MOUSE_LEFT_FRACTION_KEY),
                topFraction = readFloatingMousePositionFraction(FLOATING_MOUSE_TOP_FRACTION_KEY),
                maxLeft = maxLeft,
                maxTop = maxTop,
                defaultLeft = defaultLeft,
                defaultTop = defaultTop
            )
            params.leftMargin = restoredPosition.left
            params.topMargin = restoredPosition.top
            button.layoutParams = params
            updateFloatingMouseExpandedMenuPosition()
        }
    }

    private inner class VirtualMouseWheelView(context: android.content.Context) : FrameLayout(context) {
        private val thumbView = View(context)
        private var normalizedOffset = 0f
        private var scrollRepeatRunnable: Runnable? = null

        init {
            isClickable = true
            isFocusable = false
            clipChildren = false
            clipToPadding = false
            setPadding(0, dpToPx(VIRTUAL_WHEEL_TRACK_PADDING_VERTICAL_DP), 0, dpToPx(VIRTUAL_WHEEL_TRACK_PADDING_VERTICAL_DP))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(12).toFloat()
                setColor(uiPalette.surfaceHigh)
                setStroke(dpToPx(1), uiPalette.outline)
            }

            addView(
                TextView(context).apply {
                    text = "▲"
                    gravity = Gravity.CENTER
                    setTextColor(uiPalette.onSurfaceMuted)
                    textSize = VIRTUAL_WHEEL_ARROW_TEXT_SIZE_SP
                },
                LayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL
                ).apply {
                    topMargin = dpToPx(2)
                }
            )

            addView(
                View(context).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = dpToPx(2).toFloat()
                        setColor(uiPalette.outlineStrong)
                    }
                },
                LayoutParams(
                    dpToPx(VIRTUAL_WHEEL_CENTER_MARKER_WIDTH_DP),
                    dpToPx(VIRTUAL_WHEEL_CENTER_MARKER_HEIGHT_DP),
                    Gravity.CENTER
                )
            )

            addView(
                TextView(context).apply {
                    text = "▼"
                    gravity = Gravity.CENTER
                    setTextColor(uiPalette.onSurfaceMuted)
                    textSize = VIRTUAL_WHEEL_ARROW_TEXT_SIZE_SP
                },
                LayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                ).apply {
                    bottomMargin = dpToPx(2)
                }
            )

            addView(
                thumbView.apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = dpToPx(8).toFloat()
                        setColor(uiPalette.onSurfaceMuted)
                    }
                    alpha = 0.96f
                    elevation = dpToPx(2).toFloat()
                },
                LayoutParams(
                    dpToPx(VIRTUAL_WHEEL_THUMB_WIDTH_DP),
                    dpToPx(VIRTUAL_WHEEL_THUMB_HEIGHT_DP),
                    Gravity.CENTER
                )
            )

            setOnTouchListener { _, event -> handleWheelTouch(event) }
        }

        fun resetToCenter() {
            stopRepeating()
            normalizedOffset = 0f
            thumbView.animate().cancel()
            thumbView.animate()
                .translationY(0f)
                .setDuration(120L)
                .start()
            updateThumbAppearance(active = false)
        }

        override fun onDetachedFromWindow() {
            stopRepeating()
            super.onDetachedFromWindow()
        }

        private fun handleWheelTouch(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    thumbView.animate().cancel()
                    val previousOffset = normalizedOffset
                    updateNormalizedOffset(event.y)
                    maybeKickoffScrolling(previousOffset)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val previousOffset = normalizedOffset
                    updateNormalizedOffset(event.y)
                    maybeKickoffScrolling(previousOffset)
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    resetToCenter()
                    return true
                }

                else -> return false
            }
        }

        private fun updateNormalizedOffset(touchY: Float) {
            val travel = maxThumbTravel()
            if (travel <= 0f) {
                normalizedOffset = 0f
                thumbView.translationY = 0f
                updateThumbAppearance(active = false)
                return
            }

            val centerY = height / 2f
            normalizedOffset = ((centerY - touchY) / travel).coerceIn(-1f, 1f)
            thumbView.translationY = -normalizedOffset * travel
            updateThumbAppearance(active = isActive())
        }

        private fun maybeKickoffScrolling(previousOffset: Float) {
            val active = isActive()
            if (!active) {
                stopRepeating()
                return
            }

            val previousActive = abs(previousOffset) >= VIRTUAL_WHEEL_DEAD_ZONE
            val previousDirection = previousOffset.compareTo(0f)
            val currentDirection = normalizedOffset.compareTo(0f)
            if (!previousActive || previousDirection != currentDirection) {
                LauncherHaptics.perform(this, HapticFeedbackConstants.CLOCK_TICK)
                dispatchScrollTick()
            }
            ensureRepeating()
        }

        private fun isActive(): Boolean {
            return abs(normalizedOffset) >= VIRTUAL_WHEEL_DEAD_ZONE
        }

        private fun ensureRepeating() {
            if (scrollRepeatRunnable != null) {
                return
            }
            val repeatRunnable = object : Runnable {
                override fun run() {
                    if (!isActive()) {
                        scrollRepeatRunnable = null
                        return
                    }
                    dispatchScrollTick()
                    postDelayed(this, currentRepeatDelayMs())
                }
            }
            scrollRepeatRunnable = repeatRunnable
            postDelayed(repeatRunnable, currentRepeatDelayMs())
        }

        private fun stopRepeating() {
            scrollRepeatRunnable?.let(::removeCallbacks)
            scrollRepeatRunnable = null
        }

        private fun currentRepeatDelayMs(): Long {
            val strength = normalizedStrength()
            val delayRange = (VIRTUAL_WHEEL_REPEAT_SLOW_MS - VIRTUAL_WHEEL_REPEAT_FAST_MS).toFloat()
            val nextDelay = VIRTUAL_WHEEL_REPEAT_SLOW_MS - (delayRange * strength).roundToInt().toLong()
            return nextDelay.coerceIn(
                VIRTUAL_WHEEL_REPEAT_FAST_MS,
                VIRTUAL_WHEEL_REPEAT_SLOW_MS
            )
        }

        private fun dispatchScrollTick() {
            val strength = normalizedStrength()
            if (strength <= 0f) {
                return
            }
            val magnitude = VIRTUAL_WHEEL_MIN_SCROLL_DELTA +
                (VIRTUAL_WHEEL_MAX_SCROLL_DELTA - VIRTUAL_WHEEL_MIN_SCROLL_DELTA) * strength
            val direction = if (normalizedOffset >= 0f) 1.0 else -1.0
            dispatchVirtualMouseScroll(direction * magnitude)
        }

        private fun normalizedStrength(): Float {
            val strength = (abs(normalizedOffset) - VIRTUAL_WHEEL_DEAD_ZONE) / (1f - VIRTUAL_WHEEL_DEAD_ZONE)
            return strength.coerceIn(0f, 1f)
        }

        private fun updateThumbAppearance(active: Boolean) {
            thumbView.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(8).toFloat()
                setColor(if (active) uiPalette.primaryStrong else uiPalette.onSurfaceMuted)
            }
        }

        private fun maxThumbTravel(): Float {
            val availableHeight = height - paddingTop - paddingBottom - thumbView.height
            return (availableHeight / 2f).coerceAtLeast(0f)
        }
    }
}
