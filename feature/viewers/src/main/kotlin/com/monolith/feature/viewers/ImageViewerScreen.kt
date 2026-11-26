package com.monolith.feature.viewers

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import com.monolith.core.vfs.GoogleDriveAuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private data class ImageMetadata(
    val sizeBytes: Long,
    val widthPx: Int,
    val heightPx: Int,
)

/**
 * Fullscreen Material 3 image viewer supporting swipe paging, pinch/double-tap zoom, pan, and rotation.
 */
@Composable
fun ImageViewerScreen(
    imagePaths: List<String>,
    initialIndex: Int,
    onNavigateBack: () -> Unit,
    imageNames: List<String> = emptyList(),
    imageSizes: List<Long> = emptyList(),
) {
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (imagePaths.size - 1).coerceAtLeast(0)),
        pageCount = { imagePaths.size },
    )

    var showOverlay by remember { mutableStateOf(false) }
    var currentPageScale by remember { mutableFloatStateOf(1f) }
    var rotationTrigger by remember { mutableIntStateOf(0) }
    val pageDimensions = remember { mutableStateMapOf<Int, Pair<Int, Int>>() }

    val currentPage = pagerState.currentPage
    val currentPath = imagePaths.getOrElse(currentPage) { "" }
    val currentFile = remember(currentPath) { File(currentPath) }
    val currentDisplayName = imageNames.getOrNull(currentPage)?.takeIf { it.isNotBlank() }
        ?: currentFile.name
    var localMetadata by remember(currentPath) { mutableStateOf<ImageMetadata?>(null) }

    LaunchedEffect(currentPath) {
        localMetadata = if (currentPath.isNotBlank()) {
            loadImageMetadata(currentPath)
        } else {
            null
        }
    }

    val effectiveMetadata = remember(localMetadata, currentPage, pageDimensions[currentPage], imageSizes) {
        val dims = pageDimensions[currentPage]
        val providedSize = imageSizes.getOrNull(currentPage)?.takeIf { it > 0L }
        when {
            localMetadata != null -> {
                val m = localMetadata!!
                if ((m.widthPx <= 0 || m.heightPx <= 0) && dims != null) {
                    m.copy(widthPx = dims.first, heightPx = dims.second)
                } else {
                    m
                }
            }
            dims != null || providedSize != null -> ImageMetadata(
                sizeBytes = providedSize ?: 0L,
                widthPx = dims?.first ?: 0,
                heightPx = dims?.second ?: 0,
            )
            else -> null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = currentPageScale <= 1.05f,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val path = imagePaths[page]
            val isCurrentPage = currentPage == page
            ZoomableImagePage(
                path = path,
                isCurrentPage = isCurrentPage,
                rotationTrigger = if (isCurrentPage) rotationTrigger else 0,
                onTap = { showOverlay = !showOverlay },
                onScaleChanged = { newScale ->
                    if (isCurrentPage) {
                        currentPageScale = newScale
                    }
                },
                onDimensionsLoaded = { w, h ->
                    if (w > 0 && h > 0) {
                        pageDimensions[page] = w to h
                    }
                },
            )
        }

        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ViewerTopOverlay(
                fileName = currentDisplayName,
                currentIndex = currentPage + 1,
                totalCount = imagePaths.size,
                onNavigateBack = onNavigateBack,
                onRotate = { rotationTrigger++ },
            )
        }

        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ViewerBottomOverlay(
                metadata = effectiveMetadata,
            )
        }
    }
}

@Composable
private fun ZoomableImagePage(
    path: String,
    isCurrentPage: Boolean,
    rotationTrigger: Int,
    onTap: () -> Unit,
    onScaleChanged: (Float) -> Unit,
    onDimensionsLoaded: (Int, Int) -> Unit,
) {
    val context = LocalContext.current
    var scale by remember(path) { mutableFloatStateOf(1f) }
    var offset by remember(path) { mutableStateOf(Offset.Zero) }
    var rotationDegrees by remember(path) { mutableFloatStateOf(0f) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var lastHandledTrigger by remember(path) { mutableIntStateOf(0) }

    LaunchedEffect(rotationTrigger, isCurrentPage) {
        if (isCurrentPage && rotationTrigger > lastHandledTrigger) {
            lastHandledTrigger = rotationTrigger
            rotationDegrees = (rotationDegrees + 90f) % 360f
        }
    }

    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage && scale != 1f) {
            scale = 1f
            offset = Offset.Zero
        } else if (isCurrentPage) {
            onScaleChanged(scale)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .pointerInput(path) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tapOffset ->
                        if (scale > 1.05f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            val targetScale = 2.5f
                            val center = Offset(
                                x = containerSize.width / 2f,
                                y = containerSize.height / 2f,
                            )
                            val rawOffset = (center - tapOffset) * (targetScale - 1f)
                            scale = targetScale
                            offset = clampOffset(rawOffset, targetScale, containerSize)
                        }
                        onScaleChanged(scale)
                    },
                )
            }
            .pointerInput(path) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val canceled = event.changes.any { it.isConsumed }
                        if (!canceled) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val pointerCount = event.changes.count { it.pressed }

                            // Only intercept single-finger drag when already zoomed in so HorizontalPager keeps swiping at 1x.
                            if (pointerCount > 1 || scale > 1.01f) {
                                val newScale = (scale * zoomChange).coerceIn(1f, 5f)
                                val newOffset = if (newScale > 1.01f) {
                                    clampOffset(offset + panChange, newScale, containerSize)
                                } else {
                                    Offset.Zero
                                }
                                scale = newScale
                                offset = newOffset
                                onScaleChanged(newScale)

                                if (pointerCount > 1 || (newScale > 1.01f && panChange != Offset.Zero)) {
                                    event.changes.forEach { change ->
                                        if (change.positionChanged()) {
                                            change.consume()
                                        }
                                    }
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val isRemoteUrl = path.startsWith("http://", ignoreCase = true) ||
            path.startsWith("https://", ignoreCase = true)
        val isDriveUrl = isRemoteUrl && path.contains("googleapis.com/drive/", ignoreCase = true)
        val driveToken by produceState<String?>(initialValue = null, key1 = path, key2 = isDriveUrl) {
            value = if (isDriveUrl) GoogleDriveAuthManager.getAccessToken(context) else null
        }
        SubcomposeAsyncImage(
            model = remember(path, driveToken) {
                ImageRequest.Builder(context)
                    .data(if (isRemoteUrl) path else File(path))
                    .apply {
                        if (isDriveUrl && !driveToken.isNullOrBlank()) {
                            addHeader("Authorization", "Bearer $driveToken")
                        }
                    }
                    .crossfade(true)
                    .build()
            },
            contentDescription = File(path).name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                    rotationZ = rotationDegrees
                },
        ) {
            when (painter.state) {
                is AsyncImagePainter.State.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }

                is AsyncImagePainter.State.Error -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.BrokenImage,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(modifier = Modifier.size(12.dp))
                        Text(
                            text = "Bild konnte nicht geladen werden.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.8f),
                        )
                    }
                }

                is AsyncImagePainter.State.Success -> {
                    val drawable = (painter.state as AsyncImagePainter.State.Success).result.drawable
                    LaunchedEffect(path, drawable) {
                        onDimensionsLoaded(drawable.intrinsicWidth, drawable.intrinsicHeight)
                    }
                    SubcomposeAsyncImageContent()
                }

                else -> SubcomposeAsyncImageContent()
            }
        }
    }
}

@Composable
private fun ViewerTopOverlay(
    fileName: String,
    currentIndex: Int,
    totalCount: Int,
    onNavigateBack: () -> Unit,
    onRotate: () -> Unit,
) {
    Surface(
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.75f),
                        Color.Black.copy(alpha = 0.35f),
                        Color.Transparent,
                    ),
                ),
            )
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Zurück",
                    tint = Color.White,
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            ) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (totalCount > 1) {
                    Text(
                        text = "$currentIndex / $totalCount",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }
            }

            IconButton(onClick = onRotate) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.RotateRight,
                    contentDescription = "Um 90° drehen",
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun ViewerBottomOverlay(
    metadata: ImageMetadata?,
) {
    Surface(
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.4f),
                        Color.Black.copy(alpha = 0.75f),
                    ),
                ),
            )
            .navigationBarsPadding(),
    ) {
        val infoText = buildString {
            if (metadata != null) {
                if (metadata.widthPx > 0 && metadata.heightPx > 0) {
                    append("${metadata.widthPx} × ${metadata.heightPx} px")
                }
                if (metadata.sizeBytes > 0L) {
                    if (isNotEmpty()) append("  •  ")
                    append(formatFileSize(metadata.sizeBytes))
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = infoText,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun clampOffset(offset: Offset, scale: Float, containerSize: IntSize): Offset {
    if (containerSize == IntSize.Zero || scale <= 1f) return Offset.Zero
    val maxX = (containerSize.width * (scale - 1f)) / 2f
    val maxY = (containerSize.height * (scale - 1f)) / 2f
    return Offset(
        x = offset.x.coerceIn(-maxX, maxX),
        y = offset.y.coerceIn(-maxY, maxY),
    )
}

private suspend fun loadImageMetadata(path: String): ImageMetadata? = withContext(Dispatchers.IO) {
    if (path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)) {
        return@withContext null
    }
    val file = File(path)
    val sizeBytes = if (file.exists()) file.length() else 0L
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, options)
    ImageMetadata(
        sizeBytes = sizeBytes,
        widthPx = options.outWidth.coerceAtLeast(0),
        heightPx = options.outHeight.coerceAtLeast(0),
    )
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(Locale.GERMAN, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> String.format(Locale.GERMAN, "%.2f MB", bytes / (1024.0 * 1024.0))
        else -> String.format(Locale.GERMAN, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
