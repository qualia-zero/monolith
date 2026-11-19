package com.monolith.feature.explorer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import com.monolith.core.ui.components.MonolithStarMenuOverlay
import com.monolith.core.ui.components.StarMenuItem

enum class MonolithToolDestination {
    FILES,
    ARCHIVES,
    STORAGE,
    DOWNLOADER,
    AI,
}

/**
 * Shared 5-item Monolith Menu overlay used across all top-level screens
 * (`Dateien`, `Archive`, `Speicherplatz`, `Downloader`, `AI`).
 */
@Composable
fun MonolithToolStarMenu(
    expanded: Boolean,
    anchorCenter: Offset,
    activeDestination: MonolithToolDestination,
    onSelectFiles: () -> Unit,
    onSelectArchives: () -> Unit,
    onSelectStorage: () -> Unit,
    onSelectDownloader: () -> Unit,
    onSelectAi: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    anchorIcon: ImageVector = Icons.Outlined.ViewInAr,
    onAnchorClick: (() -> Unit)? = null,
) {
    MonolithStarMenuOverlay(
        expanded = expanded,
        anchorCenter = anchorCenter,
        anchorIcon = anchorIcon,
        onAnchorClick = onAnchorClick,
        items = listOf(
            StarMenuItem(
                id = "files",
                label = "Dateien",
                icon = Icons.Outlined.FolderOpen,
                isSelected = activeDestination == MonolithToolDestination.FILES,
                onClick = {
                    if (activeDestination == MonolithToolDestination.FILES) onDismiss() else onSelectFiles()
                },
            ),
            StarMenuItem(
                id = "archives",
                label = "Archive",
                icon = Icons.Outlined.FolderZip,
                isSelected = activeDestination == MonolithToolDestination.ARCHIVES,
                onClick = {
                    if (activeDestination == MonolithToolDestination.ARCHIVES) onDismiss() else onSelectArchives()
                },
            ),
            StarMenuItem(
                id = "storage",
                label = "Speicherplatz",
                icon = Icons.Outlined.PieChart,
                isSelected = activeDestination == MonolithToolDestination.STORAGE,
                onClick = {
                    if (activeDestination == MonolithToolDestination.STORAGE) onDismiss() else onSelectStorage()
                },
            ),
            StarMenuItem(
                id = "downloader",
                label = "Downloader",
                icon = Icons.Outlined.Download,
                isSelected = activeDestination == MonolithToolDestination.DOWNLOADER,
                onClick = {
                    if (activeDestination == MonolithToolDestination.DOWNLOADER) onDismiss() else onSelectDownloader()
                },
            ),
            StarMenuItem(
                id = "ai_chat",
                label = "AI",
                icon = Icons.Outlined.AutoAwesome,
                isSelected = activeDestination == MonolithToolDestination.AI,
                onClick = {
                    if (activeDestination == MonolithToolDestination.AI) onDismiss() else onSelectAi()
                },
            ),
        ),
        onDismiss = onDismiss,
        modifier = modifier,
    )
}
