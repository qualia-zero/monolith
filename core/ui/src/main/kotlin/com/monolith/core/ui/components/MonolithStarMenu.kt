package com.monolith.core.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.monolith.core.ui.haptics.MonolithHaptics
import com.monolith.core.ui.theme.LocalMonolithButtonShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

data class StarMenuItem(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

/**
 * Radial star menu overlay expanding toward the bottom-right from [anchorCenter].
 * Connects the anchor logo to each option node with animated glowing constellation lines.
 */
@Composable
fun MonolithStarMenuOverlay(
    expanded: Boolean,
    anchorCenter: Offset,
    items: List<StarMenuItem>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    anchorIcon: ImageVector? = null,
    onAnchorClick: (() -> Unit)? = null,
    radius: Dp = 132.dp,
    startAngleDeg: Float = 16f,
    endAngleDeg: Float = 66f,
) {
    val expandProgress by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = if (expanded) {
            spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)
        } else {
            tween(durationMillis = 180, easing = FastOutSlowInEasing)
        },
        label = "starMenuExpand",
    )

    if (!expanded && expandProgress <= 0.01f) return
    if (anchorCenter == Offset.Unspecified || items.isEmpty()) return

    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val scrimColor = MaterialTheme.colorScheme.background
    val buttonShape = LocalMonolithButtonShape.current

    val radiusPx = with(density) { radius.toPx() }
    val ringRadiusPx = with(density) { 23.dp.toPx() }
    val nodeSize = 44.dp
    val nodeRadiusPx = with(density) { (nodeSize / 2).toPx() }

    val itemProgresses = remember(items.size) {
        List(items.size) { Animatable(0f) }
    }

    LaunchedEffect(expanded, items.size) {
        if (expanded) {
            itemProgresses.forEachIndexed { index, anim ->
                launch {
                    delay(index * 45L)
                    anim.animateTo(
                        targetValue = 1f,
                        animationSpec = spring(
                            dampingRatio = 0.68f,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    )
                }
            }
        } else {
            itemProgresses.forEach { anim ->
                launch {
                    anim.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
                    )
                }
            }
        }
    }

    val anglesDeg = remember(items.size, startAngleDeg, endAngleDeg) {
        if (items.size == 1) {
            listOf((startAngleDeg + endAngleDeg) / 2f)
        } else {
            val step = (endAngleDeg - startAngleDeg) / (items.size - 1)
            List(items.size) { idx -> startAngleDeg + step * idx }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Strong scrim so the background recedes cleanly while the star menu is open
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(scrimColor.copy(alpha = 0.78f * expandProgress.coerceIn(0f, 1f)))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    onDismiss()
                },
        )

        // Glowing constellation lines, orbital arc, and anchor halo
        Canvas(modifier = Modifier.fillMaxSize()) {
            val clampedProgress = expandProgress.coerceIn(0f, 1f)

            // Radial glow around the Monolith anchor icon
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.24f * clampedProgress),
                        primaryColor.copy(alpha = 0.06f * clampedProgress),
                        Color.Transparent,
                    ),
                    center = anchorCenter,
                    radius = ringRadiusPx * 2.4f,
                ),
                radius = ringRadiusPx * 2.4f,
                center = anchorCenter,
            )

            // Crisp anchor ring
            drawCircle(
                color = primaryColor.copy(alpha = 0.75f * clampedProgress),
                radius = ringRadiusPx * (0.78f + 0.22f * clampedProgress),
                center = anchorCenter,
                style = Stroke(width = 1.5.dp.toPx()),
            )

            // Dashed outer orbital arc connecting the star menu quadrant
            val arcRadius = radiusPx * clampedProgress
            if (arcRadius > ringRadiusPx) {
                drawArc(
                    color = primaryColor.copy(alpha = 0.24f * clampedProgress),
                    startAngle = (startAngleDeg - 8f),
                    sweepAngle = (endAngleDeg - startAngleDeg) + 16f,
                    useCenter = false,
                    topLeft = Offset(anchorCenter.x - arcRadius, anchorCenter.y - arcRadius),
                    size = Size(arcRadius * 2f, arcRadius * 2f),
                    style = Stroke(
                        width = 1.2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f),
                        cap = StrokeCap.Round,
                    ),
                )
            }

            // Curved glowing spokes from anchor ring to each option node
            anglesDeg.forEachIndexed { index, angleDeg ->
                val p = itemProgresses.getOrNull(index)?.value?.coerceIn(0f, 1.15f) ?: clampedProgress
                if (p <= 0.02f) return@forEachIndexed

                val rad = angleDeg * (PI / 180.0)
                val dir = Offset(cos(rad).toFloat(), sin(rad).toFloat())
                val perp = Offset(-dir.y, dir.x)

                val start = anchorCenter + dir * ringRadiusPx
                val fullEnd = anchorCenter + dir * (radiusPx * p)
                val mid = (start + fullEnd) * 0.5f
                val curveSign = if (index % 2 == 0) -1f else 1f
                val curveOffset = (16.dp.toPx()) * curveSign * p
                val control = mid + perp * curveOffset

                val fullPath = Path().apply {
                    moveTo(start.x, start.y)
                    quadraticTo(control.x, control.y, fullEnd.x, fullEnd.y)
                }

                // Trim the path slightly before the node center so it meets the node border cleanly
                val measure = PathMeasure().apply { setPath(fullPath, false) }
                val trimmedLength = (measure.length - nodeRadiusPx * 0.85f).coerceAtLeast(0f)
                val visiblePath = Path()
                if (trimmedLength > 0f) {
                    measure.getSegment(0f, trimmedLength, visiblePath, true)
                }

                val lineAlpha = p.coerceIn(0f, 1f)

                // Soft outer glow stroke
                drawPath(
                    path = visiblePath,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = 0.28f * lineAlpha),
                            secondaryColor.copy(alpha = 0.12f * lineAlpha),
                        ),
                        start = start,
                        end = fullEnd,
                    ),
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round),
                )

                // Crisp core line
                drawPath(
                    path = visiblePath,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = 0.92f * lineAlpha),
                            primaryColor.copy(alpha = 0.65f * lineAlpha),
                        ),
                        start = start,
                        end = fullEnd,
                    ),
                    style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round),
                )

                // Starlight junction dot on the anchor ring
                drawCircle(
                    color = primaryColor.copy(alpha = lineAlpha),
                    radius = 3.dp.toPx(),
                    center = start,
                )
            }
        }

        // Crisp, unblurred anchor icon rendered above the scrim & anchor ring
        if (anchorIcon != null) {
            val anchorBoxSize = 44.dp
            val anchorBoxRadiusPx = with(density) { (anchorBoxSize / 2).toPx() }
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = (anchorCenter.x - anchorBoxRadiusPx).roundToInt(),
                            y = (anchorCenter.y - anchorBoxRadiusPx).roundToInt(),
                        )
                    }
                    .size(anchorBoxSize)
                    .graphicsLayer {
                        alpha = expandProgress.coerceIn(0f, 1f)
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        MonolithHaptics.navClick(context, view)
                        if (onAnchorClick != null) {
                            onAnchorClick()
                        } else {
                            onDismiss()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = anchorIcon,
                    contentDescription = "Menü schließen",
                    tint = primaryColor,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        // Option nodes + labels
        items.forEachIndexed { index, item ->
            val p = itemProgresses.getOrNull(index)?.value ?: expandProgress
            val angleDeg = anglesDeg[index]
            val rad = angleDeg * (PI / 180.0)
            val currentRadius = radiusPx * p
            val nodeCenterX = anchorCenter.x + cos(rad).toFloat() * currentRadius
            val nodeCenterY = anchorCenter.y + sin(rad).toFloat() * currentRadius

            val alpha = p.coerceIn(0f, 1f)
            val scale = 0.65f + 0.35f * p.coerceIn(0f, 1.1f)

            Row(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = (nodeCenterX - nodeRadiusPx).roundToInt(),
                            y = (nodeCenterY - nodeRadiusPx).roundToInt(),
                        )
                    }
                    .graphicsLayer {
                        this.alpha = alpha
                        this.scaleX = scale
                        this.scaleY = scale
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        MonolithHaptics.navClick(context, view)
                        onDismiss()
                        item.onClick()
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    modifier = Modifier.size(nodeSize),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    border = BorderStroke(1.5.dp, primaryColor.copy(alpha = 0.85f)),
                    shadowElevation = 6.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = primaryColor,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                Surface(
                    shape = buttonShape,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shadowElevation = 4.dp,
                ) {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}
