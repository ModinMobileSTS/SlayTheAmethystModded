package io.stamethyst.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import io.stamethyst.config.LauncherThemeMode

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6F4C93),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF0E0FF),
    onPrimaryContainer = Color(0xFF29123E),
    secondary = Color(0xFF655A71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEBDDFA),
    onSecondaryContainer = Color(0xFF20182B),
    tertiary = Color(0xFF7D5260),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8E4),
    onTertiaryContainer = Color(0xFF31111D),
    background = Color(0xFFFFF8FC),
    onBackground = Color(0xFF1E1A20),
    surface = Color(0xFFFFF8FC),
    onSurface = Color(0xFF1E1A20),
    surfaceVariant = Color(0xFFE8E0EB),
    onSurfaceVariant = Color(0xFF4B4452),
    outline = Color(0xFF7C7483)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFD6BCFF),
    onPrimary = Color(0xFF3F1F63),
    primaryContainer = Color(0xFF57367C),
    onPrimaryContainer = Color(0xFFF0E0FF),
    secondary = Color(0xFFCEC1DB),
    onSecondary = Color(0xFF352D40),
    secondaryContainer = Color(0xFF4B4358),
    onSecondaryContainer = Color(0xFFEBDDFA),
    tertiary = Color(0xFFEFB8C8),
    onTertiary = Color(0xFF4A2532),
    tertiaryContainer = Color(0xFF643B49),
    onTertiaryContainer = Color(0xFFFFD8E4),
    background = Color(0xFF15121A),
    onBackground = Color(0xFFE9E0EA),
    surface = Color(0xFF15121A),
    onSurface = Color(0xFFE9E0EA),
    surfaceVariant = Color(0xFF4B4452),
    onSurfaceVariant = Color(0xFFCEC4D0),
    outline = Color(0xFF968E9C)
)

@Composable
fun LauncherTheme(
    themeMode: LauncherThemeMode,
    content: @Composable () -> Unit,
) {
    val useDarkTheme = when (themeMode) {
        LauncherThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
        LauncherThemeMode.LIGHT -> false
        LauncherThemeMode.DARK -> true
    }

    MaterialTheme(
        colorScheme = if (useDarkTheme) DarkColorScheme else LightColorScheme,
        content = content
    )
}
