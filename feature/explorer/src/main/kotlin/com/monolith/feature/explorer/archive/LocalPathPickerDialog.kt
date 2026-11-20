package com.monolith.feature.explorer.archive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.monolith.core.ui.components.MonolithDialog
import com.monolith.core.ui.components.MonolithLoadingSpinner
import com.monolith.core.ui.theme.LocalMonolithButtonShape
import com.monolith.core.vfs.GoogleDriveAuthManager
import com.monolith.core.vfs.GoogleDriveVfsProvider
import com.monolith.feature.explorer.ExternalVolumeKind
import com.monolith.feature.explorer.rememberExternalStorageVolumes
import kotlinx.coroutines.Dispatchers
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
)

@Composable
fun LocalPathPickerDialog(
    request: LocalPickerRequest,
    defaultRootDir: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val externalVolumes = rememberExternalStorageVolumes(context)
    val signedInAccounts = remember(request) { GoogleDriveAuthManager.getSignedInAccounts(context) }
    val driveProvider = remember(context) { GoogleDriveVfsProvider(context.applicationContext) }

    var activeRoot by remember(request) {
        val matchingExternal = externalVolumes.firstOrNull {
            request.initialDirectory.startsWith(it.rootPath)
        }?.rootPath
        mutableStateOf(matchingExternal ?: defaultRootDir)
    }
    var activeDriveEmail by remember(request) { mutableStateOf<String?>(null) }
    var driveStack by remember(request) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var currentDir by remember(request) {
        val initial = File(request.initialDirectory)
        mutableStateOf(if (initial.isDirectory) initial.absolutePath else defaultRootDir)
    }

    var children by remember { mutableStateOf<List<PickerItem>>(emptyList()) }
    var isLoadingChildren by remember { mutableStateOf(false) }
    var isDownloadingDrive by remember { mutableStateOf(false) }
    var selectedPaths by remember(request) { mutableStateOf(request.initialSelectedPaths) }
    val selectedDriveItems = remember(request) { mutableStateMapOf<String, PickerItem>() }
    var archivesOnlyFilter by remember(request) { mutableStateOf(request.archiveHighlight) }
    var searchQuery by remember(request) { mutableStateOf("") }
    var showCreateFolder by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var reloadTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(currentDir, activeDriveEmail, driveStack, archivesOnlyFilter, reloadTick, request.mode) {
        isLoadingChildren = true
        children = withContext(Dispatchers.IO) {
            if (activeDriveEmail != null) {
                val targetPath = driveStack.lastOrNull()?.first ?: "gdrive://root"
                val nodes = runCatching { driveProvider.list(targetPath, showHidden = false) }
                    .getOrDefault(emptyList())
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
                            isDrive = true,
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
        isLoadingChildren = false
    }

    val filteredChildren = remember(children, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) children
        else children.filter { it.name.contains(q, ignoreCase = true) }
    }

    val canGoUp = remember(currentDir, activeRoot, activeDriveEmail, driveStack) {
        if (activeDriveEmail != null) {
            driveStack.size > 1
        } else {
            val current = File(currentDir)
            currentDir != activeRoot && current.parentFile != null && currentDir.startsWith(activeRoot)
        }
    }

    val localBreadcrumbs = remember(currentDir, activeRoot, activeDriveEmail) {
        if (activeDriveEmail != null) {
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

    MonolithDialog(
        onDismissRequest = { if (!isDownloadingDrive) onDismiss() },
        title = request.title,
        wide = true,
        dismissLabel = "Abbrechen",
        onDismissClick = { if (!isDownloadingDrive) onDismiss() },
        confirmLabel = when {
            isDownloadingDrive -> "Wird geladen…"
            request.mode == LocalPickerMode.SINGLE_DIRECTORY && activeDriveEmail != null -> "Lokalen Ordner wählen"
            request.mode == LocalPickerMode.SINGLE_DIRECTORY -> "Diesen Ordner wählen"
            request.mode == LocalPickerMode.SINGLE_FILE -> "Öffnen"
            else -> "Übernehmen (${selectedPaths.size})"
        },
        confirmEnabled = when {
            isDownloadingDrive -> false
            request.mode == LocalPickerMode.SINGLE_DIRECTORY -> activeDriveEmail == null
            else -> selectedPaths.isNotEmpty()
        },
        onConfirmClick = {
            if (request.mode == LocalPickerMode.SINGLE_DIRECTORY) {
                onDismiss()
                request.onConfirm(listOf(currentDir))
            } else {
                val hasDriveSelection = selectedPaths.any { it.startsWith("gdrive://") }
                if (!hasDriveSelection) {
                    val result = selectedPaths.toList()
                    onDismiss()
                    request.onConfirm(result)
                } else {
                    isDownloadingDrive = true
                    scope.launch {
                        val resolved = withContext(Dispatchers.IO) {
                            suspend fun downloadDriveRecursive(remotePath: String, destFile: File): Boolean {
                                val item = selectedDriveItems[remotePath]
                                val isDir = item?.isDirectory == true
                                if (!isDir) {
                                    destFile.parentFile?.mkdirs()
                                    return driveProvider.downloadToFile(remotePath, destFile).isSuccess
                                }
                                destFile.mkdirs()
                                val subNodes = runCatching { driveProvider.list(remotePath, showHidden = false) }
                                    .getOrDefault(emptyList())
                                for (child in subNodes) {
                                    val childDest = File(destFile, child.name)
                                    if (child.isDirectory) {
                                        downloadDriveRecursive(child.path, childDest)
                                    } else {
                                        driveProvider.downloadToFile(child.path, childDest)
                                    }
                                }
                                return true
                            }

                            val cacheDir = File(context.cacheDir, "drive_picker").apply { mkdirs() }
                            selectedPaths.mapNotNull { path ->
                                if (!path.startsWith("gdrive://")) {
                                    path
                                } else {
                                    val item = selectedDriveItems[path]
                                    val matchName = item?.name
                                        ?: children.firstOrNull { it.id == path }?.name
                                        ?: "drive_file_${path.hashCode().toUInt().toString(16)}"
                                    val dest = File(cacheDir, matchName)
                                    if (item?.isDirectory == true) {
                                        if (downloadDriveRecursive(path, dest)) dest.absolutePath else null
                                    } else {
                                        driveProvider.downloadToFile(path, dest).getOrNull()?.absolutePath
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
        // 1:1 Storage Chips matching ExplorerScreen: Intern, External Volumes, Signed-in Google Drive Accounts
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = activeDriveEmail == null && activeRoot == defaultRootDir,
                shape = LocalMonolithButtonShape.current,
                onClick = {
                    activeDriveEmail = null
                    driveStack = emptyList()
                    activeRoot = defaultRootDir
                    currentDir = defaultRootDir
                },
                label = { Text("Intern") },
            )
            externalVolumes.forEach { vol ->
                FilterChip(
                    selected = activeDriveEmail == null && activeRoot == vol.rootPath,
                    shape = LocalMonolithButtonShape.current,
                    onClick = {
                        activeDriveEmail = null
                        driveStack = emptyList()
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
                        GoogleDriveAuthManager.setActiveEmail(context, email)
                        activeDriveEmail = email
                        driveStack = listOf("gdrive://root" to accountLabel)
                    },
                    label = { Text(accountLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }

        // Breadcrumbs + Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (canGoUp) {
                IconButton(
                    onClick = {
                        if (activeDriveEmail != null) {
                            if (driveStack.size > 1) driveStack = driveStack.dropLast(1)
                        } else {
                            File(currentDir).parentFile?.let { currentDir = it.absolutePath }
                        }
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                }
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (activeDriveEmail != null) {
                    val subStack = driveStack.drop(1)
                    subStack.forEachIndexed { idx, (_, label) ->
                        if (idx > 0) {
                            Icon(
                                imageVector = Icons.Outlined.ChevronRight,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = { driveStack = driveStack.take(idx + 2) },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (idx == subStack.lastIndex) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                            )
                        }
                    }
                } else {
                    localBreadcrumbs.forEachIndexed { index, (label, path) ->
                        if (index > 0) {
                            Icon(
                                imageVector = Icons.Outlined.ChevronRight,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = { currentDir = path },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (index == localBreadcrumbs.lastIndex) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            if (request.mode == LocalPickerMode.SINGLE_FILE && request.archiveHighlight) {
                FilterChip(
                    selected = archivesOnlyFilter,
                    shape = LocalMonolithButtonShape.current,
                    onClick = { archivesOnlyFilter = !archivesOnlyFilter },
                    label = { Text("Nur Archive") },
                )
            }
            if (request.mode == LocalPickerMode.SINGLE_DIRECTORY && activeDriveEmail == null) {
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
                .heightIn(min = 180.dp, max = 340.dp),
            shape = CutCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            when {
                isLoadingChildren -> {
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
                            text = if (searchQuery.isBlank()) "Keine passenden Einträge" else "Keine Treffer für „$searchQuery“",
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
                                        if (item.isDirectory) {
                                            if (item.isDrive) {
                                                driveStack = driveStack + (item.id to item.name)
                                            } else {
                                                currentDir = item.id
                                            }
                                        } else {
                                            when (request.mode) {
                                                LocalPickerMode.SINGLE_DIRECTORY -> Unit
                                                LocalPickerMode.SINGLE_FILE -> {
                                                    if (item.isDrive) selectedDriveItems[item.id] = item
                                                    selectedPaths = setOf(item.id)
                                                }
                                                LocalPickerMode.FILES_AND_FOLDERS_MULTI -> {
                                                    selectedPaths = if (isSelected) {
                                                        selectedDriveItems.remove(item.id)
                                                        selectedPaths - item.id
                                                    } else {
                                                        if (item.isDrive) selectedDriveItems[item.id] = item
                                                        selectedPaths + item.id
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = when {
                                        item.isDirectory -> Icons.Outlined.Folder
                                        item.isArchive -> Icons.Outlined.FolderZip
                                        else -> Icons.AutoMirrored.Outlined.InsertDriveFile
                                    },
                                    contentDescription = null,
                                    tint = when {
                                        item.isDirectory || item.isArchive -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(22.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (!item.isDirectory) {
                                        Text(
                                            text = formatArchiveBytes(item.sizeBytes),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                val showSelectBox = when (request.mode) {
                                    LocalPickerMode.FILES_AND_FOLDERS_MULTI -> true
                                    LocalPickerMode.SINGLE_FILE -> !item.isDirectory
                                    LocalPickerMode.SINGLE_DIRECTORY -> false
                                }
                                if (showSelectBox) {
                                    IconButton(
                                        onClick = {
                                            selectedPaths = when (request.mode) {
                                                LocalPickerMode.SINGLE_FILE -> {
                                                    if (item.isDrive) selectedDriveItems[item.id] = item
                                                    setOf(item.id)
                                                }
                                                else -> if (isSelected) {
                                                    selectedDriveItems.remove(item.id)
                                                    selectedPaths - item.id
                                                } else {
                                                    if (item.isDrive) selectedDriveItems[item.id] = item
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
}
