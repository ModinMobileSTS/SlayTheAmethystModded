package io.stamethyst.backend.launch

import android.content.Context
import androidx.annotation.StringRes

internal fun Context.progressText(@StringRes resId: Int, vararg args: Any): String {
    return getString(resId, *args)
}
