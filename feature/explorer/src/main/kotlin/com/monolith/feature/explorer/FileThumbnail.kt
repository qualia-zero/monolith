package com.monolith.feature.explorer

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.monolith.core.media.ThumbnailEngine
import com.monolith.core.model.FileCategory
import com.monolith.core.model.VfsNode

@Composable
fun FileThumbnail(
    node: VfsNode,
    fallbackIcon: ImageVector,
    modifier: Modifier = Modifier,
    isOfflineCached: Boolean = false,
) {
    val supportsThumbnail = !node.isDirectory && (
        !node.thumbnailUrl.isNullOrBlank() ||
            node.category == FileCategory.IMAGE ||
            node.category == FileCategory.VIDEO ||
            node.category == FileCategory.AUDIO
        )

    val context = LocalContext.current
    val initialBitmap = remember(node.path, node.lastModifiedMs, supportsThumbnail) {
        if (supportsThumbnail) {
            ThumbnailEngine.getCachedListThumbnail(
                path = node.path,
                lastModifiedMs = node.lastModifiedMs,
            )?.asImageBitmap()
        } else {
            null
        }
    }
    val thumbnail by produceState<ImageBitmap?>(
        initialValue = initialBitmap,
        key1 = node.path,
        key2 = node.lastModifiedMs,
        key3 = initialBitmap,
    ) {
        if (!supportsThumbnail || initialBitmap != null) return@produceState
        value = ThumbnailEngine.loadListThumbnail(
            context = context,
            path = node.path,
            category = node.category,
            lastModifiedMs = node.lastModifiedMs,
            thumbnailUrl = node.thumbnailUrl,
        )?.asImageBitmap()
    }

    Box(
        modifier = modifier
            .size(40.dp)
            .then(
                if (isOfflineCached) {
                    Modifier
                        .border(
                            width = 1.5.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
                            shape = MaterialTheme.shapes.small,
                        )
                        .padding(1.5.dp)
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = thumbnail
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.small),
            )
        } else {
            Icon(
                imageVector = fallbackIcon,
                contentDescription = null,
                tint = if (node.isDirectory) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
