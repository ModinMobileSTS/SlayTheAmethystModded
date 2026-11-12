package io.stamethyst.ui

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.preferredFrameRate
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import io.stamethyst.backend.render.shouldRequestHighViewFrameRate

/** A draw-layer hint: static content does not gain an artificial animation/keepalive loop. */
@Composable
internal fun rememberHighRefreshRateModifier(enabled: Boolean = true): Modifier {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = enabled && shouldRequestHighViewFrameRate(
        sdkInt = Build.VERSION.SDK_INT,
        foreground = lifecycleState.isAtLeast(Lifecycle.State.RESUMED),
        focused = LocalWindowInfo.current.isWindowFocused,
    )
    // Compose owns/restores its layer preference when this modifier is removed. Unlike setting
    // a ViewGroup's requestedFrameRate, this public modifier covers descendant Compose layers.
    return if (active) Modifier.preferredFrameRate(FrameRateCategory.High) else Modifier
}
