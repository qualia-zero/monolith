package com.monolith.feature.explorer.archive

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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Merge
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.monolith.core.common.MimeNormalizer
import com.monolith.core.model.FileCategory
import com.monolith.core.model.ThemeMode
import com.monolith.core.ui.components.CornerMenuItem
import com.monolith.core.ui.components.MonolithCornerMenuOverlay
import com.monolith.core.ui.components.MonolithDialog
import com.monolith.core.ui.components.MonolithDialogOption
import com.monolith.core.ui.components.MonolithLoadingSpinner
import com.monolith.core.ui.components.MonolithSwitch
import com.monolith.core.ui.haptics.MonolithHaptics
import com.monolith.core.ui.theme.LocalMonolithButtonShape
import com.monolith.core.ui.theme.LocalMonolithThemeMode
import com.monolith.feature.explorer.ExternalVolumeKind
import com.monolith.feature.explorer.MonolithToolDestination
import com.monolith.feature.explorer.MonolithToolStarMenu
import com.monolith.feature.explorer.rememberExternalStorageVolumes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private enum class ArchiveToolMode(val label: String) {
    MANAGE("Verwalten"),
    PACK("Packen"),
    EXTRACT("Entpacken"),
}

private enum class LocalPickerMode {
    FILES_MULTI,
    FOLDERS_MULTI,
    SINGLE_FILE,
    SINGLE_DIRECTORY,
}

private data class LocalPickerRequest(
    val mode: LocalPickerMode,
    val title: String,
    val initialDirectory: String,
    val archiveHighlight: Boolean = false,
    val onConfirm: (List<String>) -> Unit,
)

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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ArchiveToolScreen(
    initialArchivePath: String? = null,
    initialPackSourcePaths: List<String> = emptyList(),
    onBack: () -> Unit,
    onNavigateToFiles: () -> Unit,
    onNavigateToStorage: () -> Unit,
    onNavigateToDownloader: () -> Unit,
    onNavigateToAi: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenExtractedFolder: (String) -> Unit,
    modifier: Modifier = Modifier,
    onSelectApps: () -> Unit = {},
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val defaultRootDir = remember { Environment.getExternalStorageDirectory().absolutePath }
    val externalVolumes = rememberExternalStorageVolumes(context)

    var starMenuExpanded by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var starAnchorCenter by remember { mutableStateOf(Offset.Unspecified) }
    var menuAnchorCenter by remember { mutableStateOf(Offset.Unspecified) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }

    var activeMode by remember {
        mutableStateOf(
            when {
                initialPackSourcePaths.isNotEmpty() -> ArchiveToolMode.PACK
                else -> ArchiveToolMode.MANAGE
            },
        )
    }

    val rememberedPasswords = remember { mutableStateMapOf<String, String>() }

    // Manage mode state
    var discoveredArchives by remember { mutableStateOf<List<ArchiveVolumeGroup>>(emptyList()) }
    var isScanningArchives by remember { mutableStateOf(false) }
    var scanTrigger by remember { mutableIntStateOf(0) }
    var openedSummary by remember { mutableStateOf<ArchiveSummary?>(null) }
    var isInspecting by remember { mutableStateOf(false) }
    var archiveInternalPath by remember { mutableStateOf("") }
    var selectedEntryPaths by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Dialog states
    var activePickerRequest by remember { mutableStateOf<LocalPickerRequest?>(null) }
    var passwordPrompt by remember { mutableStateOf<PasswordPromptRequest?>(null) }
    var entryActionTarget by remember { mutableStateOf<ArchiveEntryItem?>(null) }
    var renameEntryTarget by remember { mutableStateOf<ArchiveEntryItem?>(null) }
    var deleteEntriesConfirm by remember { mutableStateOf<Set<String>?>(null) }
    var convertSourceGroup by remember { mutableStateOf<ArchiveVolumeGroup?>(null) }
    var activeProgress by remember { mutableStateOf<ArchiveOperationProgress?>(null) }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var completionDialog by remember { mutableStateOf<OperationCompletionDialogState?>(null) }

    // Pack mode state
    var packSourcePaths by remember { mutableStateOf(initialPackSourcePaths) }
    var packTotalSourceBytes by remember { mutableStateOf(0L) }
    var packBaseName by remember {
        mutableStateOf(
            initialPackSourcePaths.firstOrNull()?.let { File(it).nameWithoutExtension.ifBlank { File(it).name } }
                ?: "Archiv",
        )
    }
    var packBaseNameEdited by remember { mutableStateOf(false) }
    var packOutputDir by remember {
        mutableStateOf(
            initialPackSourcePaths.firstOrNull()?.let { File(it).parent } ?: defaultRootDir,
        )
    }
    var packFormat by remember { mutableStateOf(ArchiveFormat.ZIP) }
    var packCompression by remember { mutableStateOf(ArchiveCompressionLevel.NORMAL) }
    var packEncryptionEnabled by remember { mutableStateOf(false) }
    var packEncryptionMethod by remember { mutableStateOf(ArchiveEncryptionMethod.AES_256) }
    var packPassword by remember { mutableStateOf("") }
    var packPasswordConfirm by remember { mutableStateOf("") }
    var packSplitEnabled by remember { mutableStateOf(false) }
    var packSplitPreset by remember { mutableStateOf(SplitVolumePreset.MB_50) }
    var packSplitCustomSelected by remember { mutableStateOf(false) }
    var packSplitCustomMb by remember { mutableStateOf("25") }
    var packSolidArchive by remember { mutableStateOf(true) }

    // Extract mode state
    var extractArchivePath by remember { mutableStateOf(initialArchivePath.orEmpty()) }
    var extractArchiveGroup by remember { mutableStateOf<ArchiveVolumeGroup?>(null) }
    var extractTargetDir by remember {
        mutableStateOf(
            initialArchivePath?.takeIf { it.isNotBlank() }?.let { File(it).parent } ?: defaultRootDir,
        )
    }
    var extractPassword by remember { mutableStateOf("") }
    var extractCreateSubfolder by remember { mutableStateOf(true) }
    var extractConflictPolicy by remember { mutableStateOf(ExtractConflictPolicy.RENAME) }
    var extractDeleteAfter by remember { mutableStateOf(false) }

    fun passwordKeyFor(file: File): String =
        runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

    fun resolvePasswordFor(group: ArchiveVolumeGroup): String? =
        rememberedPasswords[passwordKeyFor(group.primaryFile)]

    fun openAndInspectArchive(
        file: File,
        explicitPassword: String? = null,
        resetInternalPath: Boolean = true,
    ) {
        val group = ArchiveEngine.resolveVolumeGroup(file)
        val effectivePassword = explicitPassword ?: resolvePasswordFor(group)
        isInspecting = true
        scope.launch {
            val result = ArchiveEngine.inspectArchive(file, effectivePassword)
            isInspecting = false
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
                    openedSummary = summary
                    selectedEntryPaths = emptySet()
                    if (resetInternalPath) {
                        archiveInternalPath = ""
                    } else if (archiveInternalPath.isNotEmpty() &&
                        summary.entries.none { it.isDirectory && it.path == archiveInternalPath }
                    ) {
                        archiveInternalPath = ""
                    }
                    extractArchivePath = group.primaryFile.absolutePath
                    extractTargetDir = group.primaryFile.parent ?: defaultRootDir
                    if (!effectivePassword.isNullOrEmpty()) {
                        extractPassword = effectivePassword
                    }
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

    fun launchExtractOperation(
        archiveFile: File,
        targetDir: File,
        password: String?,
        selectedEntries: Set<String>? = null,
        createSubfolder: Boolean = extractCreateSubfolder,
        conflictPolicy: ExtractConflictPolicy = extractConflictPolicy,
        deleteAfterExtract: Boolean = false,
        onCompleteSuccess: (File) -> Unit = { extractedDir ->
            completionDialog = OperationCompletionDialogState(
                title = "Entpacken abgeschlossen",
                message = "Dateien wurden nach „${extractedDir.name}“ entpackt.",
                openFolderPath = extractedDir.absolutePath,
            )
            if (deleteAfterExtract) {
                if (openedSummary?.group?.primaryFile?.absolutePath == archiveFile.absolutePath) {
                    openedSummary = null
                }
                scanTrigger++
            }
        },
    ) {
        val group = ArchiveEngine.resolveVolumeGroup(archiveFile)
        val pwdToUse = password?.takeIf { it.isNotEmpty() } ?: resolvePasswordFor(group)
        activeJob?.cancel()
        activeProgress = ArchiveOperationProgress(
            title = "Archiv wird entpackt…",
            currentItem = group.displayName,
            processedBytes = 0L,
            totalBytes = group.totalSizeBytes.coerceAtLeast(1L),
        )
        activeJob = scope.launch {
            val result = ArchiveEngine.extractArchive(
                file = group.primaryFile,
                targetDir = targetDir,
                password = pwdToUse,
                selectedEntryPaths = selectedEntries,
                createSubfolder = createSubfolder,
                conflictPolicy = conflictPolicy,
                deleteAfterExtract = deleteAfterExtract,
                onProgress = { progress -> activeProgress = progress },
            )
            activeProgress = null
            activeJob = null
            result.onSuccess { extractedDir ->
                onCompleteSuccess(extractedDir)
            }.onFailure { err ->
                if (err is CancellationException) return@onFailure
                if (err is ArchivePasswordException) {
                    passwordPrompt = PasswordPromptRequest(
                        archiveFile = group.primaryFile,
                        archiveLabel = group.displayName,
                        isWrongPassword = err.isWrongPassword,
                        onSubmit = { pwd ->
                            launchExtractOperation(
                                archiveFile = group.primaryFile,
                                targetDir = targetDir,
                                password = pwd,
                                selectedEntries = selectedEntries,
                                createSubfolder = createSubfolder,
                                conflictPolicy = conflictPolicy,
                                deleteAfterExtract = deleteAfterExtract,
                                onCompleteSuccess = onCompleteSuccess,
                            )
                        },
                    )
                } else {
                    snackbarHostState.showSnackbar(err.message ?: "Entpacken fehlgeschlagen.")
                }
            }
        }
    }

    fun launchVerifyOperation(group: ArchiveVolumeGroup, password: String? = null) {
        val pwdToUse = password?.takeIf { it.isNotEmpty() } ?: resolvePasswordFor(group)
        activeJob?.cancel()
        activeProgress = ArchiveOperationProgress(
            title = "Integrität wird geprüft…",
            currentItem = group.displayName,
            processedBytes = 0L,
            totalBytes = group.totalSizeBytes.coerceAtLeast(1L),
        )
        activeJob = scope.launch {
            val result = ArchiveEngine.verifyArchive(
                archiveFile = group.primaryFile,
                password = pwdToUse,
                onProgress = { progress -> activeProgress = progress },
            )
            activeProgress = null
            activeJob = null
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
        activeJob?.cancel()
        activeProgress = ArchiveOperationProgress(
            title = "Teilarchive werden zusammengeführt…",
            currentItem = group.displayName,
            processedBytes = 0L,
            totalBytes = group.totalSizeBytes.coerceAtLeast(1L),
        )
        activeJob = scope.launch {
            val result = ArchiveEngine.mergeSplitArchive(
                archiveFile = group.primaryFile,
                outputFile = outputFile,
                onProgress = { progress -> activeProgress = progress },
            )
            activeProgress = null
            activeJob = null
            result.onSuccess { mergedFile ->
                scanTrigger++
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

    // Handle initial arguments
    LaunchedEffect(initialArchivePath, initialPackSourcePaths) {
        if (initialPackSourcePaths.isNotEmpty()) {
            activeMode = ArchiveToolMode.PACK
            packSourcePaths = initialPackSourcePaths
            if (!packBaseNameEdited) {
                val first = File(initialPackSourcePaths.first())
                packBaseName = first.nameWithoutExtension.ifBlank { first.name }
                packOutputDir = first.parent ?: defaultRootDir
            }
        } else if (!initialArchivePath.isNullOrBlank()) {
            activeMode = ArchiveToolMode.MANAGE
            val f = File(initialArchivePath)
            if (f.exists()) {
                openAndInspectArchive(f)
            }
        }
    }

    // Discover local archives in background
    LaunchedEffect(scanTrigger, externalVolumes) {
        isScanningArchives = true
        val found = withContext(Dispatchers.IO) {
            val roots = listOf(File(defaultRootDir)) + externalVolumes.map { File(it.rootPath) }
            val merged = LinkedHashMap<String, ArchiveVolumeGroup>()
            for (root in roots) {
                for (group in ArchiveEngine.discoverArchiveGroups(root, recursiveDepth = 2)) {
                    val key = runCatching { group.primaryFile.canonicalPath }
                        .getOrDefault(group.primaryFile.absolutePath)
                    merged.putIfAbsent(key, group)
                }
            }
            merged.values.sortedByDescending { it.primaryFile.lastModified() }
        }
        discoveredArchives = found
        isScanningArchives = false
    }

    // Compute total pack source size
    LaunchedEffect(packSourcePaths) {
        packTotalSourceBytes = withContext(Dispatchers.IO) {
            fun sizeOf(file: File, depth: Int): Long {
                if (!file.exists()) return 0L
                if (file.isFile) return file.length().coerceAtLeast(0L)
                if (depth > 8) return 0L
                return file.listFiles()?.sumOf { sizeOf(it, depth + 1) } ?: 0L
            }
            packSourcePaths.sumOf { sizeOf(File(it), 0) }
        }
    }

    // Resolve extract volume group whenever path changes
    LaunchedEffect(extractArchivePath) {
        if (extractArchivePath.isBlank()) {
            extractArchiveGroup = null
        } else {
            extractArchiveGroup = withContext(Dispatchers.IO) {
                val f = File(extractArchivePath)
                if (f.exists()) ArchiveEngine.resolveVolumeGroup(f) else null
            }
            extractArchiveGroup?.let { grp ->
                resolvePasswordFor(grp)?.let { saved ->
                    if (extractPassword.isEmpty()) extractPassword = saved
                }
            }
        }
    }

    BackHandler(enabled = starMenuExpanded || menuExpanded) {
        starMenuExpanded = false
        menuExpanded = false
    }

    BackHandler(enabled = !starMenuExpanded && !menuExpanded) {
        when {
            activeMode == ArchiveToolMode.MANAGE && selectedEntryPaths.isNotEmpty() -> {
                selectedEntryPaths = emptySet()
            }
            activeMode == ArchiveToolMode.MANAGE && openedSummary != null && archiveInternalPath.isNotEmpty() -> {
                MonolithHaptics.backNav(context, view)
                archiveInternalPath = archiveInternalPath.substringBeforeLast('/', "")
            }
            activeMode == ArchiveToolMode.MANAGE && openedSummary != null && initialArchivePath.isNullOrBlank() -> {
                MonolithHaptics.backNav(context, view)
                openedSummary = null
                selectedEntryPaths = emptySet()
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
                val summary = openedSummary
                if (activeMode == ArchiveToolMode.MANAGE && summary != null && selectedEntryPaths.isNotEmpty()) {
                    val isModifiable = !summary.group.isMultiVolume && summary.group.format.canCreate
                    val currentFolderEntries = remember(summary, archiveInternalPath) {
                        summary.entries.filter { it.parentPath == archiveInternalPath }
                    }
                    TopAppBar(
                        title = {
                            Text(
                                text = "${selectedEntryPaths.size}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = { selectedEntryPaths = emptySet() }) {
                                Icon(Icons.Default.Close, contentDescription = "Auswahl aufheben")
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = {
                                    selectedEntryPaths = if (
                                        currentFolderEntries.isNotEmpty() &&
                                        currentFolderEntries.all { it.path in selectedEntryPaths }
                                    ) {
                                        emptySet()
                                    } else {
                                        currentFolderEntries.map { it.path }.toSet()
                                    }
                                },
                            ) {
                                Icon(Icons.Outlined.SelectAll, contentDescription = "Alle auswählen")
                            }
                            IconButton(
                                onClick = {
                                    val chosen = selectedEntryPaths
                                    val initialDir = summary.group.primaryFile.parent ?: defaultRootDir
                                    activePickerRequest = LocalPickerRequest(
                                        mode = LocalPickerMode.SINGLE_DIRECTORY,
                                        title = "Zielordner für Auswahl",
                                        initialDirectory = initialDir,
                                        onConfirm = { dirs ->
                                            val target = dirs.firstOrNull() ?: return@LocalPickerRequest
                                            selectedEntryPaths = emptySet()
                                            launchExtractOperation(
                                                archiveFile = summary.group.primaryFile,
                                                targetDir = File(target),
                                                password = null,
                                                selectedEntries = chosen,
                                                createSubfolder = false,
                                                deleteAfterExtract = false,
                                            )
                                        },
                                    )
                                },
                            ) {
                                Icon(Icons.Outlined.Unarchive, contentDescription = "Auswahl entpacken")
                            }
                            if (isModifiable && selectedEntryPaths.size == 1) {
                                val targetPath = selectedEntryPaths.first()
                                val targetItem = summary.entries.firstOrNull { it.path == targetPath }
                                if (targetItem != null) {
                                    IconButton(onClick = { renameEntryTarget = targetItem }) {
                                        Icon(Icons.Outlined.Edit, contentDescription = "Umbenennen")
                                    }
                                }
                            }
                            if (isModifiable) {
                                IconButton(onClick = { deleteEntriesConfirm = selectedEntryPaths }) {
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
                // Top 3 mode chips
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
                            selected = activeMode == mode,
                            shape = LocalMonolithButtonShape.current,
                            onClick = {
                                activeMode = mode
                                selectedEntryPaths = emptySet()
                            },
                            label = { Text(mode.label) },
                        )
                    }
                }

                when (activeMode) {
                    ArchiveToolMode.MANAGE -> {
                        ManageArchiveContent(
                            summary = openedSummary,
                            isInspecting = isInspecting,
                            isScanning = isScanningArchives,
                            discoveredArchives = discoveredArchives,
                            archiveInternalPath = archiveInternalPath,
                            selectedEntryPaths = selectedEntryPaths,
                            hasSavedPassword = openedSummary?.let { resolvePasswordFor(it.group) != null } == true,
                            onRefreshScan = { scanTrigger++ },
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
                                openedSummary = null
                                archiveInternalPath = ""
                                selectedEntryPaths = emptySet()
                            },
                            onSwitchToPack = { activeMode = ArchiveToolMode.PACK },
                            onSwitchToExtract = { group ->
                                extractArchivePath = group.primaryFile.absolutePath
                                extractTargetDir = group.primaryFile.parent ?: defaultRootDir
                                resolvePasswordFor(group)?.let { extractPassword = it }
                                activeMode = ArchiveToolMode.EXTRACT
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
                            onAddFilesToArchive = { group ->
                                activePickerRequest = LocalPickerRequest(
                                    mode = LocalPickerMode.FILES_MULTI,
                                    title = "Dateien ins Archiv hinzufügen",
                                    initialDirectory = group.primaryFile.parent ?: defaultRootDir,
                                    onConfirm = { paths ->
                                        if (paths.isEmpty()) return@LocalPickerRequest
                                        fun runAdd(pwd: String?) {
                                            activeJob?.cancel()
                                            activeProgress = ArchiveOperationProgress(
                                                title = "Einträge werden hinzugefügt…",
                                                currentItem = group.displayName,
                                                processedBytes = 0L,
                                                totalBytes = 1L,
                                            )
                                            activeJob = scope.launch {
                                                val res = ArchiveEngine.addEntriesToArchive(
                                                    archiveFile = group.primaryFile,
                                                    pathsToAdd = paths,
                                                    targetInternalDir = archiveInternalPath,
                                                    password = pwd ?: resolvePasswordFor(group),
                                                    onProgress = { p -> activeProgress = p },
                                                )
                                                activeProgress = null
                                                activeJob = null
                                                res.onSuccess {
                                                    snackbarHostState.showSnackbar("${paths.size} Element(e) hinzugefügt.")
                                                    openAndInspectArchive(
                                                        group.primaryFile,
                                                        pwd ?: resolvePasswordFor(group),
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
                            },
                            onVerifyArchive = { group -> launchVerifyOperation(group) },
                            onConvertArchive = { group -> convertSourceGroup = group },
                            onMergeArchive = { group -> launchMergeOperation(group) },
                            onNavigateInternalPath = { newPath ->
                                MonolithHaptics.folderOpen(context, view)
                                archiveInternalPath = newPath
                                selectedEntryPaths = emptySet()
                            },
                            onEntryClick = { entry ->
                                if (selectedEntryPaths.isNotEmpty()) {
                                    selectedEntryPaths = if (entry.path in selectedEntryPaths) {
                                        selectedEntryPaths - entry.path
                                    } else {
                                        selectedEntryPaths + entry.path
                                    }
                                } else if (entry.isDirectory) {
                                    MonolithHaptics.folderOpen(context, view)
                                    archiveInternalPath = entry.path
                                } else {
                                    entryActionTarget = entry
                                }
                            },
                            onEntryLongClick = { entry ->
                                MonolithHaptics.dragLift(view)
                                selectedEntryPaths = if (entry.path in selectedEntryPaths) {
                                    selectedEntryPaths - entry.path
                                } else {
                                    selectedEntryPaths + entry.path
                                }
                            },
                            onEntryMoreClick = { entry -> entryActionTarget = entry },
                        )
                    }

                    ArchiveToolMode.PACK -> {
                        PackArchiveContent(
                            sourcePaths = packSourcePaths,
                            totalSourceBytes = packTotalSourceBytes,
                            baseName = packBaseName,
                            outputDir = packOutputDir,
                            format = packFormat,
                            compressionLevel = packCompression,
                            encryptionEnabled = packEncryptionEnabled,
                            encryptionMethod = packEncryptionMethod,
                            password = packPassword,
                            passwordConfirm = packPasswordConfirm,
                            splitEnabled = packSplitEnabled,
                            splitPreset = packSplitPreset,
                            splitCustomSelected = packSplitCustomSelected,
                            splitCustomMb = packSplitCustomMb,
                            solidArchive = packSolidArchive,
                            onPickFiles = {
                                activePickerRequest = LocalPickerRequest(
                                    mode = LocalPickerMode.FILES_MULTI,
                                    title = "Dateien zum Packen wählen",
                                    initialDirectory = packOutputDir,
                                    onConfirm = { picked ->
                                        val combined = (packSourcePaths + picked).distinct()
                                        packSourcePaths = combined
                                        if (!packBaseNameEdited && combined.isNotEmpty()) {
                                            val first = File(combined.first())
                                            packBaseName = first.nameWithoutExtension.ifBlank { first.name }
                                        }
                                    },
                                )
                            },
                            onPickFolders = {
                                activePickerRequest = LocalPickerRequest(
                                    mode = LocalPickerMode.FOLDERS_MULTI,
                                    title = "Ordner zum Packen wählen",
                                    initialDirectory = packOutputDir,
                                    onConfirm = { picked ->
                                        val combined = (packSourcePaths + picked).distinct()
                                        packSourcePaths = combined
                                        if (!packBaseNameEdited && combined.isNotEmpty()) {
                                            val first = File(combined.first())
                                            packBaseName = first.name
                                        }
                                    },
                                )
                            },
                            onRemoveSource = { path -> packSourcePaths = packSourcePaths - path },
                            onClearSources = { packSourcePaths = emptyList() },
                            onBaseNameChange = {
                                packBaseNameEdited = true
                                packBaseName = it.replace('\n', ' ').replace('\r', ' ')
                            },
                            onPickOutputDir = {
                                activePickerRequest = LocalPickerRequest(
                                    mode = LocalPickerMode.SINGLE_DIRECTORY,
                                    title = "Zielordner wählen",
                                    initialDirectory = packOutputDir,
                                    onConfirm = { dirs -> dirs.firstOrNull()?.let { packOutputDir = it } },
                                )
                            },
                            onFormatChange = { newFmt ->
                                packFormat = newFmt
                                if (!newFmt.supportsEncryption) {
                                    packEncryptionEnabled = false
                                } else if (newFmt == ArchiveFormat.SEVEN_Z) {
                                    packEncryptionMethod = ArchiveEncryptionMethod.AES_256
                                }
                            },
                            onCompressionChange = { packCompression = it },
                            onEncryptionEnabledChange = { packEncryptionEnabled = it },
                            onEncryptionMethodChange = { packEncryptionMethod = it },
                            onPasswordChange = { packPassword = it },
                            onPasswordConfirmChange = { packPasswordConfirm = it },
                            onSplitEnabledChange = { packSplitEnabled = it },
                            onSplitPresetChange = {
                                packSplitCustomSelected = false
                                packSplitPreset = it
                            },
                            onSelectCustomSplit = { packSplitCustomSelected = true },
                            onCustomSplitMbChange = { raw ->
                                packSplitCustomMb = raw.filter { it.isDigit() }.take(6)
                            },
                            onSolidArchiveChange = { packSolidArchive = it },
                            onCreateArchive = {
                                val splitBytes = if (!packSplitEnabled) {
                                    null
                                } else if (packSplitCustomSelected) {
                                    val mb = packSplitCustomMb.toLongOrNull() ?: 0L
                                    if (mb <= 0L) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Bitte eine gültige Teilgröße in MB eingeben.")
                                        }
                                        return@PackArchiveContent
                                    }
                                    mb * 1024L * 1024L
                                } else {
                                    packSplitPreset.sizeBytes
                                }

                                val effectiveEnc = if (packFormat.supportsEncryption && packEncryptionEnabled) {
                                    if (packFormat == ArchiveFormat.SEVEN_Z) {
                                        ArchiveEncryptionMethod.AES_256
                                    } else {
                                        packEncryptionMethod
                                    }
                                } else {
                                    ArchiveEncryptionMethod.NONE
                                }
                                if (effectiveEnc != ArchiveEncryptionMethod.NONE) {
                                    if (packPassword.isEmpty()) {
                                        scope.launch { snackbarHostState.showSnackbar("Bitte ein Passwort eingeben.") }
                                        return@PackArchiveContent
                                    }
                                    if (packPassword != packPasswordConfirm) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Die Passwörter stimmen nicht überein.")
                                        }
                                        return@PackArchiveContent
                                    }
                                }

                                val request = PackArchiveRequest(
                                    sourcePaths = packSourcePaths,
                                    outputDirectory = packOutputDir,
                                    archiveBaseName = packBaseName,
                                    format = packFormat,
                                    compressionLevel = packCompression,
                                    encryptionMethod = effectiveEnc,
                                    password = if (effectiveEnc != ArchiveEncryptionMethod.NONE) packPassword else "",
                                    splitVolumeBytes = splitBytes,
                                    solidArchive = packSolidArchive,
                                )

                                MonolithHaptics.launchItem(context)
                                activeJob?.cancel()
                                activeProgress = ArchiveOperationProgress(
                                    title = "${packFormat.label}-Archiv wird erstellt…",
                                    currentItem = packBaseName,
                                    processedBytes = 0L,
                                    totalBytes = packTotalSourceBytes.coerceAtLeast(1L),
                                )
                                activeJob = scope.launch {
                                    val res = ArchiveEngine.createArchive(
                                        request = request,
                                        onProgress = { progress -> activeProgress = progress },
                                    )
                                    activeProgress = null
                                    activeJob = null
                                    res.onSuccess { createdGroup ->
                                        if (request.password.isNotEmpty()) {
                                            rememberedPasswords[passwordKeyFor(createdGroup.primaryFile)] =
                                                request.password
                                        }
                                        scanTrigger++
                                        val partsHint = if (createdGroup.isMultiVolume) {
                                            "\n${createdGroup.parts.size} Teilarchive (${formatArchiveBytes(createdGroup.totalSizeBytes)})"
                                        } else {
                                            "\nGröße: ${formatArchiveBytes(createdGroup.totalSizeBytes)}"
                                        }
                                        completionDialog = OperationCompletionDialogState(
                                            title = "Archiv erstellt",
                                            message = "„${createdGroup.displayName}“ wurde erfolgreich erstellt.$partsHint",
                                            openFolderPath = createdGroup.primaryFile.parent,
                                            openArchivePath = createdGroup.primaryFile.absolutePath,
                                        )
                                    }.onFailure { err ->
                                        if (err is CancellationException) return@onFailure
                                        snackbarHostState.showSnackbar(err.message ?: "Packen fehlgeschlagen.")
                                    }
                                }
                            },
                        )
                    }

                    ArchiveToolMode.EXTRACT -> {
                        ExtractArchiveContent(
                            archivePath = extractArchivePath,
                            volumeGroup = extractArchiveGroup,
                            targetDir = extractTargetDir,
                            password = extractPassword,
                            createSubfolder = extractCreateSubfolder,
                            conflictPolicy = extractConflictPolicy,
                            deleteAfterExtract = extractDeleteAfter,
                            onPickArchive = {
                                activePickerRequest = LocalPickerRequest(
                                    mode = LocalPickerMode.SINGLE_FILE,
                                    title = "Archiv zum Entpacken wählen",
                                    initialDirectory = extractTargetDir,
                                    archiveHighlight = true,
                                    onConfirm = { paths ->
                                        paths.firstOrNull()?.let { picked ->
                                            extractArchivePath = picked
                                            File(picked).parent?.let { extractTargetDir = it }
                                        }
                                    },
                                )
                            },
                            onPickTargetDir = {
                                activePickerRequest = LocalPickerRequest(
                                    mode = LocalPickerMode.SINGLE_DIRECTORY,
                                    title = "Zielordner wählen",
                                    initialDirectory = extractTargetDir,
                                    onConfirm = { dirs -> dirs.firstOrNull()?.let { extractTargetDir = it } },
                                )
                            },
                            onPasswordChange = { extractPassword = it },
                            onCreateSubfolderChange = { extractCreateSubfolder = it },
                            onConflictPolicyChange = { extractConflictPolicy = it },
                            onDeleteAfterExtractChange = { extractDeleteAfter = it },
                            onExtractNow = {
                                val srcFile = File(extractArchivePath)
                                if (!srcFile.exists()) {
                                    scope.launch { snackbarHostState.showSnackbar("Bitte zuerst ein gültiges Archiv wählen.") }
                                    return@ExtractArchiveContent
                                }
                                MonolithHaptics.launchItem(context)
                                launchExtractOperation(
                                    archiveFile = srcFile,
                                    targetDir = File(extractTargetDir),
                                    password = extractPassword.takeIf { it.isNotEmpty() },
                                    createSubfolder = extractCreateSubfolder,
                                    conflictPolicy = extractConflictPolicy,
                                    deleteAfterExtract = extractDeleteAfter,
                                )
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

        val cornerItems = buildList {
            add(
                CornerMenuItem(
                    id = "open_archive",
                    label = "Archiv öffnen…",
                    onClick = {
                        activeMode = ArchiveToolMode.MANAGE
                        activePickerRequest = LocalPickerRequest(
                            mode = LocalPickerMode.SINGLE_FILE,
                            title = "Archiv öffnen",
                            initialDirectory = defaultRootDir,
                            archiveHighlight = true,
                            onConfirm = { paths -> paths.firstOrNull()?.let { openAndInspectArchive(File(it)) } },
                        )
                    },
                ),
            )
            add(
                CornerMenuItem(
                    id = "rescan",
                    label = "Archive neu scannen",
                    onClick = { scanTrigger++ },
                ),
            )
            if (openedSummary != null) {
                add(
                    CornerMenuItem(
                        id = "close_archive",
                        label = "Archiv schließen",
                        onClick = {
                            openedSummary = null
                            archiveInternalPath = ""
                            selectedEntryPaths = emptySet()
                        },
                    ),
                )
            }
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
            items = cornerItems,
            onDismiss = { menuExpanded = false },
        )
    }

    // -------------------------------------------------------------------------
    // Modals & Pickers
    // -------------------------------------------------------------------------

    activePickerRequest?.let { req ->
        LocalPathPickerDialog(
            request = req,
            defaultRootDir = defaultRootDir,
            onDismiss = { activePickerRequest = null },
        )
    }

    passwordPrompt?.let { prompt ->
        var pwdDraft by remember(prompt) { mutableStateOf("") }
        var showPwd by remember(prompt) { mutableStateOf(false) }
        var rememberSession by remember(prompt) { mutableStateOf(true) }

        MonolithDialog(
            onDismissRequest = { passwordPrompt = null },
            title = if (prompt.isWrongPassword) "Falsches Passwort" else "Passwort erforderlich",
            dismissLabel = "Abbrechen",
            onDismissClick = { passwordPrompt = null },
            confirmLabel = "Entsperren",
            confirmEnabled = pwdDraft.isNotEmpty(),
            onConfirmClick = {
                val entered = pwdDraft
                if (rememberSession) {
                    rememberedPasswords[passwordKeyFor(prompt.archiveFile)] = entered
                }
                passwordPrompt = null
                prompt.onSubmit(entered)
            },
        ) {
            Text(
                text = if (prompt.isWrongPassword) {
                    "Das eingegebene Passwort für „${prompt.archiveLabel}“ war falsch. Bitte erneut versuchen."
                } else {
                    "„${prompt.archiveLabel}“ ist verschlüsselt. Bitte Passwort eingeben."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = pwdDraft,
                onValueChange = { pwdDraft = it },
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
        val summary = openedSummary
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
                MonolithDialogOption(
                    label = "Entpacken…",
                    subtitle = "In einen Zielordner entpacken",
                    selected = false,
                    onClick = {
                        entryActionTarget = null
                        val initialDir = summary.group.primaryFile.parent ?: defaultRootDir
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
        val summary = openedSummary
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
                    activeJob?.cancel()
                    activeProgress = ArchiveOperationProgress(
                        title = "Eintrag wird umbenannt…",
                        currentItem = updated,
                        processedBytes = 0L,
                        totalBytes = 1L,
                    )
                    activeJob = scope.launch {
                        val res = ArchiveEngine.renameEntryInArchive(
                            archiveFile = grp.primaryFile,
                            oldEntryPath = target.path,
                            newName = updated,
                            password = pwd ?: resolvePasswordFor(grp),
                        )
                        activeProgress = null
                        activeJob = null
                        res.onSuccess {
                            selectedEntryPaths = emptySet()
                            snackbarHostState.showSnackbar("Umbenannt zu „$updated“.")
                            openAndInspectArchive(grp.primaryFile, pwd ?: resolvePasswordFor(grp), resetInternalPath = false)
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
        val summary = openedSummary
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
                    activeJob?.cancel()
                    activeProgress = ArchiveOperationProgress(
                        title = "Einträge werden gelöscht…",
                        currentItem = grp.displayName,
                        processedBytes = 0L,
                        totalBytes = 1L,
                    )
                    activeJob = scope.launch {
                        val res = ArchiveEngine.deleteEntriesFromArchive(
                            archiveFile = grp.primaryFile,
                            entryPaths = pathsToDelete,
                            password = pwd ?: resolvePasswordFor(grp),
                            onProgress = { p -> activeProgress = p },
                        )
                        activeProgress = null
                        activeJob = null
                        res.onSuccess {
                            selectedEntryPaths = emptySet()
                            snackbarHostState.showSnackbar("${pathsToDelete.size} Eintrag/Einträge gelöscht.")
                            openAndInspectArchive(grp.primaryFile, pwd ?: resolvePasswordFor(grp), resetInternalPath = false)
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
                    activeJob?.cancel()
                    activeProgress = ArchiveOperationProgress(
                        title = "Archiv wird konvertiert…",
                        currentItem = srcGroup.displayName,
                        processedBytes = 0L,
                        totalBytes = srcGroup.totalSizeBytes.coerceAtLeast(1L),
                    )
                    activeJob = scope.launch {
                        val res = ArchiveEngine.convertToFormat(
                            archiveFile = srcGroup.primaryFile,
                            targetFormat = chosenFormat,
                            password = sourcePwd ?: resolvePasswordFor(srcGroup),
                            newPassword = chosenNewPwd,
                            onProgress = { p -> activeProgress = p },
                        )
                        activeProgress = null
                        activeJob = null
                        res.onSuccess { newFile ->
                            if (!chosenNewPwd.isNullOrEmpty()) {
                                rememberedPasswords[passwordKeyFor(newFile)] = chosenNewPwd
                            }
                            scanTrigger++
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

    activeProgress?.let { prog ->
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
                activeJob?.cancel()
                activeJob = null
                activeProgress = null
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
        MonolithDialog(
            onDismissRequest = { completionDialog = null },
            title = done.title,
            dismissLabel = "Schließen",
            onDismissClick = { completionDialog = null },
            confirmLabel = when {
                done.openArchivePath != null -> "Archiv verwalten"
                done.openFolderPath != null -> "Zielordner öffnen"
                else -> "OK"
            },
            onConfirmClick = {
                completionDialog = null
                when {
                    done.openArchivePath != null -> {
                        activeMode = ArchiveToolMode.MANAGE
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

// -----------------------------------------------------------------------------
// Verwalten Mode Content
// -----------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun ManageArchiveContent(
    summary: ArchiveSummary?,
    isInspecting: Boolean,
    isScanning: Boolean,
    discoveredArchives: List<ArchiveVolumeGroup>,
    archiveInternalPath: String,
    selectedEntryPaths: Set<String>,
    hasSavedPassword: Boolean,
    onRefreshScan: () -> Unit,
    onPickArchiveToOpen: () -> Unit,
    onOpenArchiveGroup: (ArchiveVolumeGroup) -> Unit,
    onCloseArchive: () -> Unit,
    onSwitchToPack: () -> Unit,
    onSwitchToExtract: (ArchiveVolumeGroup) -> Unit,
    onRequestPasswordEdit: (ArchiveVolumeGroup) -> Unit,
    onAddFilesToArchive: (ArchiveVolumeGroup) -> Unit,
    onVerifyArchive: (ArchiveVolumeGroup) -> Unit,
    onConvertArchive: (ArchiveVolumeGroup) -> Unit,
    onMergeArchive: (ArchiveVolumeGroup) -> Unit,
    onNavigateInternalPath: (String) -> Unit,
    onEntryClick: (ArchiveEntryItem) -> Unit,
    onEntryLongClick: (ArchiveEntryItem) -> Unit,
    onEntryMoreClick: (ArchiveEntryItem) -> Unit,
) {
    if (isInspecting) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MonolithLoadingSpinner()
                Text(
                    text = "Archiv wird analysiert…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    if (summary == null) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = onPickArchiveToOpen,
                        shape = LocalMonolithButtonShape.current,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Archiv öffnen…")
                    }
                    OutlinedButton(
                        onClick = onSwitchToPack,
                        shape = LocalMonolithButtonShape.current,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Archive,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Neu packen")
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Gefundene Archive (${discoveredArchives.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    IconButton(onClick = onRefreshScan) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Neu scannen")
                    }
                }
            }

            if (isScanning && discoveredArchives.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        MonolithLoadingSpinner()
                    }
                }
            } else if (discoveredArchives.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Text(
                            text = "Keine Archive gefunden.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            } else {
                items(discoveredArchives, key = { it.primaryFile.absolutePath }) { group ->
                    DiscoveredArchiveCard(
                        group = group,
                        onClick = { onOpenArchiveGroup(group) },
                    )
                }
            }
        }
        return
    }

    val group = summary.group
    val isModifiable = !group.isMultiVolume && group.format.canCreate
    val visibleEntries = remember(summary, archiveInternalPath) {
        summary.entries.filter { it.parentPath == archiveInternalPath }
    }
    val childCounts = remember(summary) {
        summary.entries.groupingBy { it.parentPath }.eachCount()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Opened Archive Header Card
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FolderZip,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = group.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val ratioText = if (summary.totalUncompressedBytes > 0L) {
                            val pct = ((summary.totalCompressedBytes.toDouble() / summary.totalUncompressedBytes.toDouble()) * 100.0)
                                .toInt()
                                .coerceIn(0, 999)
                            " • $pct%"
                        } else {
                            ""
                        }
                        Text(
                            text = "${formatArchiveBytes(summary.totalCompressedBytes)} → ${formatArchiveBytes(summary.totalUncompressedBytes)}$ratioText • ${summary.fileCount} Dateien, ${summary.folderCount} Ordner",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onCloseArchive) {
                        Icon(Icons.Default.Close, contentDescription = "Archiv schließen")
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BadgeChip(
                        text = group.format.label,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    if (summary.isEncrypted) {
                        BadgeChip(
                            text = if (hasSavedPassword) "Entsperrt" else "Verschlüsselt",
                            icon = Icons.Outlined.Lock,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            onClick = { onRequestPasswordEdit(group) },
                        )
                    }
                    if (group.isMultiVolume) {
                        BadgeChip(
                            text = "Zusammenhängend · ${group.parts.size} Teile",
                            icon = Icons.Outlined.Inventory2,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    if (group.isMissingParts) {
                        BadgeChip(
                            text = "Teile fehlen",
                            icon = Icons.Outlined.WarningAmber,
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { onSwitchToExtract(group) },
                        shape = LocalMonolithButtonShape.current,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Unarchive,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Entpacken")
                    }
                    if (isModifiable) {
                        OutlinedButton(
                            onClick = { onAddFilesToArchive(group) },
                            shape = LocalMonolithButtonShape.current,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Dateien")
                        }
                    }
                    OutlinedButton(
                        onClick = { onVerifyArchive(group) },
                        shape = LocalMonolithButtonShape.current,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.VerifiedUser,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Prüfen")
                    }
                    OutlinedButton(
                        onClick = { onConvertArchive(group) },
                        shape = LocalMonolithButtonShape.current,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.SwapHoriz,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Konvertieren")
                    }
                    if (ArchiveEngine.canBinaryMerge(group)) {
                        OutlinedButton(
                            onClick = { onMergeArchive(group) },
                            shape = LocalMonolithButtonShape.current,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Merge,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Zusammenführen")
                        }
                    }
                }
            }
        }

        // Archive Internal Breadcrumb Row
        val pathSegments = remember(archiveInternalPath) {
            if (archiveInternalPath.isEmpty()) emptyList() else archiveInternalPath.split('/')
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (archiveInternalPath.isNotEmpty()) {
                IconButton(
                    onClick = { onNavigateInternalPath(archiveInternalPath.substringBeforeLast('/', "")) },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ArrowUpward,
                        contentDescription = "Ebene höher",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            TextButton(
                onClick = { onNavigateInternalPath("") },
                shape = LocalMonolithButtonShape.current,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text("Archivstamm")
            }
            pathSegments.forEachIndexed { idx, segment ->
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                val targetPath = pathSegments.take(idx + 1).joinToString("/")
                TextButton(
                    onClick = { onNavigateInternalPath(targetPath) },
                    shape = LocalMonolithButtonShape.current,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = segment,
                        fontWeight = if (idx == pathSegments.lastIndex) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }

        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

        if (visibleEntries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = if (summary.requiresPasswordToList) {
                            "Inhaltsverzeichnis ist verschlüsselt."
                        } else {
                            "Dieser Ordner im Archiv ist leer."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (summary.requiresPasswordToList) {
                        Button(
                            onClick = { onRequestPasswordEdit(group) },
                            shape = LocalMonolithButtonShape.current,
                        ) {
                            Text("Passwort eingeben")
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
            ) {
                items(visibleEntries, key = { it.path }) { entry ->
                    val isSelected = entry.path in selectedEntryPaths
                    val childrenCount = if (entry.isDirectory) childCounts[entry.path] ?: 0 else 0
                    Row(
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
                                onClick = { onEntryClick(entry) },
                                onLongClick = { onEntryLongClick(entry) },
                            )
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isSelected) {
                            Box(
                                modifier = Modifier.size(36.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier.size(36.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = iconForArchiveEntry(entry),
                                    contentDescription = null,
                                    tint = if (entry.isDirectory) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val sub = if (entry.isDirectory) {
                                "$childrenCount Elemente"
                            } else {
                                val rawSize = formatArchiveBytes(entry.uncompressedSize)
                                if (entry.compressedSize > 0L) {
                                    "$rawSize • ${formatArchiveBytes(entry.compressedSize)} gepackt"
                                } else {
                                    rawSize
                                }
                            }
                            Text(
                                text = sub,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (entry.isEncrypted) {
                            Icon(
                                imageVector = Icons.Outlined.Lock,
                                contentDescription = "Verschlüsselt",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(end = 4.dp)
                                    .size(16.dp),
                            )
                        }
                        IconButton(onClick = { onEntryMoreClick(entry) }) {
                            Icon(
                                imageVector = Icons.Outlined.MoreVert,
                                contentDescription = "Aktionen",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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

@Composable
private fun DiscoveredArchiveCard(
    group: ArchiveVolumeGroup,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.FolderZip,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${formatArchiveBytes(group.totalSizeBytes)} • ${group.primaryFile.parentFile?.name.orEmpty()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BadgeChip(
                    text = group.format.label,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            if (group.isMultiVolume || group.isMissingParts) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (group.isMultiVolume) {
                        BadgeChip(
                            text = "Zusammenhängend · ${group.parts.size} Teile",
                            icon = Icons.Outlined.Inventory2,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    if (group.isMissingParts) {
                        BadgeChip(
                            text = "Teile fehlen",
                            icon = Icons.Outlined.WarningAmber,
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Packen Mode Content
// -----------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PackArchiveContent(
    sourcePaths: List<String>,
    totalSourceBytes: Long,
    baseName: String,
    outputDir: String,
    format: ArchiveFormat,
    compressionLevel: ArchiveCompressionLevel,
    encryptionEnabled: Boolean,
    encryptionMethod: ArchiveEncryptionMethod,
    password: String,
    passwordConfirm: String,
    splitEnabled: Boolean,
    splitPreset: SplitVolumePreset,
    splitCustomSelected: Boolean,
    splitCustomMb: String,
    solidArchive: Boolean,
    onPickFiles: () -> Unit,
    onPickFolders: () -> Unit,
    onRemoveSource: (String) -> Unit,
    onClearSources: () -> Unit,
    onBaseNameChange: (String) -> Unit,
    onPickOutputDir: () -> Unit,
    onFormatChange: (ArchiveFormat) -> Unit,
    onCompressionChange: (ArchiveCompressionLevel) -> Unit,
    onEncryptionEnabledChange: (Boolean) -> Unit,
    onEncryptionMethodChange: (ArchiveEncryptionMethod) -> Unit,
    onPasswordChange: (String) -> Unit,
    onPasswordConfirmChange: (String) -> Unit,
    onSplitEnabledChange: (Boolean) -> Unit,
    onSplitPresetChange: (SplitVolumePreset) -> Unit,
    onSelectCustomSplit: () -> Unit,
    onCustomSplitMbChange: (String) -> Unit,
    onSolidArchiveChange: (Boolean) -> Unit,
    onCreateArchive: () -> Unit,
) {
    var showPwd by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1. Source Selection Card
        SectionCard(title = "Dateien & Ordner") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (sourcePaths.isEmpty()) {
                        "Keine Quellen ausgewählt"
                    } else {
                        "${sourcePaths.size} ausgewählt • ${formatArchiveBytes(totalSourceBytes)}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (sourcePaths.isNotEmpty()) {
                    TextButton(onClick = onClearSources) {
                        Text("Leeren")
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onPickFiles,
                    shape = LocalMonolithButtonShape.current,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.InsertDriveFile,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Dateien wählen")
                }
                OutlinedButton(
                    onClick = onPickFolders,
                    shape = LocalMonolithButtonShape.current,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Ordner wählen")
                }
            }
            if (sourcePaths.isNotEmpty()) {
                val visibleChips = sourcePaths.take(20)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    visibleChips.forEach { path ->
                        val f = File(path)
                        FilterChip(
                            selected = false,
                            shape = LocalMonolithButtonShape.current,
                            onClick = { onRemoveSource(path) },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (f.isDirectory) Icons.Outlined.Folder else Icons.AutoMirrored.Outlined.InsertDriveFile,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Entfernen",
                                    modifier = Modifier.size(15.dp),
                                )
                            },
                            label = {
                                Text(
                                    text = f.name.ifBlank { path },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                    if (sourcePaths.size > visibleChips.size) {
                        BadgeChip(
                            text = "+${sourcePaths.size - visibleChips.size} weitere",
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }

        // 2. Archive Name & Output Directory
        SectionCard(title = "Name & Speicherort") {
            OutlinedTextField(
                value = baseName,
                onValueChange = onBaseNameChange,
                label = { Text("Archivname") },
                suffix = { Text(".${format.extension}") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = CutCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                onClick = onPickOutputDir,
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
                            text = outputDir,
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
        }

        // 3. Format & Compression Level
        SectionCard(title = "Format & Kompression") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ArchiveFormat.entries.filter { it.canCreate }.forEach { fmt ->
                    FilterChip(
                        selected = format == fmt,
                        shape = LocalMonolithButtonShape.current,
                        onClick = { onFormatChange(fmt) },
                        label = { Text(fmt.label) },
                    )
                }
            }
            Text(
                text = "Kompressionsstufe",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ArchiveCompressionLevel.entries.forEach { level ->
                    FilterChip(
                        selected = compressionLevel == level,
                        shape = LocalMonolithButtonShape.current,
                        onClick = { onCompressionChange(level) },
                        label = { Text(level.label) },
                    )
                }
            }
        }

        // 4. Expandable Encryption Card
        SectionCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = if (format.supportsEncryption && encryptionEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Verschlüsselung",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (format.supportsEncryption) {
                            "Archiv mit Passwort schützen"
                        } else {
                            "Für ${format.label} nicht unterstützt"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MonolithSwitch(
                    checked = format.supportsEncryption && encryptionEnabled,
                    onCheckedChange = onEncryptionEnabledChange,
                    enabled = format.supportsEncryption,
                )
            }

            AnimatedVisibility(visible = format.supportsEncryption && encryptionEnabled) {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (format == ArchiveFormat.ZIP) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                ArchiveEncryptionMethod.AES_256,
                                ArchiveEncryptionMethod.ZIP_STANDARD,
                            ).forEach { method ->
                                FilterChip(
                                    selected = encryptionMethod == method,
                                    shape = LocalMonolithButtonShape.current,
                                    onClick = { onEncryptionMethodChange(method) },
                                    label = { Text(method.label) },
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "Verfahren: AES-256 (7z Standard)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    OutlinedTextField(
                        value = password,
                        onValueChange = onPasswordChange,
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
                    OutlinedTextField(
                        value = passwordConfirm,
                        onValueChange = onPasswordConfirmChange,
                        label = { Text("Passwort bestätigen") },
                        singleLine = true,
                        isError = passwordConfirm.isNotEmpty() && password != passwordConfirm,
                        supportingText = if (passwordConfirm.isNotEmpty() && password != passwordConfirm) {
                            { Text("Passwörter stimmen nicht überein") }
                        } else {
                            null
                        },
                        visualTransformation = if (showPwd) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // 5. Expandable Connected / Multi-Volume & Solid Card
        SectionCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Inventory2,
                    contentDescription = null,
                    tint = if (splitEnabled || (format.supportsSolid && solidArchive)) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Zusammenhängendes Archiv",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Split-Volumes & Solid-Komprimierung",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Multi-Volume Split",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "Großes Archiv in verbundene Teilarchive aufteilen",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MonolithSwitch(
                    checked = splitEnabled,
                    onCheckedChange = onSplitEnabledChange,
                )
            }

            AnimatedVisibility(visible = splitEnabled) {
                Column(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        SplitVolumePreset.entries.filter { it.sizeBytes != null }.forEach { preset ->
                            FilterChip(
                                selected = !splitCustomSelected && splitPreset == preset,
                                shape = LocalMonolithButtonShape.current,
                                onClick = { onSplitPresetChange(preset) },
                                label = { Text(preset.label) },
                            )
                        }
                        FilterChip(
                            selected = splitCustomSelected,
                            shape = LocalMonolithButtonShape.current,
                            onClick = onSelectCustomSplit,
                            label = { Text("Eigene MB") },
                        )
                    }
                    if (splitCustomSelected) {
                        OutlinedTextField(
                            value = splitCustomMb,
                            onValueChange = onCustomSplitMbChange,
                            label = { Text("Teilgröße in MB") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        text = if (format == ArchiveFormat.ZIP) {
                            "Erzeugt zusammenhängende Teile: .z01, .z02, … + .zip"
                        } else {
                            "Erzeugt zusammenhängende Teile: .${format.extension}.001, .${format.extension}.002, …"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            AnimatedVisibility(visible = format.supportsSolid) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Solid-Komprimierung",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "Dateien als einen zusammenhängenden Datenstrom für höhere Kompression packen",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        MonolithSwitch(
                            checked = solidArchive,
                            onCheckedChange = onSolidArchiveChange,
                        )
                    }
                }
            }
        }

        // 6. Primary Create Action
        Button(
            onClick = onCreateArchive,
            enabled = sourcePaths.isNotEmpty() && baseName.isNotBlank(),
            shape = LocalMonolithButtonShape.current,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Archive,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Archiv erstellen")
        }
    }
}

// -----------------------------------------------------------------------------
// Entpacken Mode Content
// -----------------------------------------------------------------------------

@Composable
private fun ExtractArchiveContent(
    archivePath: String,
    volumeGroup: ArchiveVolumeGroup?,
    targetDir: String,
    password: String,
    createSubfolder: Boolean,
    conflictPolicy: ExtractConflictPolicy,
    deleteAfterExtract: Boolean,
    onPickArchive: () -> Unit,
    onPickTargetDir: () -> Unit,
    onPasswordChange: (String) -> Unit,
    onCreateSubfolderChange: (Boolean) -> Unit,
    onConflictPolicyChange: (ExtractConflictPolicy) -> Unit,
    onDeleteAfterExtractChange: (Boolean) -> Unit,
    onExtractNow: () -> Unit,
) {
    var showPwd by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1. Source Archive
        SectionCard(title = "Quellarchiv") {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = CutCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                onClick = onPickArchive,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FolderZip,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = volumeGroup?.displayName
                                ?: archivePath.takeIf { it.isNotBlank() }?.let { File(it).name }
                                ?: "Kein Archiv ausgewählt",
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (volumeGroup != null) {
                                "${volumeGroup.format.label} • ${formatArchiveBytes(volumeGroup.totalSizeBytes)}"
                            } else {
                                "Tippen, um ZIP, 7z, RAR oder TAR zu wählen"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "Wählen",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (volumeGroup != null && (volumeGroup.isMultiVolume || volumeGroup.isMissingParts)) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = CutCornerShape(8.dp),
                    color = if (volumeGroup.isMissingParts) {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = if (volumeGroup.isMissingParts) {
                                Icons.Outlined.WarningAmber
                            } else {
                                Icons.Outlined.Inventory2
                            },
                            contentDescription = null,
                            tint = if (volumeGroup.isMissingParts) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = if (volumeGroup.isMissingParts) {
                                "Warnung: Teile dieses Split-Archivs fehlen!"
                            } else {
                                "${volumeGroup.parts.size} verbundene Teilarchive erkannt (${formatArchiveBytes(volumeGroup.totalSizeBytes)})"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }

        // 2. Target Folder
        SectionCard(title = "Zielordner") {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = CutCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                onClick = onPickTargetDir,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
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
                            text = File(targetDir).name.ifBlank { targetDir },
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = targetDir,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        }

        // 3. Optional Password
        SectionCard(title = "Passwort (optional)") {
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text("Passwort, falls geschützt") },
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

        // 4. Options & Conflict Policy
        SectionCard(title = "Optionen") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "In eigenen Unterordner entpacken",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "Erstellt automatisch einen Ordner mit dem Archivnamen",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MonolithSwitch(
                    checked = createSubfolder,
                    onCheckedChange = onCreateSubfolderChange,
                )
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
                    selected = conflictPolicy == policy,
                    onClick = { onConflictPolicyChange(policy) },
                )
            }

            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Archiv nach Entpacken löschen",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = if (volumeGroup != null && volumeGroup.isMultiVolume) {
                            "Löscht alle ${volumeGroup.parts.size} Teilarchive nach Erfolg"
                        } else {
                            "Entfernt die Archivdatei nach erfolgreichem Entpacken"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (deleteAfterExtract) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                MonolithSwitch(
                    checked = deleteAfterExtract,
                    onCheckedChange = onDeleteAfterExtractChange,
                )
            }
        }

        Button(
            onClick = onExtractNow,
            enabled = archivePath.isNotBlank() && volumeGroup != null && !volumeGroup.isMissingParts,
            shape = LocalMonolithButtonShape.current,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Unarchive,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Jetzt entpacken")
        }
    }
}

// -----------------------------------------------------------------------------
// Embedded Local Path Picker Dialog
// -----------------------------------------------------------------------------

@Composable
private fun LocalPathPickerDialog(
    request: LocalPickerRequest,
    defaultRootDir: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val externalVolumes = rememberExternalStorageVolumes(context)

    var activeRoot by remember(request) {
        val matchingExternal = externalVolumes.firstOrNull {
            request.initialDirectory.startsWith(it.rootPath)
        }?.rootPath
        mutableStateOf(matchingExternal ?: defaultRootDir)
    }
    var currentDir by remember(request) {
        val initial = File(request.initialDirectory)
        mutableStateOf(if (initial.isDirectory) initial.absolutePath else defaultRootDir)
    }
    var children by remember { mutableStateOf<List<File>>(emptyList()) }
    var selectedPaths by remember(request) { mutableStateOf<Set<String>>(emptySet()) }
    var archivesOnlyFilter by remember(request) { mutableStateOf(request.archiveHighlight) }
    var showCreateFolder by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var reloadTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(currentDir, archivesOnlyFilter, reloadTick, request.mode) {
        children = withContext(Dispatchers.IO) {
            val dir = File(currentDir)
            if (!dir.isDirectory) return@withContext emptyList()
            dir.listFiles()
                ?.asSequence()
                ?.filter { !it.name.startsWith(".") }
                ?.filter { file ->
                    when (request.mode) {
                        LocalPickerMode.SINGLE_DIRECTORY, LocalPickerMode.FOLDERS_MULTI -> file.isDirectory
                        LocalPickerMode.SINGLE_FILE -> {
                            file.isDirectory || !archivesOnlyFilter || ArchiveEngine.isSupportedArchiveFile(file)
                        }
                        LocalPickerMode.FILES_MULTI -> true
                    }
                }
                ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) })
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
        title = request.title,
        wide = true,
        dismissLabel = "Abbrechen",
        onDismissClick = onDismiss,
        confirmLabel = when (request.mode) {
            LocalPickerMode.SINGLE_DIRECTORY -> "Diesen Ordner wählen"
            LocalPickerMode.SINGLE_FILE -> "Öffnen"
            LocalPickerMode.FILES_MULTI, LocalPickerMode.FOLDERS_MULTI -> "Übernehmen (${selectedPaths.size})"
        },
        confirmEnabled = when (request.mode) {
            LocalPickerMode.SINGLE_DIRECTORY -> true
            LocalPickerMode.SINGLE_FILE, LocalPickerMode.FILES_MULTI, LocalPickerMode.FOLDERS_MULTI ->
                selectedPaths.isNotEmpty()
        },
        onConfirmClick = {
            val result = when (request.mode) {
                LocalPickerMode.SINGLE_DIRECTORY -> listOf(currentDir)
                else -> selectedPaths.toList()
            }
            onDismiss()
            request.onConfirm(result)
        },
    ) {
        // Storage Volume Selector
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
                    activeRoot = defaultRootDir
                    currentDir = defaultRootDir
                },
                label = { Text("Intern") },
            )
            externalVolumes.forEach { vol ->
                FilterChip(
                    selected = activeRoot == vol.rootPath,
                    shape = LocalMonolithButtonShape.current,
                    onClick = {
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

        // Current Path & Filter / New Folder Bar
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
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
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
            if (request.mode == LocalPickerMode.SINGLE_FILE && request.archiveHighlight) {
                FilterChip(
                    selected = archivesOnlyFilter,
                    shape = LocalMonolithButtonShape.current,
                    onClick = { archivesOnlyFilter = !archivesOnlyFilter },
                    label = { Text("Nur Archive") },
                )
            }
            if (request.mode == LocalPickerMode.SINGLE_DIRECTORY) {
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
        }

        AnimatedVisibility(visible = showCreateFolder && request.mode == LocalPickerMode.SINGLE_DIRECTORY) {
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

        // Bounded height LazyColumn inside MonolithDialog
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp, max = 330.dp),
            shape = CutCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            if (children.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Keine passenden Einträge",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 330.dp),
                ) {
                    items(children, key = { it.absolutePath }) { file ->
                        val isSelected = file.absolutePath in selectedPaths
                        val isArchive = !file.isDirectory && ArchiveEngine.isSupportedArchiveFile(file)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (isSelected) {
                                        Modifier.background(
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                                        )
                                    } else {
                                        Modifier
                                    },
                                )
                                .clickable {
                                    when (request.mode) {
                                        LocalPickerMode.SINGLE_DIRECTORY -> {
                                            if (file.isDirectory) currentDir = file.absolutePath
                                        }
                                        LocalPickerMode.SINGLE_FILE -> {
                                            if (file.isDirectory) {
                                                currentDir = file.absolutePath
                                            } else {
                                                selectedPaths = setOf(file.absolutePath)
                                            }
                                        }
                                        LocalPickerMode.FILES_MULTI -> {
                                            if (file.isDirectory) {
                                                currentDir = file.absolutePath
                                            } else {
                                                selectedPaths = if (isSelected) {
                                                    selectedPaths - file.absolutePath
                                                } else {
                                                    selectedPaths + file.absolutePath
                                                }
                                            }
                                        }
                                        LocalPickerMode.FOLDERS_MULTI -> {
                                            if (file.isDirectory) {
                                                currentDir = file.absolutePath
                                            }
                                        }
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = when {
                                    file.isDirectory -> Icons.Outlined.Folder
                                    isArchive -> Icons.Outlined.FolderZip
                                    else -> Icons.AutoMirrored.Outlined.InsertDriveFile
                                },
                                contentDescription = null,
                                tint = when {
                                    file.isDirectory || isArchive -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = file.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (!file.isDirectory) {
                                    Text(
                                        text = formatArchiveBytes(file.length()),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            val showSelectBox = when (request.mode) {
                                LocalPickerMode.FILES_MULTI -> true
                                LocalPickerMode.FOLDERS_MULTI -> file.isDirectory
                                LocalPickerMode.SINGLE_FILE -> !file.isDirectory
                                LocalPickerMode.SINGLE_DIRECTORY -> false
                            }
                            if (showSelectBox) {
                                IconButton(
                                    onClick = {
                                        selectedPaths = when (request.mode) {
                                            LocalPickerMode.SINGLE_FILE -> setOf(file.absolutePath)
                                            else -> if (isSelected) {
                                                selectedPaths - file.absolutePath
                                            } else {
                                                selectedPaths + file.absolutePath
                                            }
                                        }
                                    },
                                    modifier = Modifier.size(34.dp),
                                ) {
                                    Icon(
                                        imageVector = if (isSelected) {
                                            Icons.Outlined.CheckBox
                                        } else {
                                            Icons.Outlined.CheckBoxOutlineBlank
                                        },
                                        contentDescription = "Auswählen",
                                        tint = if (isSelected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
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
// Shared UI Helpers
// -----------------------------------------------------------------------------

@Composable
private fun SectionCard(
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!title.isNullOrBlank()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            content()
        }
    }
}

@Composable
private fun BadgeChip(
    text: String,
    containerColor: Color,
    contentColor: Color,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    val modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Surface(
        modifier = modifier,
        shape = CutCornerShape(6.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

private fun iconForArchiveEntry(entry: ArchiveEntryItem): ImageVector {
    if (entry.isDirectory) return Icons.Outlined.Folder
    val category = MimeNormalizer.categorize(
        fileName = entry.name,
        isDirectory = false,
        mimeType = MimeNormalizer.normalizeMime(entry.name),
    )
    return when (category) {
        FileCategory.DIRECTORY -> Icons.Outlined.Folder
        FileCategory.VIDEO -> Icons.Outlined.VideoFile
        FileCategory.AUDIO -> Icons.Outlined.AudioFile
        FileCategory.IMAGE -> Icons.Outlined.Image
        FileCategory.ARCHIVE -> Icons.Outlined.FolderZip
        FileCategory.TEXT, FileCategory.DOCUMENT -> Icons.Outlined.Description
        else -> Icons.AutoMirrored.Outlined.InsertDriveFile
    }
}
