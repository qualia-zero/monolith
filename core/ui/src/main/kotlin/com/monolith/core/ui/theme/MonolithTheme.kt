package com.monolith.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val MonolithRed = Color(0xFFD32F2F)
val MonolithRedDark = Color(0xFFB71C1C)
val MonolithRedLight = Color(0xFFFF6B6B)
val MonolithRedContainerLight = Color(0xFFFFDAD6)
val MonolithRedContainerDark = Color(0xFF93000A)

private val LightColors = lightColorScheme(
    primary = MonolithRed,
    onPrimary = Color.White,
    primaryContainer = MonolithRedContainerLight,
    onPrimaryContainer = Color(0xFF410002),
    secondary = Color(0xFF775652),
    onSecondary = Color.White,
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1A1A1A),
    surface = Color.White,
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFF2F2F2),
    onSurfaceVariant = Color(0xFF5A5A5A),
    outline = Color(0xFFE0E0E0),
)

private val DarkColors = darkColorScheme(
    primary = MonolithRedLight,
    onPrimary = Color(0xFF690005),
    primaryContainer = MonolithRedContainerDark,
    onPrimaryContainer = MonolithRedContainerLight,
    secondary = Color(0xFFE7BDB8),
    onSecondary = Color(0xFF442926),
    background = Color(0xFF121212),
    onBackground = Color(0xFFF0F0F0),
    surface = Color(0xFF1A1A1A),
    onSurface = Color(0xFFF0F0F0),
    surfaceVariant = Color(0xFF242424),
    onSurfaceVariant = Color(0xFFB0B0B0),
    outline = Color(0xFF2D2D2D),
)

@Composable
fun MonolithTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
