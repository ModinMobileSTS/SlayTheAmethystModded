package io.stamethyst.ui

import android.app.Activity
import android.widget.Toast
import androidx.annotation.StringRes
import io.stamethyst.LauncherActivity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class LauncherTransientNoticeDuration {
    SHORT,
    LONG
}

data class LauncherTransientNoticeRequest(
    val message: UiText,
    val duration: LauncherTransientNoticeDuration = LauncherTransientNoticeDuration.SHORT,
    val actionLabel: UiText? = null,
    val onAction: (() -> Unit)? = null
)

object LauncherTransientNoticeBus {
    private val _requests = MutableSharedFlow<LauncherTransientNoticeRequest>(extraBufferCapacity = 64)
    val requests = _requests.asSharedFlow()

    fun show(
        host: Activity,
        message: UiText,
        duration: Int
    ) {
        show(host, message, duration.toLauncherTransientNoticeDuration())
    }

    fun show(
        host: Activity,
        message: String,
        duration: Int
    ) {
        show(host, UiText.DynamicString(message), duration)
    }

    fun show(
        host: Activity,
        @StringRes resId: Int,
        duration: Int,
        vararg args: Any
    ) {
        show(host, UiText.StringResource(resId, *args), duration)
    }

    fun show(
        host: Activity,
        message: UiText,
        duration: LauncherTransientNoticeDuration = LauncherTransientNoticeDuration.SHORT
    ) {
        if (host is LauncherActivity) {
            show(message, duration)
            return
        }
        host.runOnUiThread {
            Toast.makeText(host, message.resolve(host), duration.toToastDuration()).show()
        }
    }

    fun show(
        host: Activity,
        message: String,
        duration: LauncherTransientNoticeDuration = LauncherTransientNoticeDuration.SHORT
    ) {
        show(host, UiText.DynamicString(message), duration)
    }

    fun show(
        message: UiText,
        duration: LauncherTransientNoticeDuration = LauncherTransientNoticeDuration.SHORT
    ) {
        show(
            message = message,
            duration = duration,
            actionLabel = null,
            onAction = null
        )
    }

    fun show(
        message: UiText,
        duration: LauncherTransientNoticeDuration = LauncherTransientNoticeDuration.SHORT,
        actionLabel: UiText? = null,
        onAction: (() -> Unit)? = null
    ) {
        _requests.tryEmit(
            LauncherTransientNoticeRequest(
                message = message,
                duration = duration,
                actionLabel = actionLabel,
                onAction = onAction
            )
        )
    }
}

private fun Int.toLauncherTransientNoticeDuration(): LauncherTransientNoticeDuration {
    return if (this == Toast.LENGTH_SHORT) {
        LauncherTransientNoticeDuration.SHORT
    } else {
        LauncherTransientNoticeDuration.LONG
    }
}

private fun LauncherTransientNoticeDuration.toToastDuration(): Int {
    return if (this == LauncherTransientNoticeDuration.SHORT) {
        Toast.LENGTH_SHORT
    } else {
        Toast.LENGTH_LONG
    }
}
