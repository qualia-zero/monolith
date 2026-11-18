package com.monolith.core.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Minimal, theme-colored Monolith pull-to-refresh indicator:
 * rotating geometric prism, progress arc in theme primary, and outer ring once filled.
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
    val verticalOffsetPx = if (isRefreshing) 28f else (fraction.coerceIn(0f, 1.2f) * 32f)

    MonolithPrismIndicatorBody(
        fraction = fraction,
        isSpinning = isRefreshing,
        showOuterRing = thresholdReached || isRefreshing,
        scale = scale,
        alpha = alpha,
        verticalOffsetPx = verticalOffsetPx,
        modifier = modifier,
    )
}

/**
 * Spinning Monolith prism indicator placed at the top center while a folder is loading.
 */
@Composable
fun MonolithLoadingSpinner(
    modifier: Modifier = Modifier,
) {
    MonolithPrismIndicatorBody(
        fraction = 1f,
        isSpinning = true,
        showOuterRing = true,
        scale = 1f,
        alpha = 1f,
        verticalOffsetPx = 28f,
        modifier = modifier,
    )
}

@Composable
private fun MonolithPrismIndicatorBody(
    fraction: Float,
    isSpinning: Boolean,
    showOuterRing: Boolean,
    scale: Float,
    alpha: Float,
    verticalOffsetPx: Float,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
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
        Canvas(modifier = Modifier.size(46.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val arcRadius = size.minDimension * 0.34f
            val outerRingRadius = size.minDimension * 0.44f
            val prismRadius = size.minDimension * 0.19f

            // Outer ring once filled
            if (showOuterRing) {
                drawCircle(
                    color = primary.copy(alpha = 0.78f),
                    radius = outerRingRadius,
                    center = center,
                    style = Stroke(width = 1.4.dp.toPx()),
                )
            }

            // Filling progress arc in theme primary color
            val sweep = if (isSpinning) 280f else (fraction.coerceIn(0f, 1f) * 360f)
            val startAngle = if (isSpinning) spinAngle - 90f else -90f
            drawArc(
                color = primary,
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                size = Size(arcRadius * 2f, arcRadius * 2f),
                style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round),
            )

            // Rotating Monolith diamond prism (crisp stroke, no background fill)
            val rotation = if (isSpinning) spinAngle else fraction * 180f
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
                    color = primary,
                    style = Stroke(width = 1.6.dp.toPx()),
                )
            }
        }
    }
}
