package io.stamethyst.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import io.stamethyst.config.LauncherThemeColor
import io.stamethyst.config.LauncherThemeMode

private val LightSurfaceBase = Color(0xFFFFF8FC)
private val LightOnSurfaceBase = Color(0xFF1E1A20)
private val DarkSurfaceBase = Color(0xFF15121A)
private val DarkOnSurfaceBase = Color(0xFFE9E0EA)
private val LightOutlineBase = Color(0xFF7C7483)
private val DarkOutlineBase = Color(0xFF968E9C)
private val LightInverseSurface = Color(0xFF332F35)
private val LightInverseOnSurface = Color(0xFFF6EEF7)
private val DarkInverseSurface = Color(0xFFE9E0EA)
private val DarkInverseOnSurface = Color(0xFF2E2A30)
private val LightError = Color(0xFFBA1A1A)
private val LightOnError = Color.White
private val LightErrorContainer = Color(0xFFFFDAD6)
private val LightOnErrorContainer = Color(0xFF410002)
private val DarkError = Color(0xFFFFB4AB)
private val DarkOnError = Color(0xFF690005)
private val DarkErrorContainer = Color(0xFF93000A)
private val DarkOnErrorContainer = Color(0xFFFFDAD6)

@Composable
fun LauncherTheme(
    themeMode: LauncherThemeMode,
    themeColor: LauncherThemeColor,
    content: @Composable () -> Unit,
) {
    val useDarkTheme = when (themeMode) {
        LauncherThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
        LauncherThemeMode.LIGHT -> false
        LauncherThemeMode.DARK -> true
    }
    val colorScheme = remember(themeColor, useDarkTheme) {
        if (useDarkTheme) {
            darkLauncherColorScheme(themeColor)
        } else {
            lightLauncherColorScheme(themeColor)
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

private fun lightLauncherColorScheme(themeColor: LauncherThemeColor): ColorScheme {
    val seed = themeColor.seedColor
    val primary = transformColor(
        seed,
        saturationMultiplier = 1.06f,
        lightnessMultiplier = 0.92f
    )
    val secondary = transformColor(
        seed,
        hueShift = -18f,
        saturationMultiplier = 0.42f,
        lightnessDelta = 0.04f
    )
    val tertiary = transformColor(
        seed,
        hueShift = 24f,
        saturationMultiplier = 0.58f,
        lightnessDelta = 0.03f
    )
    val background = blend(seed, LightSurfaceBase, 0.95f)
    val surface = blend(seed, LightSurfaceBase, 0.92f)
    val surfaceVariant = blend(seed, LightSurfaceBase, 0.80f)
    val onSurface = blend(LightOnSurfaceBase, seed, 0.08f)
    val onSurfaceVariant = blend(LightOnSurfaceBase, seed, 0.24f)
    val outline = blend(LightOutlineBase, seed, 0.28f)
    val outlineVariant = blend(surfaceVariant, outline, 0.42f)

    return lightColorScheme(
        primary = primary,
        onPrimary = contentColorFor(primary),
        primaryContainer = blend(seed, Color.White, 0.78f),
        onPrimaryContainer = blend(seed, LightOnSurfaceBase, 0.68f),
        inversePrimary = blend(primary, Color.White, 0.22f),
        secondary = secondary,
        onSecondary = contentColorFor(secondary),
        secondaryContainer = blend(secondary, Color.White, 0.80f),
        onSecondaryContainer = blend(secondary, LightOnSurfaceBase, 0.66f),
        tertiary = tertiary,
        onTertiary = contentColorFor(tertiary),
        tertiaryContainer = blend(tertiary, Color.White, 0.78f),
        onTertiaryContainer = blend(tertiary, LightOnSurfaceBase, 0.68f),
        background = background,
        onBackground = onSurface,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceTint = primary,
        inverseSurface = LightInverseSurface,
        inverseOnSurface = LightInverseOnSurface,
        error = LightError,
        onError = LightOnError,
        errorContainer = LightErrorContainer,
        onErrorContainer = LightOnErrorContainer,
        outline = outline,
        outlineVariant = outlineVariant,
        scrim = Color.Black.copy(alpha = 0.32f),
        surfaceBright = blend(surface, Color.White, 0.16f),
        surfaceDim = blend(surface, LightOnSurfaceBase, 0.06f),
        surfaceContainerLowest = blend(surface, Color.White, 0.24f),
        surfaceContainerLow = blend(surface, seed, 0.03f),
        surfaceContainer = blend(surface, seed, 0.06f),
        surfaceContainerHigh = blend(surface, seed, 0.11f),
        surfaceContainerHighest = blend(surface, seed, 0.16f),
    )
}

private fun darkLauncherColorScheme(themeColor: LauncherThemeColor): ColorScheme {
    val seed = themeColor.seedColor
    val primary = blend(seed, Color.White, 0.42f)
    val secondary = blend(
        transformColor(
            seed,
            hueShift = -18f,
            saturationMultiplier = 0.52f,
            lightnessDelta = 0.02f
        ),
        Color.White,
        0.46f
    )
    val tertiary = blend(
        transformColor(
            seed,
            hueShift = 24f,
            saturationMultiplier = 0.60f,
            lightnessDelta = 0.03f
        ),
        Color.White,
        0.40f
    )
    val background = blend(seed, DarkSurfaceBase, 0.88f)
    val surface = blend(seed, DarkSurfaceBase, 0.84f)
    val surfaceVariant = blend(seed, DarkSurfaceBase, 0.64f)
    val onSurface = blend(DarkOnSurfaceBase, seed, 0.06f)
    val onSurfaceVariant = blend(DarkOnSurfaceBase, seed, 0.22f)
    val outline = blend(DarkOutlineBase, seed, 0.26f)
    val outlineVariant = blend(surfaceVariant, outline, 0.44f)

    return darkColorScheme(
        primary = primary,
        onPrimary = contentColorFor(primary),
        primaryContainer = blend(seed, DarkSurfaceBase, 0.36f),
        onPrimaryContainer = blend(seed, Color.White, 0.80f),
        inversePrimary = transformColor(seed, lightnessMultiplier = 0.90f),
        secondary = secondary,
        onSecondary = contentColorFor(secondary),
        secondaryContainer = blend(secondary, DarkSurfaceBase, 0.42f),
        onSecondaryContainer = blend(secondary, Color.White, 0.72f),
        tertiary = tertiary,
        onTertiary = contentColorFor(tertiary),
        tertiaryContainer = blend(tertiary, DarkSurfaceBase, 0.38f),
        onTertiaryContainer = blend(tertiary, Color.White, 0.74f),
        background = background,
        onBackground = onSurface,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceTint = primary,
        inverseSurface = DarkInverseSurface,
        inverseOnSurface = DarkInverseOnSurface,
        error = DarkError,
        onError = DarkOnError,
        errorContainer = DarkErrorContainer,
        onErrorContainer = DarkOnErrorContainer,
        outline = outline,
        outlineVariant = outlineVariant,
        scrim = Color.Black.copy(alpha = 0.48f),
        surfaceBright = blend(surface, Color.White, 0.10f),
        surfaceDim = blend(surface, Color.Black, 0.14f),
        surfaceContainerLowest = blend(surface, Color.Black, 0.20f),
        surfaceContainerLow = blend(surface, seed, 0.04f),
        surfaceContainer = blend(surface, seed, 0.08f),
        surfaceContainerHigh = blend(surface, seed, 0.13f),
        surfaceContainerHighest = blend(surface, seed, 0.18f),
    )
}

private fun contentColorFor(background: Color): Color {
    return if (background.luminance() > 0.44f) {
        LightOnSurfaceBase
    } else {
        Color.White
    }
}

private fun blend(start: Color, end: Color, amount: Float): Color {
    return Color(
        ColorUtils.blendARGB(
            start.toArgb(),
            end.toArgb(),
            amount.coerceIn(0f, 1f)
        )
    )
}

private fun transformColor(
    color: Color,
    hueShift: Float = 0f,
    saturationMultiplier: Float = 1f,
    lightnessMultiplier: Float = 1f,
    lightnessDelta: Float = 0f,
): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color.toArgb(), hsl)
    hsl[0] = normalizeHue(hsl[0] + hueShift)
    hsl[1] = (hsl[1] * saturationMultiplier).coerceIn(0f, 1f)
    hsl[2] = (hsl[2] * lightnessMultiplier + lightnessDelta).coerceIn(0f, 1f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun normalizeHue(value: Float): Float {
    var normalized = value % 360f
    if (normalized < 0f) {
        normalized += 360f
    }
    return normalized
}
