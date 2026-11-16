package com.monolith.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
    primary = Color(0xFF16C79A),
    onPrimary = Color(0xFF02140E),
    primaryContainer = Color(0xFF0A241C),
    onPrimaryContainer = Color(0xFF7CE8C8),
    secondary = Color(0xFFD97706),
    onSecondary = Color(0xFF1F0E00),
    secondaryContainer = Color(0xFF241608),
    onSecondaryContainer = Color(0xFFF6C27D),
    background = Color(0xFF040605),
    onBackground = Color(0xFFD7E6DF),
    surface = Color(0xFF080C0A),
    onSurface = Color(0xFFD7E6DF),
    surfaceVariant = Color(0xFF101815),
    onSurfaceVariant = Color(0xFF789388),
    outline = Color(0xFF21382F),
)

private val PhosphorRoughShapes = Shapes(
    extraSmall = CutCornerShape(2.dp),
    small = CutCornerShape(4.dp),
    medium = CutCornerShape(6.dp),
    large = CutCornerShape(8.dp),
    extraLarge = CutCornerShape(10.dp),
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
    val shapes = if (themeMode == ThemeMode.CYBER_PHOSPHOR) {
        PhosphorRoughShapes
    } else {
        MaterialTheme.shapes
    }
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = shapes,
        content = content,
    )
}
