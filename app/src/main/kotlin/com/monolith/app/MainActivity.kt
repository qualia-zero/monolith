package com.monolith.app

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

class MainActivity : ComponentActivity() {
    private var hasStoragePermission by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppSettingsStore.init(applicationContext)
        refreshPermission()
        setContent {
            val appSettings by AppSettingsStore.settings.collectAsState()
            var showSettings by remember { mutableStateOf(false) }

            val darkTheme = when (appSettings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            MonolithTheme(darkTheme = darkTheme) {
                if (showSettings) {
                    SettingsScreen(onBack = { showSettings = false })
                } else {
                    ExplorerScreen(
                        hasStoragePermission = hasStoragePermission,
                        onRequestStoragePermission = ::requestAllFilesAccess,
                        onOpenMedia = { node, playlist ->
                            val isVideo = node.category == FileCategory.VIDEO
                            val useInternal = if (isVideo) {
                                appSettings.useInternalVideoPlayer
                            } else {
                                appSettings.useInternalAudioPlayer
                            }
                            if (useInternal) {
                                val uri = Uri.fromFile(File(node.path)).toString()
                                val pairs = playlist.map { Uri.fromFile(File(it.path)).toString() to it.name }
                                PlayerActivity.launch(
                                    context = this@MainActivity,
                                    uri = uri,
                                    title = node.name,
                                    isVideo = isVideo,
                                    playlist = pairs,
                                )
                            } else {
                                openExternal(node)
                            }
                        },
                        onOpenImage = { node, images ->
                            if (appSettings.useInternalImageViewer) {
                                ImageViewerActivity.launch(
                                    context = this@MainActivity,
                                    path = node.path,
                                    imagePaths = images.map { it.path },
                                )
                            } else {
                                openExternal(node)
                            }
                        },
                        onOpenText = { node ->
                            if (appSettings.useInternalTextEditor) {
                                TextEditorActivity.launch(
                                    context = this@MainActivity,
                                    path = node.path,
                                )
                            } else {
                                openExternal(node)
                            }
                        },
                        onOpenOther = { node -> openExternal(node) },
                        onOpenMiniPlayer = {
                            PlayerActivity.reopen(this@MainActivity)
                        },
                        onOpenSettings = { showSettings = true },
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

    private fun openExternal(node: VfsNode) {
        runCatching {
            val file = File(node.path)
            val contentUri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, node.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, node.name))
        }
    }
}
