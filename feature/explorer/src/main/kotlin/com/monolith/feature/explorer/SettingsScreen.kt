package com.monolith.feature.explorer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.monolith.core.media.MpvEngine
import com.monolith.core.media.PlayerSettingsStore
import com.monolith.core.model.ResumeMode
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    PlayerSettingsStore.init(context)
    val settings by PlayerSettingsStore.settings.collectAsState()

    BackHandler(onBack = onBack)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Zurück",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Medien & Wiedergabe",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            SettingsToggle(
                title = "Hardware-Dekodierung",
                subtitle = "MediaCodec für Video verwenden",
                checked = settings.useHwDecoding,
                onCheckedChange = { checked ->
                    PlayerSettingsStore.update { it.copy(useHwDecoding = checked) }
                },
            )

            SettingsToggle(
                title = "Hintergrundwiedergabe",
                subtitle = "Audio beim Verlassen der App weiterspielen",
                checked = settings.useBackgroundPlay,
                onCheckedChange = { checked ->
                    PlayerSettingsStore.update { it.copy(useBackgroundPlay = checked) }
                },
            )

            SettingsToggle(
                title = "Bild-in-Bild (PiP)",
                subtitle = "Automatisches Mini-Video beim Verlassen",
                checked = settings.usePipMode,
                onCheckedChange = { checked ->
                    PlayerSettingsStore.update { it.copy(usePipMode = checked) }
                },
            )

            HorizontalDivider(thickness = 0.5.dp)

            Text(
                text = "Untertitel",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Untertitelgröße",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "${settings.subtitleSize.roundToInt()} pt",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Slider(
                    value = settings.subtitleSize.coerceIn(12f, 48f),
                    onValueChange = { size ->
                        MpvEngine.setSubtitleSize(size)
                    },
                    valueRange = 12f..48f,
                )
            }

            HorizontalDivider(thickness = 0.5.dp)

            Text(
                text = "Weitermachen wo aufgehört",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ResumeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.resumeMode == mode,
                        onClick = {
                            PlayerSettingsStore.update { it.copy(resumeMode = mode) }
                        },
                        label = {
                            Text(
                                when (mode) {
                                    ResumeMode.ASK -> "Fragen"
                                    ResumeMode.ALWAYS -> "Immer"
                                    ResumeMode.NEVER -> "Nie"
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
private fun SettingsToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}
