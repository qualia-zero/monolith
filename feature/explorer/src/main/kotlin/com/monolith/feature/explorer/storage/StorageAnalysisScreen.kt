package com.monolith.feature.explorer.storage

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.monolith.feature.explorer.rememberExternalStorageVolumes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

private enum class StorageTab(val title: String) {
    FOLDERS("Ordner"),
    FILES("Dateien"),
}

private enum class FolderSortBy(val label: String) {
    SIZE("Größe"),
    NAME("Name"),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageAnalysisScreen(
    initialRootPath: String? = null,
    onBack: () -> Unit,
    onNavigateToFiles: () -> Unit,
    onOpenFolderInExplorer: (String) -> Unit,
    onNavigateToArchives: (List<String>) -> Unit,
    onNavigateToDownloader: () -> Unit,
    onNavigateToAi: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current

    val defaultInternalPath = remember { Environment.getExternalStorageDirectory().absolutePath }
    val externalVolumes = rememberExternalStorageVolumes(context)
    val localProvider = remember { LocalVfsProvider() }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val normalizedInitialRoot = remember(initialRootPath) {
        initialRootPath?.takeIf { it.isNotBlank() }?.let { File(it).absolutePath }
    }
    val matchingExternalForInitial = remember(normalizedInitialRoot, externalVolumes) {
        normalizedInitialRoot?.let { target ->
            externalVolumes.firstOrNull { vol -> isSameOrDescendant(target, vol.rootPath) }
        }
    }
    val initialVolumeRoot = matchingExternalForInitial?.rootPath ?: defaultInternalPath
    val initialSubfolderTarget = remember(normalizedInitialRoot, initialVolumeRoot) {
        val target = normalizedInitialRoot
        if (
            target != null &&
            target != initialVolumeRoot &&
            isSameOrDescendant(target, initialVolumeRoot) &&
            File(target).isDirectory
        ) {
            target
        } else {
            null
        }
    }

    var userSelectedVolume by remember(initialRootPath) { mutableStateOf(false) }
    var activeVolumeRoot by remember(initialRootPath) { mutableStateOf(initialVolumeRoot) }
    var currentBrowsePath by remember(initialRootPath) {
        mutableStateOf(initialSubfolderTarget ?: initialVolumeRoot)
    }
    var pendingTargetPath by remember(initialRootPath) { mutableStateOf(initialSubfolderTarget) }
    var scanTrigger by remember { mutableIntStateOf(0) }

    var isScanning by remember { mutableStateOf(true) }
    var progressFiles by remember { mutableIntStateOf(0) }
    var progressBytes by remember { mutableLongStateOf(0L) }
    var progressCurrentDir by remember { mutableStateOf(initialVolumeRoot) }
    var scanResult by remember { mutableStateOf<StorageScanResult?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }

    var selectedTab by remember { mutableStateOf(StorageTab.FOLDERS) }
    var folderSortBy by remember { mutableStateOf(FolderSortBy.SIZE) }
    var showHidden by remember { mutableStateOf(true) }
    var showEmptyFolders by remember { mutableStateOf(false) }
    var summaryExpanded by remember { mutableStateOf(false) }
    var activeFileFilter by remember { mutableStateOf(FileFilter.ALL) }
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }

    var showSortDialog by remember { mutableStateOf(false) }
    var inspectedFileItem by remember { mutableStateOf<AnalyzedFileItem?>(null) }
    var singleFileDeleteTarget by remember { mutableStateOf<AnalyzedFileItem?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }

    var starMenuExpanded by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var starAnchorCenter by remember { mutableStateOf(Offset.Unspecified) }
    var menuAnchorCenter by remember { mutableStateOf(Offset.Unspecified) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }

    val activeVolumeLabel = remember(activeVolumeRoot, externalVolumes) {
        externalVolumes.firstOrNull { it.rootPath == activeVolumeRoot }?.name ?: "Intern"
    }

    LaunchedEffect(initialVolumeRoot, initialSubfolderTarget) {
        if (!userSelectedVolume && activeVolumeRoot != initialVolumeRoot) {
            activeVolumeRoot = initialVolumeRoot
            currentBrowsePath = initialSubfolderTarget ?: initialVolumeRoot
            pendingTargetPath = initialSubfolderTarget
            selectedPaths = emptySet()
        }
    }

    LaunchedEffect(activeVolumeRoot, scanTrigger) {
        isScanning = true
        scanError = null
        progressFiles = 0
        progressBytes = 0L
        progressCurrentDir = activeVolumeRoot
        try {
            val result = StorageAnalyzer.scanStorage(
                rootPath = activeVolumeRoot,
                volumeLabel = activeVolumeLabel,
                maxResults = 200,
                onProgress = { files, bytes, dir ->
                    progressFiles = files
                    progressBytes = bytes
                    progressCurrentDir = dir
                },
            )
            scanResult = result
            selectedPaths = emptySet()
            val desiredPath = pendingTargetPath ?: currentBrowsePath
            pendingTargetPath = null
            currentBrowsePath = resolveBestExistingPath(desiredPath, result)
            isScanning = false
        } catch (e: CancellationException) {
            throw e
        } catch (err: Exception) {
            scanError = err.message ?: "Speicher konnte nicht analysiert werden."
            isScanning = false
        }
    }

    val activeFolder: StorageFolderNode? = scanResult?.let {
        it.folderByPath[currentBrowsePath] ?: it.rootFolder
    }

    val visibleFolders = remember(activeFolder, folderSortBy, showHidden, showEmptyFolders) {
        val folder = activeFolder ?: return@remember emptyList()
        val filtered = folder.directFolders.filter { child ->
            val hiddenOk = showHidden || !child.name.startsWith(".")
            val emptyOk = showEmptyFolders ||
                child.totalBytes > 0L ||
                child.fileCount > 0 ||
                child.subfolderCount > 0
            hiddenOk && emptyOk
        }
        when (folderSortBy) {
            FolderSortBy.SIZE -> filtered.sortedWith(
                compareByDescending<StorageFolderNode> { it.totalBytes }
                    .thenBy { it.name.lowercase(Locale.ROOT) },
            )
            FolderSortBy.NAME -> filtered.sortedBy { it.name.lowercase(Locale.ROOT) }
        }
    }

    val visibleFiles = remember(activeFolder, folderSortBy, showHidden) {
        val folder = activeFolder ?: return@remember emptyList()
        val filtered = folder.directFiles.filter { file ->
            showHidden || !file.name.startsWith(".")
        }
        when (folderSortBy) {
            FolderSortBy.SIZE -> filtered.sortedWith(
                compareByDescending<AnalyzedFileItem> { it.sizeBytes }
                    .thenBy { it.name.lowercase(Locale.ROOT) },
            )
            FolderSortBy.NAME -> filtered.sortedBy { it.name.lowercase(Locale.ROOT) }
        }
    }

    val nowMs = remember(scanResult) { System.currentTimeMillis() }
    val filteredFilesInFilesTab = remember(scanResult, activeFileFilter, nowMs) {
        val result = scanResult ?: return@remember emptyList()
        when (activeFileFilter) {
            FileFilter.ALL -> result.largestFiles
            FileFilter.DUPLICATES -> emptyList()
            FileFilter.VIDEOS -> result.largestFiles.filter { it.category == FileCategory.VIDEO }
            FileFilter.IMAGES -> result.largestFiles.filter { it.category == FileCategory.IMAGE }
            FileFilter.AUDIO -> result.largestFiles.filter { it.category == FileCategory.AUDIO }
            FileFilter.ARCHIVES -> result.largestFiles.filter { it.category == FileCategory.ARCHIVE }
            FileFilter.APKS -> result.largestFiles.filter { it.category == FileCategory.APK }
            FileFilter.DOCUMENTS -> result.largestFiles.filter {
                it.category == FileCategory.DOCUMENT || it.category == FileCategory.TEXT
            }
            FileFilter.OLD_FILES -> result.largestFiles.filter {
                it.lastModifiedMs > 0L && (nowMs - it.lastModifiedMs) > SIX_MONTHS_MS
            }
            FileFilter.OTHER -> result.largestFiles.filter { it.category == FileCategory.OTHER }
        }
    }

    val selectablePathsInTab = remember(
        selectedTab,
        visibleFolders,
        visibleFiles,
        activeFileFilter,
        scanResult,
        filteredFilesInFilesTab,
    ) {
        when (selectedTab) {
            StorageTab.FOLDERS -> visibleFolders.map { it.path } + visibleFiles.map { it.path }
            StorageTab.FILES -> if (activeFileFilter == FileFilter.DUPLICATES) {
                scanResult?.duplicateGroups
                    ?.flatMap { group -> group.files.drop(1).map { it.path } }
                    .orEmpty()
            } else {
                filteredFilesInFilesTab.map { it.path }
            }
        }
    }

    val selectedTotalBytes = remember(scanResult, activeFolder, selectedPaths) {
        val result = scanResult
        if (selectedPaths.isEmpty() || result == null) {
            0L
        } else {
            val fileSizeByPath = HashMap<String, Long>()
            activeFolder?.directFiles?.forEach { fileSizeByPath[it.path] = it.sizeBytes }
            result.largestFiles.forEach { fileSizeByPath[it.path] = it.sizeBytes }
            result.duplicateGroups.forEach { group ->
                group.files.forEach { fileSizeByPath[it.path] = it.sizeBytes }
            }
            selectedPaths.sumOf { path ->
                result.folderByPath[path]?.totalBytes ?: fileSizeByPath[path] ?: 0L
            }
        }
    }

    val canNavigateUp = selectedTab == StorageTab.FOLDERS && currentBrowsePath != activeVolumeRoot

    fun navigateUp() {
        val parentPath = File(currentBrowsePath).parentFile?.absolutePath ?: activeVolumeRoot
        val targetPath = if (isSameOrDescendant(parentPath, activeVolumeRoot)) {
            parentPath
        } else {
            activeVolumeRoot
        }
        selectedPaths = emptySet()
        MonolithHaptics.backNav(context, view)
        currentBrowsePath = targetPath
    }

    fun switchVolume(newVolumeRoot: String) {
        if (newVolumeRoot == activeVolumeRoot) return
        userSelectedVolume = true
        selectedPaths = emptySet()
        pendingTargetPath = null
        currentBrowsePath = newVolumeRoot
        activeVolumeRoot = newVolumeRoot
    }

    fun togglePathSelection(path: String) {
        selectedPaths = if (path in selectedPaths) {
            selectedPaths - path
        } else {
            selectedPaths + path
        }
    }

    LaunchedEffect(canNavigateUp, selectedPaths.isNotEmpty()) {
        if (canNavigateUp || selectedPaths.isNotEmpty()) {
            starMenuExpanded = false
        }
    }

    BackHandler(enabled = true) {
        when {
            starMenuExpanded || menuExpanded -> {
                starMenuExpanded = false
                menuExpanded = false
            }
            selectedPaths.isNotEmpty() -> {
                selectedPaths = emptySet()
            }
            canNavigateUp -> {
                navigateUp()
            }
            else -> {
                MonolithHaptics.backNav(context, view)
                onBack()
            }
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
                if (selectedPaths.isNotEmpty()) {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { selectedPaths = emptySet() }) {
                                Icon(Icons.Default.Close, contentDescription = "Auswahl aufheben")
                            }
                        },
                        title = {
                            Column {
                                Text(
                                    text = "${selectedPaths.size}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = formatStorageBytes(selectedTotalBytes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = {
                                    if (selectablePathsInTab.isNotEmpty()) {
                                        val allSelected = selectablePathsInTab.all { it in selectedPaths }
                                        selectedPaths = if (allSelected) {
                                            selectedPaths - selectablePathsInTab.toSet()
                                        } else {
                                            selectedPaths + selectablePathsInTab
                                        }
                                    }
                                },
                            ) {
                                Icon(Icons.Outlined.SelectAll, contentDescription = "Alle auswählen")
                            }
                            if (selectedPaths.size == 1) {
                                val singlePath = selectedPaths.first()
                                if (scanResult?.folderByPath?.containsKey(singlePath) == true) {
                                    IconButton(
                                        onClick = {
                                            selectedPaths = emptySet()
                                            onOpenFolderInExplorer(singlePath)
                                        },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.FolderOpen,
                                            contentDescription = "Im Explorer öffnen",
                                        )
                                    }
                                }
                            }
                            IconButton(
                                onClick = {
                                    val pathsToPack = selectedPaths.toList()
                                    selectedPaths = emptySet()
                                    onNavigateToArchives(pathsToPack)
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.FolderZip,
                                    contentDescription = "Packen",
                                )
                            }
                            IconButton(onClick = { showDeleteConfirm = true }) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = "Löschen",
                                )
                            }
                        },
                    )
                } else {
                    TopAppBar(
                        title = {
                            val titleText = if (canNavigateUp) {
                                activeFolder?.name ?: File(currentBrowsePath).name
                            } else {
                                "Speicherplatz"
                            }
                            Text(
                                text = titleText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        navigationIcon = {
                            if (canNavigateUp) {
                                IconButton(onClick = { navigateUp() }) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Zurück",
                                    )
                                }
                            } else {
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
                }
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (externalVolumes.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = activeVolumeRoot == defaultInternalPath,
                            shape = LocalMonolithButtonShape.current,
                            onClick = { switchVolume(defaultInternalPath) },
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
                                selected = activeVolumeRoot == vol.rootPath,
                                shape = LocalMonolithButtonShape.current,
                                onClick = { switchVolume(vol.rootPath) },
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
                            message = scanError ?: "Unbekannter Fehler.",
                            onRetry = { scanTrigger++ },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    scanResult != null && activeFolder != null -> {
                        val result = scanResult!!
                        StorageSummaryCard(
                            scan = result,
                            expanded = summaryExpanded,
                            onToggleExpand = { summaryExpanded = !summaryExpanded },
                            onCategoryClick = { category ->
                                selectedPaths = emptySet()
                                activeFileFilter = fileFilterForCategory(category)
                                selectedTab = StorageTab.FILES
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
                                    onClick = {
                                        if (selectedTab != tab) {
                                            selectedPaths = emptySet()
                                            selectedTab = tab
                                        }
                                    },
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
                                StorageFolderBrowserTab(
                                    rootFolder = result.rootFolder,
                                    currentFolder = activeFolder,
                                    baseVolumeLabel = activeVolumeLabel,
                                    visibleFolders = visibleFolders,
                                    visibleFiles = visibleFiles,
                                    selectedPaths = selectedPaths,
                                    nowMs = nowMs,
                                    onNavigateToFolder = { targetPath ->
                                        if (targetPath != currentBrowsePath) {
                                            selectedPaths = emptySet()
                                            MonolithHaptics.backNav(context, view)
                                            currentBrowsePath = targetPath
                                        }
                                    },
                                    onFolderClick = { folder ->
                                        if (selectedPaths.isNotEmpty()) {
                                            togglePathSelection(folder.path)
                                        } else {
                                            MonolithHaptics.folderOpen(context, view)
                                            currentBrowsePath = folder.path
                                        }
                                    },
                                    onFolderLongClick = { folder ->
                                        MonolithHaptics.dragLift(view)
                                        togglePathSelection(folder.path)
                                    },
                                    onFileClick = { file ->
                                        if (selectedPaths.isNotEmpty()) {
                                            togglePathSelection(file.path)
                                        } else {
                                            inspectedFileItem = file
                                        }
                                    },
                                    onFileLongClick = { file ->
                                        MonolithHaptics.dragLift(view)
                                        togglePathSelection(file.path)
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            StorageTab.FILES -> {
                                FilesBreakdownTab(
                                    scan = result,
                                    filteredFiles = filteredFilesInFilesTab,
                                    activeFilter = activeFileFilter,
                                    selectedPaths = selectedPaths,
                                    nowMs = nowMs,
                                    onFilterChange = { newFilter ->
                                        activeFileFilter = newFilter
                                        selectedPaths = emptySet()
                                    },
                                    onToggleSelection = { path -> togglePathSelection(path) },
                                    onLongPressSelection = { path ->
                                        MonolithHaptics.dragLift(view)
                                        togglePathSelection(path)
                                    },
                                    onSelectPaths = { paths, add ->
                                        selectedPaths = if (add) {
                                            selectedPaths + paths
                                        } else {
                                            selectedPaths - paths.toSet()
                                        }
                                    },
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
            onSelectFiles = onNavigateToFiles,
            onSelectArchives = { onNavigateToArchives(emptyList()) },
            onSelectStorage = { starMenuExpanded = false },
            onSelectDownloader = onNavigateToDownloader,
            onSelectAi = onNavigateToAi,
            onDismiss = { starMenuExpanded = false },
        )

        MonolithCornerMenuOverlay(
            expanded = menuExpanded,
            anchorCenter = menuAnchorCenter,
            items = listOf(
                CornerMenuItem(
                    id = "rescan",
                    label = "Neu scannen",
                    subtitle = if (isScanning) "Läuft…" else null,
                    onClick = { if (!isScanning) scanTrigger++ },
                ),
                CornerMenuItem(
                    id = "sort",
                    label = "Sortieren nach",
                    subtitle = "(${folderSortBy.label})",
                    onClick = { showSortDialog = true },
                ),
                CornerMenuItem(
                    id = "hidden",
                    label = if (showHidden) "Versteckte ausblenden" else "Versteckte anzeigen",
                    onClick = { showHidden = !showHidden },
                ),
                CornerMenuItem(
                    id = "empty_folders",
                    label = if (showEmptyFolders) "Leere Ordner ausblenden" else "Leere Ordner anzeigen",
                    onClick = { showEmptyFolders = !showEmptyFolders },
                ),
                CornerMenuItem(
                    id = "open_explorer",
                    label = "Im Explorer öffnen",
                    onClick = { onOpenFolderInExplorer(currentBrowsePath) },
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

    if (showSortDialog) {
        MonolithDialog(
            onDismissRequest = { showSortDialog = false },
            title = "Sortieren nach",
            confirmLabel = "Schließen",
            onConfirmClick = { showSortDialog = false },
        ) {
            FolderSortBy.entries.forEach { option ->
                MonolithDialogOption(
                    label = option.label,
                    selected = folderSortBy == option,
                    onClick = {
                        folderSortBy = option
                        showSortDialog = false
                    },
                )
            }
        }
    }

    inspectedFileItem?.let { file ->
        val ageText = formatAgeDays(file.lastModifiedMs, nowMs)
        MonolithDialog(
            onDismissRequest = { inspectedFileItem = null },
            title = file.name,
            dismissLabel = "Schließen",
            onDismissClick = { inspectedFileItem = null },
        ) {
            Text(
                text = file.relativePath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${formatStorageBytes(file.sizeBytes)}${if (ageText != null) " · $ageText" else ""}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (file.isDuplicateCandidate) {
                Surface(
                    shape = CutCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = "Duplikat",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            OutlinedButton(
                onClick = {
                    val parentDir = File(file.path).parentFile?.absolutePath ?: currentBrowsePath
                    inspectedFileItem = null
                    onOpenFolderInExplorer(parentDir)
                },
                shape = LocalMonolithButtonShape.current,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Im Explorer öffnen")
            }
            OutlinedButton(
                onClick = {
                    inspectedFileItem = null
                    onNavigateToArchives(listOf(file.path))
                },
                shape = LocalMonolithButtonShape.current,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Als Archiv packen")
            }
            OutlinedButton(
                onClick = {
                    inspectedFileItem = null
                    singleFileDeleteTarget = file
                },
                shape = LocalMonolithButtonShape.current,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.7f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Löschen")
            }
        }
    }

    singleFileDeleteTarget?.let { targetFile ->
        MonolithDialog(
            onDismissRequest = { if (!isDeleting) singleFileDeleteTarget = null },
            title = "Datei löschen",
            dismissLabel = "Abbrechen",
            dismissEnabled = !isDeleting,
            onDismissClick = { singleFileDeleteTarget = null },
            confirmLabel = if (isDeleting) "Lösche…" else "Löschen",
            confirmEnabled = !isDeleting,
            onConfirmClick = {
                isDeleting = true
                scope.launch {
                    val res = localProvider.delete(listOf(targetFile.path))
                    isDeleting = false
                    singleFileDeleteTarget = null
                    res.onSuccess {
                        snackbarHostState.showSnackbar("„${targetFile.name}“ gelöscht.")
                        scanTrigger++
                    }.onFailure { err ->
                        snackbarHostState.showSnackbar(err.message ?: "Löschen fehlgeschlagen.")
                    }
                }
            },
        ) {
            Text(
                "Möchtest du „${targetFile.name}“ (${formatStorageBytes(targetFile.sizeBytes)}) wirklich unwiderruflich löschen?",
            )
        }
    }

    if (showDeleteConfirm) {
        val pathsToDelete = selectedPaths.toList()
        val singleName = if (pathsToDelete.size == 1) {
            val singlePath = pathsToDelete.first()
            scanResult?.folderByPath?.get(singlePath)?.name ?: File(singlePath).name
        } else {
            null
        }
        MonolithDialog(
            onDismissRequest = { if (!isDeleting) showDeleteConfirm = false },
            title = if (pathsToDelete.size == 1) "Element löschen" else "Elemente löschen",
            dismissLabel = "Abbrechen",
            dismissEnabled = !isDeleting,
            onDismissClick = { showDeleteConfirm = false },
            confirmLabel = if (isDeleting) "Lösche…" else "Löschen",
            confirmEnabled = !isDeleting && pathsToDelete.isNotEmpty(),
            onConfirmClick = {
                isDeleting = true
                scope.launch {
                    val res = localProvider.delete(pathsToDelete)
                    isDeleting = false
                    showDeleteConfirm = false
                    res.onSuccess {
                        selectedPaths = emptySet()
                        val msg = if (singleName != null) {
                            "„$singleName“ gelöscht."
                        } else {
                            "${pathsToDelete.size} Elemente gelöscht."
                        }
                        snackbarHostState.showSnackbar(msg)
                        scanTrigger++
                    }.onFailure { err ->
                        snackbarHostState.showSnackbar(err.message ?: "Löschen fehlgeschlagen.")
                    }
                }
            },
        ) {
            val promptText = if (singleName != null) {
                "Möchtest du „$singleName“ (${formatStorageBytes(selectedTotalBytes)}) wirklich unwiderruflich löschen?"
            } else {
                "${pathsToDelete.size} ausgewählte Elemente (${formatStorageBytes(selectedTotalBytes)}) wirklich unwiderruflich löschen?"
            }
            Text(promptText)
        }
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                    modifier = Modifier.weight(1f),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (totalCap > 0L) {
                        Text(
                            text = "${formatStorageBytes(stats.freeCapacityBytes)} frei",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (expanded) "Einklappen" else "Ausklappen",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            SegmentedCategoryBar(
                breakdowns = scan.categoryBreakdowns,
                usedFraction = usedFraction,
                scannedBytes = scan.scannedBytes,
                totalCapacityBytes = totalCap,
            )

            AnimatedVisibility(visible = expanded && scan.categoryBreakdowns.isNotEmpty()) {
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
            .height(8.dp)
            .clip(CutCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (breakdowns.isNotEmpty() && scannedBytes > 0L) {
            val scaleFactor = if (totalCapacityBytes > 0L) {
                (scannedBytes.toDouble() / totalCapacityBytes.toDouble())
                    .toFloat()
                    .coerceIn(0.05f, usedFraction.coerceAtLeast(0.05f))
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
private fun StorageFolderBrowserTab(
    rootFolder: StorageFolderNode,
    currentFolder: StorageFolderNode,
    baseVolumeLabel: String,
    visibleFolders: List<StorageFolderNode>,
    visibleFiles: List<AnalyzedFileItem>,
    selectedPaths: Set<String>,
    nowMs: Long,
    onNavigateToFolder: (String) -> Unit,
    onFolderClick: (StorageFolderNode) -> Unit,
    onFolderLongClick: (StorageFolderNode) -> Unit,
    onFileClick: (AnalyzedFileItem) -> Unit,
    onFileLongClick: (AnalyzedFileItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(currentFolder.path) {
        listState.scrollToItem(0)
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (currentFolder.path != rootFolder.path) {
            val breadcrumbs = remember(rootFolder.path, currentFolder.relativePath, baseVolumeLabel) {
                buildList {
                    add(baseVolumeLabel to rootFolder.path)
                    var acc = rootFolder.path
                    val parts = currentFolder.relativePath.split('/').filter { it.isNotEmpty() }
                    for (part in parts) {
                        acc = File(acc, part).absolutePath
                        add(part to acc)
                    }
                }
            }
            val breadcrumbScrollState = rememberScrollState()
            LaunchedEffect(currentFolder.path) {
                breadcrumbScrollState.animateScrollTo(breadcrumbScrollState.maxValue)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(breadcrumbScrollState),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    breadcrumbs.forEachIndexed { index, (label, path) ->
                        if (index > 0) {
                            Icon(
                                imageVector = Icons.Outlined.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        val isLast = index == breadcrumbs.lastIndex
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isLast) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            modifier = Modifier
                                .clip(CutCornerShape(4.dp))
                                .clickable(enabled = !isLast) { onNavigateToFolder(path) }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(
                    text = formatStorageBytes(currentFolder.totalBytes),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }

        if (visibleFolders.isEmpty() && visibleFiles.isEmpty()) {
            val emptyMessage = if (currentFolder.directFolders.isEmpty() && currentFolder.directFiles.isEmpty()) {
                "Dieser Ordner ist leer."
            } else {
                "Keine sichtbaren Einträge in diesem Ordner."
            }
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = emptyMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(visibleFolders, key = { "dir:${it.path}" }) { folder ->
                    StorageFolderExplorerRow(
                        folder = folder,
                        parentTotalBytes = currentFolder.totalBytes,
                        isSelected = folder.path in selectedPaths,
                        onClick = { onFolderClick(folder) },
                        onLongClick = { onFolderLongClick(folder) },
                    )
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                items(visibleFiles, key = { "file:${it.path}" }) { file ->
                    StorageDirectFileExplorerRow(
                        file = file,
                        parentTotalBytes = currentFolder.totalBytes,
                        isSelected = file.path in selectedPaths,
                        nowMs = nowMs,
                        onClick = { onFileClick(file) },
                        onLongClick = { onFileLongClick(file) },
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StorageFolderExplorerRow(
    folder: StorageFolderNode,
    parentTotalBytes: Long,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val fraction = computeShareFraction(folder.totalBytes, parentTotalBytes)
    val percentLabel = formatSharePercent(folder.totalBytes, parentTotalBytes)
    val filesLabel = if (folder.fileCount == 1) "1 Datei" else "${folder.fileCount} Dateien"
    val subtitle = if (folder.subfolderCount > 0) {
        "${folder.subfolderCount} Ordner · $filesLabel"
    } else {
        filesLabel
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isSelected) {
                    Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                } else {
                    Modifier
                },
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        if (!isSelected && fraction > 0f) {
            Box(modifier = Modifier.matchParentSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = folder.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = formatStorageBytes(folder.totalBytes),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = percentLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CutCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    if (folder.totalBytes > 0L) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction.coerceIn(0.015f, 1f))
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StorageDirectFileExplorerRow(
    file: AnalyzedFileItem,
    parentTotalBytes: Long,
    isSelected: Boolean,
    nowMs: Long,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val fraction = computeShareFraction(file.sizeBytes, parentTotalBytes)
    val percentLabel = formatSharePercent(file.sizeBytes, parentTotalBytes)
    val ageText = formatAgeDays(file.lastModifiedMs, nowMs)
    val node = remember(file.path, file.lastModifiedMs, file.sizeBytes) {
        VfsNode(
            id = file.path,
            name = file.name,
            path = file.path,
            parentPath = file.path.substringBeforeLast('/', ""),
            locationType = LocationType.LOCAL,
            isDirectory = false,
            sizeBytes = file.sizeBytes,
            lastModifiedMs = file.lastModifiedMs,
            mimeType = file.mimeType,
            category = file.category,
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isSelected) {
                    Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                } else {
                    Modifier
                },
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        if (!isSelected && fraction > 0f) {
            Box(modifier = Modifier.matchParentSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.06f)),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isSelected) {
                Box(
                    modifier = Modifier.size(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                }
            } else {
                FileThumbnail(
                    node = node,
                    fallbackIcon = categoryIcon(file.category),
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = formatStorageBytes(file.sizeBytes),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (ageText != null) {
                            Text(
                                text = ageText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (file.isDuplicateCandidate) {
                            Surface(
                                shape = CutCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Text(
                                    text = "Duplikat",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = percentLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(CutCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    if (file.sizeBytes > 0L) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction.coerceIn(0.015f, 1f))
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.secondary),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilesBreakdownTab(
    scan: StorageScanResult,
    filteredFiles: List<AnalyzedFileItem>,
    activeFilter: FileFilter,
    selectedPaths: Set<String>,
    nowMs: Long,
    onFilterChange: (FileFilter) -> Unit,
    onToggleSelection: (String) -> Unit,
    onLongPressSelection: (String) -> Unit,
    onSelectPaths: (List<String>, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
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
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(scan.duplicateGroups, key = { it.key }) { group ->
                        DuplicateGroupCard(
                            group = group,
                            selectedPaths = selectedPaths,
                            nowMs = nowMs,
                            onToggleSelection = onToggleSelection,
                            onLongPressSelection = onLongPressSelection,
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
            Text(
                text = "${filteredFiles.size} größte Dateien",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(filteredFiles, key = { it.path }) { item ->
                    AnalyzedFileRow(
                        item = item,
                        isSelected = item.path in selectedPaths,
                        nowMs = nowMs,
                        onToggle = { onToggleSelection(item.path) },
                        onLongClick = { onLongPressSelection(item.path) },
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
    onLongPressSelection: (String) -> Unit,
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
                    onLongClick = { onLongPressSelection(file.path) },
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AnalyzedFileRow(
    item: AnalyzedFileItem,
    isSelected: Boolean,
    nowMs: Long,
    onToggle: () -> Unit,
    onLongClick: () -> Unit,
    badgeOverride: String? = null,
) {
    val node = remember(item.path, item.lastModifiedMs, item.sizeBytes) {
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
            .combinedClickable(
                onClick = onToggle,
                onLongClick = onLongClick,
            )
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

private fun isSameOrDescendant(path: String, root: String): Boolean {
    val normRoot = root.trimEnd('/')
    val normPath = path.trimEnd('/')
    if (normRoot.isEmpty()) return normPath.startsWith('/')
    return normPath == normRoot || normPath.startsWith("$normRoot/")
}

private fun resolveBestExistingPath(desiredPath: String, result: StorageScanResult): String {
    var cursor: String? = desiredPath
    val rootPath = result.rootFolder.path
    while (!cursor.isNullOrBlank()) {
        if (result.folderByPath.containsKey(cursor)) {
            return cursor
        }
        if (cursor == rootPath) break
        val parent = File(cursor).parentFile?.absolutePath
        if (parent == null || parent == cursor || !isSameOrDescendant(parent, rootPath)) {
            break
        }
        cursor = parent
    }
    return rootPath
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
