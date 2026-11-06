package io.stamethyst.ui.settings.common

import androidx.compose.runtime.staticCompositionLocalOf

/** Allows a host to retain its confirmation flow while reusing an action control. */
internal val LocalSettingsActionGate = staticCompositionLocalOf<((() -> Unit) -> Unit)?> { null }
