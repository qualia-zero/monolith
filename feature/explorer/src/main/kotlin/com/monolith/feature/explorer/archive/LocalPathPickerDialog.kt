package com.monolith.feature.explorer.archive

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.monolith.core.common.MimeNormalizer
import com.monolith.core.model.LocationType
import com.monolith.core.model.VfsNode
import com.monolith.core.ui.components.MonolithDialog
import com.monolith.core.ui.components.MonolithLoadingSpinner
import com.monolith.core.ui.haptics.MonolithHaptics
import com.monolith.core.ui.theme.LocalMonolithButtonShape
import com.monolith.core.vfs.GoogleDriveAuthManager
import com.monolith.core.vfs.GoogleDriveVfsProvider
import com.monolith.core.vfs.SmbConnectionStore
import com.monolith.core.vfs.SmbServerConfig
import com.monolith.core.vfs.SmbVfsProvider
import com.monolith.feature.explorer.FileRow
import com.monolith.feature.explorer.rememberExternalStorageVolumes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private data class PickerItem(
    val id: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val isArchive: Boolean,
    val isDrive: Boolean,
    val isSmb: Boolean = false,
)

@Composable
fun LocalPathPickerDialog(
    request: LocalPickerRequest,
    defaultRootDir: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val externalVolumes = rememberExternalStorageVolumes(context)
    val signedInAccounts = remember(request) { GoogleDriveAuthManager.getSignedInAccounts(context) }
    val driveProvider = remember(context) { GoogleDriveVfsProvider(context.applicationContext) }
    val smbServers = remember(request) { SmbConnectionStore.getServers(context) }
    val smbProvider = remember(context) { SmbVfsProvider(context.applicationContext) }

    var activeRoot by remember(request) {
        val matchingExternal = externalVolumes.firstOrNull {
            request.initialDirectory.startsWith(it.rootPath)
        }?.rootPath
        mutableStateOf(matchingExternal ?: defaultRootDir)
    }
    var activeDriveEmail by remember(request) { mutableStateOf<String?>(null) }
    var driveStack by remember(request) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var activeSmbServer by remember(request) { mutableStateOf<SmbServerConfig?>(null) }
    var smbStack by remember(request) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var currentDir by remember(request) {
        val initial = File(request.initialDirectory)
        mutableStateOf(if (initial.isDirectory) initial.absolutePath else defaultRootDir)
    }

    var children by remember { mutableStateOf<List<PickerItem>>(emptyList()) }
    var isLoadingChildren by remember(request) { mutableStateOf(true) }
    var hasLoadedOnce by remember(request) { mutableStateOf(false) }
    var showLoadingOverlay by remember { mutableStateOf(false) }
    var isDownloadingDrive by remember { mutableStateOf(false) }
    var selectedPaths by remember(request) { mutableStateOf(request.initialSelectedPaths) }
    val selectedDriveItems = remember(request) { mutableStateMapOf<String, PickerItem>() }
    var archivesOnlyFilter by remember(request) { mutableStateOf(request.archiveHighlight) }
    var searchQuery by remember(request) { mutableStateOf("") }
    var showCreateFolder by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var reloadTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(currentDir, activeDriveEmail, driveStack, activeSmbServer, smbStack, archivesOnlyFilter, reloadTick, request.mode) {
        isLoadingChildren = true
        try {
            children = withContext(Dispatchers.IO) {
            if (activeDriveEmail != null || activeSmbServer != null) {
                val isSmbMode = activeSmbServer != null
                val targetPath = if (isSmbMode) {
                    smbStack.lastOrNull()?.first ?: activeSmbServer!!.rootPath()
                } else {
                    driveStack.lastOrNull()?.first ?: "gdrive://root"
                }
                val nodes = runCatching {
                    if (isSmbMode) smbProvider.list(targetPath, showHidden = false)
                    else driveProvider.list(targetPath, showHidden = false)
                }.getOrDefault(emptyList())
                nodes.asSequence()
                    .filter { node ->
                        when (request.mode) {
                            LocalPickerMode.SINGLE_DIRECTORY -> node.isDirectory
                            LocalPickerMode.SINGLE_FILE -> {
                                node.isDirectory || !archivesOnlyFilter ||
                                    ArchiveEngine.isSupportedArchiveFile(File(node.name))
                            }
                            LocalPickerMode.FILES_AND_FOLDERS_MULTI -> true
                        }
                    }
                    .sortedWith(
                        compareByDescending<com.monolith.core.model.VfsNode> { it.isDirectory }
                            .thenBy { it.name.lowercase(Locale.ROOT) },
                    )
                    .map { node ->
                        PickerItem(
                            id = node.path,
                            name = node.name,
                            isDirectory = node.isDirectory,
                            sizeBytes = node.sizeBytes,
                            isArchive = !node.isDirectory && ArchiveEngine.isSupportedArchiveFile(File(node.name)),
                            isDrive = !isSmbMode,
                            isSmb = isSmbMode,
                        )
                    }
                    .toList()
            } else {
                val dir = File(currentDir)
                if (!dir.isDirectory) return@withContext emptyList()
                dir.listFiles()
                    ?.asSequence()
                    ?.filter { !it.name.startsWith(".") }
                    ?.filter { file ->
                        when (request.mode) {
                            LocalPickerMode.SINGLE_DIRECTORY -> file.isDirectory
                            LocalPickerMode.SINGLE_FILE -> {
                                file.isDirectory || !archivesOnlyFilter || ArchiveEngine.isSupportedArchiveFile(file)
                            }
                            LocalPickerMode.FILES_AND_FOLDERS_MULTI -> true
                        }
                    }
                    ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) })
                    ?.map { file ->
                        PickerItem(
                            id = file.absolutePath,
                            name = file.name,
                            isDirectory = file.isDirectory,
                            sizeBytes = file.length(),
                            isArchive = !file.isDirectory && ArchiveEngine.isSupportedArchiveFile(file),
                            isDrive = false,
                        )
                    }
                    ?.toList()
                    ?: emptyList()
            }
            }
        } finally {
            isLoadingChildren = false
            hasLoadedOnce = true
        }
    }

    LaunchedEffect(isLoadingChildren) {
        if (isLoadingChildren) {
            delay(120L)
            showLoadingOverlay = true
        } else {
            showLoadingOverlay = false
        }
    }

    val filteredChildren = remember(children, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) children
        else children.filter { it.name.contains(q, ignoreCase = true) }
    }

    val canGoUp = remember(currentDir, activeRoot, activeDriveEmail, driveStack, activeSmbServer, smbStack) {
        when {
            activeDriveEmail != null -> driveStack.size > 1
            activeSmbServer != null -> smbStack.size > 1
            else -> {
                val current = File(currentDir)
                currentDir != activeRoot && current.parentFile != null && currentDir.startsWith(activeRoot)
            }
        }
    }

    val localBreadcrumbs = remember(currentDir, activeRoot, activeDriveEmail, activeSmbServer) {
        if (activeDriveEmail != null || activeSmbServer != null) {
            emptyList()
        } else {
            val relative = currentDir.removePrefix(activeRoot).trim('/')
            if (relative.isEmpty()) {
                emptyList()
            } else {
                val parts = relative.split('/').filter { it.isNotBlank() }
                var acc = activeRoot.trimEnd('/')
                parts.map { seg ->
                    acc = "$acc/$seg"
                    seg to acc
                }
            }
        }
    }

    BackHandler(enabled = canGoUp && !isDownloadingDrive) {
        when {
            activeDriveEmail != null -> driveStack = driveStack.dropLast(1)
            activeSmbServer != null -> smbStack = smbStack.dropLast(1)
            else -> currentDir = File(currentDir).parentFile?.absolutePath ?: activeRoot
        }
    }

    MonolithDialog(
        onDismissRequest = { if (!isDownloadingDrive) onDismiss() },
        title = request.title,
        wide = true,
        dismissLabel = "Abbrechen",
        onDismissClick = { if (!isDownloadingDrive) onDismiss() },
        confirmLabel = when {
            isDownloadingDrive -> "Wird geladen…"
            request.mode == LocalPickerMode.SINGLE_DIRECTORY -> "Diesen Ordner wählen"
            request.mode == LocalPickerMode.SINGLE_FILE -> "Öffnen"
            else -> "Übernehmen (${selectedPaths.size})"
        },
        confirmEnabled = when {
            isDownloadingDrive -> false
            request.mode == LocalPickerMode.SINGLE_DIRECTORY -> true
            else -> selectedPaths.isNotEmpty()
        },
        onConfirmClick = {
            if (request.mode == LocalPickerMode.SINGLE_DIRECTORY) {
                val chosenDir = when {
                    activeDriveEmail != null -> driveStack.lastOrNull()?.first ?: "gdrive://root"
                    activeSmbServer != null -> smbStack.lastOrNull()?.first ?: activeSmbServer!!.rootPath()
                    else -> currentDir
                }
                onDismiss()
                request.onConfirm(listOf(chosenDir))
            } else {
                val hasRemoteSelection = selectedPaths.any { it.startsWith("gdrive://") || it.startsWith("smb://") }
                if (!hasRemoteSelection) {
                    val result = selectedPaths.toList()
                    onDismiss()
                    request.onConfirm(result)
                } else {
                    isDownloadingDrive = true
                    scope.launch {
                        val resolved = withContext(Dispatchers.IO) {
                            suspend fun downloadRemoteRecursive(remotePath: String, destFile: File): Boolean {
                                val isSmb = remotePath.startsWith("smb://")
                                val item = selectedDriveItems[remotePath]
                                val isDir = item?.isDirectory == true
                                if (!isDir) {
                                    destFile.parentFile?.mkdirs()
                                    return if (isSmb) {
                                        smbProvider.downloadToFile(remotePath, destFile).isSuccess
                                    } else {
                                        driveProvider.downloadToFile(remotePath, destFile).isSuccess
                                    }
                                }
                                destFile.mkdirs()
                                val subNodes = runCatching {
                                    if (isSmb) smbProvider.list(remotePath, showHidden = false)
                                    else driveProvider.list(remotePath, showHidden = false)
                                }.getOrDefault(emptyList())
                                for (child in subNodes) {
                                    val childDest = File(destFile, child.name)
                                    if (child.isDirectory) {
                                        downloadRemoteRecursive(child.path, childDest)
                                    } else {
                                        if (isSmb) smbProvider.downloadToFile(child.path, childDest)
                                        else driveProvider.downloadToFile(child.path, childDest)
                                    }
                                }
                                return true
                            }

                            val cacheDir = File(context.cacheDir, "drive_picker").apply { mkdirs() }
                            selectedPaths.mapNotNull { path ->
                                if (!path.startsWith("gdrive://") && !path.startsWith("smb://")) {
                                    path
                                } else {
                                    val isSmb = path.startsWith("smb://")
                                    val item = selectedDriveItems[path]
                                    val matchName = item?.name
                                        ?: children.firstOrNull { it.id == path }?.name
                                        ?: "remote_file_${path.hashCode().toUInt().toString(16)}"
                                    val dest = File(cacheDir, matchName)
                                    if (item?.isDirectory == true) {
                                        if (downloadRemoteRecursive(path, dest)) dest.absolutePath else null
                                    } else {
                                        if (isSmb) smbProvider.downloadToFile(path, dest).getOrNull()?.absolutePath
                                        else driveProvider.downloadToFile(path, dest).getOrNull()?.absolutePath
                                    }
                                }
                            }
                        }
                        isDownloadingDrive = false
                        onDismiss()
                        if (resolved.isNotEmpty()) {
                            request.onConfirm(resolved)
                        }
                    }
                }
            }
        },
    ) {
        // 1:1 Storage Chips + Breadcrumbs matching ExplorerScreen
        val isInsideSubfolder = when {
            activeDriveEmail != null -> driveStack.size > 1
            activeSmbServer != null -> smbStack.size > 1
            else -> localBreadcrumbs.isNotEmpty()
        }
        val chipScrollState = rememberScrollState()
        LaunchedEffect(currentDir, driveStack, activeDriveEmail, smbStack, activeSmbServer, activeRoot) {
            if (isInsideSubfolder) {
                chipScrollState.animateScrollTo(chipScrollState.maxValue)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(chipScrollState),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isInsideSubfolder) {
                if (activeDriveEmail != null) {
                    val accountLabel = activeDriveEmail?.substringBefore('@') ?: "Google Drive"
                    FilterChip(
                        selected = false,
                        shape = LocalMonolithButtonShape.current,
                        onClick = {
                            MonolithHaptics.pathChipNav(context, view)
                            driveStack = driveStack.take(1)
                        },
                        label = { Text(accountLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                    val subStack = driveStack.drop(1)
                    subStack.forEachIndexed { idx, (_, label) ->
                        Icon(
                            imageVector = Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FilterChip(
                            selected = idx == subStack.lastIndex,
                            shape = LocalMonolithButtonShape.current,
                            onClick = {
                                MonolithHaptics.pathChipNav(context, view)
                                driveStack = driveStack.take(idx + 2)
                            },
                            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                } else if (activeSmbServer != null) {
                    val serverLabel = activeSmbServer?.effectiveDisplayName() ?: "SMB"
                    FilterChip(
                        selected = false,
                        shape = LocalMonolithButtonShape.current,
                        onClick = {
                            MonolithHaptics.pathChipNav(context, view)
                            smbStack = smbStack.take(1)
                        },
                        label = { Text(serverLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                    val subStack = smbStack.drop(1)
                    subStack.forEachIndexed { idx, (_, label) ->
                        Icon(
                            imageVector = Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FilterChip(
                            selected = idx == subStack.lastIndex,
                            shape = LocalMonolithButtonShape.current,
                            onClick = {
                                MonolithHaptics.pathChipNav(context, view)
                                smbStack = smbStack.take(idx + 2)
                            },
                            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                } else {
                    val rootChipLabel = externalVolumes.firstOrNull { it.rootPath == activeRoot }?.name ?: "Intern"
                    FilterChip(
                        selected = false,
                        shape = LocalMonolithButtonShape.current,
                        onClick = {
                            MonolithHaptics.pathChipNav(context, view)
                            currentDir = activeRoot
                        },
                        label = { Text(rootChipLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                    localBreadcrumbs.forEachIndexed { index, (label, path) ->
                        Icon(
                            imageVector = Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FilterChip(
                            selected = index == localBreadcrumbs.lastIndex,
                            shape = LocalMonolithButtonShape.current,
                            onClick = {
                                MonolithHaptics.pathChipNav(context, view)
                                currentDir = path
                            },
                            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            } else {
                FilterChip(
                    selected = activeDriveEmail == null && activeSmbServer == null && activeRoot == defaultRootDir,
                    shape = LocalMonolithButtonShape.current,
                    onClick = {
                        MonolithHaptics.pathChipNav(context, view)
                        activeDriveEmail = null
                        driveStack = emptyList()
                        activeSmbServer = null
                        smbStack = emptyList()
                        activeRoot = defaultRootDir
                        currentDir = defaultRootDir
                    },
                    label = { Text("Intern") },
                )
                externalVolumes.forEach { vol ->
                    FilterChip(
                        selected = activeDriveEmail == null && activeSmbServer == null && activeRoot == vol.rootPath,
                        shape = LocalMonolithButtonShape.current,
                        onClick = {
                            MonolithHaptics.pathChipNav(context, view)
                            activeDriveEmail = null
                            driveStack = emptyList()
                            activeSmbServer = null
                            smbStack = emptyList()
                            activeRoot = vol.rootPath
                            currentDir = vol.rootPath
                        },
                        label = { Text(vol.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
                signedInAccounts.forEach { email ->
                    val accountLabel = email.substringBefore('@')
                    FilterChip(
                        selected = activeDriveEmail == email,
                        shape = LocalMonolithButtonShape.current,
                        onClick = {
                            MonolithHaptics.pathChipNav(context, view)
                            GoogleDriveAuthManager.setActiveEmail(context, email)
                            activeSmbServer = null
                            smbStack = emptyList()
                            activeDriveEmail = email
                            driveStack = listOf("gdrive://root" to accountLabel)
                        },
                        label = { Text(accountLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
                smbServers.forEach { server ->
                    val serverLabel = server.effectiveDisplayName()
                    FilterChip(
                        selected = activeSmbServer?.id == server.id,
                        shape = LocalMonolithButtonShape.current,
                        onClick = {
                            MonolithHaptics.pathChipNav(context, view)
                            activeDriveEmail = null
                            driveStack = emptyList()
                            activeSmbServer = server
                            smbStack = listOf(server.rootPath() to serverLabel)
                        },
                        label = { Text(serverLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }

        // Actions row (only shown when needed)
        if ((request.mode == LocalPickerMode.SINGLE_FILE && request.archiveHighlight) ||
            (request.mode == LocalPickerMode.SINGLE_DIRECTORY && activeDriveEmail == null && activeSmbServer == null)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                if (request.mode == LocalPickerMode.SINGLE_FILE && request.archiveHighlight) {
                    FilterChip(
                        selected = archivesOnlyFilter,
                        shape = LocalMonolithButtonShape.current,
                        onClick = { archivesOnlyFilter = !archivesOnlyFilter },
                        label = { Text("Nur Archive") },
                    )
                }
                if (request.mode == LocalPickerMode.SINGLE_DIRECTORY && activeDriveEmail == null && activeSmbServer == null) {
                    IconButton(
                        onClick = {
                            newFolderName = ""
                            showCreateFolder = !showCreateFolder
                        },
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(Icons.Outlined.CreateNewFolder, contentDescription = "Neuer Ordner")
                    }
                }
            }
        }

        // Search filter inside current directory
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Im Ordner filtern…") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Filter löschen", modifier = Modifier.size(18.dp))
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        AnimatedVisibility(visible = showCreateFolder && request.mode == LocalPickerMode.SINGLE_DIRECTORY && activeDriveEmail == null) {
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
                .heightIn(min = 180.dp, max = 340.dp)
                .animateContentSize(),
            shape = CutCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                when {
                    !hasLoadedOnce && isLoadingChildren -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            MonolithLoadingSpinner()
                        }
                    }
                    filteredChildren.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (searchQuery.isBlank()) "Keine passenden Einträge." else "Keine Treffer für „$searchQuery“.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 340.dp),
                        ) {
                            items(filteredChildren, key = { it.id }) { item ->
                                val isSelected = item.id in selectedPaths
                                val node = remember(item) {
                                    val mime = if (item.isDirectory) "inode/directory" else MimeNormalizer.normalizeMime(item.name)
                                    val category = MimeNormalizer.categorize(
                                        fileName = item.name,
                                        isDirectory = item.isDirectory,
                                        mimeType = mime,
                                    )
                                    VfsNode(
                                        id = item.id,
                                        name = item.name,
                                        path = item.id,
                                        parentPath = null,
                                        locationType = when {
                                            item.isSmb -> LocationType.SMB
                                            item.isDrive -> LocationType.GOOGLE_DRIVE
                                            else -> LocationType.LOCAL
                                        },
                                        isDirectory = item.isDirectory,
                                        sizeBytes = item.sizeBytes,
                                        lastModifiedMs = 0L,
                                        mimeType = mime,
                                        category = category,
                                    )
                                }
                                val showSelectBox = when (request.mode) {
                                    LocalPickerMode.FILES_AND_FOLDERS_MULTI -> true
                                    LocalPickerMode.SINGLE_FILE -> !item.isDirectory
                                    LocalPickerMode.SINGLE_DIRECTORY -> false
                                }
                                FileRow(
                                    node = node,
                                    isSelected = isSelected,
                                    onClick = {
                                        if (item.isDirectory) {
                                            MonolithHaptics.folderOpen(context, view)
                                            when {
                                                item.isDrive -> driveStack = driveStack + (item.id to item.name)
                                                item.isSmb -> smbStack = smbStack + (item.id to item.name)
                                                else -> currentDir = item.id
                                            }
                                        } else {
                                            val isRemote = item.isDrive || item.isSmb
                                            when (request.mode) {
                                                LocalPickerMode.SINGLE_DIRECTORY -> Unit
                                                LocalPickerMode.SINGLE_FILE -> {
                                                    if (isRemote) selectedDriveItems[item.id] = item
                                                    selectedPaths = setOf(item.id)
                                                }
                                                LocalPickerMode.FILES_AND_FOLDERS_MULTI -> {
                                                    selectedPaths = if (isSelected) {
                                                        selectedDriveItems.remove(item.id)
                                                        selectedPaths - item.id
                                                    } else {
                                                        if (isRemote) selectedDriveItems[item.id] = item
                                                        selectedPaths + item.id
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    trailingContent = if (showSelectBox) {
                                        {
                                            IconButton(
                                                onClick = {
                                                    val isRemote = item.isDrive || item.isSmb
                                                    selectedPaths = when (request.mode) {
                                                        LocalPickerMode.SINGLE_FILE -> {
                                                            if (isRemote) selectedDriveItems[item.id] = item
                                                            setOf(item.id)
                                                        }
                                                        else -> if (isSelected) {
                                                            selectedDriveItems.remove(item.id)
                                                            selectedPaths - item.id
                                                        } else {
                                                            if (isRemote) selectedDriveItems[item.id] = item
                                                            selectedPaths + item.id
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
                                    } else {
                                        null
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
                if (hasLoadedOnce && showLoadingOverlay) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.65f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        MonolithLoadingSpinner()
                    }
                }
            }
        }
    }
}
