package com.monolith.feature.explorer.archive

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.monolith.core.model.ThemeMode
import com.monolith.core.model.TransferOperation
import com.monolith.core.ui.components.CornerMenuItem
import com.monolith.core.ui.components.MonolithCornerMenuOverlay
import com.monolith.core.ui.components.MonolithDialog
import com.monolith.core.ui.components.MonolithDialogOption
import com.monolith.core.ui.components.MonolithSwitch
import com.monolith.core.ui.haptics.MonolithHaptics
import com.monolith.core.ui.theme.LocalMonolithButtonShape
import com.monolith.core.ui.theme.LocalMonolithThemeMode
import com.monolith.core.vfs.FileTransferService
import com.monolith.core.vfs.GoogleDriveVfsProvider
import com.monolith.feature.explorer.MonolithToolDestination
import com.monolith.feature.explorer.MonolithToolStarMenu
import com.monolith.feature.explorer.rememberExternalStorageVolumes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class PasswordPromptRequest(
    val archiveFile: File,
    val archiveLabel: String,
    val isWrongPassword: Boolean,
    val onSubmit: (password: String) -> Unit,
)

private data class OperationCompletionDialogState(
    val title: String,
    val message: String,
    val openFolderPath: String? = null,
    val openArchivePath: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveToolScreen(
    state: ArchiveManagerState = rememberArchiveManagerState(
        defaultRootDir = remember { Environment.getExternalStorageDirectory().absolutePath },
    ),
    launchRequest: ArchiveLaunchRequest = ArchiveLaunchRequest(),
    onBack: () -> Unit,
    onNavigateToFiles: () -> Unit,
    onNavigateToStorage: () -> Unit,
    onNavigateToDownloader: () -> Unit,
    onNavigateToAi: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenExtractedFolder: (String) -> Unit,
    onOpenPreviewFile: (File) -> Unit = {},
    modifier: Modifier = Modifier,
    onSelectApps: () -> Unit = {},
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val defaultRootDir = state.defaultRootDir
    val downloadsDirPath = remember {
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
    }
    fun nonDownloadsInitialDir(path: String): String {
        return if (path == downloadsDirPath || path.startsWith("$downloadsDirPath/")) {
            defaultRootDir
        } else {
            path
        }
    }
    val externalVolumes = rememberExternalStorageVolumes(context)
    val driveProvider = remember(context) { GoogleDriveVfsProvider(context.applicationContext) }

    suspend fun uploadTreeToDrive(localDir: File, targetDriveFolder: String): Result<Unit> = runCatching {
        suspend fun uploadItem(item: File, parentDrivePath: String) {
            if (item.isFile) {
                driveProvider.uploadFile(item, parentDrivePath).getOrThrow()
            } else if (item.isDirectory) {
                driveProvider.createDirectory(parentDrivePath, item.name).getOrThrow()
                val updated = driveProvider.list(parentDrivePath, showHidden = false, forceRefresh = true)
                val createdFolder = updated.firstOrNull { it.isDirectory && it.name == item.name }
                    ?: return
                item.listFiles()?.forEach { child ->
                    uploadItem(child, createdFolder.path)
                }
            }
        }
        localDir.listFiles()?.forEach { child ->
            uploadItem(child, targetDriveFolder)
        }
    }

    var starMenuExpanded by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var starAnchorCenter by remember { mutableStateOf(Offset.Unspecified) }
    var menuAnchorCenter by remember { mutableStateOf(Offset.Unspecified) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }

    // Dialog states
    var activePickerRequest by remember { mutableStateOf<LocalPickerRequest?>(null) }
    var passwordPrompt by remember { mutableStateOf<PasswordPromptRequest?>(null) }
    var entryActionTarget by remember { mutableStateOf<ArchiveEntryItem?>(null) }
    var renameEntryTarget by remember { mutableStateOf<ArchiveEntryItem?>(null) }
    var deleteEntriesConfirm by remember { mutableStateOf<Set<String>?>(null) }
    var convertSourceGroup by remember { mutableStateOf<ArchiveVolumeGroup?>(null) }
    var extractDialogGroup by remember { mutableStateOf<ArchiveVolumeGroup?>(null) }
    var extractDialogSelection by remember { mutableStateOf<Set<String>?>(null) }
    var completionDialog by remember { mutableStateOf<OperationCompletionDialogState?>(null) }

    fun openAndInspectArchive(
        file: File,
        explicitPassword: String? = null,
        resetInternalPath: Boolean = true,
    ) {
        val group = ArchiveEngine.resolveVolumeGroup(file)
        val effectivePassword = explicitPassword ?: state.resolvePasswordFor(group)
        state.isInspecting = true
        scope.launch {
            val result = ArchiveEngine.inspectArchive(file, effectivePassword)
            state.isInspecting = false
            result.onSuccess { summary ->
                if (summary.requiresPasswordToList) {
                    passwordPrompt = PasswordPromptRequest(
                        archiveFile = group.primaryFile,
                        archiveLabel = group.displayName,
                        isWrongPassword = false,
                        onSubmit = { pwd ->
                            openAndInspectArchive(group.primaryFile, pwd, resetInternalPath)
                        },
                    )
                } else {
                    state.openedSummary = summary
                    state.selectedEntryPaths = emptySet()
                    if (resetInternalPath) {
                        state.archiveInternalPath = ""
                    } else if (state.archiveInternalPath.isNotEmpty() &&
                        summary.entries.none { it.isDirectory && it.path == state.archiveInternalPath }
                    ) {
                        state.archiveInternalPath = ""
                    }
                    state.prepareExtractFor(group.primaryFile)
                }
            }.onFailure { err ->
                if (err is ArchivePasswordException) {
                    passwordPrompt = PasswordPromptRequest(
                        archiveFile = group.primaryFile,
                        archiveLabel = group.displayName,
                        isWrongPassword = err.isWrongPassword,
                        onSubmit = { pwd ->
                            openAndInspectArchive(group.primaryFile, pwd, resetInternalPath)
                        },
                    )
                } else {
                    snackbarHostState.showSnackbar(err.message ?: "Archiv konnte nicht geöffnet werden.")
                }
            }
        }
    }

    fun openEntryPreview(
        group: ArchiveVolumeGroup,
        entry: ArchiveEntryItem,
        explicitPassword: String? = null,
    ) {
        val pwdToUse = explicitPassword ?: state.resolvePasswordFor(group)
        state.activeJob?.cancel()
        state.activeProgress = ArchiveOperationProgress(
            title = "Vorschau wird vorbereitet…",
            currentItem = entry.name,
            processedBytes = 0L,
            totalBytes = entry.uncompressedSize.coerceAtLeast(1L),
        )
        state.activeJob = scope.launch {
            val res = ArchiveEngine.extractSingleEntryToCache(
                archiveFile = group.primaryFile,
                entryPath = entry.path,
                cacheRootDir = context.cacheDir,
                password = pwdToUse,
            )
            state.activeProgress = null
            state.activeJob = null
            res.onSuccess { previewFile ->
                onOpenPreviewFile(previewFile)
            }.onFailure { err ->
                if (err is CancellationException) return@onFailure
                if (err is ArchivePasswordException) {
                    passwordPrompt = PasswordPromptRequest(
                        archiveFile = group.primaryFile,
                        archiveLabel = group.displayName,
                        isWrongPassword = err.isWrongPassword,
                        onSubmit = { pwd -> openEntryPreview(group, entry, pwd) },
                    )
                } else {
                    snackbarHostState.showSnackbar(err.message ?: "Datei konnte nicht geöffnet werden.")
                }
            }
        }
    }

    fun launchExtractOperation(
        archiveFile: File,
        targetDir: File,
        password: String?,
        selectedEntries: Set<String>? = null,
        createSubfolder: Boolean = state.extractCreateSubfolder,
        conflictPolicy: ExtractConflictPolicy = state.extractConflictPolicy,
        deleteAfterExtract: Boolean = false,
    ) {
        val group = ArchiveEngine.resolveVolumeGroup(archiveFile)
        val pwdToUse = password?.takeIf { it.isNotEmpty() } ?: state.resolvePasswordFor(group)
        val isDriveTarget = targetDir.path.startsWith("gdrive://")
        FileTransferService.enqueueArchiveTask(
            context = context,
            operation = TransferOperation.EXTRACT,
            initialItem = group.displayName,
            totalBytes = group.totalSizeBytes.coerceAtLeast(1L),
        ) { updateProgress ->
            val effectiveLocalTarget = if (isDriveTarget) {
                File(context.cacheDir, "archive_drive_extract/${System.currentTimeMillis()}").apply { mkdirs() }
            } else {
                targetDir
            }
            val result = ArchiveEngine.extractArchive(
                file = group.primaryFile,
                targetDir = effectiveLocalTarget,
                password = pwdToUse,
                selectedEntryPaths = selectedEntries,
                createSubfolder = createSubfolder,
                conflictPolicy = conflictPolicy,
                deleteAfterExtract = deleteAfterExtract && !isDriveTarget,
                onProgress = { p ->
                    updateProgress(p.currentItem, p.processedBytes, p.totalBytes)
                },
            )
            if (result.isFailure) {
                if (isDriveTarget) effectiveLocalTarget.deleteRecursively()
                return@enqueueArchiveTask Result.failure(result.exceptionOrNull() ?: Exception("Entpacken fehlgeschlagen."))
            }
            val extractedDir = result.getOrThrow()
            if (isDriveTarget) {
                updateProgress("Wird nach Google Drive hochgeladen…", 0L, 1L)
                val uploadRes = uploadTreeToDrive(effectiveLocalTarget, targetDir.path)
                effectiveLocalTarget.deleteRecursively()
                if (uploadRes.isFailure) {
                    return@enqueueArchiveTask Result.failure(
                        uploadRes.exceptionOrNull() ?: Exception("Upload nach Google Drive fehlgeschlagen."),
                    )
                }
                Result.success("${group.displayName} → Google Drive")
            } else {
                if (deleteAfterExtract) {
                    if (state.openedSummary?.group?.primaryFile?.absolutePath == archiveFile.absolutePath) {
                        state.openedSummary = null
                    }
                    state.scanTrigger++
                }
                Result.success("${group.displayName} → ${extractedDir.name}")
            }
        }
    }

    fun launchVerifyOperation(group: ArchiveVolumeGroup, password: String? = null) {
        val pwdToUse = password?.takeIf { it.isNotEmpty() } ?: state.resolvePasswordFor(group)
        state.activeJob?.cancel()
        state.activeProgress = ArchiveOperationProgress(
            title = "Integrität wird geprüft…",
            currentItem = group.displayName,
            processedBytes = 0L,
            totalBytes = group.totalSizeBytes.coerceAtLeast(1L),
        )
        state.activeJob = scope.launch {
            val result = ArchiveEngine.verifyArchive(
                archiveFile = group.primaryFile,
                password = pwdToUse,
                onProgress = { progress -> state.activeProgress = progress },
            )
            state.activeProgress = null
            state.activeJob = null
            result.onSuccess { report ->
                completionDialog = OperationCompletionDialogState(
                    title = "Prüfung erfolgreich",
                    message = report,
                )
            }.onFailure { err ->
                if (err is CancellationException) return@onFailure
                if (err is ArchivePasswordException) {
                    passwordPrompt = PasswordPromptRequest(
                        archiveFile = group.primaryFile,
                        archiveLabel = group.displayName,
                        isWrongPassword = err.isWrongPassword,
                        onSubmit = { pwd -> launchVerifyOperation(group, pwd) },
                    )
                } else {
                    snackbarHostState.showSnackbar(err.message ?: "Archivprüfung fehlgeschlagen.")
                }
            }
        }
    }

    fun launchMergeOperation(group: ArchiveVolumeGroup) {
        val parent = group.primaryFile.parentFile ?: File(defaultRootDir)
        val mergedName = "merged_${group.displayName}"
        val outputFile = File(parent, mergedName)
        state.activeJob?.cancel()
        state.activeProgress = ArchiveOperationProgress(
            title = "Teilarchive werden zusammengeführt…",
            currentItem = group.displayName,
            processedBytes = 0L,
            totalBytes = group.totalSizeBytes.coerceAtLeast(1L),
        )
        state.activeJob = scope.launch {
            val result = ArchiveEngine.mergeSplitArchive(
                archiveFile = group.primaryFile,
                outputFile = outputFile,
                onProgress = { progress -> state.activeProgress = progress },
            )
            state.activeProgress = null
            state.activeJob = null
            result.onSuccess { mergedFile ->
                state.scanTrigger++
                completionDialog = OperationCompletionDialogState(
                    title = "Zusammenführen abgeschlossen",
                    message = "Einzelarchiv erstellt: „${mergedFile.name}“ (${formatArchiveBytes(mergedFile.length())}).",
                    openFolderPath = mergedFile.parent,
                    openArchivePath = mergedFile.absolutePath,
                )
            }.onFailure { err ->
                if (err is CancellationException) return@onFailure
                snackbarHostState.showSnackbar(err.message ?: "Zusammenführen fehlgeschlagen.")
            }
        }
    }

    fun launchAddFilesToArchive(group: ArchiveVolumeGroup) {
        activePickerRequest = LocalPickerRequest(
            mode = LocalPickerMode.FILES_AND_FOLDERS_MULTI,
            title = "Dateien & Ordner hinzufügen",
            initialDirectory = nonDownloadsInitialDir(group.primaryFile.parent ?: defaultRootDir),
            onConfirm = { paths ->
                if (paths.isEmpty()) return@LocalPickerRequest
                fun runAdd(pwd: String?) {
                    state.activeJob?.cancel()
                    state.activeProgress = ArchiveOperationProgress(
                        title = "Einträge werden hinzugefügt…",
                        currentItem = group.displayName,
                        processedBytes = 0L,
                        totalBytes = 1L,
                    )
                    state.activeJob = scope.launch {
                        val res = ArchiveEngine.addEntriesToArchive(
                            archiveFile = group.primaryFile,
                            pathsToAdd = paths,
                            targetInternalDir = state.archiveInternalPath,
                            password = pwd ?: state.resolvePasswordFor(group),
                            onProgress = { p -> state.activeProgress = p },
                        )
                        state.activeProgress = null
                        state.activeJob = null
                        res.onSuccess {
                            snackbarHostState.showSnackbar("${paths.size} Element(e) hinzugefügt.")
                            openAndInspectArchive(
                                group.primaryFile,
                                pwd ?: state.resolvePasswordFor(group),
                                resetInternalPath = false,
                            )
                        }.onFailure { err ->
                            if (err is CancellationException) return@onFailure
                            if (err is ArchivePasswordException) {
                                passwordPrompt = PasswordPromptRequest(
                                    archiveFile = group.primaryFile,
                                    archiveLabel = group.displayName,
                                    isWrongPassword = err.isWrongPassword,
                                    onSubmit = { newPwd -> runAdd(newPwd) },
                                )
                            } else {
                                snackbarHostState.showSnackbar(
                                    err.message ?: "Hinzufügen fehlgeschlagen.",
                                )
                            }
                        }
                    }
                }
                runAdd(null)
            },
        )
    }

    // Consume each incoming launchRequest once
    LaunchedEffect(launchRequest.nonce) {
        if (launchRequest.nonce > 0L && launchRequest.nonce != state.lastConsumedLaunchNonce) {
            state.lastConsumedLaunchNonce = launchRequest.nonce
            when {
                launchRequest.packSourcePaths.isNotEmpty() -> {
                    state.activeMode = launchRequest.mode ?: ArchiveToolMode.PACK
                    state.packBaseNameEdited = false
                    state.applyPackSources(launchRequest.packSourcePaths)
                }
                !launchRequest.archivePath.isNullOrBlank() -> {
                    val f = File(launchRequest.archivePath)
                    if (f.exists()) {
                        state.activeMode = ArchiveToolMode.EXTRACT
                        state.prepareExtractFor(f)
                        openAndInspectArchive(f)
                    }
                }
                launchRequest.mode != null -> {
                    state.activeMode = launchRequest.mode
                }
            }
        }
    }

    // Discover local archives in background
    LaunchedEffect(state.scanTrigger, externalVolumes) {
        if (state.discoveredArchives.isNotEmpty() && state.scanTrigger == 0) return@LaunchedEffect
        state.isScanningArchives = true
        val discovery = withContext(Dispatchers.IO) {
            val roots = listOf(File(defaultRootDir)) + externalVolumes.map { File(it.rootPath) }
            val merged = LinkedHashMap<String, ArchiveVolumeGroup>()
            var hitLimit = false
            for (root in roots) {
                val res = ArchiveEngine.discoverArchiveGroupsWithInfo(root)
                if (res.reachedScanLimit) hitLimit = true
                for (group in res.groups) {
                    val key = runCatching { group.primaryFile.canonicalPath }
                        .getOrDefault(group.primaryFile.absolutePath)
                    merged.putIfAbsent(key, group)
                }
            }
            ArchiveDiscoveryResult(
                groups = merged.values.sortedByDescending { it.primaryFile.lastModified() },
                reachedScanLimit = hitLimit,
            )
        }
        state.discoveredArchives = discovery.groups
        state.reachedScanLimit = discovery.reachedScanLimit
        state.isScanningArchives = false
    }

    // Compute total pack source size
    LaunchedEffect(state.packSourcePaths) {
        state.packTotalSourceBytes = withContext(Dispatchers.IO) {
            fun sizeOf(file: File, depth: Int): Long {
                if (!file.exists()) return 0L
                if (file.isFile) return file.length().coerceAtLeast(0L)
                if (depth > 8) return 0L
                return file.listFiles()?.sumOf { sizeOf(it, depth + 1) } ?: 0L
            }
            state.packSourcePaths.sumOf { sizeOf(File(it), 0) }
        }
    }

    BackHandler(enabled = starMenuExpanded || menuExpanded) {
        starMenuExpanded = false
        menuExpanded = false
    }

    BackHandler(enabled = !starMenuExpanded && !menuExpanded) {
        when {
            state.activeMode == ArchiveToolMode.EXTRACT && state.selectedEntryPaths.isNotEmpty() -> {
                state.selectedEntryPaths = emptySet()
            }
            state.activeMode == ArchiveToolMode.EXTRACT && state.openedSummary != null && state.archiveInternalPath.isNotEmpty() -> {
                MonolithHaptics.backNav(context, view)
                state.archiveInternalPath = state.archiveInternalPath.substringBeforeLast('/', "")
            }
            state.activeMode == ArchiveToolMode.EXTRACT && state.openedSummary != null -> {
                MonolithHaptics.backNav(context, view)
                state.openedSummary = null
                state.selectedEntryPaths = emptySet()
            }
            else -> {
                MonolithHaptics.backNav(context, view)
                onBack()
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { rootOrigin = it.positionInRoot() },
    ) {
        val backgroundRecede by animateFloatAsState(
            targetValue = if (starMenuExpanded) 1f else 0f,
            label = "archiveStarMenuRecede",
        )
        val cornerMenuRecede by animateFloatAsState(
            targetValue = if (menuExpanded) 1f else 0f,
            label = "archiveCornerMenuRecede",
        )

        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    when {
                        backgroundRecede > 0.01f -> {
                            Modifier
                                .blur(10.dp * backgroundRecede)
                                .graphicsLayer {
                                    val s = 1f - 0.025f * backgroundRecede
                                    scaleX = s
                                    scaleY = s
                                }
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
                val summary = state.openedSummary
                if (state.activeMode == ArchiveToolMode.EXTRACT && summary != null && state.selectedEntryPaths.isNotEmpty()) {
                    val isModifiable = !summary.group.isMultiVolume && summary.group.format.canCreate
                    val currentFolderEntries = remember(summary, state.archiveInternalPath) {
                        summary.entries.filter { it.parentPath == state.archiveInternalPath }
                    }
                    TopAppBar(
                        title = {
                            Text(
                                text = "${state.selectedEntryPaths.size}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = { state.selectedEntryPaths = emptySet() }) {
                                Icon(Icons.Default.Close, contentDescription = "Auswahl aufheben")
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = {
                                    state.selectedEntryPaths = if (
                                        currentFolderEntries.isNotEmpty() &&
                                        currentFolderEntries.all { it.path in state.selectedEntryPaths }
                                    ) {
                                        emptySet()
                                    } else {
                                        currentFolderEntries.map { it.path }.toSet()
                                    }
                                },
                            ) {
                                Icon(Icons.Outlined.SelectAll, contentDescription = "Alle auswählen")
                            }
                            if (isModifiable && state.selectedEntryPaths.size == 1) {
                                val targetPath = state.selectedEntryPaths.first()
                                val targetItem = summary.entries.firstOrNull { it.path == targetPath }
                                if (targetItem != null) {
                                    IconButton(onClick = { renameEntryTarget = targetItem }) {
                                        Icon(Icons.Outlined.Edit, contentDescription = "Umbenennen")
                                    }
                                }
                            }
                            if (isModifiable) {
                                IconButton(onClick = { deleteEntriesConfirm = state.selectedEntryPaths }) {
                                    Icon(Icons.Outlined.Delete, contentDescription = "Aus Archiv löschen")
                                }
                            }
                        },
                    )
                } else {
                    TopAppBar(
                        title = {
                            Text(
                                text = "Archivmanager",
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
                                    imageVector = Icons.Outlined.FolderZip,
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
                }
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                // Clean 2-tab selector: Entpacken & Packen
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ArchiveToolMode.entries.forEach { mode ->
                        FilterChip(
                            selected = state.activeMode == mode,
                            shape = LocalMonolithButtonShape.current,
                            onClick = {
                                state.activeMode = mode
                                state.selectedEntryPaths = emptySet()
                            },
                            label = { Text(mode.label) },
                        )
                    }
                }

                when (state.activeMode) {
                    ArchiveToolMode.EXTRACT -> {
                        ExtractArchiveTab(
                            summary = state.openedSummary,
                            isInspecting = state.isInspecting,
                            isScanning = state.isScanningArchives,
                            reachedScanLimit = state.reachedScanLimit,
                            discoveredArchives = state.discoveredArchives,
                            searchQuery = state.archiveSearchQuery,
                            archiveInternalPath = state.archiveInternalPath,
                            selectedEntryPaths = state.selectedEntryPaths,
                            hasSavedPassword = state.openedSummary?.let { state.resolvePasswordFor(it.group) != null } == true,
                            onSearchQueryChange = { state.archiveSearchQuery = it },
                            onRefreshScan = { state.scanTrigger++ },
                            onPickArchiveToOpen = {
                                activePickerRequest = LocalPickerRequest(
                                    mode = LocalPickerMode.SINGLE_FILE,
                                    title = "Archiv öffnen",
                                    initialDirectory = defaultRootDir,
                                    archiveHighlight = true,
                                    onConfirm = { paths ->
                                        paths.firstOrNull()?.let { openAndInspectArchive(File(it)) }
                                    },
                                )
                            },
                            onOpenArchiveGroup = { group -> openAndInspectArchive(group.primaryFile) },
                            onCloseArchive = {
                                state.openedSummary = null
                                state.archiveInternalPath = ""
                                state.selectedEntryPaths = emptySet()
                            },
                            onExtractClick = {
                                val summary = state.openedSummary ?: return@ExtractArchiveTab
                                state.prepareExtractFor(summary.group.primaryFile)
                                extractDialogSelection = state.selectedEntryPaths.takeIf { it.isNotEmpty() }
                                extractDialogGroup = summary.group
                            },
                            onRequestPasswordEdit = { group ->
                                passwordPrompt = PasswordPromptRequest(
                                    archiveFile = group.primaryFile,
                                    archiveLabel = group.displayName,
                                    isWrongPassword = false,
                                    onSubmit = { pwd ->
                                        openAndInspectArchive(group.primaryFile, pwd, resetInternalPath = false)
                                    },
                                )
                            },
                            onNavigateInternalPath = { newPath ->
                                MonolithHaptics.folderOpen(context, view)
                                state.archiveInternalPath = newPath
                                state.selectedEntryPaths = emptySet()
                            },
                            onEntryClick = { entry ->
                                if (state.selectedEntryPaths.isNotEmpty()) {
                                    state.selectedEntryPaths = if (entry.path in state.selectedEntryPaths) {
                                        state.selectedEntryPaths - entry.path
                                    } else {
                                        state.selectedEntryPaths + entry.path
                                    }
                                } else if (entry.isDirectory) {
                                    MonolithHaptics.folderOpen(context, view)
                                    state.archiveInternalPath = entry.path
                                } else {
                                    state.openedSummary?.let { summary ->
                                        openEntryPreview(summary.group, entry)
                                    } ?: run { entryActionTarget = entry }
                                }
                            },
                            onEntryLongClick = { entry ->
                                MonolithHaptics.dragLift(view)
                                state.selectedEntryPaths = if (entry.path in state.selectedEntryPaths) {
                                    state.selectedEntryPaths - entry.path
                                } else {
                                    state.selectedEntryPaths + entry.path
                                }
                            },
                            onEntryMoreClick = { entry -> entryActionTarget = entry },
                        )
                    }

                    ArchiveToolMode.PACK -> {
                        PackArchiveContent(
                            sourcePaths = state.packSourcePaths,
                            totalSourceBytes = state.packTotalSourceBytes,
                            baseName = state.packBaseName,
                            outputDir = state.packOutputDir,
                            format = state.packFormat,
                            compressionLevel = state.packCompression,
                            encryptionEnabled = state.packEncryptionEnabled,
                            encryptionMethod = state.packEncryptionMethod,
                            password = state.packPassword,
                            passwordConfirm = state.packPasswordConfirm,
                            encryptFilenames = state.packEncryptFilenames,
                            splitEnabled = state.packSplitEnabled,
                            splitPreset = state.packSplitPreset,
                            splitCustomSelected = state.packSplitCustomSelected,
                            splitCustomMb = state.packSplitCustomMb,
                            solidArchive = state.packSolidArchive,
                            onPickSources = {
                                activePickerRequest = LocalPickerRequest(
                                    mode = LocalPickerMode.FILES_AND_FOLDERS_MULTI,
                                    title = "Dateien & Ordner wählen",
                                    initialDirectory = nonDownloadsInitialDir(state.packOutputDir),
                                    onConfirm = { picked ->
                                        state.applyPackSources(state.packSourcePaths + picked)
                                    },
                                )
                            },
                            onRemoveSource = { path -> state.packSourcePaths = state.packSourcePaths - path },
                            onClearSources = { state.packSourcePaths = emptyList() },
                            onBaseNameChange = {
                                state.packBaseNameEdited = true
                                state.packBaseName = it.replace('\n', ' ').replace('\r', ' ')
                            },
                            onPickOutputDir = {
                                activePickerRequest = LocalPickerRequest(
                                    mode = LocalPickerMode.SINGLE_DIRECTORY,
                                    title = "Zielordner wählen",
                                    initialDirectory = nonDownloadsInitialDir(state.packOutputDir),
                                    onConfirm = { dirs -> dirs.firstOrNull()?.let { state.packOutputDir = it } },
                                )
                            },
                            onFormatChange = { newFmt ->
                                state.packFormat = newFmt
                                if (!newFmt.supportsEncryption) {
                                    state.packEncryptionEnabled = false
                                    state.packEncryptFilenames = false
                                } else if (newFmt == ArchiveFormat.SEVEN_Z) {
                                    state.packEncryptionMethod = ArchiveEncryptionMethod.AES_256
                                } else {
                                    state.packEncryptFilenames = false
                                }
                            },
                            onCompressionChange = { state.packCompression = it },
                            onEncryptionEnabledChange = { state.packEncryptionEnabled = it },
                            onEncryptionMethodChange = { state.packEncryptionMethod = it },
                            onPasswordChange = { state.packPassword = it },
                            onPasswordConfirmChange = { state.packPasswordConfirm = it },
                            onEncryptFilenamesChange = { state.packEncryptFilenames = it },
                            onSplitEnabledChange = { state.packSplitEnabled = it },
                            onSplitPresetChange = {
                                state.packSplitCustomSelected = false
                                state.packSplitPreset = it
                            },
                            onSelectCustomSplit = { state.packSplitCustomSelected = true },
                            onCustomSplitMbChange = { raw ->
                                state.packSplitCustomMb = raw.filter { it.isDigit() }.take(6)
                            },
                            onSolidArchiveChange = { state.packSolidArchive = it },
                            onCreateArchive = {
                                val splitBytes = if (!state.packSplitEnabled) {
                                    null
                                } else if (state.packSplitCustomSelected) {
                                    val mb = state.packSplitCustomMb.toLongOrNull() ?: 0L
                                    if (mb <= 0L) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Bitte eine gültige Teilgröße in MB eingeben.")
                                        }
                                        return@PackArchiveContent
                                    }
                                    mb * 1024L * 1024L
                                } else {
                                    state.packSplitPreset.sizeBytes
                                }

                                val effectiveEnc = if (state.packFormat.supportsEncryption && state.packEncryptionEnabled) {
                                    if (state.packFormat == ArchiveFormat.SEVEN_Z) {
                                        ArchiveEncryptionMethod.AES_256
                                    } else {
                                        state.packEncryptionMethod
                                    }
                                } else {
                                    ArchiveEncryptionMethod.NONE
                                }
                                if (effectiveEnc != ArchiveEncryptionMethod.NONE) {
                                    if (state.packPassword.isEmpty()) {
                                        scope.launch { snackbarHostState.showSnackbar("Bitte ein Passwort eingeben.") }
                                        return@PackArchiveContent
                                    }
                                    if (state.packPassword != state.packPasswordConfirm) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Die Passwörter stimmen nicht überein.")
                                        }
                                        return@PackArchiveContent
                                    }
                                }

                                val isDriveOutput = state.packOutputDir.startsWith("gdrive://")
                                val effectiveOutputDir = if (isDriveOutput) {
                                    File(context.cacheDir, "archive_drive_pack/${System.currentTimeMillis()}")
                                        .apply { mkdirs() }
                                        .absolutePath
                                } else {
                                    state.packOutputDir
                                }
                                val request = PackArchiveRequest(
                                    sourcePaths = state.packSourcePaths,
                                    outputDirectory = effectiveOutputDir,
                                    archiveBaseName = state.packBaseName,
                                    format = state.packFormat,
                                    compressionLevel = state.packCompression,
                                    encryptionMethod = effectiveEnc,
                                    password = if (effectiveEnc != ArchiveEncryptionMethod.NONE) state.packPassword else "",
                                    encryptFilenames = effectiveEnc != ArchiveEncryptionMethod.NONE &&
                                        state.packFormat.supportsFilenameEncryption &&
                                        state.packEncryptFilenames,
                                    splitVolumeBytes = splitBytes,
                                    solidArchive = state.packSolidArchive,
                                )

                                MonolithHaptics.launchItem(context)
                                FileTransferService.enqueueArchiveTask(
                                    context = context,
                                    operation = TransferOperation.COMPRESS,
                                    initialItem = state.packBaseName,
                                    totalBytes = state.packTotalSourceBytes.coerceAtLeast(1L),
                                ) { updateProgress ->
                                    val res = ArchiveEngine.createArchive(
                                        request = request,
                                        onProgress = { p ->
                                            updateProgress(p.currentItem, p.processedBytes, p.totalBytes)
                                        },
                                    )
                                    if (res.isFailure) {
                                        if (isDriveOutput) File(effectiveOutputDir).deleteRecursively()
                                        return@enqueueArchiveTask Result.failure(
                                            res.exceptionOrNull() ?: Exception("Packen fehlgeschlagen."),
                                        )
                                    }
                                    val createdGroup = res.getOrThrow()
                                    if (request.password.isNotEmpty()) {
                                        state.rememberedPasswords[state.passwordKeyFor(createdGroup.primaryFile)] =
                                            request.password
                                    }
                                    if (isDriveOutput) {
                                        updateProgress("Wird nach Google Drive hochgeladen…", 0L, 1L)
                                        var uploadErr: Throwable? = null
                                        for (part in createdGroup.parts) {
                                            val up = driveProvider.uploadFile(part, state.packOutputDir)
                                            if (up.isFailure) {
                                                uploadErr = up.exceptionOrNull()
                                                break
                                            }
                                        }
                                        File(effectiveOutputDir).deleteRecursively()
                                        if (uploadErr != null) {
                                            return@enqueueArchiveTask Result.failure(uploadErr)
                                        }
                                        Result.success("${createdGroup.displayName} → Google Drive")
                                    } else {
                                        state.scanTrigger++
                                        Result.success(createdGroup.displayName)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }

        MonolithToolStarMenu(
            expanded = starMenuExpanded,
            anchorCenter = starAnchorCenter,
            anchorIcon = Icons.Outlined.FolderZip,
            activeDestination = MonolithToolDestination.ARCHIVES,
            onSelectFiles = onNavigateToFiles,
            onSelectArchives = { starMenuExpanded = false },
            onSelectStorage = onNavigateToStorage,
            onSelectDownloader = onNavigateToDownloader,
            onSelectApps = onSelectApps,
            onSelectAi = onNavigateToAi,
            onDismiss = { starMenuExpanded = false },
        )

        val openedGroup = state.openedSummary?.group
        val menuItems = buildList {
            if (openedGroup != null) {
                val isModifiable = !openedGroup.isMultiVolume && openedGroup.format.canCreate
                if (isModifiable) {
                    add(
                        CornerMenuItem(
                            id = "add_files",
                            label = "Dateien hinzufügen",
                            onClick = { launchAddFilesToArchive(openedGroup) },
                        ),
                    )
                }
                add(
                    CornerMenuItem(
                        id = "verify_archive",
                        label = "Archiv prüfen",
                        onClick = { launchVerifyOperation(openedGroup) },
                    ),
                )
                add(
                    CornerMenuItem(
                        id = "convert_archive",
                        label = "Format konvertieren",
                        onClick = { convertSourceGroup = openedGroup },
                    ),
                )
                if (ArchiveEngine.canBinaryMerge(openedGroup)) {
                    add(
                        CornerMenuItem(
                            id = "merge_archive",
                            label = "Teile zusammenführen",
                            onClick = { launchMergeOperation(openedGroup) },
                        ),
                    )
                }
            }
            add(
                CornerMenuItem(
                    id = "open_archive",
                    label = "Anderes Archiv öffnen",
                    onClick = {
                        activePickerRequest = LocalPickerRequest(
                            mode = LocalPickerMode.SINGLE_FILE,
                            title = "Archiv öffnen",
                            initialDirectory = defaultRootDir,
                            archiveHighlight = true,
                            onConfirm = { paths ->
                                paths.firstOrNull()?.let {
                                    state.activeMode = ArchiveToolMode.EXTRACT
                                    openAndInspectArchive(File(it))
                                }
                            },
                        )
                    },
                ),
            )
            add(
                CornerMenuItem(
                    id = "rescan_archives",
                    label = "Archive neu scannen",
                    onClick = { state.scanTrigger++ },
                ),
            )
            add(
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
            items = menuItems,
            onDismiss = { menuExpanded = false },
        )
    }

    // -------------------------------------------------------------------------
    // Dialogs
    // -------------------------------------------------------------------------

    activePickerRequest?.let { req ->
        LocalPathPickerDialog(
            request = req,
            defaultRootDir = defaultRootDir,
            onDismiss = { activePickerRequest = null },
        )
    }

    extractDialogGroup?.let { grp ->
        var showPwd by remember(grp) { mutableStateOf(false) }
        MonolithDialog(
            onDismissRequest = { extractDialogGroup = null },
            title = if (extractDialogSelection != null) {
                "Auswahl entpacken (${extractDialogSelection?.size ?: 0})"
            } else {
                "Entpacken: ${grp.displayName}"
            },
            dismissLabel = "Abbrechen",
            onDismissClick = { extractDialogGroup = null },
            confirmLabel = "Jetzt entpacken",
            onConfirmClick = {
                val chosenSelection = extractDialogSelection
                extractDialogGroup = null
                extractDialogSelection = null
                state.selectedEntryPaths = emptySet()
                MonolithHaptics.launchItem(context)
                launchExtractOperation(
                    archiveFile = grp.primaryFile,
                    targetDir = File(state.extractTargetDir),
                    password = state.extractPassword.takeIf { it.isNotEmpty() },
                    selectedEntries = chosenSelection,
                    createSubfolder = state.extractCreateSubfolder,
                    conflictPolicy = state.extractConflictPolicy,
                    deleteAfterExtract = state.extractDeleteAfter && chosenSelection == null,
                )
            },
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = CutCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                onClick = {
                    activePickerRequest = LocalPickerRequest(
                        mode = LocalPickerMode.SINGLE_DIRECTORY,
                        title = "Zielordner wählen",
                        initialDirectory = nonDownloadsInitialDir(state.extractTargetDir),
                        onConfirm = { dirs -> dirs.firstOrNull()?.let { state.extractTargetDir = it } },
                    )
                },
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
                            text = "Zielordner",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = state.extractTargetDir,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = "Ändern",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (extractDialogSelection == null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "In eigenen Unterordner entpacken",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    MonolithSwitch(
                        checked = state.extractCreateSubfolder,
                        onCheckedChange = { state.extractCreateSubfolder = it },
                    )
                }
            }

            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

            Text(
                text = "Bei vorhandenen Dateien",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ExtractConflictPolicy.entries.forEach { policy ->
                MonolithDialogOption(
                    label = policy.label,
                    selected = state.extractConflictPolicy == policy,
                    onClick = { state.extractConflictPolicy = policy },
                )
            }

            if (state.openedSummary?.isEncrypted == true && state.resolvePasswordFor(grp).isNullOrEmpty()) {
                OutlinedTextField(
                    value = state.extractPassword,
                    onValueChange = { state.extractPassword = it },
                    label = { Text("Passwort (falls erforderlich)") },
                    singleLine = true,
                    visualTransformation = if (showPwd) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPwd = !showPwd }) {
                            Icon(
                                imageVector = if (showPwd) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (showPwd) "Verbergen" else "Anzeigen",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (extractDialogSelection == null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Archiv nach Entpacken löschen",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    MonolithSwitch(
                        checked = state.extractDeleteAfter,
                        onCheckedChange = { state.extractDeleteAfter = it },
                    )
                }
            }
        }
    }

    passwordPrompt?.let { prompt ->
        var inputPwd by remember(prompt) { mutableStateOf("") }
        var showPwd by remember(prompt) { mutableStateOf(false) }
        var rememberSession by remember(prompt) { mutableStateOf(true) }

        MonolithDialog(
            onDismissRequest = { passwordPrompt = null },
            title = if (prompt.isWrongPassword) "Falsches Passwort" else "Passwort erforderlich",
            dismissLabel = "Abbrechen",
            onDismissClick = { passwordPrompt = null },
            confirmLabel = "Entsperren",
            confirmEnabled = inputPwd.isNotEmpty(),
            onConfirmClick = {
                val pwd = inputPwd
                if (rememberSession) {
                    state.rememberedPasswords[state.passwordKeyFor(prompt.archiveFile)] = pwd
                }
                passwordPrompt = null
                prompt.onSubmit(pwd)
            },
        ) {
            Text(
                text = if (prompt.isWrongPassword) {
                    "Das eingegebene Passwort für „${prompt.archiveLabel}“ war falsch."
                } else {
                    "„${prompt.archiveLabel}“ ist passwortgeschützt."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = inputPwd,
                onValueChange = { inputPwd = it },
                label = { Text("Passwort") },
                singleLine = true,
                visualTransformation = if (showPwd) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPwd = !showPwd }) {
                        Icon(
                            imageVector = if (showPwd) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (showPwd) "Verbergen" else "Anzeigen",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Passwort für diese Sitzung merken",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                MonolithSwitch(
                    checked = rememberSession,
                    onCheckedChange = { rememberSession = it },
                )
            }
        }
    }

    entryActionTarget?.let { entry ->
        val summary = state.openedSummary
        val isModifiable = summary != null && !summary.group.isMultiVolume && summary.group.format.canCreate
        MonolithDialog(
            onDismissRequest = { entryActionTarget = null },
            title = entry.name,
            confirmLabel = "Schließen",
            onConfirmClick = { entryActionTarget = null },
        ) {
            if (!entry.isDirectory) {
                Text(
                    text = "Größe: ${formatArchiveBytes(entry.uncompressedSize)}" +
                        if (entry.compressedSize > 0L) " (${formatArchiveBytes(entry.compressedSize)} gepackt)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (summary != null) {
                if (!entry.isDirectory) {
                    MonolithDialogOption(
                        label = "Öffnen / Vorschau",
                        subtitle = "Datei direkt aus dem Archiv anzeigen",
                        selected = false,
                        onClick = {
                            entryActionTarget = null
                            openEntryPreview(summary.group, entry)
                        },
                    )
                }
                MonolithDialogOption(
                    label = "Entpacken",
                    subtitle = "In einen Zielordner entpacken",
                    selected = false,
                    onClick = {
                        entryActionTarget = null
                        val initialDir = nonDownloadsInitialDir(summary.group.primaryFile.parent ?: defaultRootDir)
                        activePickerRequest = LocalPickerRequest(
                            mode = LocalPickerMode.SINGLE_DIRECTORY,
                            title = "Zielordner wählen",
                            initialDirectory = initialDir,
                            onConfirm = { dirs ->
                                val target = dirs.firstOrNull() ?: return@LocalPickerRequest
                                launchExtractOperation(
                                    archiveFile = summary.group.primaryFile,
                                    targetDir = File(target),
                                    password = null,
                                    selectedEntries = setOf(entry.path),
                                    createSubfolder = false,
                                    deleteAfterExtract = false,
                                )
                            },
                        )
                    },
                )
                if (isModifiable) {
                    MonolithDialogOption(
                        label = "Umbenennen",
                        subtitle = "Eintrag im Archiv umbenennen",
                        selected = false,
                        onClick = {
                            entryActionTarget = null
                            renameEntryTarget = entry
                        },
                    )
                    MonolithDialogOption(
                        label = "Aus Archiv löschen",
                        subtitle = "Eintrag dauerhaft aus dem Archiv entfernen",
                        selected = false,
                        onClick = {
                            entryActionTarget = null
                            deleteEntriesConfirm = setOf(entry.path)
                        },
                    )
                }
            }
        }
    }

    renameEntryTarget?.let { target ->
        val summary = state.openedSummary
        var newName by remember(target.path) { mutableStateOf(target.name) }
        MonolithDialog(
            onDismissRequest = { renameEntryTarget = null },
            title = "Im Archiv umbenennen",
            dismissLabel = "Abbrechen",
            onDismissClick = { renameEntryTarget = null },
            confirmLabel = "Umbenennen",
            confirmEnabled = newName.isNotBlank() && newName.trim() != target.name && summary != null,
            onConfirmClick = {
                val grp = summary?.group ?: return@MonolithDialog
                val updated = newName.trim()
                renameEntryTarget = null
                fun runRename(pwd: String?) {
                    state.activeJob?.cancel()
                    state.activeProgress = ArchiveOperationProgress(
                        title = "Eintrag wird umbenannt…",
                        currentItem = updated,
                        processedBytes = 0L,
                        totalBytes = 1L,
                    )
                    state.activeJob = scope.launch {
                        val res = ArchiveEngine.renameEntryInArchive(
                            archiveFile = grp.primaryFile,
                            oldEntryPath = target.path,
                            newName = updated,
                            password = pwd ?: state.resolvePasswordFor(grp),
                        )
                        state.activeProgress = null
                        state.activeJob = null
                        res.onSuccess {
                            state.selectedEntryPaths = emptySet()
                            snackbarHostState.showSnackbar("Umbenannt zu „$updated“.")
                            openAndInspectArchive(
                                grp.primaryFile,
                                pwd ?: state.resolvePasswordFor(grp),
                                resetInternalPath = false,
                            )
                        }.onFailure { err ->
                            if (err is CancellationException) return@onFailure
                            if (err is ArchivePasswordException) {
                                passwordPrompt = PasswordPromptRequest(
                                    archiveFile = grp.primaryFile,
                                    archiveLabel = grp.displayName,
                                    isWrongPassword = err.isWrongPassword,
                                    onSubmit = { newPwd -> runRename(newPwd) },
                                )
                            } else {
                                snackbarHostState.showSnackbar(err.message ?: "Umbenennen fehlgeschlagen.")
                            }
                        }
                    }
                }
                runRename(null)
            },
        ) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it.replace('\n', ' ').replace('\r', ' ') },
                label = { Text("Neuer Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    deleteEntriesConfirm?.let { pathsToDelete ->
        val summary = state.openedSummary
        MonolithDialog(
            onDismissRequest = { deleteEntriesConfirm = null },
            title = "Aus Archiv löschen",
            dismissLabel = "Abbrechen",
            onDismissClick = { deleteEntriesConfirm = null },
            confirmLabel = "Löschen",
            onConfirmClick = {
                val grp = summary?.group ?: return@MonolithDialog
                deleteEntriesConfirm = null
                fun runDelete(pwd: String?) {
                    state.activeJob?.cancel()
                    state.activeProgress = ArchiveOperationProgress(
                        title = "Einträge werden gelöscht…",
                        currentItem = grp.displayName,
                        processedBytes = 0L,
                        totalBytes = 1L,
                    )
                    state.activeJob = scope.launch {
                        val res = ArchiveEngine.deleteEntriesFromArchive(
                            archiveFile = grp.primaryFile,
                            entryPaths = pathsToDelete,
                            password = pwd ?: state.resolvePasswordFor(grp),
                            onProgress = { p -> state.activeProgress = p },
                        )
                        state.activeProgress = null
                        state.activeJob = null
                        res.onSuccess {
                            state.selectedEntryPaths = emptySet()
                            snackbarHostState.showSnackbar("${pathsToDelete.size} Eintrag/Einträge gelöscht.")
                            openAndInspectArchive(
                                grp.primaryFile,
                                pwd ?: state.resolvePasswordFor(grp),
                                resetInternalPath = false,
                            )
                        }.onFailure { err ->
                            if (err is CancellationException) return@onFailure
                            if (err is ArchivePasswordException) {
                                passwordPrompt = PasswordPromptRequest(
                                    archiveFile = grp.primaryFile,
                                    archiveLabel = grp.displayName,
                                    isWrongPassword = err.isWrongPassword,
                                    onSubmit = { newPwd -> runDelete(newPwd) },
                                )
                            } else {
                                snackbarHostState.showSnackbar(err.message ?: "Löschen fehlgeschlagen.")
                            }
                        }
                    }
                }
                runDelete(null)
            },
        ) {
            val text = if (pathsToDelete.size == 1) {
                "„${pathsToDelete.first().substringAfterLast('/')}“ wirklich aus dem Archiv entfernen?"
            } else {
                "${pathsToDelete.size} Einträge wirklich aus dem Archiv entfernen?"
            }
            Text(text)
        }
    }

    convertSourceGroup?.let { srcGroup ->
        val creatableFormats = remember { ArchiveFormat.entries.filter { it.canCreate } }
        var targetFormat by remember(srcGroup) {
            mutableStateOf(
                creatableFormats.firstOrNull { it != srcGroup.format } ?: ArchiveFormat.ZIP,
            )
        }
        var useNewPassword by remember(srcGroup) { mutableStateOf(false) }
        var newPassword by remember(srcGroup) { mutableStateOf("") }
        var showNewPwd by remember(srcGroup) { mutableStateOf(false) }

        MonolithDialog(
            onDismissRequest = { convertSourceGroup = null },
            title = "Archiv konvertieren",
            dismissLabel = "Abbrechen",
            onDismissClick = { convertSourceGroup = null },
            confirmLabel = "Konvertieren",
            confirmEnabled = !useNewPassword || !targetFormat.supportsEncryption || newPassword.isNotEmpty(),
            onConfirmClick = {
                val chosenFormat = targetFormat
                val chosenNewPwd = if (useNewPassword && chosenFormat.supportsEncryption) newPassword else null
                convertSourceGroup = null
                fun runConvert(sourcePwd: String?) {
                    state.activeJob?.cancel()
                    state.activeProgress = ArchiveOperationProgress(
                        title = "Archiv wird konvertiert…",
                        currentItem = srcGroup.displayName,
                        processedBytes = 0L,
                        totalBytes = srcGroup.totalSizeBytes.coerceAtLeast(1L),
                    )
                    state.activeJob = scope.launch {
                        val res = ArchiveEngine.convertToFormat(
                            archiveFile = srcGroup.primaryFile,
                            targetFormat = chosenFormat,
                            password = sourcePwd ?: state.resolvePasswordFor(srcGroup),
                            newPassword = chosenNewPwd,
                            onProgress = { p -> state.activeProgress = p },
                        )
                        state.activeProgress = null
                        state.activeJob = null
                        res.onSuccess { newFile ->
                            if (!chosenNewPwd.isNullOrEmpty()) {
                                state.rememberedPasswords[state.passwordKeyFor(newFile)] = chosenNewPwd
                            }
                            state.scanTrigger++
                            completionDialog = OperationCompletionDialogState(
                                title = "Konvertierung abgeschlossen",
                                message = "Erstellt: „${newFile.name}“ (${formatArchiveBytes(newFile.length())}).",
                                openFolderPath = newFile.parent,
                                openArchivePath = newFile.absolutePath,
                            )
                        }.onFailure { err ->
                            if (err is CancellationException) return@onFailure
                            if (err is ArchivePasswordException) {
                                passwordPrompt = PasswordPromptRequest(
                                    archiveFile = srcGroup.primaryFile,
                                    archiveLabel = srcGroup.displayName,
                                    isWrongPassword = err.isWrongPassword,
                                    onSubmit = { pwd -> runConvert(pwd) },
                                )
                            } else {
                                snackbarHostState.showSnackbar(err.message ?: "Konvertierung fehlgeschlagen.")
                            }
                        }
                    }
                }
                runConvert(null)
            },
        ) {
            Text(
                text = "Zielformat für „${srcGroup.displayName}“:",
                style = MaterialTheme.typography.bodyMedium,
            )
            creatableFormats.forEach { fmt ->
                MonolithDialogOption(
                    label = fmt.label,
                    subtitle = when {
                        fmt.supportsEncryption -> "Mit optionaler AES-256-Verschlüsselung"
                        else -> "Ohne Verschlüsselung"
                    },
                    selected = targetFormat == fmt,
                    onClick = { targetFormat = fmt },
                )
            }
            if (targetFormat.supportsEncryption) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Neues Passwort setzen",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    MonolithSwitch(
                        checked = useNewPassword,
                        onCheckedChange = { useNewPassword = it },
                    )
                }
                AnimatedVisibility(visible = useNewPassword) {
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        label = { Text("Neues Passwort (AES-256)") },
                        singleLine = true,
                        visualTransformation = if (showNewPwd) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showNewPwd = !showNewPwd }) {
                                Icon(
                                    imageVector = if (showNewPwd) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = if (showNewPwd) "Verbergen" else "Anzeigen",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    state.activeProgress?.let { prog ->
        val fraction = if (prog.totalBytes > 0L) {
            (prog.processedBytes.toFloat() / prog.totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        MonolithDialog(
            onDismissRequest = {},
            title = prog.title,
            dismissLabel = "Abbrechen",
            onDismissClick = {
                state.activeJob?.cancel()
                state.activeJob = null
                state.activeProgress = null
            },
        ) {
            if (prog.currentItem.isNotBlank()) {
                Text(
                    text = prog.currentItem,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (prog.totalBytes > 1L) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "${formatArchiveBytes(prog.processedBytes)} / ${formatArchiveBytes(prog.totalBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${(fraction * 100f).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }

    completionDialog?.let { done ->
        val hasFollowUpAction = done.openArchivePath != null || done.openFolderPath != null
        MonolithDialog(
            onDismissRequest = { completionDialog = null },
            title = done.title,
            dismissLabel = if (hasFollowUpAction) "Schließen" else null,
            onDismissClick = if (hasFollowUpAction) ({ completionDialog = null }) else null,
            confirmLabel = when {
                done.openArchivePath != null -> "Archiv öffnen"
                done.openFolderPath != null -> "Zielordner öffnen"
                else -> "Schließen"
            },
            onConfirmClick = {
                completionDialog = null
                when {
                    done.openArchivePath != null -> {
                        state.activeMode = ArchiveToolMode.EXTRACT
                        openAndInspectArchive(File(done.openArchivePath))
                    }
                    done.openFolderPath != null -> {
                        onOpenExtractedFolder(done.openFolderPath)
                    }
                }
            },
        ) {
            Text(done.message, style = MaterialTheme.typography.bodyMedium)
            if (done.openArchivePath != null && done.openFolderPath != null) {
                OutlinedButton(
                    onClick = {
                        val folder = done.openFolderPath
                        completionDialog = null
                        onOpenExtractedFolder(folder)
                    },
                    shape = LocalMonolithButtonShape.current,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Zielordner öffnen")
                }
            }
        }
    }
}
