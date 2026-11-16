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
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.monolith.core.common.AppSettingsStore
import com.monolith.core.common.DefaultAppsStore
import com.monolith.core.media.MpvEngine
import com.monolith.core.media.PlayerSettingsStore
import com.monolith.core.model.AppSettings
import com.monolith.core.model.PlayerSettings
import com.monolith.core.model.ResumeMode
import com.monolith.core.model.ThemeMode
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    AppSettingsStore.init(context)
    PlayerSettingsStore.init(context)
    val appSettings by AppSettingsStore.settings.collectAsState()
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
                text = "Design",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = appSettings.themeMode == mode,
                        onClick = {
                            AppSettingsStore.update { it.copy(themeMode = mode) }
                        },
                        label = {
                            Text(
                                when (mode) {
                                    ThemeMode.SYSTEM -> "System"
                                    ThemeMode.LIGHT -> "Hell"
                                    ThemeMode.DARK -> "Dunkel"
                                },
                            )
                        },
                    )
                }
            }

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

            HorizontalDivider(thickness = 0.5.dp)

            var showResetConfirm by remember { mutableStateOf(false) }

            OutlinedButton(
                onClick = { showResetConfirm = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("App-Einstellungen zurücksetzen")
            }

            if (showResetConfirm) {
                AlertDialog(
                    onDismissRequest = { showResetConfirm = false },
                    title = { Text("App-Einstellungen zurücksetzen") },
                    text = {
                        Text(
                            "Design, Wiedergabe-Einstellungen sowie alle gespeicherten Standard-Apps (inkl. der integrierten Apps) werden auf die Standardwerte zurückgesetzt.",
                        )
                    },
                    dismissButton = {
                        TextButton(onClick = { showResetConfirm = false }) {
                            Text("Abbrechen")
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showResetConfirm = false
                                AppSettingsStore.update { AppSettings() }
                                val defaultPlayer = PlayerSettings()
                                PlayerSettingsStore.update { defaultPlayer }
                                MpvEngine.setSubtitleSize(defaultPlayer.subtitleSize)
                                DefaultAppsStore.clearAll()
                            },
                        ) {
                            Text("Zurücksetzen")
                        }
                    },
                )
            }

            HorizontalDivider(thickness = 0.5.dp)

            val defaultApps by DefaultAppsStore.associations.collectAsState()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Standard-Apps",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (defaultApps.isNotEmpty()) {
                    TextButton(onClick = { DefaultAppsStore.clearAll() }) {
                        Text("Alle zurücksetzen")
                    }
                }
            }

            if (defaultApps.isEmpty()) {
                Text(
                    text = "Keine Standard-Apps festgelegt. Wähle beim Öffnen einer Datei „Immer“, um einen Dateityp fest zuzuordnen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                defaultApps.forEach { assoc ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Text(
                                    text = ".${assoc.extension.uppercase()}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                            Text(
                                text = assoc.handlerLabel,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        IconButton(
                            onClick = { DefaultAppsStore.removeForExtension(assoc.extension) },
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = "Zurücksetzen",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            val packageInfo = remember(context) {
                runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
            }
            val versionName = packageInfo?.versionName ?: "–"
            val versionCode = if (packageInfo != null) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode.toLong()
                }
            } else {
                0L
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Über Monolith",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Version $versionName (Build $versionCode)",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = context.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
