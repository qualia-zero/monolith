package com.monolith.core.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monolith.core.model.ThemeMode
import kotlin.random.Random

val MonolithRed = Color(0xFFD32F2F)
val MonolithRedDark = Color(0xFFB71C1C)
val MonolithRedLight = Color(0xFFEF5350)

val LocalMonolithButtonShape = staticCompositionLocalOf<Shape> { RoundedCornerShape(50) }
val LocalMonolithThemeMode = staticCompositionLocalOf<ThemeMode?> { null }

object MonolithThemeDefaults {
    val buttonShape: Shape
        @Composable get() = LocalMonolithButtonShape.current
}

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
    surfaceContainerLow = Color(0xFFF4F4F7),
    surfaceContainer = Color(0xFFEEEEF1),
    surfaceContainerHigh = Color(0xFFEAEAEF),
    surfaceContainerHighest = Color(0xFFE6E6EA),
    outline = Color(0xFF72727A),
    outlineVariant = Color(0xFFE0E0E4),
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
    surfaceContainerLow = Color(0xFF141418),
    surfaceContainer = Color(0xFF18181D),
    surfaceContainerHigh = Color(0xFF1D1D22),
    surfaceContainerHighest = Color(0xFF202026),
    outline = Color(0xFF8E8E98),
    outlineVariant = Color(0xFF2A2A2E),
)

private val CyberPhosphorColors = darkColorScheme(
    primary = Color(0xFF00E5A3),
    onPrimary = Color(0xFF02140E),
    primaryContainer = Color(0xFF07261C),
    onPrimaryContainer = Color(0xFF7BF2CB),
    secondary = Color(0xFFFF9E2C),
    onSecondary = Color(0xFF1A0B00),
    secondaryContainer = Color(0xFF1F1205),
    onSecondaryContainer = Color(0xFFE8A45C),
    background = Color(0xFF020403),
    onBackground = Color(0xFFCDE0D7),
    surface = Color(0xFF050A08),
    onSurface = Color(0xFFCDE0D7),
    surfaceVariant = Color(0xFF0B1611),
    onSurfaceVariant = Color(0xFF6B887C),
    surfaceContainerLow = Color(0xFF06100C),
    surfaceContainer = Color(0xFF08140F),
    surfaceContainerHigh = Color(0xFF0A1611),
    surfaceContainerHighest = Color(0xFF0A1813),
    outline = Color(0xFF2E7D60),
    outlineVariant = Color(0xFF1A3027),
)

private val ObsidianColors = darkColorScheme(
    primary = Color(0xFFF2C14E),
    onPrimary = Color(0xFF1A1100),
    primaryContainer = Color(0xFF2D230E),
    onPrimaryContainer = Color(0xFFFFE08A),
    secondary = Color(0xFF9BA4B5),
    onSecondary = Color(0xFF101318),
    secondaryContainer = Color(0xFF1C2028),
    onSecondaryContainer = Color(0xFFD4DAE4),
    background = Color(0xFF090A0D),
    onBackground = Color(0xFFECEEF2),
    surface = Color(0xFF0E1014),
    onSurface = Color(0xFFECEEF2),
    surfaceVariant = Color(0xFF161920),
    onSurfaceVariant = Color(0xFF9EA4B0),
    surfaceContainerLow = Color(0xFF12151B),
    surfaceContainer = Color(0xFF161920),
    surfaceContainerHigh = Color(0xFF1A1E26),
    surfaceContainerHighest = Color(0xFF1E222B),
    outline = Color(0xFFA88B4A),
    outlineVariant = Color(0xFF262A34),
)

private val ObsidianShapes = Shapes(
    extraSmall = CutCornerShape(3.dp),
    small = CutCornerShape(6.dp),
    medium = CutCornerShape(10.dp),
    large = CutCornerShape(14.dp),
    extraLarge = CutCornerShape(18.dp),
)

private val PhosphorTypography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontFamily = FontFamily.Monospace),
        titleMedium = base.titleMedium.copy(fontFamily = FontFamily.Monospace),
        titleSmall = base.titleSmall.copy(fontFamily = FontFamily.Monospace),
        bodyLarge = base.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        bodySmall = base.bodySmall.copy(fontFamily = FontFamily.Monospace),
        labelLarge = base.labelLarge.copy(fontFamily = FontFamily.Monospace),
        labelMedium = base.labelMedium.copy(fontFamily = FontFamily.Monospace),
        labelSmall = base.labelSmall.copy(fontFamily = FontFamily.Monospace),
    )
}

private val ObsidianTypography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(
            fontFamily = FontFamily.Serif,
            letterSpacing = 0.5.sp,
        ),
        headlineSmall = base.headlineSmall.copy(
            fontFamily = FontFamily.Serif,
            letterSpacing = 0.4.sp,
        ),
        titleLarge = base.titleLarge.copy(
            fontFamily = FontFamily.Serif,
            letterSpacing = 0.35.sp,
        ),
        titleMedium = base.titleMedium.copy(
            fontFamily = FontFamily.Serif,
            letterSpacing = 0.25.sp,
        ),
        titleSmall = base.titleSmall.copy(
            fontFamily = FontFamily.Serif,
            letterSpacing = 0.2.sp,
        ),
        bodyLarge = base.bodyLarge.copy(
            fontFamily = FontFamily.Serif,
            letterSpacing = 0.15.sp,
        ),
        bodyMedium = base.bodyMedium.copy(
            fontFamily = FontFamily.Serif,
            letterSpacing = 0.15.sp,
        ),
        bodySmall = base.bodySmall.copy(
            fontFamily = FontFamily.Serif,
            letterSpacing = 0.1.sp,
        ),
        labelLarge = base.labelLarge.copy(
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.3.sp,
        ),
        labelMedium = base.labelMedium.copy(
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.25.sp,
        ),
        labelSmall = base.labelSmall.copy(
            fontFamily = FontFamily.Serif,
            letterSpacing = 0.2.sp,
        ),
    )
}

private val ObsidianButtonShape: Shape = CutCornerShape(
    topStart = 2.dp,
    topEnd = 10.dp,
    bottomEnd = 2.dp,
    bottomStart = 10.dp,
)

@Composable
fun MonolithTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeMode: ThemeMode? = null,
    content: @Composable () -> Unit,
) {
    val colorScheme = when (themeMode) {
        ThemeMode.CYBER_PHOSPHOR -> CyberPhosphorColors
        ThemeMode.OBSIDIAN -> ObsidianColors
        ThemeMode.LIGHT -> LightColors
        ThemeMode.DARK -> DarkColors
        ThemeMode.SYSTEM, null -> if (darkTheme) DarkColors else LightColors
    }
    val shapes = ObsidianShapes
    val typography = when (themeMode) {
        ThemeMode.CYBER_PHOSPHOR -> PhosphorTypography
        ThemeMode.OBSIDIAN -> ObsidianTypography
        else -> MaterialTheme.typography
    }
    val buttonShape = ObsidianButtonShape
    CompositionLocalProvider(
        LocalMonolithButtonShape provides buttonShape,
        LocalMonolithThemeMode provides themeMode,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = shapes,
            typography = typography,
            content = {
                if (themeMode == ThemeMode.OBSIDIAN) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(ObsidianColors.background)
                            .drawWithCache {
                                val w = size.width
                                val h = size.height
                                val rng = Random(1337)

                                // Subtle fractured stone planes (obsidian facets)
                                val facet1 = Path().apply {
                                    moveTo(0f, 0f)
                                    lineTo(w * 0.72f, 0f)
                                    lineTo(w * 0.28f, h * 0.48f)
                                    lineTo(0f, h * 0.34f)
                                    close()
                                }
                                val facet2 = Path().apply {
                                    moveTo(w, h * 0.18f)
                                    lineTo(w, h * 0.78f)
                                    lineTo(w * 0.36f, h * 0.62f)
                                    close()
                                }
                                val facet3 = Path().apply {
                                    moveTo(0f, h * 0.68f)
                                    lineTo(w * 0.62f, h * 0.85f)
                                    lineTo(w * 0.35f, h)
                                    lineTo(0f, h)
                                    close()
                                }

                                // Imperfect carved stone cracks (jagged paths with shadow + highlight edge)
                                fun buildCrack(startX: Float, startY: Float, dx: Float, dy: Float, steps: Int): Pair<Path, Path> {
                                    val shadow = Path()
                                    val highlight = Path()
                                    shadow.moveTo(startX, startY)
                                    highlight.moveTo(startX + 1.8f, startY + 1.8f)
                                    var cx = startX
                                    var cy = startY
                                    for (i in 1..steps) {
                                        val jitterX = (rng.nextFloat() - 0.5f) * 26f
                                        val jitterY = (rng.nextFloat() - 0.5f) * 26f
                                        cx += dx / steps + jitterX
                                        cy += dy / steps + jitterY
                                        shadow.lineTo(cx, cy)
                                        highlight.lineTo(cx + 1.8f, cy + 1.8f)
                                    }
                                    return shadow to highlight
                                }

                                val cracks = listOf(
                                    buildCrack(w * 0.08f, h * 0.10f, w * 0.42f, h * 0.26f, 7),
                                    buildCrack(w * 0.88f, h * 0.14f, -w * 0.34f, h * 0.22f, 6),
                                    buildCrack(w * 0.15f, h * 0.46f, w * 0.30f, -h * 0.14f, 5),
                                    buildCrack(w * 0.78f, h * 0.54f, -w * 0.46f, h * 0.20f, 7),
                                    buildCrack(w * 0.22f, h * 0.82f, w * 0.48f, -h * 0.12f, 6),
                                )

                                val grains = List(260) {
                                    Triple(
                                        Offset(rng.nextFloat() * w, rng.nextFloat() * h),
                                        0.7f + rng.nextFloat() * 1.6f,
                                        if (rng.nextFloat() > 0.82f) {
                                            Color(0xFFF2C14E).copy(alpha = 0.055f)
                                        } else {
                                            Color.White.copy(alpha = 0.03f + rng.nextFloat() * 0.04f)
                                        },
                                    )
                                }
                                val sheen = Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFF1D222C).copy(alpha = 0.55f),
                                        Color.Transparent,
                                    ),
                                    center = Offset(w * 0.45f, h * 0.16f),
                                    radius = size.maxDimension * 0.7f,
                                )
                                onDrawBehind {
                                    drawRect(brush = sheen)
                                    drawPath(facet1, color = Color(0xFF141820).copy(alpha = 0.48f))
                                    drawPath(facet2, color = Color(0xFF040507).copy(alpha = 0.55f))
                                    drawPath(facet3, color = Color(0xFF12151C).copy(alpha = 0.42f))
                                    for ((shadow, highlight) in cracks) {
                                        drawPath(
                                            path = shadow,
                                            color = Color.Black.copy(alpha = 0.72f),
                                            style = Stroke(width = 3.0f, cap = StrokeCap.Round, join = StrokeJoin.Miter),
                                        )
                                        drawPath(
                                            path = highlight,
                                            color = Color(0xFFD8D4CC).copy(alpha = 0.10f),
                                            style = Stroke(width = 1.4f, cap = StrokeCap.Round, join = StrokeJoin.Miter),
                                        )
                                    }
                                    for ((pos, radius, grainColor) in grains) {
                                        drawCircle(
                                            color = grainColor,
                                            radius = radius,
                                            center = pos,
                                        )
                                    }
                                }
                            },
                    ) {
                        CompositionLocalProvider(LocalContentColor provides ObsidianColors.onBackground) {
                            content()
                        }
                    }
                } else {
                    content()
                }
            },
        )
    }
}
