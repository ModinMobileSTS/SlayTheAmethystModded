package io.stamethyst

import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

internal class FloatingMouseImeController(
    private val activity: AppCompatActivity,
    private val requestRenderViewFocus: () -> Unit,
    private val debugLogger: (String) -> Unit,
    private val callbacks: InputCallbacks
) {
    interface InputCallbacks {
        fun onCommitText(
            text: CharSequence?,
            source: String
        ): Boolean

        fun onDeleteSurroundingText(
            beforeLength: Int,
            afterLength: Int
        ): Boolean

        fun onSendKeyEvent(event: KeyEvent): Boolean

        fun onPerformEditorAction(actionCode: Int): Boolean

        fun onKeyboardVisibilityChanged(visible: Boolean)
    }

    private val sessionMachine = SoftKeyboardSessionMachine()
    private var sessionState = SoftKeyboardSessionMachine.State()
    private var hostView: FrameLayout? = null
    private var editorView: GameImeEditor? = null
    private var pendingVerifyRunnable: Runnable? = null
    private var pendingShowReadyRunnable: Runnable? = null
    private var pendingUnexpectedHideRecoveryRunnable: Runnable? = null
    private var lastKeyboardVisible = false
    private var keepKeyboardVisibleRequested = false
    private var lastExplicitShowRequestAtMs = 0L
    private var lastInputInteractionAtMs = 0L
    private var unexpectedHideRecoveryAttempts = 0

    fun attachToHost(host: FrameLayout) {
        detach()
        hostView = host
        val editor = GameImeEditor(
            context = activity,
            debugLogger = debugLogger,
            callbacks = callbacks,
            windowFocusChangedCallback = ::onEditorWindowFocusChanged,
            inputInteractionCallback = ::noteInputInteraction
        ).apply {
            // Keep the editor fully opaque to system focus heuristics while still visually invisible.
            alpha = EDITOR_HOST_ALPHA
            setTextColor(Color.TRANSPARENT)
            highlightColor = Color.TRANSPARENT
            setBackgroundColor(Color.TRANSPARENT)
            isCursorVisible = false
            isFocusable = true
            isFocusableInTouchMode = true
            showSoftInputOnFocus = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setOnFocusChangeListener { _, hasFocus ->
                debugLogger(
                    "editorFocus hasFocus=$hasFocus " +
                        snapshotState(editor = this)
                )
                onEditorFocusChanged(hasFocus)
            }
        }
        host.addView(
            editor,
            FrameLayout.LayoutParams(
                HIDDEN_EDITOR_SIZE_PX,
                HIDDEN_EDITOR_SIZE_PX,
                Gravity.TOP or Gravity.START
            )
        )
        ViewCompat.setOnApplyWindowInsetsListener(editor) { _, insets ->
            syncVisibilitySnapshot(source = "insets")
            insets
        }
        editorView = editor
        lastKeyboardVisible = isKeyboardVisible()
        sessionState = sessionMachine.onVisibilityChanged(sessionState, lastKeyboardVisible)
        debugLogger("attachToHost ${snapshotState(editor = editor)}")
        ViewCompat.requestApplyInsets(editor)
    }

    fun detach() {
        cancelPendingVerify()
        cancelPendingShowReady()
        cancelPendingUnexpectedHideRecovery()
        editorView?.let { editor ->
            ViewCompat.setOnApplyWindowInsetsListener(editor, null)
            (editor.parent as? FrameLayout)?.removeView(editor)
        }
        editorView = null
        hostView = null
        lastKeyboardVisible = false
        keepKeyboardVisibleRequested = false
        lastExplicitShowRequestAtMs = 0L
        lastInputInteractionAtMs = 0L
        unexpectedHideRecoveryAttempts = 0
        sessionState = SoftKeyboardSessionMachine.State()
    }

    fun requestShow(reason: String) {
        keepKeyboardVisibleRequested = true
        lastExplicitShowRequestAtMs = SystemClock.uptimeMillis()
        cancelPendingUnexpectedHideRecovery()
        debugLogger(
            "requestShow reason=$reason " +
                snapshotState()
        )
        applyTransition(
            sessionMachine.requestShow(
                state = sessionState,
                currentlyVisible = isKeyboardVisible()
            )
        )
    }

    fun requestHide(
        reason: String,
        refocusRenderView: Boolean = true
    ) {
        keepKeyboardVisibleRequested = false
        unexpectedHideRecoveryAttempts = 0
        cancelPendingUnexpectedHideRecovery()
        debugLogger(
            "requestHide reason=$reason " +
                snapshotState()
        )
        applyTransition(
            sessionMachine.requestHide(
                state = sessionState,
                refocusRenderView = refocusRenderView
            )
        )
    }

    fun isVisible(): Boolean = isKeyboardVisible()

    fun shouldHoldRenderSurfaceStable(): Boolean {
        return keepKeyboardVisibleRequested ||
            sessionState.pendingShow ||
            lastKeyboardVisible ||
            pendingUnexpectedHideRecoveryRunnable != null
    }

    fun postOnEditor(
        runnable: Runnable,
        delayMs: Long = 0L
    ): Boolean {
        val editor = editorView ?: return false
        if (delayMs <= 0L) {
            editor.post(runnable)
        } else {
            editor.postDelayed(runnable, delayMs)
        }
        return true
    }

    fun removeEditorCallback(runnable: Runnable): Boolean {
        val editor = editorView ?: return false
        editor.removeCallbacks(runnable)
        return true
    }

    private fun applyTransition(transition: SoftKeyboardSessionMachine.Transition) {
        sessionState = transition.state
        cancelPendingVerify()
        cancelPendingShowReady()
        transition.commands.forEach(::executeCommand)
        syncVisibilitySnapshot(source = "transition")
    }

    private fun executeCommand(command: SoftKeyboardSessionMachine.Command) {
        when (command) {
            is SoftKeyboardSessionMachine.Command.PerformShowAttempt -> {
                performShowAttempt(
                    generation = command.generation,
                    attempt = command.attempt
                )
            }

            is SoftKeyboardSessionMachine.Command.ScheduleVerify -> {
                scheduleVerify(
                    generation = command.generation,
                    delayMs = command.delayMs
                )
            }

            is SoftKeyboardSessionMachine.Command.PerformHide -> {
                performHide(refocusRenderView = command.refocusRenderView)
            }
        }
    }

    private fun performShowAttempt(
        generation: Int,
        attempt: Int
    ) {
        val editor = editorView ?: return
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        if (generation != sessionState.generation) {
            return
        }

        editor.prepareForIme()
        val focusRequested = editor.requestFocus()
        val touchFocusRequested = editor.requestFocusFromTouch()
        editor.setSelection(editor.text?.length ?: 0)
        imm.restartInput(editor)
        debugLogger(
            "showAttemptPrepare generation=$generation attempt=$attempt " +
                "focus=$focusRequested touchFocus=$touchFocusRequested " +
                snapshotState(editor = editor, imm = imm)
        )
        scheduleShowWhenReady(
            generation = generation,
            attempt = attempt,
            checksRemaining = SHOW_READY_MAX_CHECKS,
            reason = "attempt_prepare"
        )
    }

    private fun performHide(refocusRenderView: Boolean) {
        val editor = editorView ?: return
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        cancelPendingShowReady()
        cancelPendingUnexpectedHideRecovery()
        windowInsetsController(editor).hide(WindowInsetsCompat.Type.ime())
        val hideAccepted = imm.hideSoftInputFromWindow(editor.windowToken, 0)
        editor.clearFocus()
        debugLogger(
            "hideKeyboard hideAccepted=$hideAccepted " +
                snapshotState(editor = editor, imm = imm)
        )
        if (refocusRenderView) {
            requestRenderViewFocus.invoke()
        }
        syncVisibilitySnapshot(source = "hide")
    }

    private fun scheduleVerify(
        generation: Int,
        delayMs: Long
    ) {
        val editor = editorView ?: return
        val verifyRunnable = Runnable {
            pendingVerifyRunnable = null
            val visible = isKeyboardVisible()
            debugLogger(
                "verifyShow generation=$generation delayMs=$delayMs " +
                    "visible=$visible ${snapshotState(editor = editor)}"
            )
            applyTransition(
                sessionMachine.onVerify(
                    state = sessionState,
                    generation = generation,
                    currentlyVisible = visible
                )
            )
        }
        pendingVerifyRunnable = verifyRunnable
        if (delayMs <= 0L) {
            editor.post(verifyRunnable)
        } else {
            editor.postDelayed(verifyRunnable, delayMs)
        }
    }

    private fun cancelPendingVerify() {
        val editor = editorView
        val verifyRunnable = pendingVerifyRunnable
        if (editor != null && verifyRunnable != null) {
            editor.removeCallbacks(verifyRunnable)
        }
        pendingVerifyRunnable = null
    }

    private fun scheduleShowWhenReady(
        generation: Int,
        attempt: Int,
        checksRemaining: Int,
        reason: String
    ) {
        val editor = editorView ?: return
        cancelPendingShowReady()
        val runnable = Runnable {
            pendingShowReadyRunnable = null
            runShowWhenReady(
                generation = generation,
                attempt = attempt,
                checksRemaining = checksRemaining,
                reason = reason
            )
        }
        pendingShowReadyRunnable = runnable
        editor.postDelayed(
            runnable,
            if (checksRemaining == SHOW_READY_MAX_CHECKS) 0L else SHOW_READY_RECHECK_DELAY_MS
        )
    }

    private fun runShowWhenReady(
        generation: Int,
        attempt: Int,
        checksRemaining: Int,
        reason: String
    ) {
        val editor = editorView ?: return
        if (generation != sessionState.generation) {
            return
        }
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return

        editor.prepareForIme()
        if (!editor.hasFocus()) {
            editor.requestFocus()
            editor.requestFocusFromTouch()
        }
        val hasEditorFocus = editor.hasFocus()
        val hasEditorWindowFocus = editor.hasWindowFocus()
        val hasWindowFocus = hasActivityWindowFocus()
        val isEditorActive = imm.isActive(editor)
        val shouldWaitForConnection = hasEditorFocus && hasEditorWindowFocus && hasWindowFocus && !isEditorActive

        if (shouldWaitForConnection && checksRemaining > 0) {
            imm.restartInput(editor)
        }

        if (hasEditorFocus && hasEditorWindowFocus && hasWindowFocus && (!shouldWaitForConnection || checksRemaining <= 0)) {
            issueExplicitShow(
                editor = editor,
                imm = imm,
                generation = generation,
                attempt = attempt,
                reason = reason,
                checksRemaining = checksRemaining
            )
            return
        }

        debugLogger(
            "showAwait generation=$generation attempt=$attempt " +
                "checksRemaining=$checksRemaining reason=$reason " +
                snapshotState(editor = editor, imm = imm)
        )
        if (checksRemaining <= 0) {
            return
        }
        scheduleShowWhenReady(
            generation = generation,
            attempt = attempt,
            checksRemaining = checksRemaining - 1,
            reason = reason
        )
    }

    private fun issueExplicitShow(
        editor: GameImeEditor,
        imm: InputMethodManager,
        generation: Int,
        attempt: Int,
        reason: String,
        checksRemaining: Int
    ) {
        editor.prepareForIme()
        editor.setSelection(editor.text?.length ?: 0)
        imm.restartInput(editor)
        windowInsetsController(editor).show(WindowInsetsCompat.Type.ime())
        val showAccepted = imm.showSoftInput(editor, 0)
        debugLogger(
            "showDispatch generation=$generation attempt=$attempt " +
                "checksRemaining=$checksRemaining reason=$reason " +
                "showAccepted=$showAccepted ${snapshotState(editor = editor, imm = imm)}"
        )
        syncVisibilitySnapshot(source = "show_dispatch")
    }

    private fun cancelPendingShowReady() {
        val editor = editorView
        val runnable = pendingShowReadyRunnable
        if (editor != null && runnable != null) {
            editor.removeCallbacks(runnable)
        }
        pendingShowReadyRunnable = null
    }

    private fun scheduleUnexpectedHideRecovery(trigger: String) {
        if (!shouldAttemptUnexpectedHideRecovery()) {
            return
        }
        val editor = editorView ?: return
        cancelPendingUnexpectedHideRecovery()
        val runnable = Runnable {
            pendingUnexpectedHideRecoveryRunnable = null
            if (!shouldAttemptUnexpectedHideRecovery()) {
                return@Runnable
            }
            unexpectedHideRecoveryAttempts++
            debugLogger(
                "unexpectedHideRecovery trigger=$trigger attempt=$unexpectedHideRecoveryAttempts " +
                    snapshotState(editor = editor)
            )
            requestShow(reason = "unexpected_hide_recovery:$trigger")
        }
        pendingUnexpectedHideRecoveryRunnable = runnable
        editor.postDelayed(runnable, UNEXPECTED_HIDE_RECOVERY_DELAY_MS)
    }

    private fun cancelPendingUnexpectedHideRecovery() {
        val editor = editorView
        val runnable = pendingUnexpectedHideRecoveryRunnable
        if (editor != null && runnable != null) {
            editor.removeCallbacks(runnable)
        }
        pendingUnexpectedHideRecoveryRunnable = null
    }

    private fun syncVisibilitySnapshot(source: String) {
        val visible = isKeyboardVisible()
        sessionState = sessionMachine.onVisibilityChanged(sessionState, visible)
        if (visible == lastKeyboardVisible) {
            return
        }
        lastKeyboardVisible = visible
        debugLogger(
            "keyboardVisibility source=$source visible=$visible " +
                snapshotState()
        )
        if (visible) {
            cancelPendingVerify()
            cancelPendingShowReady()
            cancelPendingUnexpectedHideRecovery()
            unexpectedHideRecoveryAttempts = 0
        } else {
            scheduleUnexpectedHideRecovery(trigger = "visibility:$source")
        }
        callbacks.onKeyboardVisibilityChanged(visible)
    }

    private fun isKeyboardVisible(): Boolean {
        val anchor = editorView ?: hostView ?: return false
        return ViewCompat.getRootWindowInsets(anchor)?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }

    private fun isEditorActive(): Boolean {
        val editor = editorView ?: return false
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return false
        return imm.isActive(editor)
    }

    private fun onEditorFocusChanged(hasFocus: Boolean) {
        if (hasFocus && sessionState.pendingShow) {
            scheduleShowWhenReady(
                generation = sessionState.generation,
                attempt = sessionState.nextRetryIndex.coerceAtLeast(1) - 1,
                checksRemaining = SHOW_READY_MAX_CHECKS,
                reason = "editor_focus"
            )
        }
        if (hasFocus) {
            scheduleUnexpectedHideRecovery(trigger = "editor_focus")
        }
    }

    private fun onEditorWindowFocusChanged(hasWindowFocus: Boolean) {
        debugLogger(
            "editorWindowFocus hasWindowFocus=$hasWindowFocus " +
                "pendingShow=${sessionState.pendingShow} ${snapshotState()}"
        )
        if (hasWindowFocus && sessionState.pendingShow) {
            scheduleShowWhenReady(
                generation = sessionState.generation,
                attempt = sessionState.nextRetryIndex.coerceAtLeast(1) - 1,
                checksRemaining = SHOW_READY_MAX_CHECKS,
                reason = "window_focus"
            )
        }
        if (hasWindowFocus) {
            scheduleUnexpectedHideRecovery(trigger = "window_focus")
        }
    }

    private fun shouldAttemptUnexpectedHideRecovery(nowMs: Long = SystemClock.uptimeMillis()): Boolean {
        if (!keepKeyboardVisibleRequested ||
            sessionState.pendingShow ||
            lastKeyboardVisible ||
            activity.isFinishing ||
            activity.isDestroyed ||
            !hasActivityWindowFocus() ||
            unexpectedHideRecoveryAttempts >= MAX_UNEXPECTED_HIDE_RECOVERY_ATTEMPTS
        ) {
            return false
        }

        val recentShowRequest = nowMs - lastExplicitShowRequestAtMs
        if (recentShowRequest in 0..UNEXPECTED_HIDE_RECOVERY_AFTER_SHOW_WINDOW_MS) {
            return true
        }

        val recentInputInteraction = nowMs - lastInputInteractionAtMs
        return recentInputInteraction in 0..UNEXPECTED_HIDE_RECOVERY_AFTER_INPUT_WINDOW_MS
    }

    private fun noteInputInteraction() {
        lastInputInteractionAtMs = SystemClock.uptimeMillis()
    }

    private fun snapshotState(
        editor: View? = editorView,
        imm: InputMethodManager? =
            activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    ): String {
        val target = editor
        return buildString {
            append("visible=").append(isKeyboardVisible())
            append(" editorAttached=").append(target?.isAttachedToWindow == true)
            append(" editorFocus=").append(target?.hasFocus() == true)
            append(" editorWindowFocus=").append(target?.hasWindowFocus() == true)
            append(" activityWindowFocus=").append(hasActivityWindowFocus())
            append(" windowToken=").append(target?.windowToken != null)
            append(" active=").append(target != null && imm?.isActive(target) == true)
            append(" acceptingText=").append(imm?.isAcceptingText == true)
        }
    }

    private fun hasActivityWindowFocus(): Boolean {
        return activity.window?.decorView?.hasWindowFocus() == true
    }

    private fun windowInsetsController(editor: View) =
        WindowCompat.getInsetsController(activity.window, editor)

    private class GameImeEditor(
        context: Context,
        private val debugLogger: (String) -> Unit,
        private val callbacks: InputCallbacks,
        private val windowFocusChangedCallback: (Boolean) -> Unit,
        private val inputInteractionCallback: () -> Unit
    ) : AppCompatEditText(context) {
        init {
            inputType = DEFAULT_INPUT_TYPE
            imeOptions = DEFAULT_IME_OPTIONS
            setPadding(0, 0, 0, 0)
            minWidth = 0
            minHeight = 0
            setEms(1)
            setText("", BufferType.EDITABLE)
        }

        fun prepareForIme() {
            if (text == null) {
                setText("", BufferType.EDITABLE)
            }
            setSelection(text?.length ?: 0)
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            debugLogger("editorAttached attached=$isAttachedToWindow hasWindowFocus=${hasWindowFocus()} hasFocus=${hasFocus()}")
        }

        override fun onDetachedFromWindow() {
            debugLogger("editorDetached attached=$isAttachedToWindow hasWindowFocus=${hasWindowFocus()} hasFocus=${hasFocus()}")
            super.onDetachedFromWindow()
        }

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            debugLogger("editorWindowFocusCallback hasWindowFocus=$hasWindowFocus hasFocus=${hasFocus()}")
            windowFocusChangedCallback.invoke(hasWindowFocus)
        }

        override fun onCheckIsTextEditor(): Boolean = true

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
            outAttrs.inputType = DEFAULT_INPUT_TYPE
            outAttrs.imeOptions = DEFAULT_IME_OPTIONS
            val baseConnection = super.onCreateInputConnection(outAttrs)
            return object : InputConnectionWrapper(
                baseConnection,
                true
            ) {
                override fun commitText(
                    text: CharSequence?,
                    newCursorPosition: Int
                ): Boolean {
                    val result = super.commitText(text, newCursorPosition)
                    debugLogger(
                        "InputConnection.commitText text=${describeText(text)} " +
                            "cursor=$newCursorPosition result=$result"
                    )
                    inputInteractionCallback.invoke()
                    callbacks.onCommitText(text, source = "commit_text")
                    return true
                }

                override fun setComposingText(
                    text: CharSequence?,
                    newCursorPosition: Int
                ): Boolean {
                    val result = super.setComposingText(text, newCursorPosition)
                    debugLogger(
                        "InputConnection.setComposingText text=${describeText(text)} " +
                            "cursor=$newCursorPosition result=$result"
                    )
                    return result
                }

                override fun finishComposingText(): Boolean {
                    val result = super.finishComposingText()
                    debugLogger("InputConnection.finishComposingText result=$result")
                    return result
                }

                override fun deleteSurroundingText(
                    beforeLength: Int,
                    afterLength: Int
                ): Boolean {
                    val result = super.deleteSurroundingText(beforeLength, afterLength)
                    debugLogger(
                        "InputConnection.deleteSurroundingText before=$beforeLength " +
                            "after=$afterLength result=$result"
                    )
                    inputInteractionCallback.invoke()
                    callbacks.onDeleteSurroundingText(beforeLength, afterLength)
                    return true
                }

                override fun sendKeyEvent(event: KeyEvent): Boolean {
                    debugLogger(
                        "InputConnection.sendKeyEvent event=${describeKeyEvent(event)}"
                    )
                    inputInteractionCallback.invoke()
                    return callbacks.onSendKeyEvent(event)
                }

                override fun performEditorAction(actionCode: Int): Boolean {
                    debugLogger("InputConnection.performEditorAction actionCode=$actionCode")
                    inputInteractionCallback.invoke()
                    return callbacks.onPerformEditorAction(actionCode)
                }
            }
        }

        private fun describeText(text: CharSequence?): String {
            if (text == null) {
                return "<null>"
            }
            return buildString {
                append('"')
                text.forEach { ch ->
                    append(
                        when (ch) {
                            '\b' -> "\\b"
                            '\n' -> "\\n"
                            '\r' -> "\\r"
                            '\t' -> "\\t"
                            else -> if (Character.isISOControl(ch)) {
                                "\\u" + ch.code.toString(16).padStart(4, '0')
                            } else {
                                ch
                            }
                        }
                    )
                }
                append('"')
                append(" len=").append(text.length)
            }
        }

        private fun describeKeyEvent(event: KeyEvent): String {
            return buildString {
                append(
                    when (event.action) {
                        KeyEvent.ACTION_DOWN -> "DOWN"
                        KeyEvent.ACTION_UP -> "UP"
                        KeyEvent.ACTION_MULTIPLE -> "MULTIPLE"
                        else -> event.action.toString()
                    }
                )
                append('/')
                append(KeyEvent.keyCodeToString(event.keyCode))
                append(" repeat=").append(event.repeatCount)
                append(" unicode=").append(event.unicodeChar)
                if (!event.characters.isNullOrEmpty()) {
                    append(" chars=").append(describeText(event.characters))
                }
            }
        }
    }

    companion object {
        private const val EDITOR_HOST_ALPHA = 1f
        private const val HIDDEN_EDITOR_SIZE_PX = 1
        private const val SHOW_READY_RECHECK_DELAY_MS = 16L
        private const val SHOW_READY_MAX_CHECKS = 8
        private const val UNEXPECTED_HIDE_RECOVERY_DELAY_MS = 96L
        private const val UNEXPECTED_HIDE_RECOVERY_AFTER_SHOW_WINDOW_MS = 1_500L
        private const val UNEXPECTED_HIDE_RECOVERY_AFTER_INPUT_WINDOW_MS = 900L
        private const val MAX_UNEXPECTED_HIDE_RECOVERY_ATTEMPTS = 1
        private const val DEFAULT_INPUT_TYPE = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        private const val DEFAULT_IME_OPTIONS = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
    }
}
