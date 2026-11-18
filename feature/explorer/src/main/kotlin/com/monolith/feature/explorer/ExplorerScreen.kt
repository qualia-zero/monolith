package com.monolith.feature.explorer

import android.app.Activity
import android.content.Intent
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.monolith.core.media.MpvEngine
import com.monolith.core.media.ThumbnailEngine
import com.monolith.core.model.FileCategory
import com.monolith.core.model.LocationType
import com.monolith.core.model.SortBy
import com.monolith.core.model.ThemeMode
import com.monolith.core.model.VfsNode
import com.monolith.core.ui.components.CornerMenuItem
import com.monolith.core.ui.components.MonolithCornerMenuOverlay
import com.monolith.core.ui.components.MonolithDialog
import com.monolith.core.ui.components.MonolithDialogOption
import com.monolith.core.ui.components.MonolithLoadingSpinner
import com.monolith.core.ui.components.MonolithRefreshIndicator
import com.monolith.core.ui.haptics.MonolithHaptics
import com.monolith.core.ui.theme.LocalMonolithButtonShape
import com.monolith.core.ui.theme.LocalMonolithThemeMode
import com.monolith.core.vfs.FileTransferService
import com.monolith.core.vfs.GoogleDriveAuthManager
import com.monolith.core.vfs.GoogleDriveVfsProvider
import com.monolith.core.vfs.LocalVfsProvider
import com.monolith.core.vfs.VfsProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ExplorerNavigationState(rootPath: String) {
    var activeLocation by mutableStateOf(LocationType.LOCAL)
    var activeLocalRootPath by mutableStateOf(rootPath)
    var localPath by mutableStateOf(rootPath)
    var driveStack by mutableStateOf(listOf("gdrive://root" to "Google Drive"))
}

@Composable
fun rememberExplorerNavigationState(
    rootPath: String = remember { Environment.getExternalStorageDirectory().absolutePath },
): ExplorerNavigationState {
    return remember(rootPath) { ExplorerNavigationState(rootPath) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplorerScreen(
    hasStoragePermission: Boolean,
    onRequestStoragePermission: () -> Unit,
    onOpenMedia: (node: VfsNode, playlist: List<VfsNode>) -> Unit = { _, _ -> },
    onOpenImage: (node: VfsNode, imagePlaylist: List<VfsNode>) -> Unit = { _, _ -> },
    onOpenText: (node: VfsNode) -> Unit = {},
    onOpenOther: (node: VfsNode) -> Unit = {},
    onOpenWith: (node: VfsNode, siblings: List<VfsNode>) -> Unit = { _, _ -> },
    onOpenMiniPlayer: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenAiChat: (currentDirectory: String) -> Unit = {},
    onOpenArchiveTool: (initialArchivePath: String?, initialPackPaths: List<String>) -> Unit = { _, _ -> },
    onOpenStorageAnalysis: (rootPath: String) -> Unit = {},
    navState: ExplorerNavigationState = rememberExplorerNavigationState(),
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val localProvider = remember { LocalVfsProvider() }
    val driveProvider = remember(context) { GoogleDriveVfsProvider(context.applicationContext) }
    val rootPath = remember { Environment.getExternalStorageDirectory().absolutePath }
    val externalVolumes = rememberExternalStorageVolumes(context)

    var activeLocation by navState::activeLocation
    var activeLocalRootPath by navState::activeLocalRootPath
    var localPath by navState::localPath
    var driveStack by navState::driveStack
    val activeExternalVolume = externalVolumes.firstOrNull { it.rootPath == activeLocalRootPath }

    LaunchedEffect(externalVolumes, activeLocalRootPath) {
        if (activeLocalRootPath != rootPath && externalVolumes.none { it.rootPath == activeLocalRootPath }) {
            activeLocalRootPath = rootPath
            localPath = rootPath
        }
    }
    val currentPath = if (activeLocation == LocationType.GOOGLE_DRIVE) {
        driveStack.last().first
    } else {
        localPath
    }
    val activeProvider: VfsProvider = if (activeLocation == LocationType.GOOGLE_DRIVE) {
        driveProvider
    } else {
        localProvider
    }

    var signedInAccounts by remember { mutableStateOf(GoogleDriveAuthManager.getSignedInAccounts(context)) }
    var signedInEmail by remember { mutableStateOf(GoogleDriveAuthManager.getActiveEmail(context)) }
    var isLoading by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            GoogleDriveAuthManager.handleSignInResult(context, result.data)
                .onSuccess { email ->
                    signedInAccounts = GoogleDriveAuthManager.getSignedInAccounts(context)
                    signedInEmail = email
                    activeLocation = LocationType.GOOGLE_DRIVE
                    driveStack = listOf("gdrive://root" to email.substringBefore('@'))
                }
                .onFailure { err ->
                    scope.launch {
                        snackbarHostState.showSnackbar(err.message ?: "Google-Anmeldung fehlgeschlagen.")
                    }
                }
        }
    }

    var nodes by remember { mutableStateOf<List<VfsNode>>(emptyList()) }
    var showHidden by remember { mutableStateOf(false) }
    var sortBy by remember { mutableStateOf(SortBy.NAME) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var clipboardPaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var isMoveOperation by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var renameTargetNode by remember { mutableStateOf<VfsNode?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var shareCloudTarget by remember { mutableStateOf<VfsNode?>(null) }
    var isDownloadingForShare by remember { mutableStateOf(false) }
    var cacheVersionTick by remember { mutableIntStateOf(0) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var forceRefreshNextLoad by remember { mutableStateOf(false) }
    var loadRequestSeq by remember { mutableIntStateOf(0) }

    val playbackState by MpvEngine.uiState.collectAsState()

    LaunchedEffect(Unit) {
        FileTransferService.events.collect { event ->
            refreshTrigger++
            cacheVersionTick++
            if (event.errorMessage != null) {
                snackbarHostState.showSnackbar(event.errorMessage ?: "Übertragung fehlgeschlagen.")
            }
        }
    }

    fun runFileOperation(
        onSuccess: () -> Unit = {},
        block: suspend () -> Result<Unit>,
    ) {
        scope.launch {
            block()
                .onSuccess {
                    onSuccess()
                    refreshTrigger++
                }
                .onFailure { error ->
                    snackbarHostState.showSnackbar(
                        error.message ?: "Vorgang fehlgeschlagen."
                    )
                }
        }
    }

    val canNavigateUp = if (activeLocation == LocationType.GOOGLE_DRIVE) {
        driveStack.size > 1
    } else {
        localPath != activeLocalRootPath
    }

    fun navigateUp() {
        MonolithHaptics.backNav(context, view)
        if (activeLocation == LocationType.GOOGLE_DRIVE) {
            if (driveStack.size > 1) {
                driveStack = driveStack.dropLast(1)
            }
        } else {
            localPath = localPath.substringBeforeLast('/', activeLocalRootPath).ifEmpty { activeLocalRootPath }
        }
    }

    var starMenuExpanded by remember { mutableStateOf(false) }
    var starAnchorCenter by remember { mutableStateOf(Offset.Unspecified) }
    var menuAnchorCenter by remember { mutableStateOf(Offset.Unspecified) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }

    BackHandler(enabled = starMenuExpanded || menuExpanded) {
        starMenuExpanded = false
        menuExpanded = false
    }

    BackHandler(enabled = selectedPaths.isNotEmpty() || canNavigateUp) {
        if (selectedPaths.isNotEmpty()) {
            selectedPaths = emptySet()
        } else {
            navigateUp()
        }
    }

    LaunchedEffect(
        activeLocation,
        currentPath,
        showHidden,
        sortBy,
        hasStoragePermission,
        signedInEmail,
        refreshTrigger,
    ) {
        val requestId = ++loadRequestSeq
        val ready = if (activeLocation == LocationType.GOOGLE_DRIVE) {
            signedInEmail != null
        } else {
            hasStoragePermission
        }
        if (!ready) {
            nodes = emptyList()
            isLoading = false
            return@LaunchedEffect
        }
        val forceRefresh = forceRefreshNextLoad
        forceRefreshNextLoad = false
        isLoading = if (activeLocation == LocationType.GOOGLE_DRIVE) {
            forceRefresh || !driveProvider.hasCachedList(currentPath, showHidden)
        } else {
            false
        }
        try {
            val raw = if (activeLocation == LocationType.GOOGLE_DRIVE) {
                driveProvider.list(currentPath, showHidden, forceRefresh = forceRefresh)
            } else {
                activeProvider.list(currentPath, showHidden)
            }
            if (requestId != loadRequestSeq) return@LaunchedEffect
            nodes = withContext(Dispatchers.Default) {
                raw.sortedWith(
                    compareByDescending<VfsNode> { it.isDirectory }.thenBy {
                        when (sortBy) {
                            SortBy.NAME -> it.name.lowercase()
                            SortBy.SIZE -> it.sizeBytes.toString().padStart(20, '0')
                            SortBy.DATE_MODIFIED -> it.lastModifiedMs.toString().padStart(20, '0')
                            SortBy.TYPE -> it.category.name
                        }
                    }
                )
            }
            isLoading = false
        } catch (e: CancellationException) {
            throw e
        } catch (err: Exception) {
            if (requestId != loadRequestSeq) return@LaunchedEffect
            nodes = emptyList()
            isLoading = false
            snackbarHostState.showSnackbar(err.message ?: "Ordner konnte nicht geladen werden.")
        }
    }

    val selectedNodes = remember(nodes, selectedPaths) {
        nodes.filter { it.path in selectedPaths }
    }

    val offlineCachedPaths by produceState(
        initialValue = emptySet<String>(),
        key1 = nodes,
        key2 = cacheVersionTick,
    ) {
        value = withContext(Dispatchers.IO) {
            nodes.asSequence()
                .filter { it.locationType == LocationType.GOOGLE_DRIVE && CloudCacheManager.isNodeCached(context, it) }
                .map { it.path }
                .toSet()
        }
    }

    LaunchedEffect(canNavigateUp, selectedPaths.isNotEmpty()) {
        if (canNavigateUp || selectedPaths.isNotEmpty()) {
            starMenuExpanded = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { rootOrigin = it.positionInRoot() },
    ) {
        val backgroundRecedeState = animateFloatAsState(
            targetValue = if (starMenuExpanded) 1f else 0f,
            label = "starMenuBackgroundRecede",
        )
        val cornerMenuRecedeState = animateFloatAsState(
            targetValue = if (menuExpanded) 1f else 0f,
            label = "cornerMenuBackgroundRecede",
        )
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val starP = backgroundRecedeState.value
                val cornerP = cornerMenuRecedeState.value
                val p = maxOf(starP, cornerP * 0.4f)
                if (p > 0.001f) {
                    val s = 1f - 0.022f * starP
                    scaleX = s
                    scaleY = s
                }
            },
        containerColor = if (LocalMonolithThemeMode.current == ThemeMode.OBSIDIAN) {
            androidx.compose.ui.graphics.Color.Transparent
        } else {
            MaterialTheme.colorScheme.background
        },
        contentColor = MaterialTheme.colorScheme.onBackground,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            if (selectedPaths.isNotEmpty()) {
                TopAppBar(
                    title = {
                        Text(
                            text = "${selectedPaths.size}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { selectedPaths = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = "Auswahl aufheben")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            selectedPaths = if (selectedPaths.size == nodes.size && nodes.isNotEmpty()) {
                                emptySet()
                            } else {
                                nodes.map { it.path }.toSet()
                            }
                        }) {
                            Icon(Icons.Outlined.SelectAll, contentDescription = "Alle auswählen")
                        }
                        IconButton(onClick = {
                            clipboardPaths = selectedPaths.toList()
                            isMoveOperation = false
                            selectedPaths = emptySet()
                        }) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = "Kopieren")
                        }
                        IconButton(onClick = {
                            clipboardPaths = selectedPaths.toList()
                            isMoveOperation = true
                            selectedPaths = emptySet()
                        }) {
                            Icon(Icons.Outlined.ContentCut, contentDescription = "Ausschneiden")
                        }
                        if (activeLocation == LocationType.GOOGLE_DRIVE) {
                            val allSelectedCached = remember(selectedNodes, cacheVersionTick) {
                                selectedNodes.isNotEmpty() && selectedNodes.all {
                                    CloudCacheManager.isNodeCached(context, it)
                                }
                            }
                            IconButton(
                                onClick = {
                                    val nodesToProcess = selectedNodes
                                    if (allSelectedCached) {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                CloudCacheManager.removeCachedNodes(context, nodesToProcess)
                                            }
                                            cacheVersionTick++
                                            selectedPaths = emptySet()
                                            snackbarHostState.showSnackbar("Aus Zwischenspeicher gelöscht.")
                                        }
                                    } else {
                                        FileTransferService.enqueueCloudDownload(
                                            context = context,
                                            sources = nodesToProcess.map { it.path },
                                        )
                                        selectedPaths = emptySet()
                                    }
                                },
                            ) {
                                if (allSelectedCached) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Zwischengespeicherte Dateien löschen",
                                    )
                                } else {
                                    Icon(
                                        Icons.Outlined.Download,
                                        contentDescription = "Offline speichern",
                                    )
                                }
                            }
                        }
                        if (selectedPaths.size == 1) {
                            val singleNode = selectedNodes.firstOrNull()
                            if (singleNode != null) {
                                IconButton(onClick = { renameTargetNode = singleNode }) {
                                    Icon(Icons.Outlined.Edit, contentDescription = "Umbenennen")
                                }
                                if (!singleNode.isDirectory) {
                                    IconButton(onClick = {
                                        selectedPaths = emptySet()
                                        if (singleNode.locationType == LocationType.GOOGLE_DRIVE) {
                                            if (CloudCacheManager.isNodeCached(context, singleNode)) {
                                                runCatching {
                                                    shareLocalFile(
                                                        context = context,
                                                        file = CloudCacheManager.cachedFileFor(context, singleNode),
                                                        mimeType = singleNode.mimeType,
                                                        title = singleNode.name,
                                                    )
                                                }.onFailure { err ->
                                                    scope.launch {
                                                        snackbarHostState.showSnackbar(err.message ?: "Teilen fehlgeschlagen.")
                                                    }
                                                }
                                            } else {
                                                shareCloudTarget = singleNode
                                            }
                                        } else {
                                            runCatching {
                                                shareLocalFile(
                                                    context = context,
                                                    file = File(singleNode.path),
                                                    mimeType = singleNode.mimeType,
                                                    title = singleNode.name,
                                                )
                                            }.onFailure { err ->
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(err.message ?: "Teilen fehlgeschlagen.")
                                                }
                                            }
                                        }
                                    }) {
                                        Icon(
                                            Icons.Outlined.Share,
                                            contentDescription = "Teilen",
                                        )
                                    }
                                }
                            }
                        }
                        if (activeLocation == LocationType.LOCAL) {
                            IconButton(onClick = {
                                val pathsToPack = selectedPaths.toList()
                                selectedPaths = emptySet()
                                onOpenArchiveTool(null, pathsToPack)
                            }) {
                                Icon(
                                    imageVector = Icons.Outlined.FolderZip,
                                    contentDescription = "Packen",
                                )
                            }
                        }
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Löschen")
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        val label = when {
                            !canNavigateUp -> "Monolith"
                            activeLocation == LocationType.GOOGLE_DRIVE -> driveStack.last().second
                            else -> localPath.substringAfterLast('/')
                        }
                        Text(
                            text = label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        if (canNavigateUp) {
                            IconButton(onClick = { navigateUp() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
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
                                    imageVector = when {
                                        activeLocation == LocationType.GOOGLE_DRIVE -> Icons.Outlined.CloudQueue
                                        activeExternalVolume?.kind == ExternalVolumeKind.USB -> Icons.Outlined.Usb
                                        activeExternalVolume?.kind == ExternalVolumeKind.SD_CARD -> Icons.Outlined.SdStorage
                                        else -> Icons.Outlined.ViewInAr
                                    },
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
        bottomBar = {
            val showMiniPlayer = playbackState.mediaUri.isNotBlank() && !playbackState.isEnded
            if (clipboardPaths.isNotEmpty() || showMiniPlayer) {
                Column(
                    modifier = if (!showMiniPlayer) Modifier.navigationBarsPadding() else Modifier,
                ) {
                    if (clipboardPaths.isNotEmpty()) {
                        ClipboardActionBar(
                            count = clipboardPaths.size,
                            isMoveOperation = isMoveOperation,
                            onPaste = {
                                val sources = clipboardPaths
                                val move = isMoveOperation
                                clipboardPaths = emptyList()
                                FileTransferService.enqueue(
                                    context = context,
                                    sources = sources,
                                    destinationDir = currentPath,
                                    isMove = move,
                                )
                            },
                            onCancel = { clipboardPaths = emptyList() },
                        )
                    }
                    if (showMiniPlayer) {
                        MiniPlayerBar(
                            mediaUri = playbackState.mediaUri,
                            title = playbackState.title.ifBlank { "Wiedergabe" },
                            subtitle = listOf(playbackState.artist, playbackState.album)
                                .filter { it.isNotBlank() }
                                .joinToString(" • "),
                            isVideo = playbackState.isVideo,
                            isPlaying = playbackState.isPlaying,
                            onOpen = onOpenMiniPlayer,
                            onTogglePlayPause = { MpvEngine.togglePlayPause() },
                            onStop = { MpvEngine.stopAndReset() },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            var draggingAccount by remember { mutableStateOf<String?>(null) }
            var dragOffsetX by remember { mutableFloatStateOf(0f) }
            val chipBounds = remember { mutableStateMapOf<String, Pair<Float, Float>>() }
            val currentAccounts by rememberUpdatedState(signedInAccounts)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = activeLocation == LocationType.LOCAL && activeLocalRootPath == rootPath,
                    shape = LocalMonolithButtonShape.current,
                    onClick = {
                        activeLocation = LocationType.LOCAL
                        activeLocalRootPath = rootPath
                        localPath = rootPath
                        selectedPaths = emptySet()
                    },
                    label = { Text("Intern") },
                )
                externalVolumes.forEach { volume ->
                    FilterChip(
                        selected = activeLocation == LocationType.LOCAL && activeLocalRootPath == volume.rootPath,
                        shape = LocalMonolithButtonShape.current,
                        onClick = {
                            activeLocation = LocationType.LOCAL
                            activeLocalRootPath = volume.rootPath
                            localPath = volume.rootPath
                            selectedPaths = emptySet()
                        },
                        label = {
                            Text(
                                text = volume.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
                signedInAccounts.forEach { email ->
                    key(email) {
                        val accountLabel = email.substringBefore('@')
                        val isDragging = draggingAccount == email
                        val dragScale by animateFloatAsState(
                            targetValue = if (isDragging) 1.07f else 1f,
                            label = "driveChipScale",
                        )
                        val canReorder = signedInAccounts.size > 1

                        Box(
                            modifier = Modifier
                                .zIndex(if (isDragging) 1f else 0f)
                                .onGloballyPositioned { coords ->
                                    chipBounds[email] = coords.positionInParent().x to coords.size.width.toFloat()
                                }
                                .graphicsLayer {
                                    translationX = if (isDragging) dragOffsetX else 0f
                                    scaleX = dragScale
                                    scaleY = dragScale
                                    alpha = if (isDragging) 0.92f else 1f
                                }
                                .pointerInput(email, canReorder) {
                                    if (!canReorder) return@pointerInput
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            MonolithHaptics.dragLift(view)
                                            draggingAccount = email
                                            dragOffsetX = 0f
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragOffsetX += dragAmount.x
                                            val list = currentAccounts
                                            val idx = list.indexOf(email)
                                            if (idx != -1) {
                                                val (myX, myW) = chipBounds[email] ?: return@detectDragGesturesAfterLongPress
                                                val draggedCenter = myX + (myW / 2f) + dragOffsetX

                                                var swapped = false
                                                if (idx < list.lastIndex) {
                                                    val nextEmail = list[idx + 1]
                                                    val (nextX, nextW) = chipBounds[nextEmail] ?: return@detectDragGesturesAfterLongPress
                                                    val nextCenter = nextX + (nextW / 2f)
                                                    if (draggedCenter > nextCenter) {
                                                        val updated = list.toMutableList().apply {
                                                            add(idx + 1, removeAt(idx))
                                                        }
                                                        signedInAccounts = updated
                                                        dragOffsetX -= (nextX - myX)
                                                        swapped = true
                                                    }
                                                }
                                                if (!swapped && idx > 0) {
                                                    val prevEmail = list[idx - 1]
                                                    val (prevX, prevW) = chipBounds[prevEmail] ?: return@detectDragGesturesAfterLongPress
                                                    val prevCenter = prevX + (prevW / 2f)
                                                    if (draggedCenter < prevCenter) {
                                                        val updated = list.toMutableList().apply {
                                                            add(idx - 1, removeAt(idx))
                                                        }
                                                        signedInAccounts = updated
                                                        dragOffsetX += (myX - prevX)
                                                    }
                                                }
                                            }
                                        },
                                        onDragEnd = {
                                            draggingAccount = null
                                            dragOffsetX = 0f
                                            GoogleDriveAuthManager.saveAccountOrder(context, currentAccounts)
                                        },
                                        onDragCancel = {
                                            draggingAccount = null
                                            dragOffsetX = 0f
                                        },
                                    )
                                },
                        ) {
                            FilterChip(
                                selected = activeLocation == LocationType.GOOGLE_DRIVE && signedInEmail == email,
                                shape = LocalMonolithButtonShape.current,
                                onClick = {
                                    if (signedInEmail != email) {
                                        GoogleDriveAuthManager.setActiveEmail(context, email)
                                        signedInEmail = email
                                        driveStack = listOf("gdrive://root" to accountLabel)
                                    } else if (driveStack.firstOrNull()?.second == "Google Drive") {
                                        driveStack = listOf("gdrive://root" to accountLabel)
                                    }
                                    activeLocation = LocationType.GOOGLE_DRIVE
                                    selectedPaths = emptySet()
                                },
                                elevation = FilterChipDefaults.filterChipElevation(
                                    elevation = if (isDragging) 6.dp else 0.dp,
                                ),
                                label = {
                                    Text(
                                        text = accountLabel,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                            )
                        }
                    }
                }
                FilterChip(
                    selected = false,
                    shape = LocalMonolithButtonShape.current,
                    onClick = {
                        scope.launch {
                            signInLauncher.launch(GoogleDriveAuthManager.getAddAccountIntent(context))
                        }
                    },
                    label = {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = "Google Drive hinzufügen",
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }

            if (activeLocation == LocationType.LOCAL && !hasStoragePermission) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Speicherzugriff erforderlich",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    TextButton(onClick = onRequestStoragePermission) {
                        Text("Zugriff erlauben")
                    }
                }
            } else if (activeLocation == LocationType.GOOGLE_DRIVE && signedInEmail == null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CloudQueue,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        text = "Mit Google Drive verbinden",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Melde dich mit deinem Google-Konto an, um Dateien direkt zu durchsuchen und Medien zu streamen.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        shape = LocalMonolithButtonShape.current,
                        onClick = {
                            signInLauncher.launch(GoogleDriveAuthManager.getSignInIntent(context))
                        },
                    ) {
                        Text("Mit Google anmelden")
                    }
                }
            } else if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    MonolithLoadingSpinner(
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            } else {
                var isPullRefreshing by remember { mutableStateOf(false) }
                val pullState = rememberPullToRefreshState()
                val pullThresholdReached = pullState.distanceFraction >= 1f
                val targetContentOffsetDp = if (isPullRefreshing) {
                    68f
                } else {
                    pullState.distanceFraction.coerceIn(0f, 1.2f) * 68f
                }
                val contentOffsetDp by animateFloatAsState(
                    targetValue = targetContentOffsetDp,
                    label = "pullRefreshContentOffset",
                )

                LaunchedEffect(pullThresholdReached) {
                    if (pullThresholdReached && !isPullRefreshing) {
                        MonolithHaptics.refreshDetent(context, view)
                    }
                }

                PullToRefreshBox(
                    isRefreshing = isPullRefreshing,
                    onRefresh = {
                        if (!isPullRefreshing) {
                            isPullRefreshing = true
                            forceRefreshNextLoad = true
                            refreshTrigger++
                            scope.launch {
                                delay(480L)
                                while (isLoading) {
                                    delay(50L)
                                }
                                isPullRefreshing = false
                                MonolithHaptics.refreshComplete(context, view)
                            }
                        }
                    },
                    state = pullState,
                    modifier = Modifier.fillMaxSize(),
                    indicator = {
                        MonolithRefreshIndicator(
                            state = pullState,
                            isRefreshing = isPullRefreshing,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    },
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationY = contentOffsetDp.dp.toPx()
                            },
                    ) {
                    items(nodes, key = { it.id }) { node ->
                        val isSelected = node.path in selectedPaths
                        FileRow(
                            node = node,
                            isSelected = isSelected,
                            isOfflineCached = node.path in offlineCachedPaths,
                            onClick = {
                                if (selectedPaths.isNotEmpty()) {
                                    selectedPaths = if (isSelected) {
                                        selectedPaths - node.path
                                    } else {
                                        selectedPaths + node.path
                                    }
                                } else {
                                    when {
                                        node.isDirectory -> {
                                            MonolithHaptics.folderOpen(context, view)
                                            if (activeLocation == LocationType.GOOGLE_DRIVE) {
                                                driveStack = driveStack + (node.path to node.name)
                                            } else {
                                                localPath = node.path
                                            }
                                        }
                                        node.category == FileCategory.VIDEO || node.category == FileCategory.AUDIO -> {
                                            MonolithHaptics.launchItem(context)
                                            val playlist = nodes.filter { it.category == node.category }
                                            onOpenMedia(node, playlist)
                                        }
                                        node.category == FileCategory.IMAGE -> {
                                            MonolithHaptics.launchItem(context)
                                            val images = nodes.filter { it.category == FileCategory.IMAGE }
                                            onOpenImage(node, images)
                                        }
                                        node.category == FileCategory.TEXT -> {
                                            MonolithHaptics.launchItem(context)
                                            onOpenText(node)
                                        }
                                        else -> {
                                            MonolithHaptics.launchItem(context)
                                            onOpenOther(node)
                                        }
                                    }
                                }
                            },
                            onLongClick = {
                                MonolithHaptics.dragLift(view)
                                selectedPaths = if (isSelected) {
                                    selectedPaths - node.path
                                } else {
                                    selectedPaths + node.path
                                }
                            },
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
    }

    val currentAnchorIcon = when {
        activeLocation == LocationType.GOOGLE_DRIVE -> Icons.Outlined.CloudQueue
        activeExternalVolume?.kind == ExternalVolumeKind.USB -> Icons.Outlined.Usb
        activeExternalVolume?.kind == ExternalVolumeKind.SD_CARD -> Icons.Outlined.SdStorage
        else -> Icons.Outlined.ViewInAr
    }

    MonolithToolStarMenu(
        expanded = starMenuExpanded,
        anchorCenter = starAnchorCenter,
        anchorIcon = currentAnchorIcon,
        activeDestination = MonolithToolDestination.FILES,
        onSelectFiles = {
            starMenuExpanded = false
        },
        onSelectArchives = {
            onOpenArchiveTool(null, emptyList())
        },
        onSelectStorage = {
            onOpenStorageAnalysis(activeLocalRootPath)
        },
        onSelectAi = {
            onOpenAiChat(localPath)
        },
        onDismiss = { starMenuExpanded = false },
    )

    val cornerItems = buildList {
        add(
            CornerMenuItem(
                id = "new_folder",
                label = "Neuer Ordner",
                onClick = { showCreateFolderDialog = true },
            ),
        )
        add(
            CornerMenuItem(
                id = "sort",
                label = "Sortieren nach",
                onClick = { showSortDialog = true },
            ),
        )
        add(
            CornerMenuItem(
                id = "hidden",
                label = if (showHidden) "Versteckte ausblenden" else "Versteckte anzeigen",
                onClick = { showHidden = !showHidden },
            ),
        )
        add(
            CornerMenuItem(
                id = "settings",
                label = "Einstellungen",
                onClick = onOpenSettings,
            ),
        )
        if (activeLocation == LocationType.GOOGLE_DRIVE && signedInEmail != null) {
            add(
                CornerMenuItem(
                    id = "drive_signout",
                    label = "Drive abmelden",
                    subtitle = "(${signedInEmail!!.substringBefore('@')})",
                    onClick = {
                        val targetEmail = signedInEmail
                        scope.launch {
                            GoogleDriveAuthManager.signOut(context, targetEmail)
                            signedInAccounts = GoogleDriveAuthManager.getSignedInAccounts(context)
                            val nextEmail = GoogleDriveAuthManager.getActiveEmail(context)
                            signedInEmail = nextEmail
                            driveStack = listOf(
                                "gdrive://root" to (nextEmail?.substringBefore('@') ?: "Google Drive"),
                            )
                            if (nextEmail == null && activeLocation == LocationType.GOOGLE_DRIVE) {
                                activeLocation = LocationType.LOCAL
                                nodes = emptyList()
                            }
                        }
                    },
                ),
            )
        }
    }

    MonolithCornerMenuOverlay(
        expanded = menuExpanded,
        anchorCenter = menuAnchorCenter,
        items = cornerItems,
        onDismiss = { menuExpanded = false },
    )
    }

    shareCloudTarget?.let { target ->
        MonolithDialog(
            onDismissRequest = {
                if (!isDownloadingForShare) {
                    shareCloudTarget = null
                }
            },
            title = "Cloud-Datei teilen",
            dismissLabel = "Abbrechen",
            dismissEnabled = !isDownloadingForShare,
            onDismissClick = { shareCloudTarget = null },
            confirmLabel = "Zwischenspeichern & Teilen",
            confirmEnabled = !isDownloadingForShare,
            onConfirmClick = {
                isDownloadingForShare = true
                scope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            val cacheFolder = File(context.cacheDir, "shared_drive").apply { mkdirs() }
                            val safeName = target.name.replace('/', '_')
                            val outFile = File(cacheFolder, "${target.id}_$safeName")
                            val alreadyCached = outFile.isFile &&
                                target.sizeBytes > 0L &&
                                outFile.length() == target.sizeBytes
                            if (!alreadyCached) {
                                driveProvider.openInputStream(target.path).use { input ->
                                    outFile.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }
                            }
                            outFile
                        }
                    }
                    isDownloadingForShare = false
                    shareCloudTarget = null
                    result.onSuccess { cachedFile ->
                        cacheVersionTick++
                        runCatching {
                            shareLocalFile(
                                context = context,
                                file = cachedFile,
                                mimeType = target.mimeType,
                                title = target.name,
                            )
                        }.onFailure { err ->
                            snackbarHostState.showSnackbar(err.message ?: "Teilen fehlgeschlagen.")
                        }
                    }.onFailure { err ->
                        snackbarHostState.showSnackbar(err.message ?: "Zwischenspeichern fehlgeschlagen.")
                    }
                }
            },
        ) {
            if (isDownloadingForShare) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("„${target.name}“ wird zwischengespeichert…")
                }
            } else {
                Text("„${target.name}“ liegt auf Google Drive. Zum Teilen kurz zwischenspeichern?")
            }
        }
    }

    if (showSortDialog) {
        MonolithDialog(
            onDismissRequest = { showSortDialog = false },
            title = "Sortieren nach",
            confirmLabel = "Schließen",
            onConfirmClick = { showSortDialog = false },
        ) {
            val options = listOf(
                SortBy.NAME to "Name",
                SortBy.SIZE to "Größe",
                SortBy.DATE_MODIFIED to "Datum",
                SortBy.TYPE to "Typ",
            )
            options.forEach { (option, label) ->
                MonolithDialogOption(
                    label = label,
                    selected = sortBy == option,
                    onClick = {
                        sortBy = option
                        showSortDialog = false
                    },
                )
            }
        }
    }

    if (showCreateFolderDialog) {
        var folderName by remember { mutableStateOf("") }
        MonolithDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = "Neuer Ordner",
            dismissLabel = "Abbrechen",
            onDismissClick = { showCreateFolderDialog = false },
            confirmLabel = "Erstellen",
            confirmEnabled = folderName.isNotBlank(),
            onConfirmClick = {
                val name = folderName
                showCreateFolderDialog = false
                runFileOperation {
                    activeProvider.createDirectory(currentPath, name)
                }
            },
        ) {
            OutlinedTextField(
                value = folderName,
                onValueChange = { folderName = it },
                label = { Text("Ordnername") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    renameTargetNode?.let { target ->
        var newName by remember(target.path) { mutableStateOf(target.name) }
        MonolithDialog(
            onDismissRequest = { renameTargetNode = null },
            title = "Umbenennen",
            dismissLabel = "Abbrechen",
            onDismissClick = { renameTargetNode = null },
            confirmLabel = "Umbenennen",
            confirmEnabled = newName.isNotBlank() && newName.trim() != target.name,
            onConfirmClick = {
                val updatedName = newName
                renameTargetNode = null
                runFileOperation(
                    onSuccess = { selectedPaths = emptySet() },
                ) {
                    activeProvider.rename(target.path, updatedName)
                }
            },
        ) {
            OutlinedTextField(
                value = newName,
                onValueChange = { updated ->
                    newName = updated.replace('\n', ' ').replace('\r', ' ')
                },
                label = { Text("Neuer Name") },
                singleLine = false,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (showDeleteDialog) {
        val count = selectedPaths.size
        MonolithDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = "Löschen",
            dismissLabel = "Abbrechen",
            onDismissClick = { showDeleteDialog = false },
            confirmLabel = "Löschen",
            onConfirmClick = {
                val pathsToDelete = selectedPaths.toList()
                showDeleteDialog = false
                runFileOperation(
                    onSuccess = { selectedPaths = emptySet() },
                ) {
                    activeProvider.delete(pathsToDelete)
                }
            },
        ) {
            val message = if (count == 1) {
                val name = selectedNodes.firstOrNull()?.name ?: "Element"
                "„$name“ wirklich löschen?"
            } else {
                "$count Elemente wirklich löschen?"
            }
            Text(message)
        }
    }
}

private fun shareLocalFile(
    context: android.content.Context,
    file: File,
    mimeType: String,
    title: String,
) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType.ifBlank { "*/*" }
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(sendIntent, title))
}

@Composable
private fun ClipboardActionBar(
    count: Int,
    isMoveOperation: Boolean,
    onPaste: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column {
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$count Element(e) ${if (isMoveOperation) "ausschneiden" else "kopieren"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onPaste) {
                    Text("Hier einfügen")
                }
                TextButton(onClick = onCancel) {
                    Text("Abbrechen")
                }
            }
        }
    }
}

@Composable
private fun MiniPlayerBar(
    mediaUri: String,
    title: String,
    subtitle: String,
    isVideo: Boolean,
    isPlaying: Boolean,
    onOpen: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    val mediaPath = remember(mediaUri) {
        ThumbnailEngine.resolveMediaPath(mediaUri)
    }
    val lastModified = remember(mediaPath) {
        if (mediaPath.startsWith("/")) {
            runCatching { File(mediaPath).lastModified() }.getOrDefault(0L)
        } else {
            0L
        }
    }
    val category = if (isVideo) FileCategory.VIDEO else FileCategory.AUDIO
    val thumbnail by produceState<ImageBitmap?>(
        initialValue = ThumbnailEngine.getCachedListThumbnail(
            path = mediaPath,
            lastModifiedMs = lastModified,
        )?.asImageBitmap(),
        key1 = mediaUri,
        key2 = isVideo,
    ) {
        val cached = ThumbnailEngine.getCachedListThumbnail(
            path = mediaPath,
            lastModifiedMs = lastModified,
        )
        if (cached != null) {
            value = cached.asImageBitmap()
            return@produceState
        }
        val listThumb = ThumbnailEngine.loadListThumbnail(
            context = context,
            path = mediaPath,
            category = category,
            lastModifiedMs = lastModified,
        )
        value = (listThumb ?: ThumbnailEngine.loadMediaThumbnail(
            context = context,
            uriString = mediaUri,
            isVideo = isVideo,
        ))?.asImageBitmap()
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val bitmap = thumbnail
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = if (isVideo) "Video" else "Musik",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(MaterialTheme.shapes.small),
                        )
                    } else {
                        Icon(
                            imageVector = if (isVideo) Icons.Outlined.VideoFile else Icons.Outlined.AudioFile,
                            contentDescription = if (isVideo) "Video" else "Musik",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onTogglePlayPause) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Abspielen",
                    )
                }
                IconButton(onClick = onStop) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Beenden",
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    node: VfsNode,
    isSelected: Boolean = false,
    isOfflineCached: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val icon = when (node.category) {
        FileCategory.DIRECTORY -> Icons.Outlined.Folder
        FileCategory.VIDEO -> Icons.Outlined.VideoFile
        FileCategory.AUDIO -> Icons.Outlined.AudioFile
        FileCategory.IMAGE -> Icons.Outlined.Image
        FileCategory.ARCHIVE -> Icons.Outlined.FolderZip
        FileCategory.TEXT, FileCategory.DOCUMENT -> Icons.Outlined.Description
        else -> Icons.AutoMirrored.Outlined.InsertDriveFile
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isSelected) {
                    Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                } else {
                    Modifier
                }
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
                fallbackIcon = icon,
                isOfflineCached = isOfflineCached,
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = node.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = if (node.isDirectory) {
                node.childCount?.let { "$it Elemente" } ?: "Ordner"
            } else {
                formatBytes(node.sizeBytes)
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}
