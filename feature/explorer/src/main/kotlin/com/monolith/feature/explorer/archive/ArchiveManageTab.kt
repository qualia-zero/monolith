package com.monolith.feature.explorer.archive

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Merge
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.monolith.core.ui.components.MonolithLoadingSpinner
import com.monolith.core.ui.theme.LocalMonolithButtonShape

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun ManageArchiveContent(
    summary: ArchiveSummary?,
    isInspecting: Boolean,
    isScanning: Boolean,
    reachedScanLimit: Boolean,
    discoveredArchives: List<ArchiveVolumeGroup>,
    searchQuery: String,
    archiveInternalPath: String,
    selectedEntryPaths: Set<String>,
    hasSavedPassword: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onRefreshScan: () -> Unit,
    onPickArchiveToOpen: () -> Unit,
    onOpenArchiveGroup: (ArchiveVolumeGroup) -> Unit,
    onCloseArchive: () -> Unit,
    onSwitchToPack: () -> Unit,
    onQuickExtract: (ArchiveVolumeGroup) -> Unit,
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
        val filteredArchives = remember(discoveredArchives, searchQuery) {
            val q = searchQuery.trim()
            if (q.isEmpty()) discoveredArchives
            else discoveredArchives.filter {
                it.displayName.contains(q, ignoreCase = true) ||
                    (it.primaryFile.parentFile?.name?.contains(q, ignoreCase = true) == true)
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
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
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = { Text("Gefundene Archive durchsuchen…") },
                    leadingIcon = {
                        Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(Icons.Default.Close, contentDescription = "Suche löschen", modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (searchQuery.isBlank()) {
                                "Gefundene Archive (${discoveredArchives.size})"
                            } else {
                                "Treffer (${filteredArchives.size} von ${discoveredArchives.size})"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (reachedScanLimit) {
                            Text(
                                text = "Scan-Limit erreicht — nutze „Archiv öffnen…“ für tiefere Ordner",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
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
            } else if (filteredArchives.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Text(
                            text = if (searchQuery.isBlank()) {
                                "Keine Archive gefunden."
                            } else {
                                "Keine Archive passend zu „$searchQuery“ gefunden."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            } else {
                items(filteredArchives, key = { it.primaryFile.absolutePath }) { group ->
                    DiscoveredArchiveCard(
                        group = group,
                        onClick = { onOpenArchiveGroup(group) },
                        onExtractClick = { onQuickExtract(group) },
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
                            text = "Split · ${group.parts.size} Teile",
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
                        onClick = { onQuickExtract(group) },
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
    onExtractClick: () -> Unit,
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
                horizontalArrangement = Arrangement.spacedBy(10.dp),
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
                IconButton(
                    onClick = onExtractClick,
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Unarchive,
                        contentDescription = "Entpacken",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (group.isMultiVolume || group.isMissingParts) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (group.isMultiVolume) {
                        BadgeChip(
                            text = "Split · ${group.parts.size} Teile",
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
