package com.monolith.app

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.runtime.mutableLongStateOf
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
import com.monolith.core.common.MimeNormalizer
import com.monolith.core.media.MpvEngine
import com.monolith.core.media.ThumbnailEngine
import com.monolith.core.model.DefaultAppAssociation
import com.monolith.core.model.FileCategory
import com.monolith.core.model.LocationType
import com.monolith.core.model.ThemeMode
import com.monolith.core.model.VfsNode
import com.monolith.core.ui.components.MonolithDialog
import com.monolith.core.ui.theme.MonolithTheme
import com.monolith.core.vfs.FileTransferService
import com.monolith.core.vfs.GoogleDriveAuthManager
import com.monolith.core.vfs.GoogleDriveVfsProvider
import com.monolith.feature.explorer.CloudCacheManager
import com.monolith.feature.explorer.ExplorerScreen
import com.monolith.feature.explorer.ExternalStorageDetector
import com.monolith.feature.explorer.SettingsScreen
import com.monolith.feature.explorer.ai.AiChatScreen
import com.monolith.feature.explorer.apps.AppManagerScreen
import com.monolith.feature.explorer.archive.ArchiveEngine
import com.monolith.feature.explorer.archive.ArchiveLaunchRequest
import com.monolith.feature.explorer.archive.ArchiveToolMode
import com.monolith.feature.explorer.archive.ArchiveToolScreen
import com.monolith.feature.explorer.archive.LocalPathPickerDialog
import com.monolith.feature.explorer.archive.LocalPickerMode
import com.monolith.feature.explorer.archive.LocalPickerRequest
import com.monolith.feature.explorer.archive.rememberArchiveManagerState
import com.monolith.feature.explorer.downloader.DownloaderScreen
import com.monolith.feature.explorer.downloader.MediaDownloadService
import com.monolith.feature.explorer.rememberExplorerNavigationState
import com.monolith.feature.explorer.storage.StorageAnalysisScreen
import com.monolith.feature.player.PlayerActivity
import com.monolith.feature.viewers.ImageViewerActivity
import com.monolith.feature.viewers.TextEditorActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

private enum class MainDestination {
    EXPLORER,
    ARCHIVE_TOOL,
    STORAGE_ANALYSIS,
    DOWNLOADER,
    APP_MANAGER,
    AI_CHAT,
    SETTINGS,
}

private data class PendingOpenRequest(
    val node: VfsNode,
    val siblings: List<VfsNode>,
)

class MainActivity : ComponentActivity() {
    private var hasStoragePermission by mutableStateOf(false)
    private var openTransferFromNotification by mutableStateOf(false)
    private var notificationTargetTaskId by mutableStateOf<Long?>(null)
    private var openDownloaderFromNotification by mutableStateOf(false)
    private var pendingExternalIntent by mutableStateOf<Intent?>(null)

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
            var settingsReturnDestination by remember { mutableStateOf(MainDestination.EXPLORER) }
            var aiCurrentDirectory by remember { mutableStateOf("") }
            val defaultRootDir = remember { Environment.getExternalStorageDirectory().absolutePath }
            val archiveManagerState = rememberArchiveManagerState(defaultRootDir = defaultRootDir)
            var archiveLaunchNonce by remember { mutableLongStateOf(0L) }
            var archiveLaunchRequest by remember { mutableStateOf(ArchiveLaunchRequest()) }
            var storageInitialRootPath by remember { mutableStateOf<String?>(null) }
            val explorerNavState = rememberExplorerNavigationState()
            var pendingOpen by remember { mutableStateOf<PendingOpenRequest?>(null) }
            var pendingShareFiles by remember { mutableStateOf<List<IncomingSharedFile>?>(null) }
            var shareSavePickerRequest by remember { mutableStateOf<LocalPickerRequest?>(null) }
            var isResolvingExternalIntent by remember { mutableStateOf(false) }
            var downloadingDriveFile by remember { mutableStateOf<String?>(null) }
            val transferProgress by FileTransferService.progress.collectAsState()
            val transferTasks by FileTransferService.tasks.collectAsState()
            val latestCreatedTaskId by FileTransferService.latestCreatedTaskId.collectAsState()
            var highestSeenTaskId by remember { mutableStateOf(0L) }
            var focusedTransferTaskId by remember { mutableStateOf<Long?>(null) }
            var showTransferOverlay by remember { mutableStateOf(false) }

            LaunchedEffect(latestCreatedTaskId) {
                if (latestCreatedTaskId > highestSeenTaskId) {
                    highestSeenTaskId = latestCreatedTaskId
                    focusedTransferTaskId = latestCreatedTaskId
                    showTransferOverlay = true
                }
            }

            LaunchedEffect(openTransferFromNotification) {
                if (openTransferFromNotification) {
                    openTransferFromNotification = false
                    notificationTargetTaskId?.let { targetId ->
                        focusedTransferTaskId = targetId
                    }
                    notificationTargetTaskId = null
                    if (transferTasks.isNotEmpty() || FileTransferService.progress.value != null) {
                        showTransferOverlay = true
                    }
                }
            }

            LaunchedEffect(openDownloaderFromNotification) {
                if (openDownloaderFromNotification) {
                    openDownloaderFromNotification = false
                    activeDestination = MainDestination.DOWNLOADER
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
                    HANDLER_INTERNAL_ARCHIVE -> {
                        archiveLaunchNonce++
                        archiveLaunchRequest = ArchiveLaunchRequest(
                            nonce = archiveLaunchNonce,
                            mode = ArchiveToolMode.EXTRACT,
                            archivePath = node.path,
                        )
                        activeDestination = MainDestination.ARCHIVE_TOOL
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

            fun openLocalFolderInExplorer(folderPath: String) {
                val dir = File(folderPath)
                val targetPath = if (dir.isDirectory) {
                    dir.absolutePath
                } else {
                    dir.parent ?: Environment.getExternalStorageDirectory().absolutePath
                }
                val internalRoot = Environment.getExternalStorageDirectory().absolutePath
                val externalVolumes = ExternalStorageDetector.listMountedVolumes(this@MainActivity)
                val matchingRoot = externalVolumes
                    .firstOrNull { targetPath == it.rootPath || targetPath.startsWith(it.rootPath.trimEnd('/') + "/") }
                    ?.rootPath
                    ?: internalRoot
                explorerNavState.activeLocation = LocationType.LOCAL
                explorerNavState.activeLocalRootPath = matchingRoot
                explorerNavState.localPath = targetPath
                activeDestination = MainDestination.EXPLORER
            }

            fun openIncomingFiles(files: List<IncomingSharedFile>) {
                val first = files.firstOrNull() ?: return
                if (ArchiveEngine.isSupportedArchiveFile(first.file)) {
                    archiveLaunchNonce++
                    archiveLaunchRequest = ArchiveLaunchRequest(
                        nonce = archiveLaunchNonce,
                        mode = ArchiveToolMode.EXTRACT,
                        archivePath = first.file.absolutePath,
                    )
                    activeDestination = MainDestination.ARCHIVE_TOOL
                    return
                }
                val nodes = files.map { item ->
                    val mime = item.mimeType.ifBlank { MimeNormalizer.normalizeMime(item.displayName) }
                    val category = MimeNormalizer.categorize(
                        fileName = item.displayName,
                        isDirectory = false,
                        mimeType = mime,
                    )
                    VfsNode(
                        id = item.file.absolutePath,
                        name = item.displayName,
                        path = item.file.absolutePath,
                        parentPath = item.file.parent,
                        locationType = LocationType.LOCAL,
                        isDirectory = false,
                        sizeBytes = item.sizeBytes,
                        lastModifiedMs = item.file.lastModified(),
                        mimeType = mime,
                        category = category,
                    )
                }
                val firstNode = nodes.first()
                when (firstNode.category) {
                    FileCategory.IMAGE -> {
                        val images = nodes.filter { it.category == FileCategory.IMAGE }.ifEmpty { listOf(firstNode) }
                        openLocalWithAssociation(firstNode, images, HANDLER_INTERNAL_IMAGE, null, null)
                    }
                    FileCategory.VIDEO -> {
                        val videos = nodes.filter { it.category == FileCategory.VIDEO }.ifEmpty { listOf(firstNode) }
                        openLocalWithAssociation(firstNode, videos, HANDLER_INTERNAL_VIDEO, null, null)
                    }
                    FileCategory.AUDIO -> {
                        val audios = nodes.filter { it.category == FileCategory.AUDIO }.ifEmpty { listOf(firstNode) }
                        openLocalWithAssociation(firstNode, audios, HANDLER_INTERNAL_AUDIO, null, null)
                    }
                    FileCategory.TEXT -> {
                        openLocalWithAssociation(firstNode, listOf(firstNode), HANDLER_INTERNAL_TEXT, null, null)
                    }
                    else -> handleOpenFile(firstNode, listOf(firstNode))
                }
            }

            LaunchedEffect(pendingExternalIntent) {
                val incoming = pendingExternalIntent ?: return@LaunchedEffect
                pendingExternalIntent = null
                isResolvingExternalIntent = true
                val payload = resolveIncomingIntent(applicationContext, incoming)
                isResolvingExternalIntent = false
                if (payload == null || payload.files.isEmpty()) {
                    Toast.makeText(
                        this@MainActivity,
                        "Datei(en) konnten nicht geladen werden.",
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@LaunchedEffect
                }
                if (payload.action == Intent.ACTION_VIEW) {
                    openIncomingFiles(payload.files)
                } else {
                    pendingShareFiles = payload.files
                }
            }

            MonolithTheme(
                darkTheme = darkTheme,
                themeMode = appSettings.themeMode,
            ) {
                when (activeDestination) {
                    MainDestination.SETTINGS -> {
                        SettingsScreen(
                            onBack = {
                                activeDestination = settingsReturnDestination
                            },
                        )
                    }
                    MainDestination.ARCHIVE_TOOL -> {
                        ArchiveToolScreen(
                            state = archiveManagerState,
                            launchRequest = archiveLaunchRequest,
                            onBack = { activeDestination = MainDestination.EXPLORER },
                            onNavigateToFiles = { activeDestination = MainDestination.EXPLORER },
                            onNavigateToStorage = {
                                storageInitialRootPath = explorerNavState.activeLocalRootPath
                                activeDestination = MainDestination.STORAGE_ANALYSIS
                            },
                            onNavigateToDownloader = {
                                activeDestination = MainDestination.DOWNLOADER
                            },
                            onSelectApps = {
                                activeDestination = MainDestination.APP_MANAGER
                            },
                            onNavigateToAi = {
                                aiCurrentDirectory = explorerNavState.localPath
                                activeDestination = MainDestination.AI_CHAT
                            },
                            onOpenSettings = {
                                settingsReturnDestination = MainDestination.ARCHIVE_TOOL
                                activeDestination = MainDestination.SETTINGS
                            },
                            onOpenExtractedFolder = ::openLocalFolderInExplorer,
                            onOpenPreviewFile = { previewFile ->
                                val mime = MimeNormalizer.normalizeMime(previewFile.name)
                                val category = MimeNormalizer.categorize(
                                    fileName = previewFile.name,
                                    isDirectory = false,
                                    mimeType = mime,
                                )
                                val previewNode = VfsNode(
                                    id = previewFile.absolutePath,
                                    name = previewFile.name,
                                    path = previewFile.absolutePath,
                                    parentPath = previewFile.parent,
                                    locationType = LocationType.LOCAL,
                                    isDirectory = false,
                                    sizeBytes = previewFile.length(),
                                    lastModifiedMs = previewFile.lastModified(),
                                    mimeType = mime,
                                    category = category,
                                )
                                handleOpenFile(previewNode, listOf(previewNode))
                            },
                        )
                    }
                    MainDestination.STORAGE_ANALYSIS -> {
                        StorageAnalysisScreen(
                            initialRootPath = storageInitialRootPath,
                            onBack = { activeDestination = MainDestination.EXPLORER },
                            onNavigateToFiles = { activeDestination = MainDestination.EXPLORER },
                            onOpenFolderInExplorer = ::openLocalFolderInExplorer,
                            onNavigateToArchives = { packPaths ->
                                val singleArchive = packPaths.singleOrNull()?.takeIf { path ->
                                    val f = File(path)
                                    f.isFile && ArchiveEngine.isSupportedArchiveFile(f)
                                }
                                archiveLaunchNonce++
                                archiveLaunchRequest = if (singleArchive != null) {
                                    ArchiveLaunchRequest(
                                        nonce = archiveLaunchNonce,
                                        mode = ArchiveToolMode.EXTRACT,
                                        archivePath = singleArchive,
                                    )
                                } else {
                                    ArchiveLaunchRequest(
                                        nonce = archiveLaunchNonce,
                                        mode = if (packPaths.isNotEmpty()) ArchiveToolMode.PACK else null,
                                        packSourcePaths = packPaths,
                                    )
                                }
                                activeDestination = MainDestination.ARCHIVE_TOOL
                            },
                            onNavigateToDownloader = {
                                activeDestination = MainDestination.DOWNLOADER
                            },
                            onSelectApps = {
                                activeDestination = MainDestination.APP_MANAGER
                            },
                            onNavigateToAi = {
                                aiCurrentDirectory = explorerNavState.localPath
                                activeDestination = MainDestination.AI_CHAT
                            },
                            onOpenSettings = {
                                settingsReturnDestination = MainDestination.STORAGE_ANALYSIS
                                activeDestination = MainDestination.SETTINGS
                            },
                        )
                    }
                    MainDestination.DOWNLOADER -> {
                        DownloaderScreen(
                            hasStoragePermission = hasStoragePermission,
                            onRequestStoragePermission = ::requestAllFilesAccess,
                            onBack = { activeDestination = MainDestination.EXPLORER },
                            onNavigateToFiles = { activeDestination = MainDestination.EXPLORER },
                            onNavigateToArchives = {
                                archiveLaunchNonce++
                                archiveLaunchRequest = ArchiveLaunchRequest(nonce = archiveLaunchNonce)
                                activeDestination = MainDestination.ARCHIVE_TOOL
                            },
                            onNavigateToStorage = {
                                storageInitialRootPath = explorerNavState.activeLocalRootPath
                                activeDestination = MainDestination.STORAGE_ANALYSIS
                            },
                            onSelectApps = {
                                activeDestination = MainDestination.APP_MANAGER
                            },
                            onNavigateToAi = {
                                aiCurrentDirectory = explorerNavState.localPath
                                activeDestination = MainDestination.AI_CHAT
                            },
                            onOpenSettings = {
                                settingsReturnDestination = MainDestination.DOWNLOADER
                                activeDestination = MainDestination.SETTINGS
                            },
                            onOpenFolderInExplorer = ::openLocalFolderInExplorer,
                            onOpenDownloadedFile = { filePath ->
                                val file = File(filePath)
                                if (file.exists() && file.isFile) {
                                    val mime = MimeNormalizer.normalizeMime(file.name)
                                    val category = MimeNormalizer.categorize(
                                        fileName = file.name,
                                        isDirectory = false,
                                        mimeType = mime,
                                    )
                                    val node = VfsNode(
                                        id = file.absolutePath,
                                        name = file.name,
                                        path = file.absolutePath,
                                        parentPath = file.parent,
                                        locationType = LocationType.LOCAL,
                                        isDirectory = false,
                                        sizeBytes = file.length(),
                                        lastModifiedMs = file.lastModified(),
                                        mimeType = mime,
                                        category = category,
                                    )
                                    handleOpenFile(node, listOf(node))
                                }
                            },
                        )
                    }
                    MainDestination.APP_MANAGER -> {
                        AppManagerScreen(
                            onNavigateToFiles = { activeDestination = MainDestination.EXPLORER },
                            onNavigateToArchives = {
                                archiveLaunchNonce++
                                archiveLaunchRequest = ArchiveLaunchRequest(nonce = archiveLaunchNonce)
                                activeDestination = MainDestination.ARCHIVE_TOOL
                            },
                            onNavigateToStorage = {
                                storageInitialRootPath = null
                                activeDestination = MainDestination.STORAGE_ANALYSIS
                            },
                            onNavigateToDownloader = {
                                activeDestination = MainDestination.DOWNLOADER
                            },
                            onNavigateToAi = {
                                aiCurrentDirectory = explorerNavState.localPath
                                activeDestination = MainDestination.AI_CHAT
                            },
                            onOpenSettings = {
                                settingsReturnDestination = MainDestination.APP_MANAGER
                                activeDestination = MainDestination.SETTINGS
                            },
                            onOpenFolderInExplorer = ::openLocalFolderInExplorer,
                        )
                    }
                    MainDestination.AI_CHAT -> {
                        AiChatScreen(
                            currentDirectory = aiCurrentDirectory,
                            onBack = { activeDestination = MainDestination.EXPLORER },
                            onOpenArchives = {
                                archiveLaunchNonce++
                                archiveLaunchRequest = ArchiveLaunchRequest(nonce = archiveLaunchNonce)
                                activeDestination = MainDestination.ARCHIVE_TOOL
                            },
                            onOpenStorage = {
                                storageInitialRootPath = explorerNavState.activeLocalRootPath
                                activeDestination = MainDestination.STORAGE_ANALYSIS
                            },
                            onOpenDownloader = {
                                activeDestination = MainDestination.DOWNLOADER
                            },
                            onSelectApps = {
                                activeDestination = MainDestination.APP_MANAGER
                            },
                            onOpenSettings = {
                                settingsReturnDestination = MainDestination.AI_CHAT
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
                                settingsReturnDestination = MainDestination.EXPLORER
                                activeDestination = MainDestination.SETTINGS
                            },
                            onOpenDownloader = {
                                activeDestination = MainDestination.DOWNLOADER
                            },
                            onOpenAiChat = { currentDir ->
                                aiCurrentDirectory = currentDir
                                activeDestination = MainDestination.AI_CHAT
                            },
                            onOpenArchiveTool = { initialArchive, initialPack, initialMode ->
                                archiveLaunchNonce++
                                archiveLaunchRequest = ArchiveLaunchRequest(
                                    nonce = archiveLaunchNonce,
                                    mode = initialMode,
                                    archivePath = initialArchive,
                                    packSourcePaths = initialPack,
                                )
                                activeDestination = MainDestination.ARCHIVE_TOOL
                            },
                            onOpenStorageAnalysis = { root ->
                                storageInitialRootPath = root
                                activeDestination = MainDestination.STORAGE_ANALYSIS
                            },
                            onSelectApps = {
                                activeDestination = MainDestination.APP_MANAGER
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

                if (isResolvingExternalIntent) {
                    MonolithDialog(
                        onDismissRequest = {},
                        title = "Monolith",
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            CircularProgressIndicator()
                            Text("Datei(en) werden vorbereitet…")
                        }
                    }
                }

                pendingShareFiles?.let { sharedFiles ->
                    IncomingShareDialog(
                        files = sharedFiles,
                        onSaveToFolder = {
                            pendingShareFiles = null
                            shareSavePickerRequest = LocalPickerRequest(
                                mode = LocalPickerMode.SINGLE_DIRECTORY,
                                title = "Zielordner zum Speichern wählen",
                                initialDirectory = explorerNavState.localPath.ifBlank { defaultRootDir },
                                onConfirm = { dirs ->
                                    val targetPath = dirs.firstOrNull() ?: return@LocalPickerRequest
                                    scope.launch {
                                        if (targetPath.startsWith("gdrive://")) {
                                            var uploaded = 0
                                            var lastErr: Throwable? = null
                                            for (item in sharedFiles) {
                                                val res = driveProvider.uploadFile(item.file, targetPath)
                                                if (res.isSuccess) uploaded++ else lastErr = res.exceptionOrNull()
                                            }
                                            if (uploaded > 0) {
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "$uploaded Datei(en) in Google Drive gespeichert.",
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                                explorerNavState.activeLocation = LocationType.GOOGLE_DRIVE
                                                explorerNavState.driveStack = listOf(targetPath to "Google Drive")
                                                activeDestination = MainDestination.EXPLORER
                                            } else {
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    lastErr?.message ?: "Upload fehlgeschlagen.",
                                                    Toast.LENGTH_LONG,
                                                ).show()
                                            }
                                        } else {
                                            val res = copyIncomingFilesToDirectory(sharedFiles, File(targetPath))
                                            res.onSuccess { count ->
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "$count Datei(en) gespeichert.",
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                                openLocalFolderInExplorer(targetPath)
                                            }.onFailure { err ->
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    err.message ?: "Speichern fehlgeschlagen.",
                                                    Toast.LENGTH_LONG,
                                                ).show()
                                            }
                                        }
                                    }
                                },
                            )
                        },
                        onOpenFiles = {
                            pendingShareFiles = null
                            openIncomingFiles(sharedFiles)
                        },
                        onDismiss = { pendingShareFiles = null },
                    )
                }

                shareSavePickerRequest?.let { req ->
                    LocalPathPickerDialog(
                        request = req,
                        defaultRootDir = defaultRootDir,
                        onDismiss = { shareSavePickerRequest = null },
                    )
                }

                if (showTransferOverlay) {
                    val activeProgress = focusedTransferTaskId?.let { transferTasks[it] }
                        ?: transferProgress
                        ?: transferTasks.values.lastOrNull()
                    activeProgress?.let { progress ->
                        val activeTasks = transferTasks.values
                            .filter { !it.isFinished || it.taskId == progress.taskId }
                            .sortedBy { it.taskId }
                        TransferProgressDialog(
                            progress = progress,
                            activeTasks = activeTasks,
                            onSelectTask = { focusedTransferTaskId = it },
                            onDismiss = { showTransferOverlay = false },
                            onCancel = {
                                FileTransferService.cancel(this@MainActivity, progress.taskId)
                                val nextUnfinished = transferTasks.values
                                    .filter { !it.isFinished && it.taskId != progress.taskId }
                                    .minByOrNull { it.taskId }
                                if (nextUnfinished != null) {
                                    focusedTransferTaskId = nextUnfinished.taskId
                                } else {
                                    showTransferOverlay = false
                                }
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
            val targetId = intent.getLongExtra(FileTransferService.EXTRA_TRANSFER_TASK_ID, -1L)
                .takeIf { it > 0L }
            intent.removeExtra(FileTransferService.EXTRA_OPEN_TRANSFER_PANEL)
            intent.removeExtra(FileTransferService.EXTRA_TRANSFER_TASK_ID)
            notificationTargetTaskId = targetId
            openTransferFromNotification = true
        }
        if (intent?.getBooleanExtra(MediaDownloadService.EXTRA_OPEN_DOWNLOADER, false) == true) {
            intent.removeExtra(MediaDownloadService.EXTRA_OPEN_DOWNLOADER)
            openDownloaderFromNotification = true
        }
        val action = intent?.action
        if (action == Intent.ACTION_VIEW || action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE) {
            pendingExternalIntent = intent
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
