package io.stamethyst.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

sealed interface UiText {
    data class StringResource(
        @param:StringRes val resId: Int,
        val args: List<Any> = emptyList()
    ) : UiText {
        constructor(@StringRes resId: Int, vararg args: Any) : this(resId, args.toList())
    }

    data class DynamicString(val value: String) : UiText
}

fun UiText.resolve(context: Context): String {
    return when (this) {
        is UiText.StringResource -> context.getString(
            resId,
            *args.map { arg ->
                if (arg is UiText) {
                    arg.resolve(context)
                } else {
                    arg
                }
            }.toTypedArray()
        )

        is UiText.DynamicString -> value
    }
}

@Composable
fun UiText.resolve(): String {
    return resolve(LocalContext.current)
}
