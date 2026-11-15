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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.monolith.core.model.FileCategory
import com.monolith.core.ui.theme.MonolithTheme
import com.monolith.feature.explorer.ExplorerScreen
import com.monolith.feature.explorer.SettingsScreen
import com.monolith.feature.player.PlayerActivity
import java.io.File

class MainActivity : ComponentActivity() {
    private var hasStoragePermission by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshPermission()
        setContent {
            var showSettings by remember { mutableStateOf(false) }

            MonolithTheme {
                if (showSettings) {
                    SettingsScreen(onBack = { showSettings = false })
                } else {
                    ExplorerScreen(
                        hasStoragePermission = hasStoragePermission,
                        onRequestStoragePermission = ::requestAllFilesAccess,
                        onOpenMedia = { node, playlist ->
                            val isVideo = node.category == FileCategory.VIDEO
                            val uri = Uri.fromFile(File(node.path)).toString()
                            val pairs = playlist.map { Uri.fromFile(File(it.path)).toString() to it.name }
                            PlayerActivity.launch(
                                context = this@MainActivity,
                                uri = uri,
                                title = node.name,
                                isVideo = isVideo,
                                playlist = pairs,
                            )
                        },
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
}
