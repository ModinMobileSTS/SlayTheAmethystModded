package io.stamethyst.ui.main

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Fixed recognition accents for the launcher game page icon tiles.
 *
 * These are deliberately independent of the user-selectable theme seed: the tile
 * color tells you which card you are looking at, so the same three cards stay
 * recognizable across all five seed colors. Every accent ships a light and a dark
 * variant so it keeps contrast on both surfaces.
 *
 * Apply an accent to the icon tile only. Card containers, borders, and switch
 * controls stay on Material 3 theme roles.
 */
internal object GameCardAccents {
    private val VirtualLanLight = Color(0xFF397463)
    private val VirtualLanDark = Color(0xFF98CBB8)
    private val AchievementsLight = Color(0xFF8E681D)
    private val AchievementsDark = Color(0xFFE1C387)
    private val RendererLight = Color(0xFF36618E)
    private val RendererDark = Color(0xFFA6C9EC)

    val virtualLan: Color
        @Composable
        @ReadOnlyComposable
        get() = if (isDarkScheme()) VirtualLanDark else VirtualLanLight

    val achievements: Color
        @Composable
        @ReadOnlyComposable
        get() = if (isDarkScheme()) AchievementsDark else AchievementsLight

    val renderer: Color
        @Composable
        @ReadOnlyComposable
        get() = if (isDarkScheme()) RendererDark else RendererLight
}

/** Icon tile container fill for an accent. Matches the existing tile alpha convention. */
internal fun accentTileColor(accent: Color): Color = accent.copy(alpha = 0.14f)

@Composable
@ReadOnlyComposable
private fun isDarkScheme(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f
