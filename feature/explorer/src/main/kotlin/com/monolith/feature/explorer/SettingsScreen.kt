package com.monolith.feature.explorer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.io.File
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.monolith.core.common.AppSettingsStore
import com.monolith.core.common.DefaultAppsStore
import com.monolith.core.media.MpvEngine
import com.monolith.core.media.PlayerSettingsStore
import com.monolith.core.media.ThumbnailEngine
import com.monolith.core.model.AppSettings
import com.monolith.core.model.PlayerSettings
import com.monolith.core.model.ResumeMode
import com.monolith.core.model.ThemeMode
import com.monolith.core.ui.components.MonolithDialog
import com.monolith.core.ui.components.MonolithSwitch
import com.monolith.core.ui.haptics.MonolithHaptics
import com.monolith.core.ui.theme.LocalMonolithButtonShape
import com.monolith.feature.explorer.downloader.DownloaderSettingsStore
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    AppSettingsStore.init(context)
    PlayerSettingsStore.init(context)
    val appSettings by AppSettingsStore.settings.collectAsState()
    val settings by PlayerSettingsStore.settings.collectAsState()

    var showHapticsSubmenu by remember { mutableStateOf(false) }

    BackHandler(enabled = showHapticsSubmenu) {
        MonolithHaptics.backNav(context, view)
        showHapticsSubmenu = false
    }

    BackHandler(enabled = !showHapticsSubmenu) {
        MonolithHaptics.backNav(context, view)
        onBack()
    }

    if (showHapticsSubmenu) {
        HapticsSubmenuScreen(
            appSettings = appSettings,
            onBack = {
                MonolithHaptics.backNav(context, view)
                showHapticsSubmenu = false
            },
            modifier = modifier,
        )
        return
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = if (com.monolith.core.ui.theme.LocalMonolithThemeMode.current == ThemeMode.OBSIDIAN) {
            androidx.compose.ui.graphics.Color.Transparent
        } else {
            MaterialTheme.colorScheme.background
        },
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                navigationIcon = {
                    IconButton(onClick = {
                        MonolithHaptics.backNav(context, view)
                        onBack()
                    }) {
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
                        shape = LocalMonolithButtonShape.current,
                        onClick = {
                            AppSettingsStore.update { it.copy(themeMode = mode) }
                        },
                        label = {
                            Text(
                                when (mode) {
                                    ThemeMode.SYSTEM -> "System"
                                    ThemeMode.LIGHT -> "Hell"
                                    ThemeMode.DARK -> "Dunkel"
                                    ThemeMode.CYBER_PHOSPHOR -> "Phosphor"
                                    ThemeMode.OBSIDIAN -> "Obsidian"
                                },
                            )
                        },
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Haptik",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HapticToggleRow(
                    title = "Haptisches Feedback",
                    subtitle = "Alle taktilen Effekte in der App",
                    checked = appSettings.hapticsEnabled,
                    onCheckedChange = { enabled ->
                        AppSettingsStore.update { it.copy(hapticsEnabled = enabled) }
                    },
                )
                if (appSettings.hapticsEnabled) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showHapticsSubmenu = true
                            },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Haptik-Details",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = "Einzelne Aktionen fein einstellen",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                imageVector = Icons.Outlined.ChevronRight,
                                contentDescription = "Öffnen",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            HorizontalDivider(thickness = 0.5.dp)

            Text(
                text = "Untertitel",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Untertitel anzeigen",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "Untertitel standardmäßig ein- oder ausblenden",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MonolithSwitch(
                    checked = settings.subtitlesEnabled,
                    onCheckedChange = { enabled ->
                        MpvEngine.setSubtitlesEnabled(enabled)
                    },
                )
            }

            if (settings.subtitlesEnabled) {
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
                        shape = LocalMonolithButtonShape.current,
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

            Text(
                text = "Gemini-API",
                style = MaterialTheme.typography.titleMedium,
            )

            Text(
                text = "Aktiver API-Key für alle Gemini-Funktionen (lokal gespeichert).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            var showGeminiApiKey by remember { mutableStateOf(false) }

            OutlinedTextField(
                value = appSettings.activeGeminiApiKey,
                onValueChange = { newValue ->
                    AppSettingsStore.setActiveGeminiApiKeyValue(newValue)
                },
                label = { Text("Gemini-API-Key") },
                placeholder = { Text("AIza…") },
                singleLine = true,
                visualTransformation = if (showGeminiApiKey) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { showGeminiApiKey = !showGeminiApiKey }) {
                        Icon(
                            imageVector = if (showGeminiApiKey) {
                                Icons.Outlined.VisibilityOff
                            } else {
                                Icons.Outlined.Visibility
                            },
                            contentDescription = if (showGeminiApiKey) "Verbergen" else "Anzeigen",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider(thickness = 0.5.dp)

            var cacheRefreshTick by remember { mutableIntStateOf(0) }
            var showClearDownloadsConfirm by remember { mutableStateOf(false) }
            val cachedBytes = remember(cacheRefreshTick) {
                val shareDir = File(context.cacheDir, "shared_drive")
                val driveDir = File(context.cacheDir, "gdrive_dir_cache")
                folderSizeBytes(shareDir) + folderSizeBytes(driveDir)
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Cloud-Downloads",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Heruntergeladene Cloud-Dateien und Cloud-Ordnerlisten (${formatCacheSize(cachedBytes)}).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { showClearDownloadsConfirm = true },
                    enabled = cachedBytes > 0L,
                    shape = LocalMonolithButtonShape.current,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Downloads löschen")
                }
            }

            if (showClearDownloadsConfirm) {
                MonolithDialog(
                    onDismissRequest = { showClearDownloadsConfirm = false },
                    title = "Downloads löschen",
                    dismissLabel = "Abbrechen",
                    onDismissClick = { showClearDownloadsConfirm = false },
                    confirmLabel = "Löschen",
                    onConfirmClick = {
                        showClearDownloadsConfirm = false
                        File(context.cacheDir, "shared_drive").deleteRecursively()
                        File(context.cacheDir, "gdrive_dir_cache").deleteRecursively()
                        cacheRefreshTick++
                    },
                ) {
                    Text(
                        "Alle lokal heruntergeladenen Cloud-Dateien und zwischengespeicherten Ordnerlisten (${formatCacheSize(cachedBytes)}) werden gelöscht. Dateien in Google Drive bleiben erhalten.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            HorizontalDivider(thickness = 0.5.dp)

            var thumbRefreshTick by remember { mutableIntStateOf(0) }
            var showResetThumbsConfirm by remember { mutableStateOf(false) }
            val thumbBytes = remember(thumbRefreshTick) { ThumbnailEngine.diskCacheSizeBytes(context) }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Thumbnails",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Zwischengespeicherte Vorschaubilder (${formatCacheSize(thumbBytes)}).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { showResetThumbsConfirm = true },
                    shape = LocalMonolithButtonShape.current,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Thumbnails zurücksetzen")
                }
            }

            if (showResetThumbsConfirm) {
                MonolithDialog(
                    onDismissRequest = { showResetThumbsConfirm = false },
                    title = "Thumbnails zurücksetzen",
                    dismissLabel = "Abbrechen",
                    onDismissClick = { showResetThumbsConfirm = false },
                    confirmLabel = "Zurücksetzen",
                    onConfirmClick = {
                        showResetThumbsConfirm = false
                        ThumbnailEngine.clearAllCaches(context)
                        thumbRefreshTick++
                    },
                ) {
                    Text(
                        "Alle zwischengespeicherten Vorschaubilder (${formatCacheSize(thumbBytes)}) werden gelöscht und bei Bedarf neu erstellt.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            HorizontalDivider(thickness = 0.5.dp)

            var showResetConfirm by remember { mutableStateOf(false) }

            OutlinedButton(
                onClick = { showResetConfirm = true },
                shape = LocalMonolithButtonShape.current,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("App-Einstellungen zurücksetzen")
            }

            if (showResetConfirm) {
                MonolithDialog(
                    onDismissRequest = { showResetConfirm = false },
                    title = "App-Einstellungen zurücksetzen",
                    dismissLabel = "Abbrechen",
                    onDismissClick = { showResetConfirm = false },
                    confirmLabel = "Zurücksetzen",
                    onConfirmClick = {
                        showResetConfirm = false
                        AppSettingsStore.update { AppSettings() }
                        val defaultPlayer = PlayerSettings()
                        PlayerSettingsStore.update { defaultPlayer }
                        MpvEngine.setSubtitleSize(defaultPlayer.subtitleSize)
                        DownloaderSettingsStore.init(context)
                        DownloaderSettingsStore.resetToDefaults()
                    },
                ) {
                    Text(
                        "Alle App-Einstellungen werden auf ihre Standardwerte zurückgesetzt. Gespeicherte Standard-Apps bleiben erhalten.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
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
                    TextButton(
                        shape = LocalMonolithButtonShape.current,
                        onClick = { DefaultAppsStore.clearAll() },
                    ) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HapticsSubmenuScreen(
    appSettings: AppSettings,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = if (com.monolith.core.ui.theme.LocalMonolithThemeMode.current == ThemeMode.OBSIDIAN) {
            androidx.compose.ui.graphics.Color.Transparent
        } else {
            MaterialTheme.colorScheme.background
        },
        topBar = {
            TopAppBar(
                title = { Text("Haptik-Details") },
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
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HapticToggleRow(
                title = "Ordner öffnen",
                checked = appSettings.hapticFolderOpen,
                onCheckedChange = { enabled ->
                    AppSettingsStore.update { it.copy(hapticFolderOpen = enabled) }
                    if (enabled) MonolithHaptics.folderOpen(context, view)
                },
            )
            HapticToggleRow(
                title = "Zurückgehen",
                checked = appSettings.hapticBackNav,
                onCheckedChange = { enabled ->
                    AppSettingsStore.update { it.copy(hapticBackNav = enabled) }
                    if (enabled) MonolithHaptics.backNav(context, view)
                },
            )
            HapticToggleRow(
                title = "Monolith-Menü öffnen",
                checked = appSettings.hapticMonolithMenuOpen,
                onCheckedChange = { enabled ->
                    AppSettingsStore.update { it.copy(hapticMonolithMenuOpen = enabled) }
                    if (enabled) MonolithHaptics.monolithMenuOpen(context, view)
                },
            )
            HapticToggleRow(
                title = "3-Punkte-Menü öffnen",
                checked = appSettings.hapticCornerMenuOpen,
                onCheckedChange = { enabled ->
                    AppSettingsStore.update { it.copy(hapticCornerMenuOpen = enabled) }
                    if (enabled) MonolithHaptics.cornerMenuOpen(context, view)
                },
            )
            HapticToggleRow(
                title = "Gedrückthalten",
                checked = appSettings.hapticDragLift,
                onCheckedChange = { enabled ->
                    AppSettingsStore.update { it.copy(hapticDragLift = enabled) }
                    if (enabled) MonolithHaptics.dragLift(view)
                },
            )
            HapticToggleRow(
                title = "Datei öffnen",
                checked = appSettings.hapticLaunchItem,
                onCheckedChange = { enabled ->
                    AppSettingsStore.update { it.copy(hapticLaunchItem = enabled) }
                    if (enabled) MonolithHaptics.launchItem(context)
                },
            )
            HapticToggleRow(
                title = "Aktualisieren",
                checked = appSettings.hapticPullRefresh,
                onCheckedChange = { enabled ->
                    AppSettingsStore.update { it.copy(hapticPullRefresh = enabled) }
                    if (enabled) MonolithHaptics.refreshDetent(context, view)
                },
            )
        }
    }
}

private fun folderSizeBytes(dir: File): Long {
    if (!dir.exists()) return 0L
    if (dir.isFile) return dir.length()
    return dir.listFiles()?.sumOf { folderSizeBytes(it) } ?: 0L
}

private fun formatCacheSize(bytes: Long): String {
    return when {
        bytes <= 0L -> "0 KB"
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
        else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}

@Composable
private fun HapticToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        MonolithSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}
