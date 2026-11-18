package com.monolith.feature.explorer.storage

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.monolith.core.common.AppSettingsStore
import com.monolith.core.model.FileCategory
import com.monolith.core.model.LocationType
import com.monolith.core.model.ThemeMode
import com.monolith.core.model.VfsNode
import com.monolith.core.ui.components.CornerMenuItem
import com.monolith.core.ui.components.MonolithCornerMenuOverlay
import com.monolith.core.ui.components.MonolithDialog
import com.monolith.core.ui.components.MonolithDialogOption
import com.monolith.core.ui.components.MonolithLoadingSpinner
import com.monolith.core.ui.haptics.MonolithHaptics
import com.monolith.core.ui.theme.LocalMonolithButtonShape
import com.monolith.core.ui.theme.LocalMonolithThemeMode
import com.monolith.core.vfs.LocalVfsProvider
import com.monolith.feature.explorer.ExternalVolumeKind
import com.monolith.feature.explorer.FileThumbnail
import com.monolith.feature.explorer.MonolithToolDestination
import com.monolith.feature.explorer.MonolithToolStarMenu
import com.monolith.feature.explorer.ai.MarkdownText
import com.monolith.feature.explorer.rememberExternalStorageVolumes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private enum class StorageTab(val title: String) {
    FOLDERS("Ordneransicht"),
    FILES("Dateiansicht"),
    AI("AI-Analyse"),
}

private enum class FileFilter(val label: String) {
    ALL("Alle"),
    DUPLICATES("Duplikate"),
    VIDEOS("Videos"),
    IMAGES("Bilder"),
    AUDIO("Audio"),
    ARCHIVES("Archive"),
    APKS("APKs"),
    DOCUMENTS("Dokumente"),
    OLD_FILES("Alt (> 6 Mon.)"),
    OTHER("Sonstige"),
}

private val STANDARD_FILE_FILTERS = listOf(
    FileFilter.ALL,
    FileFilter.DUPLICATES,
    FileFilter.VIDEOS,
    FileFilter.IMAGES,
    FileFilter.AUDIO,
    FileFilter.ARCHIVES,
    FileFilter.APKS,
    FileFilter.DOCUMENTS,
    FileFilter.OLD_FILES,
)

private const val SIX_MONTHS_MS = 180L * 86_400_000L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StorageAnalysisScreen(
    initialRootPath: String? = null,
    onBack: () -> Unit,
    onNavigateToFiles: () -> Unit,
    onOpenFolderInExplorer: (String) -> Unit,
    onNavigateToArchives: (List<String>) -> Unit,
    onNavigateToAi: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    AppSettingsStore.init(context)
    val appSettings by AppSettingsStore.settings.collectAsState()
    val apiKey = appSettings.activeGeminiApiKey.trim()

    val defaultInternalPath = remember { Environment.getExternalStorageDirectory().absolutePath }
    val externalVolumes = rememberExternalStorageVolumes(context)
    val localProvider = remember { LocalVfsProvider() }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val resolvedInitialRoot = remember(initialRootPath, defaultInternalPath) {
        initialRootPath?.takeIf { it.isNotBlank() && File(it).isDirectory } ?: defaultInternalPath
    }
    var activeRootPath by remember { mutableStateOf(resolvedInitialRoot) }
    var pathBackStack by remember { mutableStateOf<List<String>>(emptyList()) }
    var scanTrigger by remember { mutableIntStateOf(0) }

    var isScanning by remember { mutableStateOf(true) }
    var progressFiles by remember { mutableIntStateOf(0) }
    var progressBytes by remember { mutableLongStateOf(0L) }
    var progressCurrentDir by remember { mutableStateOf(resolvedInitialRoot) }
    var scanResult by remember { mutableStateOf<StorageScanResult?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }

    var selectedTab by remember { mutableStateOf(StorageTab.FOLDERS) }
    var summaryExpanded by remember { mutableStateOf(true) }
    var activeFileFilter by remember { mutableStateOf(FileFilter.ALL) }
    var selectedFilePaths by remember { mutableStateOf<Set<String>>(emptySet()) }

    var activeFolderDialogTarget by remember { mutableStateOf<AnalyzedFolderItem?>(null) }
    var folderDeleteConfirmTarget by remember { mutableStateOf<AnalyzedFolderItem?>(null) }
    var showFilesDeleteConfirm by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    var showSubfolderPickerDialog by remember { mutableStateOf(false) }
    var showTempCacheDialog by remember { mutableStateOf(false) }

    // AI Advisor state
    var selectedPreset by remember { mutableStateOf<StorageAiPreset?>(null) }
    var customQuestion by remember { mutableStateOf("") }
    var aiResponseText by remember { mutableStateOf("") }
    var aiErrorText by remember { mutableStateOf<String?>(null) }
    var isAiStreaming by remember { mutableStateOf(false) }
    var activeAiJob by remember { mutableStateOf<Job?>(null) }
    var showMissingKeyPopup by remember { mutableStateOf(false) }
    var showApiKeyDialog by remember { mutableStateOf(false) }

    // Menu overlays
    var starMenuExpanded by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var starAnchorCenter by remember { mutableStateOf(Offset.Unspecified) }
    var menuAnchorCenter by remember { mutableStateOf(Offset.Unspecified) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }

    val matchingVolume = remember(activeRootPath, defaultInternalPath, externalVolumes) {
        when {
            activeRootPath == defaultInternalPath || activeRootPath.startsWith("$defaultInternalPath/") -> null
            else -> externalVolumes.firstOrNull {
                activeRootPath == it.rootPath || activeRootPath.startsWith("${it.rootPath}/")
            }
        }
    }
    val baseVolumeRoot = matchingVolume?.rootPath ?: defaultInternalPath
    val baseVolumeLabel = matchingVolume?.name ?: "Intern"
    val isCustomSubfolder = activeRootPath != defaultInternalPath &&
        externalVolumes.none { it.rootPath == activeRootPath }

    fun applyRootPathChange(path: String) {
        selectedFilePaths = emptySet()
        activeAiJob?.cancel()
        activeAiJob = null
        isAiStreaming = false
        aiResponseText = ""
        aiErrorText = null
        activeRootPath = path
    }

    fun navigateToAnalysisRoot(path: String, pushToHistory: Boolean) {
        if (path == activeRootPath) return
        if (pushToHistory) {
            pathBackStack = pathBackStack + activeRootPath
        } else {
            pathBackStack = emptyList()
        }
        applyRootPathChange(path)
    }

    LaunchedEffect(activeRootPath, scanTrigger, baseVolumeLabel) {
        isScanning = true
        scanError = null
        progressFiles = 0
        progressBytes = 0L
        progressCurrentDir = activeRootPath
        val label = if (isCustomSubfolder) {
            File(activeRootPath).name.ifBlank { baseVolumeLabel }
        } else {
            baseVolumeLabel
        }
        try {
            val result = StorageAnalyzer.scanStorage(
                rootPath = activeRootPath,
                volumeLabel = label,
                maxResults = 200,
                onProgress = { files, bytes, dir ->
                    progressFiles = files
                    progressBytes = bytes
                    progressCurrentDir = dir
                },
            )
            scanResult = result
            selectedFilePaths = emptySet()
            isScanning = false
        } catch (e: CancellationException) {
            throw e
        } catch (err: Exception) {
            scanError = err.message ?: "Speicher konnte nicht analysiert werden."
            isScanning = false
        }
    }

    fun triggerAiAdvice(preset: StorageAiPreset?, question: String?) {
        val currentScan = scanResult ?: return
        if (isAiStreaming) return
        if (apiKey.isBlank()) {
            showMissingKeyPopup = true
            return
        }
        activeAiJob?.cancel()
        selectedPreset = preset
        aiErrorText = null
        aiResponseText = ""
        isAiStreaming = true
        val modelId = appSettings.aiModelId
        val sb = StringBuilder()
        val textLock = Any()
        activeAiJob = scope.launch {
            val res = StorageAiAdvisor.streamStorageAdvice(
                apiKey = apiKey,
                model = modelId,
                scan = currentScan,
                preset = preset,
                customQuestion = question,
                onTextDelta = { delta ->
                    synchronized(textLock) {
                        sb.append(delta)
                    }
                    scope.launch(Dispatchers.Main.immediate) {
                        aiResponseText = synchronized(textLock) { sb.toString() }
                    }
                },
            )
            res.onSuccess {
                if (aiResponseText.isBlank()) {
                    aiResponseText = "(Keine Antwort erhalten)"
                }
            }.onFailure { err ->
                if (err !is CancellationException) {
                    aiErrorText = err.message ?: "AI-Analyse fehlgeschlagen."
                }
            }
            isAiStreaming = false
            activeAiJob = null
        }
    }

    BackHandler(enabled = starMenuExpanded || menuExpanded) {
        starMenuExpanded = false
        menuExpanded = false
    }

    BackHandler(enabled = !starMenuExpanded && !menuExpanded && selectedFilePaths.isNotEmpty()) {
        selectedFilePaths = emptySet()
    }

    BackHandler(enabled = !starMenuExpanded && !menuExpanded && selectedFilePaths.isEmpty()) {
        MonolithHaptics.backNav(context, view)
        if (pathBackStack.isNotEmpty()) {
            val previous = pathBackStack.last()
            pathBackStack = pathBackStack.dropLast(1)
            applyRootPathChange(previous)
        } else {
            activeAiJob?.cancel()
            onBack()
        }
    }

    val backgroundRecede by animateFloatAsState(
        targetValue = if (starMenuExpanded) 1f else 0f,
        label = "storageStarMenuRecede",
    )
    val cornerMenuRecede by animateFloatAsState(
        targetValue = if (menuExpanded) 1f else 0f,
        label = "storageCornerMenuRecede",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { rootOrigin = it.positionInRoot() },
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    when {
                        backgroundRecede > 0.01f -> Modifier
                            .blur(10.dp * backgroundRecede)
                            .graphicsLayer {
                                val s = 1f - 0.025f * backgroundRecede
                                scaleX = s
                                scaleY = s
                            }
                        cornerMenuRecede > 0.01f -> Modifier.blur(4.dp * cornerMenuRecede)
                        else -> Modifier
                    },
                ),
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
                        Column {
                            Text(
                                text = "Speicherplatz",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (isCustomSubfolder) {
                                Text(
                                    text = File(activeRootPath).name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
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
                                imageVector = Icons.Outlined.ViewInAr,
                                contentDescription = "Monolith-Menü",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                if (!isScanning) scanTrigger++
                            },
                            enabled = !isScanning,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = "Neu scannen",
                            )
                        }
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
            bottomBar = {
                val result = scanResult
                if (selectedTab == StorageTab.FILES && selectedFilePaths.isNotEmpty() && result != null) {
                    val selectedItems = remember(result, selectedFilePaths) {
                        val allCandidates = LinkedHashMap<String, AnalyzedFileItem>()
                        result.largestFiles.forEach { allCandidates[it.path] = it }
                        result.duplicateGroups.flatMap { it.files }.forEach { allCandidates[it.path] = it }
                        selectedFilePaths.mapNotNull { allCandidates[it] }
                    }
                    val selectedBytes = selectedItems.sumOf { it.sizeBytes }
                    StorageSelectionActionBar(
                        count = selectedFilePaths.size,
                        totalBytes = selectedBytes,
                        enabled = !isDeleting,
                        onClear = { selectedFilePaths = emptySet() },
                        onPack = { onNavigateToArchives(selectedFilePaths.toList()) },
                        onDelete = { showFilesDeleteConfirm = true },
                    )
                }
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                // Volume & custom subfolder selector chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = activeRootPath == defaultInternalPath,
                        shape = LocalMonolithButtonShape.current,
                        onClick = { navigateToAnalysisRoot(defaultInternalPath, pushToHistory = false) },
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
                            selected = activeRootPath == vol.rootPath,
                            shape = LocalMonolithButtonShape.current,
                            onClick = { navigateToAnalysisRoot(vol.rootPath, pushToHistory = false) },
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
                            label = {
                                Text(
                                    text = vol.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                    if (isCustomSubfolder) {
                        FilterChip(
                            selected = true,
                            shape = LocalMonolithButtonShape.current,
                            onClick = { navigateToAnalysisRoot(baseVolumeRoot, pushToHistory = false) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.FolderOpen,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Zurücksetzen",
                                    modifier = Modifier.size(15.dp),
                                )
                            },
                            label = {
                                Text(
                                    text = File(activeRootPath).name.ifBlank { "Unterordner" },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }

                when {
                    isScanning -> {
                        ScanningProgressView(
                            filesCount = progressFiles,
                            scannedBytes = progressBytes,
                            currentDir = progressCurrentDir,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    scanError != null -> {
                        ScanErrorView(
                            message = scanError ?: "Unbekannter Fehler",
                            onRetry = { scanTrigger++ },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    scanResult != null -> {
                        val result = scanResult!!
                        StorageSummaryCard(
                            scan = result,
                            expanded = summaryExpanded,
                            onToggleExpand = { summaryExpanded = !summaryExpanded },
                            onCategoryClick = { category ->
                                activeFileFilter = fileFilterForCategory(category)
                                selectedTab = StorageTab.FILES
                            },
                            onInsightLargeFilesClick = {
                                activeFileFilter = FileFilter.ALL
                                selectedTab = StorageTab.FILES
                            },
                            onInsightDuplicatesClick = {
                                activeFileFilter = FileFilter.DUPLICATES
                                selectedTab = StorageTab.FILES
                            },
                            onInsightArchivesClick = {
                                activeFileFilter = FileFilter.ARCHIVES
                                selectedTab = StorageTab.FILES
                            },
                            onInsightTempClick = {
                                showTempCacheDialog = true
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                        )

                        SecondaryTabRow(
                            selectedTabIndex = selectedTab.ordinal,
                            containerColor = Color.Transparent,
                        ) {
                            StorageTab.entries.forEach { tab ->
                                Tab(
                                    selected = selectedTab == tab,
                                    onClick = { selectedTab = tab },
                                    text = {
                                        Text(
                                            text = tab.title,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                )
                            }
                        }

                        when (selectedTab) {
                            StorageTab.FOLDERS -> {
                                FolderBreakdownTab(
                                    folders = result.disjointTopFolders,
                                    onFolderClick = { folder -> activeFolderDialogTarget = folder },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            StorageTab.FILES -> {
                                FilesBreakdownTab(
                                    scan = result,
                                    activeFilter = activeFileFilter,
                                    selectedPaths = selectedFilePaths,
                                    onFilterChange = { newFilter ->
                                        activeFileFilter = newFilter
                                        selectedFilePaths = emptySet()
                                    },
                                    onToggleSelection = { path ->
                                        selectedFilePaths = if (path in selectedFilePaths) {
                                            selectedFilePaths - path
                                        } else {
                                            selectedFilePaths + path
                                        }
                                    },
                                    onSelectPaths = { paths, add ->
                                        selectedFilePaths = if (add) {
                                            selectedFilePaths + paths
                                        } else {
                                            selectedFilePaths - paths.toSet()
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            StorageTab.AI -> {
                                StorageAiTab(
                                    scan = result,
                                    apiKeyPresent = apiKey.isNotBlank(),
                                    activeModelId = appSettings.aiModelId,
                                    selectedPreset = selectedPreset,
                                    customQuestion = customQuestion,
                                    onCustomQuestionChange = { customQuestion = it },
                                    aiResponseText = aiResponseText,
                                    aiErrorText = aiErrorText,
                                    isStreaming = isAiStreaming,
                                    onRunPreset = { preset -> triggerAiAdvice(preset, null) },
                                    onSendCustom = {
                                        val q = customQuestion.trim()
                                        if (q.isNotEmpty()) {
                                            triggerAiAdvice(null, q)
                                        }
                                    },
                                    onStopStreaming = {
                                        activeAiJob?.cancel()
                                        activeAiJob = null
                                        isAiStreaming = false
                                    },
                                    onOpenApiKeyManager = { showApiKeyDialog = true },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }

        MonolithToolStarMenu(
            expanded = starMenuExpanded,
            anchorCenter = starAnchorCenter,
            activeDestination = MonolithToolDestination.STORAGE,
            onSelectFiles = {
                activeAiJob?.cancel()
                onNavigateToFiles()
            },
            onSelectArchives = {
                activeAiJob?.cancel()
                onNavigateToArchives(emptyList())
            },
            onSelectStorage = { starMenuExpanded = false },
            onSelectAi = {
                activeAiJob?.cancel()
                onNavigateToAi()
            },
            onDismiss = { starMenuExpanded = false },
        )

        MonolithCornerMenuOverlay(
            expanded = menuExpanded,
            anchorCenter = menuAnchorCenter,
            items = listOf(
                CornerMenuItem(
                    id = "rescan",
                    label = "Neu scannen",
                    onClick = { if (!isScanning) scanTrigger++ },
                ),
                CornerMenuItem(
                    id = "pick_subfolder",
                    label = "Unterordner analysieren…",
                    onClick = { showSubfolderPickerDialog = true },
                ),
                CornerMenuItem(
                    id = "api_keys",
                    label = "API-Verwaltung",
                    onClick = { showApiKeyDialog = true },
                ),
                CornerMenuItem(
                    id = "settings",
                    label = "Einstellungen",
                    onClick = onOpenSettings,
                ),
            ),
            onDismiss = { menuExpanded = false },
        )
    }

    // Folder action dialog
    activeFolderDialogTarget?.let { folder ->
        MonolithDialog(
            onDismissRequest = { activeFolderDialogTarget = null },
            title = folder.name,
            dismissLabel = "Schließen",
            onDismissClick = { activeFolderDialogTarget = null },
        ) {
            Text(
                text = folder.relativePath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${formatStorageBytes(folder.totalBytes)} · ${folder.fileCount} Dateien · ${folder.subfolderCount} Unterordner",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (folder.dominantExtensions.isNotEmpty()) {
                Text(
                    text = "Häufige Endungen: ${folder.dominantExtensions.joinToString(", ") { it.uppercase(Locale.ROOT) }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

            FolderActionButton(
                label = "Im Explorer öffnen",
                onClick = {
                    activeFolderDialogTarget = null
                    onOpenFolderInExplorer(folder.path)
                },
            )
            FolderActionButton(
                label = "Tiefer analysieren",
                onClick = {
                    activeFolderDialogTarget = null
                    navigateToAnalysisRoot(folder.path, pushToHistory = true)
                },
            )
            FolderActionButton(
                label = "Als Archiv packen",
                onClick = {
                    activeFolderDialogTarget = null
                    onNavigateToArchives(listOf(folder.path))
                },
            )
            OutlinedButton(
                onClick = {
                    activeFolderDialogTarget = null
                    folderDeleteConfirmTarget = folder
                },
                shape = LocalMonolithButtonShape.current,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.7f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Ordner löschen")
            }
        }
    }

    // Confirm single folder deletion
    folderDeleteConfirmTarget?.let { folder ->
        MonolithDialog(
            onDismissRequest = { if (!isDeleting) folderDeleteConfirmTarget = null },
            title = "Ordner löschen",
            dismissLabel = "Abbrechen",
            dismissEnabled = !isDeleting,
            onDismissClick = { folderDeleteConfirmTarget = null },
            confirmLabel = if (isDeleting) "Lösche…" else "Unwiderruflich löschen",
            confirmEnabled = !isDeleting,
            onConfirmClick = {
                isDeleting = true
                scope.launch {
                    val res = localProvider.delete(listOf(folder.path))
                    isDeleting = false
                    folderDeleteConfirmTarget = null
                    res.onSuccess {
                        snackbarHostState.showSnackbar("„${folder.name}“ gelöscht.")
                        scanTrigger++
                    }.onFailure { err ->
                        snackbarHostState.showSnackbar(err.message ?: "Löschen fehlgeschlagen.")
                    }
                }
            },
        ) {
            Text(
                "Möchtest du „${folder.name}“ (${formatStorageBytes(folder.totalBytes)}, ${folder.fileCount} Dateien) wirklich unwiderruflich löschen?",
            )
        }
    }

    // Confirm multi-file deletion
    if (showFilesDeleteConfirm) {
        val pathsToDelete = selectedFilePaths.toList()
        MonolithDialog(
            onDismissRequest = { if (!isDeleting) showFilesDeleteConfirm = false },
            title = "Dateien löschen",
            dismissLabel = "Abbrechen",
            dismissEnabled = !isDeleting,
            onDismissClick = { showFilesDeleteConfirm = false },
            confirmLabel = if (isDeleting) "Lösche…" else "Löschen",
            confirmEnabled = !isDeleting && pathsToDelete.isNotEmpty(),
            onConfirmClick = {
                isDeleting = true
                scope.launch {
                    val res = localProvider.delete(pathsToDelete)
                    isDeleting = false
                    showFilesDeleteConfirm = false
                    res.onSuccess {
                        selectedFilePaths = emptySet()
                        snackbarHostState.showSnackbar("${pathsToDelete.size} Datei(en) gelöscht.")
                        scanTrigger++
                    }.onFailure { err ->
                        snackbarHostState.showSnackbar(err.message ?: "Löschen fehlgeschlagen.")
                    }
                }
            },
        ) {
            Text("${pathsToDelete.size} ausgewählte Datei(en) wirklich unwiderruflich löschen?")
        }
    }

    // Subfolder picker dialog
    if (showSubfolderPickerDialog) {
        SubfolderPickerDialog(
            initialDir = activeRootPath,
            rootFloorPath = baseVolumeRoot,
            onDismiss = { showSubfolderPickerDialog = false },
            onSelectFolder = { chosenPath ->
                showSubfolderPickerDialog = false
                navigateToAnalysisRoot(chosenPath, pushToHistory = chosenPath != activeRootPath)
            },
        )
    }

    if (showTempCacheDialog) {
        val currentScan = scanResult
        MonolithDialog(
            onDismissRequest = { showTempCacheDialog = false },
            title = "Temp, Cache & Logs",
            dismissLabel = "Schließen",
            onDismissClick = { showTempCacheDialog = false },
            confirmLabel = "AI-Tipps fragen",
            confirmEnabled = currentScan != null,
            onConfirmClick = {
                showTempCacheDialog = false
                selectedTab = StorageTab.AI
                triggerAiAdvice(StorageAiPreset.CLEANUP_RECOMMENDATIONS, null)
            },
        ) {
            if (currentScan != null) {
                Text(
                    text = "${currentScan.tempAndCacheCount} Dateien · ${formatStorageBytes(currentScan.tempAndCacheBytes)}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = "Erfasst wurden temporäre und Protokoll-Dateien (.tmp, .temp, .bak, .log, .cache) sowie Dateien in cache-/tmp-Ordnern.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // Missing API key prompt
    if (showMissingKeyPopup) {
        MonolithDialog(
            onDismissRequest = { showMissingKeyPopup = false },
            title = "Gemini-API-Key fehlt",
            dismissLabel = "Abbrechen",
            onDismissClick = { showMissingKeyPopup = false },
            confirmLabel = "API-Verwaltung öffnen",
            onConfirmClick = {
                showMissingKeyPopup = false
                showApiKeyDialog = true
            },
        ) {
            Text(
                "Für die Speicher-AI-Analyse wird ein Gemini-API-Key benötigt. Möchtest du jetzt die API-Verwaltung öffnen?",
            )
        }
    }

    // Inline API Key Management Dialog (matching AiChatScreen)
    if (showApiKeyDialog) {
        var newLabel by remember { mutableStateOf("") }
        var newKey by remember { mutableStateOf("") }
        var showNewKey by remember { mutableStateOf(false) }

        MonolithDialog(
            onDismissRequest = { showApiKeyDialog = false },
            title = "API-Verwaltung",
            confirmLabel = "Schließen",
            onConfirmClick = { showApiKeyDialog = false },
        ) {
            if (appSettings.geminiApiKeys.isEmpty()) {
                Text(
                    text = "Noch keine API-Keys gespeichert.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                appSettings.geminiApiKeys.forEach { entry ->
                    val isSelected = entry.id == appSettings.activeGeminiApiKeyId
                    MonolithDialogOption(
                        label = entry.label,
                        subtitle = maskStorageApiKey(entry.key),
                        selected = isSelected,
                        onClick = { AppSettingsStore.selectGeminiApiKey(entry.id) },
                        trailing = {
                            IconButton(
                                onClick = { AppSettingsStore.removeGeminiApiKey(entry.id) },
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteOutline,
                                    contentDescription = "Key entfernen",
                                )
                            }
                        },
                    )
                }
            }

            HorizontalDivider(thickness = 0.5.dp)

            Text(
                text = "Neuen Key hinzufügen",
                style = MaterialTheme.typography.labelLarge,
            )

            OutlinedTextField(
                value = newLabel,
                onValueChange = { newLabel = it },
                label = { Text("Bezeichnung (optional)") },
                placeholder = { Text("z. B. Privat, Arbeit") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = newKey,
                onValueChange = { newKey = it },
                label = { Text("Gemini-API-Key") },
                placeholder = { Text("AIza…") },
                singleLine = true,
                visualTransformation = if (showNewKey) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { showNewKey = !showNewKey }) {
                        Icon(
                            imageVector = if (showNewKey) {
                                Icons.Outlined.VisibilityOff
                            } else {
                                Icons.Outlined.Visibility
                            },
                            contentDescription = if (showNewKey) "Verbergen" else "Anzeigen",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    AppSettingsStore.addGeminiApiKey(newLabel, newKey)
                    newLabel = ""
                    newKey = ""
                },
                enabled = newKey.isNotBlank(),
                shape = LocalMonolithButtonShape.current,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Hinzufügen & aktivieren")
            }
        }
    }
}

@Composable
private fun FolderActionButton(
    label: String,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        shape = LocalMonolithButtonShape.current,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label)
    }
}

@Composable
private fun ScanningProgressView(
    filesCount: Int,
    scannedBytes: Long,
    currentDir: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MonolithLoadingSpinner()
        Spacer(modifier = Modifier.height(36.dp))
        Text(
            text = "Speicher wird analysiert…",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "$filesCount Dateien · ${formatStorageBytes(scannedBytes)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = currentDir,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ScanErrorView(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = "Analyse fehlgeschlagen",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onRetry,
            shape = LocalMonolithButtonShape.current,
        ) {
            Text("Erneut scannen")
        }
    }
}

@Composable
private fun StorageSummaryCard(
    scan: StorageScanResult,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onCategoryClick: (FileCategory) -> Unit,
    onInsightLargeFilesClick: () -> Unit,
    onInsightDuplicatesClick: () -> Unit,
    onInsightArchivesClick: () -> Unit,
    onInsightTempClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stats = scan.volumeStats
    val totalCap = stats.totalCapacityBytes
    val usedFraction = if (totalCap > 0L) {
        (stats.usedCapacityBytes.toDouble() / totalCap.toDouble()).toFloat().coerceIn(0f, 1f)
    } else if (scan.scannedBytes > 0L) {
        1f
    } else {
        0f
    }

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Top header row: Capacity summary + collapse toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    val capLine = if (totalCap > 0L) {
                        "${formatStorageBytes(stats.usedCapacityBytes)} / ${formatStorageBytes(totalCap)} belegt"
                    } else {
                        "${formatStorageBytes(scan.scannedBytes)} gescannt"
                    }
                    Text(
                        text = capLine,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subLine = buildString {
                        if (totalCap > 0L) {
                            append("${formatStorageBytes(stats.freeCapacityBytes)} frei · ")
                        }
                        append("${scan.totalFilesCount} Dateien · ${scan.totalFoldersCount} Ordner")
                    }
                    Text(
                        text = subLine,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Einklappen" else "Ausklappen",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }

            // Segmented category bar (always visible even when collapsed for instant orientation)
            SegmentedCategoryBar(
                breakdowns = scan.categoryBreakdowns,
                usedFraction = usedFraction,
                scannedBytes = scan.scannedBytes,
                totalCapacityBytes = totalCap,
            )

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (scan.categoryBreakdowns.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            scan.categoryBreakdowns.forEach { item ->
                                val dotColor = categoryColor(item.category)
                                Surface(
                                    shape = CutCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    border = BorderStroke(
                                        0.8.dp,
                                        MaterialTheme.colorScheme.outlineVariant,
                                    ),
                                    onClick = { onCategoryClick(item.category) },
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(dotColor),
                                        )
                                        Text(
                                            text = "${item.label} ${formatStorageBytes(item.totalBytes)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 4 quick insight tiles
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        InsightTile(
                            title = ">100 MB",
                            value = formatStorageBytes(scan.largeFilesOver100MbBytes),
                            subtitle = "${scan.largeFilesOver100MbCount} Dat.",
                            onClick = onInsightLargeFilesClick,
                            modifier = Modifier.weight(1f),
                        )
                        InsightTile(
                            title = "Duplikate",
                            value = formatStorageBytes(scan.reclaimableDuplicatesBytes),
                            subtitle = "${scan.duplicateGroups.size} Grp.",
                            highlight = scan.reclaimableDuplicatesBytes > 0L,
                            onClick = onInsightDuplicatesClick,
                            modifier = Modifier.weight(1f),
                        )
                        InsightTile(
                            title = "Arch./APK",
                            value = formatStorageBytes(scan.archiveAndApkBytes),
                            subtitle = "${scan.archiveAndApkCount} Dat.",
                            onClick = onInsightArchivesClick,
                            modifier = Modifier.weight(1f),
                        )
                        InsightTile(
                            title = "Temp/Cache",
                            value = formatStorageBytes(scan.tempAndCacheBytes),
                            subtitle = "${scan.tempAndCacheCount} Dat.",
                            onClick = onInsightTempClick,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentedCategoryBar(
    breakdowns: List<CategoryBreakdown>,
    usedFraction: Float,
    scannedBytes: Long,
    totalCapacityBytes: Long,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(CutCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (breakdowns.isNotEmpty() && scannedBytes > 0L) {
            val scaleFactor = if (totalCapacityBytes > 0L) {
                (scannedBytes.toDouble() / totalCapacityBytes.toDouble()).toFloat().coerceIn(0.05f, usedFraction.coerceAtLeast(0.05f))
            } else {
                1f
            }
            val segments = breakdowns.filter { it.fractionOfScanned > 0.004f }
            val systemOrOtherUsedFraction = if (totalCapacityBytes > 0L) {
                (usedFraction - scaleFactor).coerceAtLeast(0f)
            } else {
                0f
            }
            Row(modifier = Modifier.fillMaxSize()) {
                segments.forEach { segment ->
                    val w = (segment.fractionOfScanned * scaleFactor).coerceAtLeast(0.005f)
                    Box(
                        modifier = Modifier
                            .weight(w)
                            .fillMaxHeight()
                            .background(categoryColor(segment.category)),
                    )
                }
                if (systemOrOtherUsedFraction > 0.005f) {
                    Box(
                        modifier = Modifier
                            .weight(systemOrOtherUsedFraction)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                    )
                }
                val freeWeight = (1f - usedFraction).coerceAtLeast(0f)
                if (freeWeight > 0.005f) {
                    Spacer(modifier = Modifier.weight(freeWeight))
                }
            }
        } else if (usedFraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(usedFraction)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun InsightTile(
    title: String,
    value: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
) {
    Surface(
        modifier = modifier,
        shape = CutCornerShape(6.dp),
        color = if (highlight) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        border = BorderStroke(
            0.8.dp,
            if (highlight) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FolderBreakdownTab(
    folders: List<AnalyzedFolderItem>,
    onFolderClick: (AnalyzedFolderItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (folders.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Keine Unterordner mit Dateien gefunden.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "disjoint_note") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "Bereinigte Ansicht ohne Über-/Unterordner",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        itemsIndexed(folders, key = { _, item -> item.path }) { index, folder ->
            FolderRow(
                rank = index + 1,
                folder = folder,
                onClick = { onFolderClick(folder) },
            )
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

@Composable
private fun FolderRow(
    rank: Int,
    folder: AnalyzedFolderItem,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                shape = CutCornerShape(5.dp),
                color = if (rank <= 3) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
            ) {
                Text(
                    text = "#$rank",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (rank <= 3) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }

            Icon(
                imageVector = Icons.Outlined.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = folder.relativePath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val extText = folder.dominantExtensions.take(3).joinToString(" · ") {
                    it.uppercase(Locale.ROOT)
                }
                val meta = if (extText.isNotEmpty()) {
                    "${folder.fileCount} Dateien · $extText"
                } else {
                    "${folder.fileCount} Dateien"
                }
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatStorageBytes(folder.totalBytes),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = String.format(Locale.GERMAN, "%.1f %%", folder.fractionOfScanned * 100f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(CutCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(folder.fractionOfScanned.coerceIn(0.01f, 1f))
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun FilesBreakdownTab(
    scan: StorageScanResult,
    activeFilter: FileFilter,
    selectedPaths: Set<String>,
    onFilterChange: (FileFilter) -> Unit,
    onToggleSelection: (String) -> Unit,
    onSelectPaths: (List<String>, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val nowMs = remember(scan) { System.currentTimeMillis() }
    val filteredFiles = remember(scan, activeFilter, nowMs) {
        when (activeFilter) {
            FileFilter.ALL -> scan.largestFiles
            FileFilter.DUPLICATES -> emptyList()
            FileFilter.VIDEOS -> scan.largestFiles.filter { it.category == FileCategory.VIDEO }
            FileFilter.IMAGES -> scan.largestFiles.filter { it.category == FileCategory.IMAGE }
            FileFilter.AUDIO -> scan.largestFiles.filter { it.category == FileCategory.AUDIO }
            FileFilter.ARCHIVES -> scan.largestFiles.filter { it.category == FileCategory.ARCHIVE }
            FileFilter.APKS -> scan.largestFiles.filter { it.category == FileCategory.APK }
            FileFilter.DOCUMENTS -> scan.largestFiles.filter {
                it.category == FileCategory.DOCUMENT || it.category == FileCategory.TEXT
            }
            FileFilter.OLD_FILES -> scan.largestFiles.filter {
                it.lastModifiedMs > 0L && (nowMs - it.lastModifiedMs) > SIX_MONTHS_MS
            }
            FileFilter.OTHER -> scan.largestFiles.filter { it.category == FileCategory.OTHER }
        }
    }

    val visibleChips = remember(activeFilter) {
        if (activeFilter == FileFilter.OTHER) {
            STANDARD_FILE_FILTERS + FileFilter.OTHER
        } else {
            STANDARD_FILE_FILTERS
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            visibleChips.forEach { filter ->
                val label = if (filter == FileFilter.DUPLICATES && scan.duplicateGroups.isNotEmpty()) {
                    "Duplikate (${scan.duplicateGroups.size})"
                } else {
                    filter.label
                }
                FilterChip(
                    selected = activeFilter == filter,
                    shape = LocalMonolithButtonShape.current,
                    onClick = { onFilterChange(filter) },
                    label = { Text(label) },
                )
            }
        }

        if (activeFilter == FileFilter.DUPLICATES) {
            if (scan.duplicateGroups.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Keine Duplikate (≥ 512 KB) gefunden.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = if (selectedPaths.isNotEmpty()) 84.dp else 24.dp),
                ) {
                    items(scan.duplicateGroups, key = { it.key }) { group ->
                        DuplicateGroupCard(
                            group = group,
                            selectedPaths = selectedPaths,
                            nowMs = nowMs,
                            onToggleSelection = onToggleSelection,
                            onSelectCopies = {
                                val copies = group.files.drop(1).map { it.path }
                                val allCopiesSelected = copies.isNotEmpty() && copies.all { it in selectedPaths }
                                onSelectPaths(copies, !allCopiesSelected)
                            },
                        )
                    }
                }
            }
        } else if (filteredFiles.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Keine Dateien für diesen Filter gefunden.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val allVisiblePaths = remember(filteredFiles) { filteredFiles.map { it.path } }
            val allSelected = allVisiblePaths.isNotEmpty() && allVisiblePaths.all { it in selectedPaths }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${filteredFiles.size} größte Dateien",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = { onSelectPaths(allVisiblePaths, !allSelected) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.SelectAll,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (allSelected) "Keine" else "Alle wählen")
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = if (selectedPaths.isNotEmpty()) 84.dp else 24.dp),
            ) {
                items(filteredFiles, key = { it.path }) { item ->
                    AnalyzedFileRow(
                        item = item,
                        isSelected = item.path in selectedPaths,
                        nowMs = nowMs,
                        onToggle = { onToggleSelection(item.path) },
                    )
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DuplicateGroupCard(
    group: DuplicateGroup,
    selectedPaths: Set<String>,
    nowMs: Long,
    onToggleSelection: (String) -> Unit,
    onSelectCopies: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.fileName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${group.files.size}× ${formatStorageBytes(group.eachSizeBytes)} · Ersparnis: ${formatStorageBytes(group.reclaimableBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                TextButton(onClick = onSelectCopies) {
                    Text("Kopien wählen")
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            group.files.forEachIndexed { idx, file ->
                AnalyzedFileRow(
                    item = file,
                    isSelected = file.path in selectedPaths,
                    nowMs = nowMs,
                    badgeOverride = if (idx == 0) "Original/Neueste" else "Kopie",
                    onToggle = { onToggleSelection(file.path) },
                )
                if (idx < group.files.lastIndex) {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AnalyzedFileRow(
    item: AnalyzedFileItem,
    isSelected: Boolean,
    nowMs: Long,
    onToggle: () -> Unit,
    badgeOverride: String? = null,
) {
    val node = remember(item) {
        VfsNode(
            id = item.path,
            name = item.name,
            path = item.path,
            parentPath = item.path.substringBeforeLast('/', ""),
            locationType = LocationType.LOCAL,
            isDirectory = false,
            sizeBytes = item.sizeBytes,
            lastModifiedMs = item.lastModifiedMs,
            mimeType = item.mimeType,
            category = item.category,
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isSelected) {
                    Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f))
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onToggle)
            .padding(start = 6.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggle() },
        )

        FileThumbnail(
            node = node,
            fallbackIcon = categoryIcon(item.category),
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.relativePath,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val ageText = formatAgeDays(item.lastModifiedMs, nowMs)
            val badge = badgeOverride ?: if (item.isDuplicateCandidate) "Duplikat" else null
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "${formatStorageBytes(item.sizeBytes)}${if (ageText != null) " · $ageText" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (badge != null) {
                    Surface(
                        shape = CutCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageSelectionActionBar(
    count: Int,
    totalBytes: Long,
    enabled: Boolean,
    onClear: () -> Unit,
    onPack: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 6.dp,
    ) {
        Column {
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = onClear, enabled = enabled) {
                    Icon(Icons.Default.Close, contentDescription = "Auswahl aufheben")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "$count ausgewählt",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = formatStorageBytes(totalBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                OutlinedButton(
                    onClick = onPack,
                    enabled = enabled,
                    shape = LocalMonolithButtonShape.current,
                ) {
                    Text("Packen")
                }
                Button(
                    onClick = onDelete,
                    enabled = enabled,
                    shape = LocalMonolithButtonShape.current,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("Löschen")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StorageAiTab(
    scan: StorageScanResult,
    apiKeyPresent: Boolean,
    activeModelId: String,
    selectedPreset: StorageAiPreset?,
    customQuestion: String,
    onCustomQuestionChange: (String) -> Unit,
    aiResponseText: String,
    aiErrorText: String?,
    isStreaming: Boolean,
    onRunPreset: (StorageAiPreset) -> Unit,
    onSendCustom: () -> Unit,
    onStopStreaming: () -> Unit,
    onOpenApiKeyManager: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (_, estimatedTokens) = remember(scan) { StorageAiAdvisor.buildCompactDigest(scan) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Compact token estimate badge
            Surface(
                shape = CutCornerShape(6.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "~$estimatedTokens Input-Tokens · Kompakt-Digest (ohne Tool-Schleifen)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = activeModelId.removePrefix("gemini-"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // 3 preset action chips
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StorageAiPreset.entries.forEach { preset ->
                    val chipLabel = when (preset) {
                        StorageAiPreset.CLEANUP_RECOMMENDATIONS -> "Aufräum-Tipps"
                        StorageAiPreset.EXPLAIN_FOLDERS -> "Ordner erklären"
                        StorageAiPreset.ARCHIVE_SUGGESTIONS -> "Archiv-Potenzial"
                    }
                    FilterChip(
                        selected = selectedPreset == preset,
                        enabled = !isStreaming,
                        shape = LocalMonolithButtonShape.current,
                        onClick = { onRunPreset(preset) },
                        label = { Text(chipLabel) },
                    )
                }
            }

            if (!apiKeyPresent) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Gemini-API-Key erforderlich",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Hinterlege einen Gemini-API-Key, um personalisierte Speicher-Empfehlungen auf Basis des Kompakt-Digests abzurufen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = onOpenApiKeyManager,
                            shape = LocalMonolithButtonShape.current,
                        ) {
                            Text("API-Verwaltung öffnen")
                        }
                    }
                }
            }

            if (aiErrorText != null) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = aiErrorText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            if (isStreaming && aiResponseText.isBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(vertical = 8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = "Analysiere Kompakt-Digest…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (aiResponseText.isNotEmpty()) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SelectionContainer {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MarkdownText(
                                text = aiResponseText,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (isStreaming) {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(2.dp),
                                )
                            }
                        }
                    }
                }
            } else if (!isStreaming && apiKeyPresent && aiErrorText == null) {
                Text(
                    text = "Wähle einen Schnell-Chip oben oder stelle unten eine kurze Frage zu deinem Speicher.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Compact custom question bar
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = customQuestion,
                onValueChange = onCustomQuestionChange,
                placeholder = { Text("Frage zum Speicher…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = { if (!isStreaming) onSendCustom() },
                ),
                modifier = Modifier.weight(1f),
            )
            if (isStreaming) {
                FilledIconButton(
                    onClick = onStopStreaming,
                    shape = LocalMonolithButtonShape.current,
                    modifier = Modifier.size(44.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Icon(Icons.Outlined.Stop, contentDescription = "Abbrechen")
                }
            } else {
                FilledIconButton(
                    onClick = onSendCustom,
                    enabled = customQuestion.isNotBlank(),
                    shape = LocalMonolithButtonShape.current,
                    modifier = Modifier.size(44.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Senden")
                }
            }
        }
    }
}

@Composable
private fun SubfolderPickerDialog(
    initialDir: String,
    rootFloorPath: String,
    onDismiss: () -> Unit,
    onSelectFolder: (String) -> Unit,
) {
    var browseDir by remember(initialDir) { mutableStateOf(initialDir) }
    var subdirs by remember { mutableStateOf<List<File>>(emptyList()) }

    LaunchedEffect(browseDir) {
        subdirs = withContext(Dispatchers.IO) {
            val dir = File(browseDir)
            dir.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.sortedBy { it.name.lowercase(Locale.ROOT) }
                .orEmpty()
        }
    }

    val canGoUp = browseDir != rootFloorPath && browseDir.startsWith("$rootFloorPath/")

    MonolithDialog(
        onDismissRequest = onDismiss,
        title = "Unterordner wählen",
        wide = true,
        dismissLabel = "Abbrechen",
        onDismissClick = onDismiss,
        confirmLabel = "Analysieren",
        onConfirmClick = { onSelectFolder(browseDir) },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (canGoUp) {
                IconButton(
                    onClick = {
                        val parent = File(browseDir).parentFile?.absolutePath ?: rootFloorPath
                        browseDir = if (parent.length >= rootFloorPath.length) parent else rootFloorPath
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Nach oben",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Text(
                text = browseDir,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

        if (subdirs.isEmpty()) {
            Text(
                text = "Keine weiteren Unterordner.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            subdirs.forEach { folder ->
                Surface(
                    shape = CutCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
                    onClick = { browseDir = folder.absolutePath },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = folder.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun fileFilterForCategory(category: FileCategory): FileFilter = when (category) {
    FileCategory.VIDEO -> FileFilter.VIDEOS
    FileCategory.IMAGE -> FileFilter.IMAGES
    FileCategory.AUDIO -> FileFilter.AUDIO
    FileCategory.ARCHIVE -> FileFilter.ARCHIVES
    FileCategory.APK -> FileFilter.APKS
    FileCategory.DOCUMENT, FileCategory.TEXT -> FileFilter.DOCUMENTS
    FileCategory.OTHER -> FileFilter.OTHER
    FileCategory.DIRECTORY -> FileFilter.ALL
}

private fun categoryColor(category: FileCategory): Color = when (category) {
    FileCategory.VIDEO -> Color(0xFFEF5350)
    FileCategory.IMAGE -> Color(0xFF42A5F5)
    FileCategory.AUDIO -> Color(0xFFAB47BC)
    FileCategory.ARCHIVE -> Color(0xFFFFA726)
    FileCategory.APK -> Color(0xFF26A69A)
    FileCategory.DOCUMENT -> Color(0xFF66BB6A)
    FileCategory.TEXT -> Color(0xFF8D6E63)
    FileCategory.OTHER, FileCategory.DIRECTORY -> Color(0xFF78909C)
}

private fun categoryIcon(category: FileCategory): ImageVector = when (category) {
    FileCategory.DIRECTORY -> Icons.Outlined.Folder
    FileCategory.VIDEO -> Icons.Outlined.VideoFile
    FileCategory.AUDIO -> Icons.Outlined.AudioFile
    FileCategory.IMAGE -> Icons.Outlined.Image
    FileCategory.ARCHIVE -> Icons.Outlined.FolderZip
    FileCategory.APK -> Icons.Outlined.Android
    FileCategory.TEXT, FileCategory.DOCUMENT -> Icons.Outlined.Description
    FileCategory.OTHER -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

private fun formatAgeDays(lastModifiedMs: Long, nowMs: Long): String? {
    if (lastModifiedMs <= 0L || nowMs < lastModifiedMs) return null
    val days = (nowMs - lastModifiedMs) / 86_400_000L
    return when {
        days < 1L -> "Heute"
        days < 30L -> "vor ${days}T"
        days < 365L -> "vor ${days / 30L} Mon."
        else -> "vor ${days / 365L} J."
    }
}

private fun maskStorageApiKey(key: String): String {
    val trimmed = key.trim()
    if (trimmed.length <= 10) return "••••••••"
    return "${trimmed.take(6)}••••${trimmed.takeLast(4)}"
}
