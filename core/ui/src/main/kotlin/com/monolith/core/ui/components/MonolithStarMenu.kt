package com.monolith.core.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Immutable
data class StarMenuItem(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val isSelected: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * Modern floating menu overlay anchored below-right of [anchorCenter],
 * keeping the anchor icon crisp over the dimmed background and scaling cleanly with any item count.
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
) {
    val expandProgressState = animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = if (expanded) {
            tween(durationMillis = 210, easing = FastOutSlowInEasing)
        } else {
            tween(durationMillis = 150, easing = FastOutSlowInEasing)
        },
        label = "monolithMenuExpand",
    )
    val isVisible by remember(expanded) {
        derivedStateOf { expanded || expandProgressState.value > 0.01f }
    }

    if (!isVisible) return
    if (anchorCenter == Offset.Unspecified || items.isEmpty()) return

    val density = LocalDensity.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val scrimColor = MaterialTheme.colorScheme.background

    val ringRadiusPx = with(density) { 22.dp.toPx() }
    val menuOffsetX = with(density) { 10.dp.toPx() }
    val menuOffsetY = with(density) { 30.dp.toPx() }
    val menuStartX = anchorCenter.x + menuOffsetX
    val menuStartY = anchorCenter.y + menuOffsetY
    val haloRadiusPx = ringRadiusPx * 2.8f
    val glowBrush = remember(anchorCenter, haloRadiusPx, primaryColor) {
        Brush.radialGradient(
            0.0f to primaryColor.copy(alpha = 0.22f),
            0.45f to primaryColor.copy(alpha = 0.08f),
            0.8f to primaryColor.copy(alpha = 0.015f),
            1.0f to Color.Transparent,
            center = anchorCenter,
            radius = haloRadiusPx,
        )
    }
    val ringStroke = remember(density) { Stroke(width = with(density) { 1.5.dp.toPx() }) }
    val connectorStroke = remember(density) {
        Stroke(width = with(density) { 1.6.dp.toPx() }, cap = StrokeCap.Round)
    }
    val connectorPath = remember(anchorCenter, ringRadiusPx, menuStartX, menuStartY, density) {
        val start = Offset(anchorCenter.x + ringRadiusPx * 0.45f, anchorCenter.y + ringRadiusPx * 0.88f)
        val end = Offset(menuStartX + with(density) { 16.dp.toPx() }, menuStartY)
        Path().apply {
            moveTo(start.x, start.y)
            quadraticTo(
                start.x,
                end.y * 0.5f + start.y * 0.5f,
                end.x,
                end.y,
            )
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Strong scrim so the background recedes cleanly while the menu is open
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(scrimColor.copy(alpha = 0.78f * expandProgressState.value.coerceIn(0f, 1f)))
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    onDismiss()
                },
        )

        // Anchor halo, ring, and subtle connector trace to the menu card
        Canvas(modifier = Modifier.fillMaxSize()) {
            val p = expandProgressState.value.coerceIn(0f, 1f)

            drawCircle(
                brush = glowBrush,
                radius = haloRadiusPx,
                center = anchorCenter,
                alpha = p,
            )

            drawCircle(
                color = primaryColor.copy(alpha = 0.78f * p),
                radius = ringRadiusPx * (0.8f + 0.2f * p),
                center = anchorCenter,
                style = ringStroke,
            )

            drawPath(
                path = connectorPath,
                color = primaryColor.copy(alpha = 0.65f * p),
                style = connectorStroke,
            )
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
                        alpha = expandProgressState.value.coerceIn(0f, 1f)
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
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

        // Modern floating menu card expanding below-right of the Monolith icon
        Surface(
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = menuStartX.roundToInt(),
                        y = menuStartY.roundToInt(),
                    )
                }
                .widthIn(min = 210.dp, max = 270.dp)
                .graphicsLayer {
                    val p = expandProgressState.value.coerceIn(0f, 1f)
                    alpha = p
                    scaleX = 0.86f + 0.14f * p
                    scaleY = 0.86f + 0.14f * p
                    transformOrigin = TransformOrigin(0f, 0f)
                },
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.97f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = 6.dp,
            shadowElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items.forEach { item ->
                    val rowContainerColor = if (item.isSelected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                    } else {
                        Color.Transparent
                    }
                    val rowContentColor = if (item.isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                    val iconTint = if (item.isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = CutCornerShape(8.dp),
                        color = rowContainerColor,
                        contentColor = rowContentColor,
                        onClick = {
                            onDismiss()
                            item.onClick()
                        },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.label,
                                tint = iconTint,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (item.isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Immutable
data class CornerMenuItem(
    val id: String,
    val label: String,
    val subtitle: String? = null,
    val onClick: () -> Unit,
)

/**
 * Theme-styled overflow menu overlay anchored below-left of the top-right 3-dot icon,
 * with a gentle scrim so the background recedes softly without hiding context.
 */
@Composable
fun MonolithCornerMenuOverlay(
    expanded: Boolean,
    anchorCenter: Offset,
    items: List<CornerMenuItem>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val expandProgressState = animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = if (expanded) {
            spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMedium)
        } else {
            tween(durationMillis = 150, easing = FastOutSlowInEasing)
        },
        label = "monolithCornerMenuExpand",
    )
    val isVisible by remember(expanded) {
        derivedStateOf { expanded || expandProgressState.value > 0.01f }
    }

    if (!isVisible) return
    if (anchorCenter == Offset.Unspecified || items.isEmpty()) return

    val density = LocalDensity.current
    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val primaryColor = MaterialTheme.colorScheme.primary
    val scrimColor = MaterialTheme.colorScheme.background

    val menuWidth = 224.dp
    val menuWidthPx = with(density) { menuWidth.toPx() }
    val rightEdgeX = (anchorCenter.x + with(density) { 12.dp.toPx() }).coerceAtMost(screenWidthPx - with(density) { 8.dp.toPx() })
    val menuStartX = (rightEdgeX - menuWidthPx).coerceAtLeast(with(density) { 8.dp.toPx() })
    val menuStartY = anchorCenter.y + with(density) { 26.dp.toPx() }

    Box(modifier = modifier.fillMaxSize()) {
        // Gentle scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(scrimColor.copy(alpha = 0.38f * expandProgressState.value.coerceIn(0f, 1f)))
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    onDismiss()
                },
        )

        // Crisp 3-dot icon above the gentle scrim
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
                .graphicsLayer { alpha = expandProgressState.value.coerceIn(0f, 1f) }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    onDismiss()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = "Menü schließen",
                tint = primaryColor,
                modifier = Modifier.size(22.dp),
            )
        }

        Surface(
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = menuStartX.roundToInt(),
                        y = menuStartY.roundToInt(),
                    )
                }
                .width(menuWidth)
                .graphicsLayer {
                    val p = expandProgressState.value.coerceIn(0f, 1f)
                    alpha = p
                    scaleX = 0.9f + 0.1f * p
                    scaleY = 0.9f + 0.1f * p
                    transformOrigin = TransformOrigin(1f, 0f)
                },
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.97f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = 6.dp,
            shadowElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier
                    .padding(6.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items.forEach { item ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = CutCornerShape(8.dp),
                        color = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        onClick = {
                            onDismiss()
                            item.onClick()
                        },
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!item.subtitle.isNullOrBlank()) {
                                Text(
                                    text = item.subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
