package com.monolith.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.monolith.core.model.ThemeMode
import com.monolith.core.ui.theme.LocalMonolithThemeMode

private val SwitchWidth = 52.dp
private val SwitchHeight = 28.dp
private val ThumbSize = 18.dp
private val ThumbHorizontalPadding = 5.dp

@Composable
fun MonolithSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    when (LocalMonolithThemeMode.current) {
        ThemeMode.CYBER_PHOSPHOR -> CustomMonolithSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            trackShape = CutCornerShape(4.dp),
            thumbShape = CutCornerShape(2.dp),
            borderWidth = 1.5.dp,
            modifier = modifier,
            enabled = enabled,
        )

        ThemeMode.OBSIDIAN -> CustomMonolithSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            trackShape = CutCornerShape(7.dp),
            thumbShape = CutCornerShape(4.dp),
            borderWidth = 1.2.dp,
            modifier = modifier,
            enabled = enabled,
        )

        ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK, null -> Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
        )
    }
}

@Composable
private fun CustomMonolithSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    trackShape: Shape,
    thumbShape: Shape,
    borderWidth: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colorScheme = MaterialTheme.colorScheme
    val trackColor by animateColorAsState(
        targetValue = if (checked) colorScheme.primaryContainer else colorScheme.surfaceContainerHighest,
        label = "monolithSwitchTrackColor",
    )
    val borderColor by animateColorAsState(
        targetValue = if (checked) colorScheme.primary else colorScheme.outline,
        label = "monolithSwitchBorderColor",
    )
    val thumbColor by animateColorAsState(
        targetValue = if (checked) colorScheme.primary else colorScheme.outline,
        label = "monolithSwitchThumbColor",
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) {
            SwitchWidth - ThumbSize - ThumbHorizontalPadding
        } else {
            ThumbHorizontalPadding
        },
        label = "monolithSwitchThumbOffset",
    )

    val interactiveModifier = if (onCheckedChange != null) {
        Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .size(width = SwitchWidth, height = SwitchHeight)
            .alpha(if (enabled) 1f else 0.38f)
            .clip(trackShape)
            .background(trackColor, trackShape)
            .border(width = borderWidth, color = borderColor, shape = trackShape)
            .then(interactiveModifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(thumbOffset.roundToPx(), 0) }
                .size(ThumbSize)
                .clip(thumbShape)
                .background(thumbColor, thumbShape),
        )
    }
}
