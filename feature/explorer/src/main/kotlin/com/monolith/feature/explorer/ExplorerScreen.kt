package com.monolith.feature.explorer

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.monolith.core.media.MpvEngine
import com.monolith.core.model.FileCategory
import com.monolith.core.model.SortBy
import com.monolith.core.model.VfsNode
import com.monolith.core.vfs.LocalVfsProvider

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
    val provider = remember { LocalVfsProvider() }
    val rootPath = remember { Environment.getExternalStorageDirectory().absolutePath }
    var currentPath by remember { mutableStateOf(rootPath) }
    var nodes by remember { mutableStateOf<List<VfsNode>>(emptyList()) }
    var showHidden by remember { mutableStateOf(false) }
    var sortBy by remember { mutableStateOf(SortBy.NAME) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }

    val playbackState by MpvEngine.uiState.collectAsState()

    BackHandler(enabled = currentPath != rootPath) {
        currentPath = currentPath.substringBeforeLast('/', rootPath).ifEmpty { rootPath }
    }

    LaunchedEffect(currentPath, showHidden, sortBy, hasStoragePermission) {
        if (hasStoragePermission) {
            val raw = provider.list(currentPath, showHidden)
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
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    val label = if (currentPath == rootPath) "Monolith" else currentPath.substringAfterLast('/')
                    Text(
                        text = label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (currentPath != rootPath) {
                        IconButton(onClick = {
                            currentPath = currentPath.substringBeforeLast('/', rootPath).ifEmpty { rootPath }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                        }
                    } else {
                        Box(
                            modifier = Modifier.size(48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.AutoAwesome,
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
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (playbackState.mediaUri.isNotBlank() && !playbackState.isEnded) {
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
        },
    ) { padding ->
        if (!hasStoragePermission) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
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
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(nodes, key = { it.id }) { node ->
                    FileRow(
                        node = node,
                        onClick = {
                            when {
                                node.isDirectory -> currentPath = node.path
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
                        },
                        onLongClick = if (node.isDirectory) {
                            null
                        } else {
                            {
                                val siblings = nodes.filter { it.category == node.category }
                                onOpenWith(node, siblings)
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
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (node.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = node.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = if (node.isDirectory) {
                "${node.childCount ?: 0} Elemente"
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
