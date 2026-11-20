package com.monolith.feature.explorer.downloader

import android.content.ClipboardManager
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.monolith.core.model.ThemeMode
import com.monolith.core.ui.components.CornerMenuItem
import com.monolith.core.ui.components.MonolithCornerMenuOverlay
import com.monolith.core.ui.components.MonolithDialog
import com.monolith.core.ui.components.monolithMenuBackdrop
import com.monolith.core.ui.components.MonolithDialog
import com.monolith.core.ui.components.MonolithDialogOption
import com.monolith.core.ui.components.MonolithSwitch
import com.monolith.core.ui.haptics.MonolithHaptics
import com.monolith.core.ui.theme.LocalMonolithButtonShape
import com.monolith.core.ui.theme.LocalMonolithThemeMode
import com.monolith.feature.explorer.ExternalVolumeKind
import com.monolith.feature.explorer.MonolithToolDestination
import com.monolith.feature.explorer.MonolithToolStarMenu
import com.monolith.feature.explorer.rememberExternalStorageVolumes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DownloaderScreen(
    hasStoragePermission: Boolean,
    onRequestStoragePermission: () -> Unit,
    onBack: () -> Unit,
    onNavigateToFiles: () -> Unit,
    onNavigateToArchives: () -> Unit,
    onNavigateToStorage: () -> Unit,
    onNavigateToAi: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFolderInExplorer: (String) -> Unit,
    onOpenDownloadedFile: (String) -> Unit,
    modifier: Modifier = Modifier,
    onSelectApps: () -> Unit = {},
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    remember(context) { DownloaderSettingsStore.init(context) }

    val settings by DownloaderSettingsStore.settings.collectAsState()
    val engineState by DownloaderEngine.engineState.collectAsState()
    val tasks by MediaDownloadService.tasks.collectAsState()

    val activeMode = settings.defaultMode
    val resolvedOutputDir = remember(settings.outputDirectory) {
        DownloaderSettingsStore.resolveOutputDirectory(settings)
    }

    var urlInput by remember { mutableStateOf("") }
    var showDownloaderSettings by remember { mutableStateOf(false) }

    var starMenuExpanded by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var starAnchorCenter by remember { mutableStateOf(Offset.Unspecified) }
    var menuAnchorCenter by remember { mutableStateOf(Offset.Unspecified) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(Unit) {
        DownloaderSettingsStore.init(context)
        DownloaderEngine.ensureInitialized(context)
        val current = DownloaderSettingsStore.settings.value
        if (current.autoUpdateEngine) {
            DownloaderEngine.updateEngine(
                context = context,
                channel = current.updateChannel,
                forceCheck = false,
            )
        }
    }

    BackHandler(enabled = showDownloaderSettings) {
        showDownloaderSettings = false
    }

    BackHandler(enabled = !showDownloaderSettings && (starMenuExpanded || menuExpanded)) {
        starMenuExpanded = false
        menuExpanded = false
    }

    BackHandler(enabled = !showDownloaderSettings && !starMenuExpanded && !menuExpanded) {
        onBack()
    }

    if (showDownloaderSettings) {
        DownloaderSettingsSubscreen(
            settings = settings,
            engineState = engineState,
            snackbarHostState = snackbarHostState,
            onUpdateNow = { channel ->
                scope.launch {
                    val result = DownloaderEngine.updateEngine(
                        context = context,
                        channel = channel,
                        forceCheck = true,
                    )
                    result.onSuccess { status ->
                        val msg = when (status) {
                            com.yausername.youtubedl_android.YoutubeDL.UpdateStatus.DONE ->
                                "yt-dlp (${channel.label}) aktualisiert."
                            com.yausername.youtubedl_android.YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE ->
                                "yt-dlp (${channel.label}) ist bereits aktuell."
                            null -> "Update-Prüfung abgeschlossen."
                        }
                        snackbarHostState.showSnackbar(msg)
                    }.onFailure { err ->
                        snackbarHostState.showSnackbar(
                            err.message ?: "yt-dlp konnte nicht aktualisiert werden.",
                        )
                    }
                }
            },
            onBack = {
                showDownloaderSettings = false
            },
            modifier = modifier,
        )
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { rootOrigin = it.positionInRoot() },
    ) {
        val backgroundRecedeState = animateFloatAsState(
            targetValue = if (starMenuExpanded) 1f else 0f,
            label = "downloaderStarMenuRecede",
        )
        val cornerMenuRecedeState = animateFloatAsState(
            targetValue = if (menuExpanded) 1f else 0f,
            label = "downloaderCornerMenuRecede",
        )

        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .monolithMenuBackdrop(backgroundRecedeState, cornerMenuRecedeState),
            containerColor = if (LocalMonolithThemeMode.current == ThemeMode.OBSIDIAN) {
                Color.Transparent
            } else {
                MaterialTheme.colorScheme.background
            },
            contentColor = MaterialTheme.colorScheme.onBackground,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "Downloader",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                MonolithHaptics.monolithMenuOpen(context, view)
                                starMenuExpanded = !starMenuExpanded
                            },
                            modifier = Modifier.onGloballyPositioned { coords ->
                                starAnchorCenter = coords.boundsInRoot().center - rootOrigin
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Download,
                                contentDescription = "Monolith-Menü",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                MonolithHaptics.cornerMenuOpen(context, view)
                                menuExpanded = !menuExpanded
                            },
                            modifier = Modifier.onGloballyPositioned { coords ->
                                menuAnchorCenter = coords.boundsInRoot().center - rootOrigin
                            },
                        ) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "Menü")
                        }
                    },
                )
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding()
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "input_card") {
                    DownloaderInputCard(
                        activeMode = activeMode,
                        urlInput = urlInput,
                        hasStoragePermission = hasStoragePermission,
                        onModeSelect = { mode ->
                            DownloaderSettingsStore.update { it.copy(defaultMode = mode) }
                        },
                        onUrlInputChange = { urlInput = it },
                        onPasteClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            val clipText = clipboard?.primaryClip
                                ?.takeIf { it.itemCount > 0 }
                                ?.getItemAt(0)
                                ?.coerceToText(context)
                                ?.toString()
                                ?.trim()
                                .orEmpty()
                            if (clipText.isNotEmpty()) {
                                urlInput = clipText
                            } else {
                                scope.launch {
                                    snackbarHostState.showSnackbar("Zwischenablage ist leer.")
                                }
                            }
                        },
                        onClearClick = { urlInput = "" },
                        onRequestStoragePermission = onRequestStoragePermission,
                        onStartDownload = {
                            val parsedUrls = DownloaderCommandBuilder.parseUrls(urlInput)
                            if (parsedUrls.isEmpty()) {
                                scope.launch {
                                    snackbarHostState.showSnackbar("Bitte mindestens eine gültige http(s)-URL eingeben.")
                                }
                            } else {
                                MonolithHaptics.launchItem(context)
                                urlInput = ""
                                MediaDownloadService.enqueue(
                                    context = context,
                                    urls = parsedUrls,
                                    mode = activeMode,
                                    settings = settings,
                                )
                            }
                        },
                    )
                }

                if (engineState.isUpdating) {
                    item(key = "engine_update_strip") {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = CutCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Text(
                                    text = "yt-dlp (${engineState.activeChannel.label}) wird aktualisiert…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    }
                } else if (!engineState.isInitialized && !engineState.isInitializing && engineState.errorMessage != null) {
                    item(key = "engine_error_strip") {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = CutCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.WarningAmber,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = engineState.errorMessage ?: "Engine nicht bereit.",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    onClick = {
                                        scope.launch { DownloaderEngine.ensureInitialized(context) }
                                    },
                                ) {
                                    Text("Erneut")
                                }
                            }
                        }
                    }
                }

                item(key = "queue_header") {
                    val hasFinished = tasks.any { it.status.isFinished }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Warteschlange & Verlauf",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (hasFinished) {
                            TextButton(
                                onClick = { MediaDownloadService.clearFinished() },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Text("Erledigte entfernen")
                            }
                        }
                    }
                }

                if (tasks.isEmpty()) {
                    item(key = "empty_state") {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 28.dp, horizontal = 20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Download,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(32.dp),
                                )
                                Text(
                                    text = "Keine Downloads",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else {
                    items(tasks, key = { it.id }) { task ->
                        DownloadTaskCard(
                            task = task,
                            onCancel = { MediaDownloadService.cancel(context, task.id) },
                            onOpenFile = { path ->
                                val target = File(path)
                                if (target.exists() && target.isFile) {
                                    MonolithHaptics.launchItem(context)
                                    onOpenDownloadedFile(path)
                                } else {
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Die heruntergeladene Datei wurde nicht gefunden.")
                                    }
                                }
                            },
                            onOpenFolder = { folderPath ->
                                MonolithHaptics.folderOpen(context, view)
                                onOpenFolderInExplorer(folderPath)
                            },
                            onRetry = {
                                MonolithHaptics.launchItem(context)
                                MediaDownloadService.enqueue(
                                    context = context,
                                    urls = listOf(task.url),
                                    mode = task.mode,
                                    settings = settings,
                                )
                            },
                            onRemove = { MediaDownloadService.removeTask(task.id) },
                        )
                    }
                }
            }
        }

        MonolithToolStarMenu(
            expanded = starMenuExpanded,
            anchorCenter = starAnchorCenter,
            anchorIcon = Icons.Outlined.Download,
            activeDestination = MonolithToolDestination.DOWNLOADER,
            onSelectFiles = onNavigateToFiles,
            onSelectArchives = onNavigateToArchives,
            onSelectStorage = onNavigateToStorage,
            onSelectDownloader = { starMenuExpanded = false },
            onSelectApps = onSelectApps,
            onSelectAi = onNavigateToAi,
            onDismiss = { starMenuExpanded = false },
        )

        val cornerItems = remember {
            listOf(
                CornerMenuItem(
                    id = "downloader_settings",
                    label = "Downloader-Einstellungen",
                    onClick = { showDownloaderSettings = true },
                ),
                CornerMenuItem(
                    id = "settings",
                    label = "App-Einstellungen",
                    onClick = onOpenSettings,
                ),
            )
        }

        MonolithCornerMenuOverlay(
            expanded = menuExpanded,
            anchorCenter = menuAnchorCenter,
            items = cornerItems,
            onDismiss = { menuExpanded = false },
        )
    }
}

// -----------------------------------------------------------------------------
// Main Screen Cards
// -----------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloaderInputCard(
    activeMode: DownloadMode,
    urlInput: String,
    hasStoragePermission: Boolean,
    onModeSelect: (DownloadMode) -> Unit,
    onUrlInputChange: (String) -> Unit,
    onPasteClick: () -> Unit,
    onClearClick: () -> Unit,
    onRequestStoragePermission: () -> Unit,
    onStartDownload: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = activeMode == DownloadMode.VIDEO,
                    shape = LocalMonolithButtonShape.current,
                    onClick = { onModeSelect(DownloadMode.VIDEO) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.VideoFile,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    label = { Text(DownloadMode.VIDEO.label) },
                )
                FilterChip(
                    selected = activeMode == DownloadMode.AUDIO,
                    shape = LocalMonolithButtonShape.current,
                    onClick = { onModeSelect(DownloadMode.AUDIO) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.AudioFile,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    label = { Text(DownloadMode.AUDIO.label) },
                )
            }

            OutlinedTextField(
                value = urlInput,
                onValueChange = onUrlInputChange,
                placeholder = { Text("URL oder mehrere URLs einfügen…") },
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (urlInput.isBlank()) {
                        IconButton(onClick = onPasteClick) {
                            Icon(
                                imageVector = Icons.Outlined.ContentPaste,
                                contentDescription = "Aus Zwischenablage einfügen",
                            )
                        }
                    } else {
                        IconButton(onClick = onClearClick) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Eingabe leeren",
                            )
                        }
                    }
                },
            )

            if (!hasStoragePermission) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = CutCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "Dateizugriff wird für den Zielordner benötigt.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onRequestStoragePermission) {
                            Text("Zugriff erlauben")
                        }
                    }
                }
            }

            Button(
                onClick = onStartDownload,
                shape = LocalMonolithButtonShape.current,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Download,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (activeMode == DownloadMode.VIDEO) {
                        "Video herunterladen"
                    } else {
                        "Audio herunterladen"
                    },
                )
            }
        }
    }
}

@Composable
private fun DownloadTaskCard(
    task: DownloadTaskItem,
    onCancel: () -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenFolder: (String) -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    var showLogs by remember(task.id) { mutableStateOf(false) }

    val borderColor = when (task.status) {
        DownloadTaskStatus.FAILED -> MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
        DownloadTaskStatus.INITIALIZING,
        DownloadTaskStatus.PROBING,
        DownloadTaskStatus.DOWNLOADING,
        DownloadTaskStatus.POST_PROCESSING,
        -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    val badgeContainer = when (task.status) {
        DownloadTaskStatus.COMPLETED,
        DownloadTaskStatus.INITIALIZING,
        DownloadTaskStatus.PROBING,
        DownloadTaskStatus.DOWNLOADING,
        DownloadTaskStatus.POST_PROCESSING,
        -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
        DownloadTaskStatus.FAILED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
        DownloadTaskStatus.QUEUED,
        DownloadTaskStatus.CANCELLED,
        -> MaterialTheme.colorScheme.secondaryContainer
    }
    val badgeContent = when (task.status) {
        DownloadTaskStatus.COMPLETED,
        DownloadTaskStatus.INITIALIZING,
        DownloadTaskStatus.PROBING,
        DownloadTaskStatus.DOWNLOADING,
        DownloadTaskStatus.POST_PROCESSING,
        -> MaterialTheme.colorScheme.primary
        DownloadTaskStatus.FAILED -> MaterialTheme.colorScheme.error
        DownloadTaskStatus.QUEUED,
        DownloadTaskStatus.CANCELLED,
        -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = if (task.mode == DownloadMode.VIDEO) {
                        Icons.Outlined.VideoFile
                    } else {
                        Icons.Outlined.AudioFile
                    },
                    contentDescription = null,
                    tint = when (task.status) {
                        DownloadTaskStatus.FAILED -> MaterialTheme.colorScheme.error
                        DownloadTaskStatus.CANCELLED,
                        DownloadTaskStatus.QUEUED,
                        -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(22.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.title ?: extractHostOrLabel(task.url),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (task.title != null) {
                            "${extractHostOrLabel(task.url)} · ${File(task.outputDirectory).name}"
                        } else {
                            task.url
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Surface(
                    shape = CutCornerShape(6.dp),
                    color = badgeContainer,
                    contentColor = badgeContent,
                ) {
                    Text(
                        text = task.status.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            if (task.status.isActive) {
                if (task.status != DownloadTaskStatus.QUEUED) {
                    if (task.status == DownloadTaskStatus.DOWNLOADING && task.progressPercent >= 0f) {
                        LinearProgressIndicator(
                            progress = { (task.progressPercent / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = task.statusDetail ?: task.status.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    val etaText = if (task.status == DownloadTaskStatus.DOWNLOADING && task.etaSeconds > 0L) {
                        "Restzeit ${formatSecondsEta(task.etaSeconds)}"
                    } else {
                        null
                    }
                    val pctText = if (task.status == DownloadTaskStatus.DOWNLOADING && task.progressPercent >= 0f) {
                        "${task.progressPercent.toInt()}%"
                    } else {
                        null
                    }
                    val rightLabel = listOfNotNull(pctText, etaText).joinToString(" · ")
                    if (rightLabel.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = rightLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            if (task.status == DownloadTaskStatus.FAILED && !task.errorMessage.isNullOrBlank()) {
                Text(
                    text = task.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (task.status == DownloadTaskStatus.COMPLETED && !task.outputFilePath.isNullOrBlank()) {
                Text(
                    text = task.outputFilePath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    task.status.isActive -> {
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(
                            onClick = onCancel,
                            shape = LocalMonolithButtonShape.current,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text("Abbrechen")
                        }
                    }
                    task.status == DownloadTaskStatus.COMPLETED -> {
                        val filePath = task.outputFilePath
                        if (!filePath.isNullOrBlank()) {
                            OutlinedButton(
                                onClick = { onOpenFile(filePath) },
                                shape = LocalMonolithButtonShape.current,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Abspielen")
                            }
                        }
                        TextButton(
                            onClick = {
                                val targetFolder = filePath?.let { File(it).parent } ?: task.outputDirectory
                                onOpenFolder(targetFolder)
                            },
                            shape = LocalMonolithButtonShape.current,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Ordner öffnen")
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(
                            onClick = onRemove,
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = "Eintrag entfernen",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    else -> {
                        OutlinedButton(
                            onClick = onRetry,
                            shape = LocalMonolithButtonShape.current,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Erneut")
                        }
                        if (task.logs.isNotEmpty()) {
                            TextButton(
                                onClick = { showLogs = !showLogs },
                                shape = LocalMonolithButtonShape.current,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Text(if (showLogs) "Protokoll ausblenden" else "Protokoll")
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(
                            onClick = onRemove,
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = "Eintrag entfernen",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(visible = showLogs && task.logs.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp),
                    shape = CutCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    SelectionContainer {
                        Text(
                            text = task.logs.joinToString("\n"),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(10.dp),
                        )
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Unified Downloader Settings Subscreen
// -----------------------------------------------------------------------------

private enum class DownloaderSettingsSubmenu(val title: String) {
    FORMAT("Format & Qualität"),
    SPONSORBLOCK("SponsorBlock"),
    EMBEDDING("Einbettung"),
    SLEEP("Wartezeit (Sleep)"),
    OUTPUT("Zielordner & Name"),
    ENGINE("yt-dlp-Engine"),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DownloaderSettingsSubscreen(
    settings: DownloaderSettings,
    engineState: YtDlpEngineState,
    snackbarHostState: SnackbarHostState,
    onUpdateNow: (YtDlpChannel) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    var activeSubmenu by remember { mutableStateOf<DownloaderSettingsSubmenu?>(null) }
    var minSleepText by remember(settings.minSleepIntervalSec) {
        mutableStateOf(settings.minSleepIntervalSec.toString())
    }
    var maxSleepText by remember(settings.maxSleepIntervalSec) {
        mutableStateOf(settings.maxSleepIntervalSec.toString())
    }
    var showDirectoryPicker by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

    val displayedOutputDir = remember(settings.outputDirectory) {
        DownloaderSettingsStore.resolveOutputDirectory(settings)
    }
    val versionDisplay = engineState.version
        ?: engineState.versionName
        ?: if (engineState.isInitializing) "Wird geladen…" else "Nicht initialisiert"

    BackHandler(enabled = activeSubmenu != null) {
        activeSubmenu = null
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = if (LocalMonolithThemeMode.current == ThemeMode.OBSIDIAN) {
            Color.Transparent
        } else {
            MaterialTheme.colorScheme.background
        },
        contentColor = MaterialTheme.colorScheme.onBackground,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(activeSubmenu?.title ?: "Downloader-Einstellungen") },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (activeSubmenu != null) {
                                activeSubmenu = null
                            } else {
                                onBack()
                            }
                        },
                    ) {
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
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (activeSubmenu) {
                null -> {
                    val formatSubtitle = "Video: ${formatShortSummary(DownloadMode.VIDEO, settings)} · Audio: ${formatShortSummary(DownloadMode.AUDIO, settings)}"
                    val sponsorSubtitle = when (settings.sponsorBlockMode) {
                        SponsorBlockMode.OFF -> "Aus"
                        else -> "${settings.sponsorBlockMode.label} (${if (settings.sponsorBlockCategories.size == 1) "1 Kategorie" else "${settings.sponsorBlockCategories.size} Kategorien"})"
                    }
                    val embedSubtitle = buildList {
                        if (settings.embedMetadata) add("Metadaten")
                        if (settings.embedChapters) add("Kapitel")
                        when (settings.thumbnailEmbedMode) {
                            ThumbnailEmbedMode.AUDIO_ONLY -> add("Vorschaubild (Audio)")
                            ThumbnailEmbedMode.ALWAYS -> add("Vorschaubild (Immer)")
                            ThumbnailEmbedMode.NEVER -> Unit
                        }
                        if (settings.embedSubtitles) add("Untertitel")
                    }.ifEmpty { listOf("Aus") }.joinToString(", ")
                    val sleepSubtitle = if (settings.sleepIntervalEnabled && settings.maxSleepIntervalSec > 0) {
                        "${settings.minSleepIntervalSec}–${settings.maxSleepIntervalSec} Sekunden"
                    } else {
                        "Aus"
                    }
                    val folderName = File(displayedOutputDir).name.ifBlank { displayedOutputDir }
                    val outputSubtitle = "$folderName · ${settings.outputTemplate}"
                    val engineSubtitle = buildString {
                        append(settings.updateChannel.label)
                        val ver = engineState.version ?: engineState.versionName
                        if (!ver.isNullOrBlank()) {
                            append(" · ")
                            append(ver)
                        }
                    }

                    DownloaderSettingsCategoryRow(
                        title = DownloaderSettingsSubmenu.FORMAT.title,
                        subtitle = formatSubtitle,
                        onClick = { activeSubmenu = DownloaderSettingsSubmenu.FORMAT },
                    )
                    DownloaderSettingsCategoryRow(
                        title = DownloaderSettingsSubmenu.SPONSORBLOCK.title,
                        subtitle = sponsorSubtitle,
                        onClick = { activeSubmenu = DownloaderSettingsSubmenu.SPONSORBLOCK },
                    )
                    DownloaderSettingsCategoryRow(
                        title = DownloaderSettingsSubmenu.EMBEDDING.title,
                        subtitle = embedSubtitle,
                        onClick = { activeSubmenu = DownloaderSettingsSubmenu.EMBEDDING },
                    )
                    DownloaderSettingsCategoryRow(
                        title = DownloaderSettingsSubmenu.SLEEP.title,
                        subtitle = sleepSubtitle,
                        onClick = { activeSubmenu = DownloaderSettingsSubmenu.SLEEP },
                    )
                    DownloaderSettingsCategoryRow(
                        title = DownloaderSettingsSubmenu.OUTPUT.title,
                        subtitle = outputSubtitle,
                        onClick = { activeSubmenu = DownloaderSettingsSubmenu.OUTPUT },
                    )
                    DownloaderSettingsCategoryRow(
                        title = DownloaderSettingsSubmenu.ENGINE.title,
                        subtitle = engineSubtitle,
                        onClick = { activeSubmenu = DownloaderSettingsSubmenu.ENGINE },
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )

                    OutlinedButton(
                        onClick = { showResetConfirm = true },
                        shape = LocalMonolithButtonShape.current,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Downloader-Einstellungen zurücksetzen")
                    }
                }

                DownloaderSettingsSubmenu.FORMAT -> {
                    Text(
                        text = "Videoformat",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    VideoFormatPreset.entries.forEach { preset ->
                        MonolithDialogOption(
                            label = preset.label,
                            subtitle = preset.subtitle,
                            selected = settings.videoFormatPreset == preset,
                            onClick = { DownloaderSettingsStore.update { it.copy(videoFormatPreset = preset) } },
                        )
                    }
                    AnimatedVisibility(visible = settings.videoFormatPreset == VideoFormatPreset.CUSTOM) {
                        OutlinedTextField(
                            value = settings.customVideoFormat,
                            onValueChange = { raw ->
                                DownloaderSettingsStore.update { it.copy(customVideoFormat = raw) }
                            },
                            label = { Text("Video-Formatausdruck (-f)") },
                            placeholder = { Text("bv*+ba/b") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Text(
                        text = "Maximale Auflösung",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        VIDEO_HEIGHT_OPTIONS.forEach { heightOpt ->
                            FilterChip(
                                selected = settings.videoMaxHeight == heightOpt,
                                shape = LocalMonolithButtonShape.current,
                                onClick = {
                                    DownloaderSettingsStore.update { it.copy(videoMaxHeight = heightOpt) }
                                },
                                label = { Text(heightOpt?.let { "${it}p" } ?: "Bestmöglich") },
                            )
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )

                    Text(
                        text = "Audioformat",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    AudioFormatPreset.entries.forEach { preset ->
                        MonolithDialogOption(
                            label = preset.label,
                            subtitle = preset.subtitle,
                            selected = settings.audioFormatPreset == preset,
                            onClick = { DownloaderSettingsStore.update { it.copy(audioFormatPreset = preset) } },
                        )
                    }
                    AnimatedVisibility(visible = settings.audioFormatPreset == AudioFormatPreset.CUSTOM) {
                        OutlinedTextField(
                            value = settings.customAudioFormat,
                            onValueChange = { raw ->
                                DownloaderSettingsStore.update { it.copy(customAudioFormat = raw) }
                            },
                            label = { Text("Audio-Formatausdruck (-f)") },
                            placeholder = { Text("bestaudio/best") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                DownloaderSettingsSubmenu.SPONSORBLOCK -> {
                    SponsorBlockMode.entries.forEach { mode ->
                        MonolithDialogOption(
                            label = mode.label,
                            subtitle = mode.subtitle,
                            selected = settings.sponsorBlockMode == mode,
                            onClick = { DownloaderSettingsStore.update { it.copy(sponsorBlockMode = mode) } },
                        )
                    }
                    AnimatedVisibility(visible = settings.sponsorBlockMode != SponsorBlockMode.OFF) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                            Text(
                                text = "Kategorien",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                SPONSORBLOCK_CATEGORIES.forEach { cat ->
                                    val selected = cat.id in settings.sponsorBlockCategories
                                    FilterChip(
                                        selected = selected,
                                        shape = LocalMonolithButtonShape.current,
                                        onClick = {
                                            val nextCategories = if (selected) {
                                                (settings.sponsorBlockCategories - cat.id)
                                                    .ifEmpty { settings.sponsorBlockCategories }
                                            } else {
                                                settings.sponsorBlockCategories + cat.id
                                            }
                                            DownloaderSettingsStore.update {
                                                it.copy(sponsorBlockCategories = nextCategories)
                                            }
                                        },
                                        label = { Text(cat.label) },
                                    )
                                }
                            }
                        }
                    }
                }

                DownloaderSettingsSubmenu.EMBEDDING -> {
                    DialogSwitchRow(
                        title = "Metadaten einbetten",
                        subtitle = "Titel, Kanal, Datum und Beschreibung (--embed-metadata)",
                        checked = settings.embedMetadata,
                        onCheckedChange = { enabled ->
                            DownloaderSettingsStore.update { it.copy(embedMetadata = enabled) }
                        },
                    )
                    DialogSwitchRow(
                        title = "Kapitel einbetten",
                        subtitle = "Video- und Podcast-Kapitelmarken (--embed-chapters)",
                        checked = settings.embedChapters,
                        onCheckedChange = { enabled ->
                            DownloaderSettingsStore.update { it.copy(embedChapters = enabled) }
                        },
                    )
                    DialogSwitchRow(
                        title = "Untertitel einbetten",
                        subtitle = "Untertitel in Videocontainer einbetten (--embed-subs).",
                        checked = settings.embedSubtitles,
                        onCheckedChange = { enabled ->
                            DownloaderSettingsStore.update { it.copy(embedSubtitles = enabled) }
                        },
                    )
                    AnimatedVisibility(visible = settings.embedSubtitles) {
                        OutlinedTextField(
                            value = settings.subtitleLanguages,
                            onValueChange = { raw ->
                                DownloaderSettingsStore.update { it.copy(subtitleLanguages = raw) }
                            },
                            label = { Text("Untertitelsprachen (--sub-langs)") },
                            placeholder = { Text("de,en") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Text(
                        text = "Vorschaubild (Thumbnail)",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    ThumbnailEmbedMode.entries.forEach { mode ->
                        MonolithDialogOption(
                            label = mode.label,
                            subtitle = mode.subtitle,
                            selected = settings.thumbnailEmbedMode == mode,
                            onClick = { DownloaderSettingsStore.update { it.copy(thumbnailEmbedMode = mode) } },
                        )
                    }
                }

                DownloaderSettingsSubmenu.SLEEP -> {
                    DialogSwitchRow(
                        title = "Sleep-Intervall aktiv",
                        subtitle = "Zufällige Pause zwischen Anfragen zum Schutz vor Rate-Limits",
                        checked = settings.sleepIntervalEnabled,
                        onCheckedChange = { enabled ->
                            DownloaderSettingsStore.update { it.copy(sleepIntervalEnabled = enabled) }
                        },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedTextField(
                            value = minSleepText,
                            onValueChange = { raw ->
                                val digits = raw.filter { it.isDigit() }.take(4)
                                minSleepText = digits
                                digits.toIntOrNull()?.let { v ->
                                    val clamped = v.coerceIn(0, 3600)
                                    DownloaderSettingsStore.update {
                                        it.copy(
                                            minSleepIntervalSec = clamped,
                                            maxSleepIntervalSec = it.maxSleepIntervalSec.coerceAtLeast(clamped),
                                        )
                                    }
                                }
                            },
                            enabled = settings.sleepIntervalEnabled,
                            label = { Text("Min. (Sek.)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = maxSleepText,
                            onValueChange = { raw ->
                                val digits = raw.filter { it.isDigit() }.take(4)
                                maxSleepText = digits
                                digits.toIntOrNull()?.let { v ->
                                    val clamped = v.coerceIn(1, 3600)
                                    DownloaderSettingsStore.update {
                                        it.copy(maxSleepIntervalSec = clamped.coerceAtLeast(it.minSleepIntervalSec))
                                    }
                                }
                            },
                            enabled = settings.sleepIntervalEnabled,
                            label = { Text("Max. (Sek.)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                DownloaderSettingsSubmenu.OUTPUT -> {
                    Text(
                        text = "Zielordner",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = CutCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        onClick = { showDirectoryPicker = true },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FolderOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = File(displayedOutputDir).name.ifBlank { displayedOutputDir },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = displayedOutputDir,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { showDirectoryPicker = true },
                            shape = LocalMonolithButtonShape.current,
                        ) {
                            Text("Ordner wählen…")
                        }
                        if (settings.outputDirectory.isNotBlank()) {
                            TextButton(
                                onClick = { DownloaderSettingsStore.update { it.copy(outputDirectory = "") } },
                                shape = LocalMonolithButtonShape.current,
                            ) {
                                Text("Standard (Download)")
                            }
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )

                    OutlinedTextField(
                        value = settings.outputTemplate,
                        onValueChange = { raw ->
                            DownloaderSettingsStore.update { it.copy(outputTemplate = raw) }
                        },
                        label = { Text("Dateinamensvorlage (-o)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OUTPUT_TEMPLATE_PRESETS.forEach { (presetTemplate, label) ->
                            FilterChip(
                                selected = settings.outputTemplate.trim() == presetTemplate,
                                shape = LocalMonolithButtonShape.current,
                                onClick = {
                                    DownloaderSettingsStore.update { it.copy(outputTemplate = presetTemplate) }
                                },
                                label = { Text(label) },
                            )
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )

                    DialogSwitchRow(
                        title = "Playlists herunterladen",
                        subtitle = "Bei Playlist-Links alle Titel herunterladen statt nur ein einzelnes Video.",
                        checked = settings.allowPlaylists,
                        onCheckedChange = { enabled ->
                            DownloaderSettingsStore.update { it.copy(allowPlaylists = enabled) }
                        },
                    )
                }

                DownloaderSettingsSubmenu.ENGINE -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = CutCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "Installierte Version",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = versionDisplay,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            if (!engineState.lastUpdateMessage.isNullOrBlank()) {
                                Text(
                                    text = engineState.lastUpdateMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (!engineState.errorMessage.isNullOrBlank()) {
                                Text(
                                    text = engineState.errorMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                    Text(
                        text = "Update-Kanal",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    YtDlpChannel.entries.forEach { ch ->
                        MonolithDialogOption(
                            label = ch.label,
                            subtitle = ch.subtitle,
                            selected = settings.updateChannel == ch,
                            onClick = { DownloaderSettingsStore.update { it.copy(updateChannel = ch) } },
                        )
                    }
                    DialogSwitchRow(
                        title = "Beim Start aktualisieren",
                        subtitle = "Prüft beim Öffnen des Downloaders auf neue yt-dlp-Releases.",
                        checked = settings.autoUpdateEngine,
                        onCheckedChange = { enabled ->
                            DownloaderSettingsStore.update { it.copy(autoUpdateEngine = enabled) }
                        },
                    )
                    OutlinedButton(
                        onClick = { onUpdateNow(settings.updateChannel) },
                        enabled = !engineState.isUpdating && !engineState.isInitializing,
                        shape = LocalMonolithButtonShape.current,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (engineState.isUpdating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Aktualisiere…")
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Jetzt aktualisieren")
                        }
                    }
                    OutlinedTextField(
                        value = settings.extraArgs,
                        onValueChange = { raw ->
                            DownloaderSettingsStore.update { it.copy(extraArgs = raw) }
                        },
                        label = { Text("Zusätzliche Argumente") },
                        placeholder = { Text("z. B. --proxy http://127.0.0.1:8080") },
                        maxLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    if (showDirectoryPicker) {
        TargetDirectoryPickerDialog(
            initialDirectory = displayedOutputDir,
            onDismiss = { showDirectoryPicker = false },
            onSelectDirectory = { pickedPath ->
                DownloaderSettingsStore.update { it.copy(outputDirectory = pickedPath) }
                showDirectoryPicker = false
            },
        )
    }

    if (showResetConfirm) {
        MonolithDialog(
            onDismissRequest = { showResetConfirm = false },
            title = "Downloader-Einstellungen zurücksetzen",
            dismissLabel = "Abbrechen",
            onDismissClick = { showResetConfirm = false },
            confirmLabel = "Zurücksetzen",
            onConfirmClick = {
                showResetConfirm = false
                DownloaderSettingsStore.resetToDefaults()
                scope.launch {
                    snackbarHostState.showSnackbar("Downloader-Einstellungen zurückgesetzt.")
                }
            },
        ) {
            Text(
                "Alle Downloader-Einstellungen werden auf ihre Standardwerte zurückgesetzt.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun DownloaderSettingsCategoryRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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

@Composable
private fun TargetDirectoryPickerDialog(
    initialDirectory: String,
    onDismiss: () -> Unit,
    onSelectDirectory: (String) -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val defaultRootDir = remember { Environment.getExternalStorageDirectory().absolutePath }
    val externalVolumes = rememberExternalStorageVolumes(context)

    var activeRoot by remember(initialDirectory, externalVolumes) {
        val matchingExternal = externalVolumes.firstOrNull { vol ->
            initialDirectory == vol.rootPath || initialDirectory.startsWith(vol.rootPath.trimEnd('/') + "/")
        }?.rootPath
        mutableStateOf(matchingExternal ?: defaultRootDir)
    }
    var currentDir by remember(initialDirectory) {
        val initial = File(initialDirectory)
        mutableStateOf(if (initial.isDirectory) initial.absolutePath else defaultRootDir)
    }
    var subDirs by remember { mutableStateOf<List<File>>(emptyList()) }
    var showCreateFolder by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var reloadTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(currentDir, reloadTick) {
        subDirs = withContext(Dispatchers.IO) {
            val dir = File(currentDir)
            if (!dir.isDirectory) return@withContext emptyList()
            dir.listFiles()
                ?.asSequence()
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.sortedBy { it.name.lowercase(Locale.ROOT) }
                ?.toList()
                ?: emptyList()
        }
    }

    val canGoUp = remember(currentDir, activeRoot) {
        val current = File(currentDir)
        currentDir != activeRoot && current.parentFile != null && currentDir.startsWith(activeRoot)
    }

    MonolithDialog(
        onDismissRequest = onDismiss,
        title = "Zielordner wählen",
        wide = true,
        dismissLabel = "Abbrechen",
        onDismissClick = onDismiss,
        confirmLabel = "Diesen Ordner wählen",
        onConfirmClick = { onSelectDirectory(currentDir) },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = activeRoot == defaultRootDir,
                shape = LocalMonolithButtonShape.current,
                onClick = {
                    MonolithHaptics.pathChipNav(context, view)
                    activeRoot = defaultRootDir
                    currentDir = defaultRootDir
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Storage,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
                label = { Text("Intern") },
            )
            externalVolumes.forEach { vol ->
                FilterChip(
                    selected = activeRoot == vol.rootPath,
                    shape = LocalMonolithButtonShape.current,
                    onClick = {
                        MonolithHaptics.pathChipNav(context, view)
                        activeRoot = vol.rootPath
                        currentDir = vol.rootPath
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = if (vol.kind == ExternalVolumeKind.USB) {
                                Icons.Outlined.Usb
                            } else {
                                Icons.Outlined.SdStorage
                            },
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    label = { Text(vol.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (canGoUp) {
                IconButton(
                    onClick = {
                        File(currentDir).parentFile?.let { currentDir = it.absolutePath }
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Übergeordneter Ordner")
                }
            }
            Text(
                text = if (currentDir == activeRoot) "/" else currentDir.removePrefix(activeRoot),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    newFolderName = ""
                    showCreateFolder = !showCreateFolder
                },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(Icons.Outlined.CreateNewFolder, contentDescription = "Neuer Ordner")
            }
        }

        AnimatedVisibility(visible = showCreateFolder) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("Ordnername") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        val trimmed = newFolderName.trim()
                        if (trimmed.isNotEmpty() && !trimmed.contains('/') && !trimmed.contains('\\')) {
                            val dir = File(currentDir, trimmed)
                            if (dir.mkdirs() || dir.isDirectory) {
                                currentDir = dir.absolutePath
                                showCreateFolder = false
                                reloadTick++
                            }
                        }
                    },
                ) {
                    Icon(Icons.Outlined.Check, contentDescription = "Erstellen")
                }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp, max = 320.dp),
            shape = CutCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            if (subDirs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Keine Unterordner",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                ) {
                    items(subDirs, key = { it.absolutePath }) { dir ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { currentDir = dir.absolutePath }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = dir.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Helpers
// -----------------------------------------------------------------------------

@Composable
private fun DialogSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        MonolithSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

private fun formatShortSummary(mode: DownloadMode, settings: DownloaderSettings): String {
    return when (mode) {
        DownloadMode.VIDEO -> {
            val base = when (settings.videoFormatPreset) {
                VideoFormatPreset.AUTO_VP9_H264 -> "Auto VP9/H.264"
                VideoFormatPreset.MP4_H264 -> "MP4 H.264"
                VideoFormatPreset.WEBM_VP9 -> "WebM VP9"
                VideoFormatPreset.BEST_ANY -> "Bestmöglich"
                VideoFormatPreset.CUSTOM -> "Benutzerdef."
            }
            val height = settings.videoMaxHeight?.let { " · ${it}p" }.orEmpty()
            base + height
        }
        DownloadMode.AUDIO -> {
            when (settings.audioFormatPreset) {
                AudioFormatPreset.M4A_BEST -> "M4A"
                AudioFormatPreset.OPUS_WEBM -> "WebM / Opus"
                AudioFormatPreset.MP3_CONVERT -> "MP3"
                AudioFormatPreset.FLAC_CONVERT -> "FLAC"
                AudioFormatPreset.BEST_ORIGINAL -> "Original"
                AudioFormatPreset.CUSTOM -> "Benutzerdef."
            }
        }
    }
}

private fun extractHostOrLabel(url: String): String {
    val host = runCatching { Uri.parse(url).host }.getOrNull()
    return host?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: url
}

private fun formatSecondsEta(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    val hours = safe / 3600L
    val mins = (safe % 3600L) / 60L
    val secs = safe % 60L
    return if (hours > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, mins, secs)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", mins, secs)
    }
}
