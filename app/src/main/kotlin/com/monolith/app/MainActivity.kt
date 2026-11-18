package com.monolith.app

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.monolith.core.common.AppSettingsStore
import com.monolith.core.common.DefaultAppsStore
import com.monolith.core.media.MpvEngine
import com.monolith.core.media.ThumbnailEngine
import com.monolith.core.model.DefaultAppAssociation
import com.monolith.core.model.FileCategory
import com.monolith.core.model.ThemeMode
import com.monolith.core.model.VfsNode
import com.monolith.core.ui.components.MonolithDialog
import com.monolith.core.ui.theme.MonolithTheme
import com.monolith.core.vfs.FileTransferService
import com.monolith.core.vfs.GoogleDriveAuthManager
import com.monolith.core.vfs.GoogleDriveVfsProvider
import com.monolith.feature.explorer.CloudCacheManager
import com.monolith.feature.explorer.ExplorerScreen
import com.monolith.feature.explorer.SettingsScreen
import com.monolith.feature.explorer.ai.AiChatScreen
import com.monolith.feature.explorer.rememberExplorerNavigationState
import com.monolith.feature.player.PlayerActivity
import com.monolith.feature.viewers.ImageViewerActivity
import com.monolith.feature.viewers.TextEditorActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

private enum class MainDestination {
    EXPLORER,
    SETTINGS,
    AI_CHAT,
}

private data class PendingOpenRequest(
    val node: VfsNode,
    val siblings: List<VfsNode>,
)

class MainActivity : ComponentActivity() {
    private var hasStoragePermission by mutableStateOf(false)
    private var openTransferFromNotification by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleTransferIntent(intent)
        AppSettingsStore.init(applicationContext)
        DefaultAppsStore.init(applicationContext)
        MpvEngine.bearerTokenProvider = { _ ->
            runBlocking { GoogleDriveAuthManager.getAccessToken(applicationContext) }
        }
        ThumbnailEngine.cloudTokenProvider = {
            runBlocking { GoogleDriveAuthManager.getAccessToken(applicationContext) }
        }
        refreshPermission()
        setContent {
            val appSettings by AppSettingsStore.settings.collectAsState()
            val driveProvider = remember { GoogleDriveVfsProvider(applicationContext) }
            val scope = rememberCoroutineScope()
            var activeDestination by remember { mutableStateOf(MainDestination.EXPLORER) }
            var settingsReturnToAi by remember { mutableStateOf(false) }
            var aiCurrentDirectory by remember { mutableStateOf("") }
            val explorerNavState = rememberExplorerNavigationState()
            var pendingOpen by remember { mutableStateOf<PendingOpenRequest?>(null) }
            var downloadingDriveFile by remember { mutableStateOf<String?>(null) }
            val transferProgress by FileTransferService.progress.collectAsState()
            var lastSeenTransferTaskId by remember { mutableStateOf<Long?>(null) }
            var showTransferOverlay by remember { mutableStateOf(false) }

            LaunchedEffect(transferProgress?.taskId) {
                val current = transferProgress ?: return@LaunchedEffect
                if (current.taskId != lastSeenTransferTaskId) {
                    lastSeenTransferTaskId = current.taskId
                    if (!current.isFinished) {
                        showTransferOverlay = true
                    }
                }
            }

            LaunchedEffect(openTransferFromNotification) {
                if (openTransferFromNotification) {
                    openTransferFromNotification = false
                    if (FileTransferService.progress.value != null) {
                        showTransferOverlay = true
                    }
                }
            }

            val darkTheme = when (appSettings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK, ThemeMode.CYBER_PHOSPHOR, ThemeMode.OBSIDIAN -> true
            }

            LaunchedEffect(darkTheme) {
                val statusBarStyle = if (darkTheme) {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                }
                val navigationBarStyle = if (darkTheme) {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                }
                enableEdgeToEdge(
                    statusBarStyle = statusBarStyle,
                    navigationBarStyle = navigationBarStyle,
                )
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }

            fun resolveOfflineDriveFile(node: VfsNode): File? {
                if (!node.path.startsWith("gdrive://")) return null
                return if (CloudCacheManager.isNodeCached(this@MainActivity, node)) {
                    CloudCacheManager.cachedFileFor(this@MainActivity, node)
                } else {
                    null
                }
            }

            fun openLocalWithAssociation(
                node: VfsNode,
                siblings: List<VfsNode>,
                handlerId: String,
                packageName: String?,
                activityName: String?,
            ): Boolean {
                return when (handlerId) {
                    HANDLER_INTERNAL_IMAGE -> {
                        val images = siblings.ifEmpty { listOf(node) }
                        ImageViewerActivity.launch(
                            context = this@MainActivity,
                            path = node.path,
                            imagePaths = images.map { it.path },
                        )
                        true
                    }
                    HANDLER_INTERNAL_VIDEO, HANDLER_INTERNAL_AUDIO -> {
                        val isVideo = handlerId == HANDLER_INTERNAL_VIDEO || node.category == FileCategory.VIDEO
                        val playlist = siblings.ifEmpty { listOf(node) }
                        val uri = if (node.path.startsWith("gdrive://")) {
                            resolveOfflineDriveFile(node)?.let { Uri.fromFile(it).toString() }
                                ?: driveProvider.streamUrl(node.path)
                        } else {
                            Uri.fromFile(File(node.path)).toString()
                        }
                        val pairs = playlist.map { item ->
                            val itemUri = if (item.path.startsWith("gdrive://")) {
                                resolveOfflineDriveFile(item)?.let { Uri.fromFile(it).toString() }
                                    ?: driveProvider.streamUrl(item.path)
                            } else {
                                Uri.fromFile(File(item.path)).toString()
                            }
                            itemUri to item.name
                        }
                        PlayerActivity.launch(
                            context = this@MainActivity,
                            uri = uri,
                            title = node.name,
                            isVideo = isVideo,
                            playlist = pairs,
                        )
                        true
                    }
                    HANDLER_INTERNAL_TEXT -> {
                        TextEditorActivity.launch(
                            context = this@MainActivity,
                            path = node.path,
                        )
                        true
                    }
                    else -> openExternal(node, packageName, activityName)
                }
            }

            fun openWithAssociation(
                node: VfsNode,
                siblings: List<VfsNode>,
                handlerId: String,
                packageName: String?,
                activityName: String?,
            ): Boolean {
                val isMediaStream = handlerId == HANDLER_INTERNAL_VIDEO || handlerId == HANDLER_INTERNAL_AUDIO
                if (node.path.startsWith("gdrive://") && !isMediaStream) {
                    val offlineFile = resolveOfflineDriveFile(node)
                    if (offlineFile != null) {
                        val localNode = node.copy(path = offlineFile.absolutePath)
                        val localSiblings = siblings.mapNotNull { sibling ->
                            if (sibling.path == node.path) {
                                localNode
                            } else if (sibling.path.startsWith("gdrive://")) {
                                resolveOfflineDriveFile(sibling)?.let { sibling.copy(path = it.absolutePath) }
                            } else {
                                sibling
                            }
                        }.ifEmpty { listOf(localNode) }
                        return openLocalWithAssociation(
                            node = localNode,
                            siblings = localSiblings,
                            handlerId = handlerId,
                            packageName = packageName,
                            activityName = activityName,
                        )
                    }
                    scope.launch {
                        downloadingDriveFile = node.name
                        val cacheDir = File(cacheDir, "drive_preview").apply { mkdirs() }
                        val destFile = File(cacheDir, node.name)
                        val result = driveProvider.downloadToFile(node.path, destFile)
                        downloadingDriveFile = null
                        result.onSuccess { downloaded ->
                            val localNode = node.copy(path = downloaded.absolutePath)
                            openLocalWithAssociation(
                                node = localNode,
                                siblings = listOf(localNode),
                                handlerId = handlerId,
                                packageName = packageName,
                                activityName = activityName,
                            )
                        }
                    }
                    return true
                }
                return openLocalWithAssociation(
                    node = node,
                    siblings = siblings,
                    handlerId = handlerId,
                    packageName = packageName,
                    activityName = activityName,
                )
            }

            fun handleOpenFile(node: VfsNode, siblings: List<VfsNode>) {
                val ext = resolveFileExtension(node)
                val saved = DefaultAppsStore.getForExtension(ext)
                if (saved != null) {
                    val launched = openWithAssociation(
                        node = node,
                        siblings = siblings,
                        handlerId = saved.handlerId,
                        packageName = saved.packageName,
                        activityName = saved.activityName,
                    )
                    if (launched) return
                    DefaultAppsStore.removeForExtension(ext)
                }
                pendingOpen = PendingOpenRequest(node, siblings)
            }

            MonolithTheme(
                darkTheme = darkTheme,
                themeMode = appSettings.themeMode,
            ) {
                when (activeDestination) {
                    MainDestination.SETTINGS -> {
                        SettingsScreen(
                            onBack = {
                                activeDestination = if (settingsReturnToAi) {
                                    settingsReturnToAi = false
                                    MainDestination.AI_CHAT
                                } else {
                                    MainDestination.EXPLORER
                                }
                            },
                        )
                    }
                    MainDestination.AI_CHAT -> {
                        AiChatScreen(
                            currentDirectory = aiCurrentDirectory,
                            onBack = { activeDestination = MainDestination.EXPLORER },
                            onOpenSettings = {
                                settingsReturnToAi = true
                                activeDestination = MainDestination.SETTINGS
                            },
                        )
                    }
                    MainDestination.EXPLORER -> {
                        ExplorerScreen(
                            hasStoragePermission = hasStoragePermission,
                            onRequestStoragePermission = ::requestAllFilesAccess,
                            onOpenMedia = { node, playlist -> handleOpenFile(node, playlist) },
                            onOpenImage = { node, images -> handleOpenFile(node, images) },
                            onOpenText = { node -> handleOpenFile(node, emptyList()) },
                            onOpenOther = { node -> handleOpenFile(node, emptyList()) },
                            onOpenWith = { node, siblings ->
                                pendingOpen = PendingOpenRequest(node, siblings)
                            },
                            onOpenMiniPlayer = {
                                PlayerActivity.reopen(this@MainActivity)
                            },
                            onOpenSettings = {
                                settingsReturnToAi = false
                                activeDestination = MainDestination.SETTINGS
                            },
                            onOpenAiChat = { currentDir ->
                                aiCurrentDirectory = currentDir
                                activeDestination = MainDestination.AI_CHAT
                            },
                            navState = explorerNavState,
                        )
                    }
                }

                pendingOpen?.let { request ->
                    OpenWithDialog(
                        node = request.node,
                        onDismiss = { pendingOpen = null },
                        onSelect = { option, always ->
                            pendingOpen = null
                            if (always) {
                                val ext = resolveFileExtension(request.node)
                                if (ext.isNotEmpty()) {
                                    DefaultAppsStore.setForExtension(
                                        DefaultAppAssociation(
                                            extension = ext,
                                            handlerId = option.handlerId,
                                            handlerLabel = option.label,
                                            packageName = option.packageName,
                                            activityName = option.activityName,
                                        ),
                                    )
                                }
                            }
                            openWithAssociation(
                                node = request.node,
                                siblings = request.siblings,
                                handlerId = option.handlerId,
                                packageName = option.packageName,
                                activityName = option.activityName,
                            )
                        },
                    )
                }

                downloadingDriveFile?.let { fileName ->
                    MonolithDialog(
                        onDismissRequest = {},
                        title = "Google Drive",
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            CircularProgressIndicator()
                            Text("Lade „$fileName“…")
                        }
                    }
                }

                if (showTransferOverlay) {
                    transferProgress?.let { progress ->
                        TransferProgressDialog(
                            progress = progress,
                            onDismiss = { showTransferOverlay = false },
                            onCancel = {
                                FileTransferService.cancel(this@MainActivity)
                                showTransferOverlay = false
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTransferIntent(intent)
    }

    private fun handleTransferIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(FileTransferService.EXTRA_OPEN_TRANSFER_PANEL, false) == true) {
            intent.removeExtra(FileTransferService.EXTRA_OPEN_TRANSFER_PANEL)
            openTransferFromNotification = true
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermission()
    }

    private fun refreshPermission() {
        hasStoragePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            startActivity(intent)
        }
    }

    private fun openExternal(
        node: VfsNode,
        packageName: String? = null,
        activityName: String? = null,
    ): Boolean {
        return runCatching {
            val file = File(node.path)
            val contentUri = FileProvider.getUriForFile(
                this,
                "${this.packageName}.fileprovider",
                file,
            )
            val mimeType = resolveMimeType(node)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (!packageName.isNullOrBlank() && !activityName.isNullOrBlank()) {
                    component = ComponentName(packageName, activityName)
                }
            }
            if (intent.component != null) {
                startActivity(intent)
            } else {
                startActivity(Intent.createChooser(intent, node.name))
            }
            true
        }.getOrDefault(false)
    }
}
