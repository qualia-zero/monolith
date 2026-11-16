package com.monolith.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
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
    primary = Color(0xFF00B884),
    onPrimary = Color(0xFF02110C),
    primaryContainer = Color(0xFF071E17),
    onPrimaryContainer = Color(0xFF5CE0B8),
    secondary = Color(0xFFC96A09),
    onSecondary = Color(0xFF1A0B00),
    secondaryContainer = Color(0xFF1F1205),
    onSecondaryContainer = Color(0xFFE8A45C),
    background = Color(0xFF030504),
    onBackground = Color(0xFFCDE0D7),
    surface = Color(0xFF060A08),
    onSurface = Color(0xFFCDE0D7),
    surfaceVariant = Color(0xFF0D1512),
    onSurfaceVariant = Color(0xFF6B887C),
    outline = Color(0xFF1A3027),
)

private val StandardShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val PhosphorRoughShapes = Shapes(
    extraSmall = CutCornerShape(2.dp),
    small = CutCornerShape(6.dp),
    medium = CutCornerShape(8.dp),
    large = CutCornerShape(10.dp),
    extraLarge = CutCornerShape(12.dp),
)

private val PhosphorTypography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontFamily = FontFamily.Monospace),
        titleMedium = base.titleMedium.copy(fontFamily = FontFamily.Monospace),
        titleSmall = base.titleSmall.copy(fontFamily = FontFamily.Monospace),
        labelLarge = base.labelLarge.copy(fontFamily = FontFamily.Monospace),
        labelMedium = base.labelMedium.copy(fontFamily = FontFamily.Monospace),
    )
}

@Composable
fun MonolithTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeMode: ThemeMode? = null,
    content: @Composable () -> Unit,
) {
    val isPhosphor = themeMode == ThemeMode.CYBER_PHOSPHOR
    val colorScheme = when (themeMode) {
        ThemeMode.CYBER_PHOSPHOR -> CyberPhosphorColors
        ThemeMode.LIGHT -> LightColors
        ThemeMode.DARK -> DarkColors
        ThemeMode.SYSTEM, null -> if (darkTheme) DarkColors else LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = if (isPhosphor) PhosphorRoughShapes else StandardShapes,
        typography = if (isPhosphor) PhosphorTypography else MaterialTheme.typography,
        content = content,
    )
}
