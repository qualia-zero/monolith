package com.monolith.feature.explorer.archive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.monolith.core.ui.components.MonolithDialog
import com.monolith.core.ui.theme.LocalMonolithButtonShape
import com.monolith.feature.explorer.ExternalVolumeKind
import com.monolith.feature.explorer.rememberExternalStorageVolumes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@Composable
fun LocalPathPickerDialog(
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
    var selectedPaths by remember(request) { mutableStateOf(request.initialSelectedPaths) }
    var archivesOnlyFilter by remember(request) { mutableStateOf(request.archiveHighlight) }
    var searchQuery by remember(request) { mutableStateOf("") }
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
                        LocalPickerMode.SINGLE_DIRECTORY -> file.isDirectory
                        LocalPickerMode.SINGLE_FILE -> {
                            file.isDirectory || !archivesOnlyFilter || ArchiveEngine.isSupportedArchiveFile(file)
                        }
                        LocalPickerMode.FILES_AND_FOLDERS_MULTI -> true
                    }
                }
                ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) })
                ?.toList()
                ?: emptyList()
        }
    }

    val filteredChildren = remember(children, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) children
        else children.filter { it.name.contains(q, ignoreCase = true) }
    }

    val canGoUp = remember(currentDir, activeRoot) {
        val current = File(currentDir)
        currentDir != activeRoot && current.parentFile != null && currentDir.startsWith(activeRoot)
    }

    val breadcrumbs = remember(currentDir, activeRoot) {
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

    MonolithDialog(
        onDismissRequest = onDismiss,
        title = request.title,
        wide = true,
        dismissLabel = "Abbrechen",
        onDismissClick = onDismiss,
        confirmLabel = when (request.mode) {
            LocalPickerMode.SINGLE_DIRECTORY -> "Diesen Ordner wählen"
            LocalPickerMode.SINGLE_FILE -> "Öffnen"
            LocalPickerMode.FILES_AND_FOLDERS_MULTI -> "Übernehmen (${selectedPaths.size})"
        },
        confirmEnabled = when (request.mode) {
            LocalPickerMode.SINGLE_DIRECTORY -> true
            LocalPickerMode.SINGLE_FILE, LocalPickerMode.FILES_AND_FOLDERS_MULTI ->
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

        // Breadcrumbs + Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (canGoUp) {
                IconButton(
                    onClick = {
                        File(currentDir).parentFile?.let { currentDir = it.absolutePath }
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
                TextButton(
                    onClick = { currentDir = activeRoot },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "Stamm",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (breadcrumbs.isEmpty()) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
                breadcrumbs.forEachIndexed { index, (label, path) ->
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = { currentDir = path },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (index == breadcrumbs.lastIndex) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                        )
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
            if (request.mode == LocalPickerMode.SINGLE_DIRECTORY) {
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

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp, max = 340.dp),
            shape = CutCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            if (filteredChildren.isEmpty()) {
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
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp),
                ) {
                    items(filteredChildren, key = { it.absolutePath }) { file ->
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
                                        LocalPickerMode.FILES_AND_FOLDERS_MULTI -> {
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
                                LocalPickerMode.FILES_AND_FOLDERS_MULTI -> true
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
