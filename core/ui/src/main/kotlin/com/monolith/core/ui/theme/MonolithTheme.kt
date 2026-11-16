package com.monolith.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.monolith.core.model.ThemeMode

val MonolithRed = Color(0xFFD32F2F)
val MonolithRedDark = Color(0xFFB71C1C)
val MonolithRedLight = Color(0xFFEF5350)

private val LightColors = lightColorScheme(
    primary = MonolithRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF1F1F3),
    onPrimaryContainer = Color(0xFF1A1A1A),
    secondary = Color(0xFF5A5A60),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEBEBEF),
    onSecondaryContainer = Color(0xFF1A1A1A),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF141416),
    surface = Color(0xFFFAFAFA),
    onSurface = Color(0xFF141416),
    surfaceVariant = Color(0xFFEFEFF2),
    onSurfaceVariant = Color(0xFF606066),
    outline = Color(0xFFE0E0E4),
)

private val DarkColors = darkColorScheme(
    primary = MonolithRedLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF232326),
    onPrimaryContainer = Color(0xFFF2F2F4),
    secondary = Color(0xFFA8A8B0),
    onSecondary = Color(0xFF141416),
    secondaryContainer = Color(0xFF27272B),
    onSecondaryContainer = Color(0xFFF2F2F4),
    background = Color(0xFF0E0E10),
    onBackground = Color(0xFFF2F2F4),
    surface = Color(0xFF0E0E10),
    onSurface = Color(0xFFF2F2F4),
    surfaceVariant = Color(0xFF1B1B1E),
    onSurfaceVariant = Color(0xFF9E9EA6),
    outline = Color(0xFF2A2A2E),
)

private val CyberPhosphorColors = darkColorScheme(
    primary = Color(0xFF00F5D4),
    onPrimary = Color(0xFF031714),
    primaryContainer = Color(0xFF0B2926),
    onPrimaryContainer = Color(0xFF9BFCEB),
    secondary = Color(0xFFFF3E8A),
    onSecondary = Color(0xFF280012),
    secondaryContainer = Color(0xFF2A1222),
    onSecondaryContainer = Color(0xFFFFC2DC),
    background = Color(0xFF05070A),
    onBackground = Color(0xFFE7F7F5),
    surface = Color(0xFF090D12),
    onSurface = Color(0xFFE7F7F5),
    surfaceVariant = Color(0xFF111922),
    onSurfaceVariant = Color(0xFF8AA8B8),
    outline = Color(0xFF1D3342),
)

@Composable
fun MonolithTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeMode: ThemeMode? = null,
    content: @Composable () -> Unit,
) {
    val colorScheme = when (themeMode) {
        ThemeMode.CYBER_PHOSPHOR -> CyberPhosphorColors
        ThemeMode.LIGHT -> LightColors
        ThemeMode.DARK -> DarkColors
        ThemeMode.SYSTEM, null -> if (darkTheme) DarkColors else LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
