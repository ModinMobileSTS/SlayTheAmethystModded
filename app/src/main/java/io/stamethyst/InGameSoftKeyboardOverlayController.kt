package io.stamethyst

import android.content.ClipboardManager
import android.graphics.Typeface
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.stamethyst.ui.haptics.LauncherHaptics
import java.util.Locale
import kotlin.math.roundToInt

internal class InGameSoftKeyboardOverlayController(
    private val activity: AppCompatActivity,
    private val requestRenderViewFocus: () -> Unit,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onCommitText(text: CharSequence): Boolean

        fun onBackspace(): Boolean

        fun onEnter(): Boolean

        fun onTab(): Boolean

        fun onSystemKeyboardRequested()

        fun onVisibilityChanged(visible: Boolean)
    }

    private sealed interface KeySpec {
        val weight: Float

        data class TextKey(
            val base: String,
            val shifted: String = base.uppercase(Locale.ROOT),
            override val weight: Float = 1f
        ) : KeySpec

        data class ActionKey(
            val action: Action,
            override val weight: Float
        ) : KeySpec
    }

    private enum class Action {
        SHIFT,
        MODE,
        TAB,
        PASTE,
        SPACE,
        BACKSPACE,
        ENTER,
        SYSTEM_KEYBOARD,
        HIDE
    }

    private enum class LayoutMode {
        LETTERS,
        SYMBOLS
    }

    private var hostView: FrameLayout? = null
    private var panelView: LinearLayout? = null
    private var rowsContainer: LinearLayout? = null
    private var visible = false
    private var shiftEnabled = false
    private var layoutMode = LayoutMode.LETTERS

    fun attachToHost(host: FrameLayout) {
        detach()
        hostView = host
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            alpha = 0f
            elevation = dpToPx(18).toFloat()
            isClickable = true
            isFocusable = false
            setBackgroundColor(0xCC1A1A1A.toInt())
            setPadding(
                dpToPx(PANEL_PADDING_HORIZONTAL_DP),
                dpToPx(PANEL_PADDING_TOP_DP),
                dpToPx(PANEL_PADDING_HORIZONTAL_DP),
                dpToPx(PANEL_PADDING_BOTTOM_DP)
            )
        }
        val rows = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        panel.addView(
            rows,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        ViewCompat.setOnApplyWindowInsetsListener(panel) { view, insets ->
            val bottomInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            view.setPadding(
                dpToPx(PANEL_PADDING_HORIZONTAL_DP),
                dpToPx(PANEL_PADDING_TOP_DP),
                dpToPx(PANEL_PADDING_HORIZONTAL_DP),
                dpToPx(PANEL_PADDING_BOTTOM_DP) + bottomInset
            )
            insets
        }
        host.addView(
            panel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )
        panelView = panel
        rowsContainer = rows
        rebuildKeys()
        ViewCompat.requestApplyInsets(panel)
    }

    fun detach() {
        panelView?.animate()?.cancel()
        panelView?.let { panel ->
            ViewCompat.setOnApplyWindowInsetsListener(panel, null)
            (panel.parent as? FrameLayout)?.removeView(panel)
        }
        panelView = null
        rowsContainer = null
        hostView = null
        visible = false
        shiftEnabled = false
        layoutMode = LayoutMode.LETTERS
    }

    fun show() {
        val panel = panelView ?: return
        if (visible) {
            return
        }
        visible = true
        shiftEnabled = false
        layoutMode = LayoutMode.LETTERS
        rebuildKeys()
        panel.bringToFront()
        panel.visibility = View.VISIBLE
        panel.animate().cancel()
        panel.alpha = 0f
        panel.translationY = dpToPx(SHOW_TRANSLATION_Y_DP).toFloat()
        panel.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(SHOW_HIDE_ANIM_DURATION_MS)
            .start()
        callbacks.onVisibilityChanged(true)
    }

    fun hide(refocusRenderView: Boolean = true) {
        val panel = panelView ?: return
        if (!visible && panel.visibility != View.VISIBLE) {
            return
        }
        visible = false
        shiftEnabled = false
        layoutMode = LayoutMode.LETTERS
        panel.animate().cancel()
        panel.visibility = View.GONE
        panel.alpha = 0f
        panel.translationY = 0f
        rebuildKeys()
        callbacks.onVisibilityChanged(false)
        if (refocusRenderView) {
            requestRenderViewFocus.invoke()
        }
    }

    fun isVisible(): Boolean = visible

    private fun rebuildKeys() {
        val rows = rowsContainer ?: return
        rows.removeAllViews()
        resolveKeyRows().forEachIndexed { index, row ->
            rows.addView(
                createKeyRow(row),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (index > 0) {
                        topMargin = dpToPx(KEY_ROW_SPACING_DP)
                    }
                }
            )
        }
    }

    private fun resolveKeyRows(): List<List<KeySpec>> {
        return if (layoutMode == LayoutMode.LETTERS) {
            listOf(
                listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p").map(::textKey),
                listOf("a", "s", "d", "f", "g", "h", "j", "k", "l").map(::textKey),
                listOf(
                    KeySpec.ActionKey(Action.SHIFT, 1.35f),
                    textKey("z"),
                    textKey("x"),
                    textKey("c"),
                    textKey("v"),
                    textKey("b"),
                    textKey("n"),
                    textKey("m"),
                    textKey(","),
                    textKey("."),
                    KeySpec.ActionKey(Action.BACKSPACE, 1.55f)
                ),
                listOf(
                    KeySpec.ActionKey(Action.MODE, 1.2f),
                    KeySpec.ActionKey(Action.TAB, 1.05f),
                    KeySpec.ActionKey(Action.PASTE, 1.2f),
                    KeySpec.ActionKey(Action.SPACE, 2.3f),
                    KeySpec.ActionKey(Action.ENTER, 1.35f),
                    KeySpec.ActionKey(Action.SYSTEM_KEYBOARD, 1.2f),
                    KeySpec.ActionKey(Action.HIDE, 1.1f)
                )
            )
        } else {
            listOf(
                listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").map(::textKey),
                listOf("@", "#", "$", "%", "&", "*", "-", "+", "(", ")").map(::textKey),
                listOf(
                    textKey("?"),
                    textKey("!"),
                    textKey("/"),
                    textKey("\\"),
                    textKey("\""),
                    textKey("'"),
                    textKey(":"),
                    textKey(";"),
                    textKey("_"),
                    KeySpec.ActionKey(Action.BACKSPACE, 1.6f)
                ),
                listOf(
                    KeySpec.ActionKey(Action.MODE, 1.2f),
                    KeySpec.ActionKey(Action.TAB, 1.05f),
                    KeySpec.ActionKey(Action.PASTE, 1.2f),
                    KeySpec.ActionKey(Action.SPACE, 2.3f),
                    KeySpec.ActionKey(Action.ENTER, 1.35f),
                    KeySpec.ActionKey(Action.SYSTEM_KEYBOARD, 1.2f),
                    KeySpec.ActionKey(Action.HIDE, 1.1f)
                )
            )
        }
    }

    private fun createKeyRow(specs: List<KeySpec>): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            specs.forEachIndexed { index, spec ->
                addView(
                    createKeyButton(spec),
                    LinearLayout.LayoutParams(
                        0,
                        dpToPx(KEY_HEIGHT_DP),
                        spec.weight
                    ).apply {
                        if (index > 0) {
                            leftMargin = dpToPx(KEY_SPACING_DP)
                        }
                    }
                )
            }
        }
    }

    private fun createKeyButton(spec: KeySpec): View {
        val isActive = when (spec) {
            is KeySpec.ActionKey -> {
                spec.action == Action.SHIFT && shiftEnabled ||
                    spec.action == Action.MODE && layoutMode == LayoutMode.SYMBOLS
            }

            else -> false
        }
        if (spec is KeySpec.ActionKey && spec.action == Action.SYSTEM_KEYBOARD) {
            return ImageView(activity).apply {
                background = createKeyBackground(accent = true, active = false)
                contentDescription = resolveLabel(spec)
                scaleType = ImageView.ScaleType.CENTER
                setImageResource(R.drawable.ic_keyboard)
                setColorFilter(0xFFFFFFFF.toInt())
                setOnClickListener {
                    LauncherHaptics.perform(this, HapticFeedbackConstants.KEYBOARD_TAP)
                    handleKeyPress(spec)
                }
            }
        }
        return TextView(activity).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            isAllCaps = false
            isSingleLine = true
            setTextColor(0xFFFFFFFF.toInt())
            textSize = KEY_TEXT_SIZE_SP
            typeface = if (spec is KeySpec.ActionKey) {
                Typeface.DEFAULT_BOLD
            } else {
                Typeface.DEFAULT
            }
            text = resolveLabel(spec)
            background = createKeyBackground(
                accent = spec is KeySpec.ActionKey,
                active = isActive
            )
            setOnClickListener {
                LauncherHaptics.perform(this, HapticFeedbackConstants.KEYBOARD_TAP)
                handleKeyPress(spec)
            }
        }
    }

    private fun resolveLabel(spec: KeySpec): String {
        return when (spec) {
            is KeySpec.TextKey -> {
                if (layoutMode == LayoutMode.LETTERS && shiftEnabled) {
                    spec.shifted
                } else {
                    spec.base
                }
            }

            is KeySpec.ActionKey -> when (spec.action) {
                Action.SHIFT -> activity.getString(R.string.touch_mouse_builtin_keyboard_shift)
                Action.MODE -> if (layoutMode == LayoutMode.LETTERS) {
                    activity.getString(R.string.touch_mouse_builtin_keyboard_symbols)
                } else {
                    activity.getString(R.string.touch_mouse_builtin_keyboard_letters)
                }

                Action.TAB -> activity.getString(R.string.touch_mouse_builtin_keyboard_tab)
                Action.PASTE -> activity.getString(R.string.touch_mouse_builtin_keyboard_paste)
                Action.SPACE -> activity.getString(R.string.touch_mouse_builtin_keyboard_space)
                Action.BACKSPACE -> activity.getString(R.string.touch_mouse_builtin_keyboard_backspace)
                Action.ENTER -> activity.getString(R.string.touch_mouse_builtin_keyboard_enter)
                Action.SYSTEM_KEYBOARD -> activity.getString(R.string.touch_mouse_builtin_keyboard_system)
                Action.HIDE -> activity.getString(R.string.touch_mouse_builtin_keyboard_hide)
            }
        }
    }

    private fun handleKeyPress(spec: KeySpec) {
        when (spec) {
            is KeySpec.TextKey -> {
                val text = if (layoutMode == LayoutMode.LETTERS && shiftEnabled) {
                    spec.shifted
                } else {
                    spec.base
                }
                callbacks.onCommitText(text)
                if (layoutMode == LayoutMode.LETTERS && shiftEnabled) {
                    shiftEnabled = false
                    rebuildKeys()
                }
            }

            is KeySpec.ActionKey -> when (spec.action) {
                Action.SHIFT -> {
                    shiftEnabled = !shiftEnabled
                    rebuildKeys()
                }

                Action.MODE -> {
                    layoutMode = if (layoutMode == LayoutMode.LETTERS) {
                        LayoutMode.SYMBOLS
                    } else {
                        LayoutMode.LETTERS
                    }
                    shiftEnabled = false
                    rebuildKeys()
                }

                Action.TAB -> callbacks.onTab()
                Action.PASTE -> {
                    readClipboardText()?.let(callbacks::onCommitText)
                }
                Action.SPACE -> callbacks.onCommitText(" ")
                Action.BACKSPACE -> callbacks.onBackspace()
                Action.ENTER -> callbacks.onEnter()
                Action.SYSTEM_KEYBOARD -> {
                    hide(refocusRenderView = false)
                    callbacks.onSystemKeyboardRequested()
                }
                Action.HIDE -> hide()
            }
        }
    }

    private fun readClipboardText(): CharSequence? {
        val clipboard = activity.getSystemService(ClipboardManager::class.java) ?: return null
        if (!clipboard.hasPrimaryClip()) {
            return null
        }
        val item = clipboard.primaryClip?.getItemAt(0) ?: return null
        return item.coerceToText(activity)
            ?.takeIf { it.isNotBlank() }
    }

    private fun createKeyBackground(
        accent: Boolean,
        active: Boolean
    ): android.graphics.drawable.GradientDrawable {
        val color = when {
            active -> 0xCC355B2E.toInt()
            accent -> 0xB83A3A3A.toInt()
            else -> 0xA82A2A2A.toInt()
        }
        val strokeColor = when {
            active -> 0xFF98D96A.toInt()
            accent -> 0xFF636363.toInt()
            else -> 0xFF4A4A4A.toInt()
        }
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(KEY_CORNER_RADIUS_DP).toFloat()
            setColor(color)
            setStroke(dpToPx(1), strokeColor)
        }
    }

    private fun textKey(value: String): KeySpec.TextKey = KeySpec.TextKey(base = value)

    private fun dpToPx(dp: Int): Int {
        return (activity.resources.displayMetrics.density * dp).roundToInt()
    }

    companion object {
        private const val PANEL_PADDING_HORIZONTAL_DP = 10
        private const val PANEL_PADDING_TOP_DP = 10
        private const val PANEL_PADDING_BOTTOM_DP = 10
        private const val KEY_HEIGHT_DP = 46
        private const val KEY_SPACING_DP = 6
        private const val KEY_ROW_SPACING_DP = 6
        private const val KEY_CORNER_RADIUS_DP = 10
        private const val SHOW_TRANSLATION_Y_DP = 10
        private const val SHOW_HIDE_ANIM_DURATION_MS = 160L
        private const val KEY_TEXT_SIZE_SP = 15f
    }
}
