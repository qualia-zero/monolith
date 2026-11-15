package com.monolith.app

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.monolith.core.common.AppSettingsStore
import com.monolith.core.common.DefaultAppsStore
import com.monolith.core.model.DefaultAppAssociation
import com.monolith.core.model.FileCategory
import com.monolith.core.model.ThemeMode
import com.monolith.core.model.VfsNode
import com.monolith.core.ui.theme.MonolithTheme
import com.monolith.feature.explorer.ExplorerScreen
import com.monolith.feature.explorer.SettingsScreen
import com.monolith.feature.player.PlayerActivity
import com.monolith.feature.viewers.ImageViewerActivity
import com.monolith.feature.viewers.TextEditorActivity
import java.io.File

private data class PendingOpenRequest(
    val node: VfsNode,
    val siblings: List<VfsNode>,
)

class MainActivity : ComponentActivity() {
    private var hasStoragePermission by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppSettingsStore.init(applicationContext)
        DefaultAppsStore.init(applicationContext)
        refreshPermission()
        setContent {
            val appSettings by AppSettingsStore.settings.collectAsState()
            var showSettings by remember { mutableStateOf(false) }
            var pendingOpen by remember { mutableStateOf<PendingOpenRequest?>(null) }

            val darkTheme = when (appSettings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            fun openWithAssociation(
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
                        val uri = Uri.fromFile(File(node.path)).toString()
                        val pairs = playlist.map { Uri.fromFile(File(it.path)).toString() to it.name }
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

            MonolithTheme(darkTheme = darkTheme) {
                if (showSettings) {
                    SettingsScreen(onBack = { showSettings = false })
                } else {
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
                        onOpenSettings = { showSettings = true },
                    )
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
            }
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
