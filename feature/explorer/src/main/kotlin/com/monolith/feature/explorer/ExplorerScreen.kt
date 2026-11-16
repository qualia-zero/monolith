package com.monolith.feature.explorer

import android.app.Activity
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.monolith.core.media.MpvEngine
import com.monolith.core.model.FileCategory
import com.monolith.core.model.LocationType
import com.monolith.core.model.SortBy
import com.monolith.core.model.VfsNode
import com.monolith.core.vfs.GoogleDriveAuthManager
import com.monolith.core.vfs.GoogleDriveVfsProvider
import com.monolith.core.vfs.LocalVfsProvider
import com.monolith.core.vfs.VfsProvider
import kotlinx.coroutines.launch
import java.io.File

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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val localProvider = remember { LocalVfsProvider() }
    val driveProvider = remember(context) { GoogleDriveVfsProvider(context.applicationContext) }
    val rootPath = remember { Environment.getExternalStorageDirectory().absolutePath }

    var activeLocation by remember { mutableStateOf(LocationType.LOCAL) }
    var localPath by remember { mutableStateOf(rootPath) }
    var driveStack by remember { mutableStateOf(listOf("gdrive://root" to "Google Drive")) }
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

    var signedInEmail by remember { mutableStateOf(GoogleDriveAuthManager.getSignedInEmail(context)) }
    var isLoading by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            GoogleDriveAuthManager.handleSignInResult(result.data)
                .onSuccess { email ->
                    signedInEmail = email
                    driveStack = listOf("gdrive://root" to "Google Drive")
                }
                .onFailure { err ->
                    scope.launch {
                        snackbarHostState.showSnackbar(err.message ?: "Google-Anmeldung fehlgeschlagen")
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
    var refreshTrigger by remember { mutableIntStateOf(0) }

    val playbackState by MpvEngine.uiState.collectAsState()

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
                        error.message ?: "Vorgang fehlgeschlagen"
                    )
                }
        }
    }

    val canNavigateUp = if (activeLocation == LocationType.GOOGLE_DRIVE) {
        driveStack.size > 1
    } else {
        localPath != rootPath
    }

    fun navigateUp() {
        if (activeLocation == LocationType.GOOGLE_DRIVE) {
            if (driveStack.size > 1) {
                driveStack = driveStack.dropLast(1)
            }
        } else {
            localPath = localPath.substringBeforeLast('/', rootPath).ifEmpty { rootPath }
        }
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
        val ready = if (activeLocation == LocationType.GOOGLE_DRIVE) {
            signedInEmail != null
        } else {
            hasStoragePermission
        }
        if (!ready) {
            nodes = emptyList()
            return@LaunchedEffect
        }
        isLoading = activeLocation == LocationType.GOOGLE_DRIVE
        runCatching {
            activeProvider.list(currentPath, showHidden)
        }.onSuccess { raw ->
            nodes = raw.sortedWith(
                compareByDescending<VfsNode> { it.isDirectory }.thenBy {
                    when (sortBy) {
                        SortBy.NAME -> it.name.lowercase()
                        SortBy.SIZE -> it.sizeBytes.toString().padStart(20, '0')
                        SortBy.DATE_MODIFIED -> it.lastModifiedMs.toString().padStart(20, '0')
                        SortBy.TYPE -> it.category.name
                    }
                }
            )
        }.onFailure { err ->
            nodes = emptyList()
            snackbarHostState.showSnackbar(err.message ?: "Ordner konnte nicht geladen werden")
        }
        isLoading = false
    }

    val selectedNodes = remember(nodes, selectedPaths) {
        nodes.filter { it.path in selectedPaths }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
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
                        if (selectedPaths.size == 1) {
                            val singleNode = selectedNodes.firstOrNull()
                            if (singleNode != null) {
                                IconButton(onClick = { renameTargetNode = singleNode }) {
                                    Icon(Icons.Outlined.Edit, contentDescription = "Umbenennen")
                                }
                                if (!singleNode.isDirectory) {
                                    IconButton(onClick = {
                                        val siblings = nodes.filter { it.category == singleNode.category }
                                        selectedPaths = emptySet()
                                        onOpenWith(singleNode, siblings)
                                    }) {
                                        Icon(
                                            Icons.AutoMirrored.Outlined.OpenInNew,
                                            contentDescription = "Öffnen mit",
                                        )
                                    }
                                }
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
                            activeLocation == LocationType.GOOGLE_DRIVE -> driveStack.last().second
                            localPath == rootPath -> "Monolith"
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
                            Box(
                                modifier = Modifier.size(48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = if (activeLocation == LocationType.GOOGLE_DRIVE) {
                                        Icons.Outlined.CloudQueue
                                    } else {
                                        Icons.Outlined.Smartphone
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = "Menü")
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Neuer Ordner") },
                                    onClick = {
                                        menuExpanded = false
                                        showCreateFolderDialog = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Sortieren nach") },
                                    onClick = {
                                        menuExpanded = false
                                        showSortDialog = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (showHidden) "Versteckte ausblenden" else "Versteckte anzeigen") },
                                    onClick = {
                                        showHidden = !showHidden
                                        menuExpanded = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Einstellungen") },
                                    onClick = {
                                        menuExpanded = false
                                        onOpenSettings()
                                    },
                                )
                                if (signedInEmail != null) {
                                    DropdownMenuItem(
                                        text = { Text("Drive abmelden") },
                                        onClick = {
                                            menuExpanded = false
                                            scope.launch {
                                                GoogleDriveAuthManager.signOut(context)
                                                signedInEmail = null
                                                driveStack = listOf("gdrive://root" to "Google Drive")
                                                if (activeLocation == LocationType.GOOGLE_DRIVE) {
                                                    nodes = emptyList()
                                                }
                                            }
                                        },
                                    )
                                }
                            }
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
                                runFileOperation(
                                    onSuccess = { clipboardPaths = emptyList() },
                                ) {
                                    val srcIsDrive = sources.firstOrNull()?.startsWith("gdrive://") == true
                                    val dstIsDrive = currentPath.startsWith("gdrive://")
                                    when {
                                        srcIsDrive && dstIsDrive -> {
                                            if (move) driveProvider.move(sources, currentPath)
                                            else driveProvider.copy(sources, currentPath)
                                        }
                                        !srcIsDrive && !dstIsDrive -> {
                                            if (move) localProvider.move(sources, currentPath)
                                            else localProvider.copy(sources, currentPath)
                                        }
                                        !srcIsDrive && dstIsDrive -> {
                                            val copyRes = driveProvider.copy(sources, currentPath)
                                            if (copyRes.isSuccess && move) {
                                                localProvider.delete(sources)
                                            } else {
                                                copyRes
                                            }
                                        }
                                        else -> {
                                            // Drive -> Lokal: Dateien herunterladen
                                            runCatching {
                                                for (src in sources) {
                                                    val info = driveProvider.stat(src)
                                                        ?: throw IllegalStateException("Quelle nicht gefunden")
                                                    if (info.isDirectory) {
                                                        throw IllegalStateException("Ordner-Download von Drive wird noch nicht unterstützt")
                                                    }
                                                    val dest = File(currentPath, info.name)
                                                    driveProvider.downloadToFile(src, dest).getOrThrow()
                                                }
                                                if (move) {
                                                    driveProvider.delete(sources).getOrThrow()
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                            onCancel = { clipboardPaths = emptyList() },
                        )
                    }
                    if (showMiniPlayer) {
                        MiniPlayerBar(
                            title = playbackState.title.ifBlank { "Wiedergabe" },
                            subtitle = listOf(playbackState.artist, playbackState.album)
                                .filter { it.isNotBlank() }
                                .joinToString(" • "),
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = activeLocation == LocationType.LOCAL,
                    onClick = {
                        activeLocation = LocationType.LOCAL
                        selectedPaths = emptySet()
                    },
                    label = { Text("Intern") },
                )
                FilterChip(
                    selected = activeLocation == LocationType.GOOGLE_DRIVE,
                    onClick = {
                        activeLocation = LocationType.GOOGLE_DRIVE
                        selectedPaths = emptySet()
                    },
                    label = {
                        Text(
                            text = signedInEmail?.substringBefore('@') ?: "Google Drive",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
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
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(nodes, key = { it.id }) { node ->
                        val isSelected = node.path in selectedPaths
                        FileRow(
                            node = node,
                            isSelected = isSelected,
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
                                            if (activeLocation == LocationType.GOOGLE_DRIVE) {
                                                driveStack = driveStack + (node.path to node.name)
                                            } else {
                                                localPath = node.path
                                            }
                                        }
                                        node.category == FileCategory.VIDEO || node.category == FileCategory.AUDIO -> {
                                            val playlist = nodes.filter { it.category == node.category }
                                            onOpenMedia(node, playlist)
                                        }
                                        node.category == FileCategory.IMAGE -> {
                                            val images = nodes.filter { it.category == FileCategory.IMAGE }
                                            onOpenImage(node, images)
                                        }
                                        node.category == FileCategory.TEXT -> onOpenText(node)
                                        else -> onOpenOther(node)
                                    }
                                }
                            },
                            onLongClick = {
                                selectedPaths = if (isSelected) {
                                    selectedPaths - node.path
                                } else {
                                    selectedPaths + node.path
                                }
                            },
                        )
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        )
                    }
                }
            }
        }
    }

    if (showSortDialog) {
        AlertDialog(
            onDismissRequest = { showSortDialog = false },
            title = { Text("Sortieren nach") },
            text = {
                Column {
                    val options = listOf(
                        SortBy.NAME to "Name",
                        SortBy.SIZE to "Größe",
                        SortBy.DATE_MODIFIED to "Datum",
                        SortBy.TYPE to "Typ",
                    )
                    options.forEach { (option, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    sortBy = option
                                    showSortDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = sortBy == option,
                                onClick = {
                                    sortBy = option
                                    showSortDialog = false
                                },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSortDialog = false }) {
                    Text("Schließen")
                }
            },
        )
    }

    if (showCreateFolderDialog) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("Neuer Ordner") },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("Ordnername") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = folderName.isNotBlank(),
                    onClick = {
                        val name = folderName
                        showCreateFolderDialog = false
                        runFileOperation {
                            activeProvider.createDirectory(currentPath, name)
                        }
                    },
                ) {
                    Text("Erstellen")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) {
                    Text("Abbrechen")
                }
            },
        )
    }

    renameTargetNode?.let { target ->
        var newName by remember(target.path) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTargetNode = null },
            title = { Text("Umbenennen") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Neuer Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank() && newName.trim() != target.name,
                    onClick = {
                        val updatedName = newName
                        renameTargetNode = null
                        runFileOperation(
                            onSuccess = { selectedPaths = emptySet() },
                        ) {
                            activeProvider.rename(target.path, updatedName)
                        }
                    },
                ) {
                    Text("Umbenennen")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTargetNode = null }) {
                    Text("Abbrechen")
                }
            },
        )
    }

    if (showDeleteDialog) {
        val count = selectedPaths.size
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Löschen") },
            text = {
                val message = if (count == 1) {
                    val name = selectedNodes.firstOrNull()?.name ?: "Element"
                    "„$name“ wirklich löschen?"
                } else {
                    "$count Elemente wirklich löschen?"
                }
                Text(message)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val pathsToDelete = selectedPaths.toList()
                        showDeleteDialog = false
                        runFileOperation(
                            onSuccess = { selectedPaths = emptySet() },
                        ) {
                            activeProvider.delete(pathsToDelete)
                        }
                    },
                ) {
                    Text("Löschen")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Abbrechen")
                }
            },
        )
    }
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
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
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
    title: String,
    subtitle: String,
    isPlaying: Boolean,
    onOpen: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
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
