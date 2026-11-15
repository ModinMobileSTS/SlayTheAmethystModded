package io.stamethyst.ui.main

import androidx.compose.runtime.staticCompositionLocalOf
import io.stamethyst.config.ModPageLayout

// Only the mod page's presentation reads this; secondary dialogs stay on the shared UI.
internal val LocalModPageLayout = staticCompositionLocalOf { ModPageLayout.COMPACT }
