package com.monolith.core.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.monolith.core.ui.theme.LocalMonolithButtonShape
import kotlin.math.roundToInt

/**
 * Tactile Monolith pull-to-refresh indicator with a rotating geometric prism,
 * progress arc that fills during pull, and radial glow on threshold/refresh.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonolithRefreshIndicator(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    val fraction = if (isRefreshing) 1f else state.distanceFraction.coerceIn(0f, 1.35f)
    if (!isRefreshing && fraction <= 0.02f) return

    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val outline = MaterialTheme.colorScheme.outlineVariant

    val infiniteTransition = rememberInfiniteTransition(label = "monolithRefreshSpin")
    val spinAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spinAngle",
    )

    val thresholdReached = fraction >= 1f
    val scale by animateFloatAsState(
        targetValue = when {
            isRefreshing -> 1f
            thresholdReached -> 1.08f
            else -> (0.45f + 0.55f * fraction.coerceIn(0f, 1f))
        },
        label = "refreshScale",
    )
    val alpha = if (isRefreshing) 1f else (fraction * 1.4f).coerceIn(0f, 1f)
    val verticalOffsetPx = if (isRefreshing) 44f else (fraction.coerceIn(0f, 1.2f) * 64f)

    Box(
        modifier = modifier
            .offset { IntOffset(0, verticalOffsetPx.roundToInt()) }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(46.dp),
            shape = LocalMonolithButtonShape.current,
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.96f),
            border = BorderStroke(
                width = if (thresholdReached || isRefreshing) 1.5.dp else 1.dp,
                color = if (thresholdReached || isRefreshing) primary else outline,
            ),
            shadowElevation = 8.dp,
        ) {
            Canvas(modifier = Modifier.size(46.dp)) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val arcRadius = size.minDimension * 0.34f
                val prismRadius = size.minDimension * 0.19f

                if (thresholdReached || isRefreshing) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                primary.copy(alpha = 0.28f),
                                Color.Transparent,
                            ),
                            center = center,
                            radius = size.minDimension * 0.5f,
                        ),
                        radius = size.minDimension * 0.5f,
                        center = center,
                    )
                }

                // Progress / orbital arc
                val sweep = if (isRefreshing) 280f else (fraction.coerceIn(0f, 1f) * 360f)
                val startAngle = if (isRefreshing) spinAngle - 90f else -90f
                drawArc(
                    color = if (thresholdReached || isRefreshing) primary else secondary,
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                    size = Size(arcRadius * 2f, arcRadius * 2f),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )

                // Rotating Monolith diamond prism
                val rotation = if (isRefreshing) spinAngle else fraction * 180f
                rotate(degrees = rotation, pivot = center) {
                    val prism = Path().apply {
                        moveTo(center.x, center.y - prismRadius)
                        lineTo(center.x + prismRadius * 0.78f, center.y)
                        lineTo(center.x, center.y + prismRadius)
                        lineTo(center.x - prismRadius * 0.78f, center.y)
                        close()
                    }
                    drawPath(
                        path = prism,
                        color = primary.copy(alpha = 0.22f),
                        style = Fill,
                    )
                    drawPath(
                        path = prism,
                        color = primary,
                        style = Stroke(width = 1.6.dp.toPx()),
                    )
                }
            }
        }
    }
}
