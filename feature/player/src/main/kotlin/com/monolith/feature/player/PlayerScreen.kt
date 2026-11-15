package com.monolith.feature.player

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.monolith.core.media.MpvEngine
import com.monolith.core.model.MediaTrackType
import com.monolith.core.model.PlaybackUiState
import com.monolith.core.model.PlayerSettings
import com.monolith.core.model.ResumeMode
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

private val PlaybackSpeeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    state: PlaybackUiState,
    settings: PlayerSettings,
    isInPip: Boolean,
    pendingResumePositionMs: Long?,
    onConfirmResume: (Long) -> Unit,
    onDismissResume: () -> Unit,
    onBack: () -> Unit,
    onEnterPip: () -> Unit,
    onUpdateSettings: ((PlayerSettings) -> PlayerSettings) -> Unit,
) {
    var showSettingsSheet by remember { mutableStateOf(false) }

    if (state.isVideo) {
        VideoPlayerContent(
            state = state,
            isInPip = isInPip,
            onBack = onBack,
            onEnterPip = onEnterPip,
            onOpenSettings = { showSettingsSheet = true },
        )
    } else {
        AudioPlayerContent(
            state = state,
            onBack = onBack,
            onOpenSettings = { showSettingsSheet = true },
        )
    }

    if (pendingResumePositionMs != null && pendingResumePositionMs > 0L) {
        AlertDialog(
            onDismissRequest = onDismissResume,
            title = { Text("Resume Playback") },
            text = {
                Text("Resume from ${formatDuration(pendingResumePositionMs)}?")
            },
            confirmButton = {
                TextButton(onClick = { onConfirmResume(pendingResumePositionMs) }) {
                    Text("Resume")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissResume) {
                    Text("Start Over")
                }
            },
        )
    }

    if (showSettingsSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            sheetState = sheetState,
        ) {
            PlayerSettingsSheetContent(
                state = state,
                settings = settings,
                onUpdateSettings = onUpdateSettings,
            )
        }
    }
}

@Composable
private fun VideoPlayerContent(
    state: PlaybackUiState,
    isInPip: Boolean,
    onBack: () -> Unit,
    onEnterPip: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var controlsVisible by remember { mutableStateOf(true) }

    LaunchedEffect(controlsVisible, state.isPlaying, isInPip) {
        if (controlsVisible && state.isPlaying && !isInPip) {
            delay(3500L)
            controlsVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                if (!isInPip) {
                    controlsVisible = !controlsVisible
                }
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(
                        object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                MpvEngine.onSurfaceCreated(holder.surface)
                            }

                            override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int,
                            ) {
                                MpvEngine.onSurfaceChanged(width, height)
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                MpvEngine.onSurfaceDestroyed()
                            }
                        },
                    )
                }
            },
        )

        if (state.isBuffering && !isInPip) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (state.errorMessage != null && !isInPip) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                shape = RoundedCornerShape(12.dp),
                color = Color.Black.copy(alpha = 0.8f),
            ) {
                Text(
                    text = state.errorMessage.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible && !isInPip,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Top bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent),
                            ),
                        )
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                        )
                    }
                    Text(
                        text = state.title.ifBlank { "Video" },
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    )
                    IconButton(onClick = onEnterPip) {
                        Icon(
                            imageVector = Icons.Default.PictureInPictureAlt,
                            contentDescription = "Picture in Picture",
                            tint = Color.White,
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = Color.White,
                        )
                    }
                }

                // Bottom controls
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)),
                            ),
                        )
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    SeekBarRow(
                        positionMs = state.positionMs,
                        durationMs = state.durationMs,
                        cacheDurationSec = state.cacheDurationSec,
                        timeTextColor = Color.White,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    TransportControlsRow(
                        isPlaying = state.isPlaying,
                        iconTint = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioPlayerContent(
    state: PlaybackUiState,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
                Text(
                    text = state.title.ifBlank { "Now Playing" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                )
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                ElevatedCard(
                    modifier = Modifier
                        .widthIn(max = 420.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(120.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(56.dp),
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Text(
                            text = state.title.ifBlank { "Unknown Title" },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )

                        val subtitle = listOf(state.artist, state.album)
                            .filter { it.isNotBlank() }
                            .joinToString(" • ")
                        if (subtitle.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }

                        if (state.isBuffering) {
                            Spacer(modifier = Modifier.height(12.dp))
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }

                        if (state.errorMessage != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = state.errorMessage.orEmpty(),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        SeekBarRow(
                            positionMs = state.positionMs,
                            durationMs = state.durationMs,
                            cacheDurationSec = state.cacheDurationSec,
                            timeTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        TransportControlsRow(
                            isPlaying = state.isPlaying,
                            iconTint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SeekBarRow(
    positionMs: Long,
    durationMs: Long,
    cacheDurationSec: Double,
    timeTextColor: Color,
) {
    val safeDuration = durationMs.coerceAtLeast(0L)
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val currentFraction = if (isDragging) {
        dragFraction
    } else if (safeDuration > 0L) {
        (positionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    val bufferedFraction = if (safeDuration > 0L && cacheDurationSec > 0.0) {
        ((positionMs + (cacheDurationSec * 1000.0)).toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    val displayPosMs = if (isDragging) (dragFraction * safeDuration).toLong() else positionMs

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            if (bufferedFraction > currentFraction) {
                LinearProgressIndicator(
                    progress = { bufferedFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .height(3.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                    trackColor = Color.Transparent,
                )
            }
            Slider(
                value = currentFraction,
                onValueChange = { fraction ->
                    isDragging = true
                    dragFraction = fraction
                },
                onValueChangeFinished = {
                    if (safeDuration > 0L) {
                        MpvEngine.seekTo((dragFraction * safeDuration).toLong())
                    }
                    isDragging = false
                },
                enabled = safeDuration > 0L,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = timeTextColor.copy(alpha = 0.25f),
                ),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatDuration(displayPosMs),
                style = MaterialTheme.typography.labelSmall,
                color = timeTextColor,
            )
            Text(
                text = formatDuration(safeDuration),
                style = MaterialTheme.typography.labelSmall,
                color = timeTextColor,
            )
        }
    }
}

@Composable
private fun TransportControlsRow(
    isPlaying: Boolean,
    iconTint: Color,
) {
    val hasPrevious = MpvEngine.hasPrevious()
    val hasNext = MpvEngine.hasNext()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { MpvEngine.skipPrevious() },
            enabled = hasPrevious,
        ) {
            Icon(
                imageVector = Icons.Default.SkipPrevious,
                contentDescription = "Previous",
                tint = if (hasPrevious) iconTint else iconTint.copy(alpha = 0.35f),
            )
        }

        IconButton(onClick = { MpvEngine.seekBy(-10_000L) }) {
            Icon(
                imageVector = Icons.Default.Replay10,
                contentDescription = "Rewind 10 seconds",
                tint = iconTint,
            )
        }

        FilledIconButton(
            onClick = { MpvEngine.togglePlayPause() },
            modifier = Modifier.size(56.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                modifier = Modifier.size(32.dp),
            )
        }

        IconButton(onClick = { MpvEngine.seekBy(10_000L) }) {
            Icon(
                imageVector = Icons.Default.Forward10,
                contentDescription = "Forward 10 seconds",
                tint = iconTint,
            )
        }

        IconButton(
            onClick = { MpvEngine.skipNext() },
            enabled = hasNext,
        ) {
            Icon(
                imageVector = Icons.Default.SkipNext,
                contentDescription = "Next",
                tint = if (hasNext) iconTint else iconTint.copy(alpha = 0.35f),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerSettingsSheetContent(
    state: PlaybackUiState,
    settings: PlayerSettings,
    onUpdateSettings: ((PlayerSettings) -> PlayerSettings) -> Unit,
) {
    val audioTracks = remember(state.tracks) {
        state.tracks.filter { it.type == MediaTrackType.AUDIO }
    }
    val subtitleTracks = remember(state.tracks) {
        state.tracks.filter { it.type == MediaTrackType.SUBTITLE }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Player Settings",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )

        // Speed selector
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Playback Speed",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PlaybackSpeeds.forEach { speed ->
                    val selected = kotlin.math.abs(state.speed - speed) < 0.01f
                    FilterChip(
                        selected = selected,
                        onClick = { MpvEngine.setSpeed(speed) },
                        label = { Text("${speed}x") },
                    )
                }
            }
        }

        // Audio tracks
        if (audioTracks.isNotEmpty()) {
            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Audio Track",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    audioTracks.forEach { track ->
                        val label = buildTrackLabel(track.id, track.title, track.language)
                        FilterChip(
                            selected = track.isSelected,
                            onClick = { MpvEngine.selectAudioTrack(track.id) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }

        // Subtitle tracks
        if (subtitleTracks.isNotEmpty()) {
            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Subtitles",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val noneSelected = subtitleTracks.none { it.isSelected }
                    FilterChip(
                        selected = noneSelected,
                        onClick = { MpvEngine.selectSubtitleTrack(-1) },
                        label = { Text("Off") },
                    )
                    subtitleTracks.forEach { track ->
                        val label = buildTrackLabel(track.id, track.title, track.language)
                        FilterChip(
                            selected = track.isSelected,
                            onClick = { MpvEngine.selectSubtitleTrack(track.id) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }

        // Subtitle font size
        HorizontalDivider()
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Subtitle Size",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "${settings.subtitleSize.roundToInt()} pt",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = settings.subtitleSize.coerceIn(12f, 48f),
                onValueChange = { size ->
                    onUpdateSettings { it.copy(subtitleSize = size) }
                    MpvEngine.setSubtitleSize(size)
                },
                valueRange = 12f..48f,
            )
        }

        // Toggles
        HorizontalDivider()
        SettingsToggleRow(
            title = "Hardware Decoding",
            checked = settings.useHwDecoding,
            onCheckedChange = { checked ->
                onUpdateSettings { it.copy(useHwDecoding = checked) }
            },
        )
        SettingsToggleRow(
            title = "Background Playback",
            checked = settings.useBackgroundPlay,
            onCheckedChange = { checked ->
                onUpdateSettings { it.copy(useBackgroundPlay = checked) }
            },
        )
        SettingsToggleRow(
            title = "Picture-in-Picture",
            checked = settings.usePipMode,
            onCheckedChange = { checked ->
                onUpdateSettings { it.copy(usePipMode = checked) }
            },
        )

        // Resume mode
        HorizontalDivider()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Resume Mode",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ResumeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.resumeMode == mode,
                        onClick = {
                            onUpdateSettings { it.copy(resumeMode = mode) }
                        },
                        label = {
                            Text(
                                when (mode) {
                                    ResumeMode.ASK -> "Ask"
                                    ResumeMode.ALWAYS -> "Always"
                                    ResumeMode.NEVER -> "Never"
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

private fun buildTrackLabel(id: Int, title: String, language: String): String {
    val parts = listOf(title, language).filter { it.isNotBlank() }
    return if (parts.isNotEmpty()) parts.joinToString(" • ") else "Track $id"
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
