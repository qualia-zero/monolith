package com.monolith.feature.viewers

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.monolith.core.common.AppSettingsStore
import com.monolith.core.ui.theme.MonolithTheme

/**
 * Activity for viewing one or more local images with zoom, pan, rotation, and swipe navigation.
 */
class ImageViewerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        val path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        val rawPaths = intent.getStringArrayListExtra(EXTRA_IMAGE_PATHS).orEmpty()
        val rawNames = intent.getStringArrayListExtra(EXTRA_IMAGE_NAMES).orEmpty()
        val rawSizes = intent.getLongArrayExtra(EXTRA_IMAGE_SIZES)?.toList().orEmpty()
        val imagePaths = when {
            rawPaths.isNotEmpty() -> rawPaths
            path.isNotBlank() -> listOf(path)
            else -> emptyList()
        }

        if (imagePaths.isEmpty()) {
            finish()
            return
        }

        val initialIndex = imagePaths.indexOf(path).coerceAtLeast(0)

        AppSettingsStore.init(applicationContext)

        setContent {
            val appSettings by AppSettingsStore.settings.collectAsState()
            MonolithTheme(
                darkTheme = true,
                themeMode = appSettings.themeMode,
            ) {
                ImageViewerScreen(
                    imagePaths = imagePaths,
                    imageNames = rawNames,
                    imageSizes = rawSizes,
                    initialIndex = initialIndex,
                    onNavigateBack = { finish() },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_PATH = "extra_path"
        private const val EXTRA_IMAGE_PATHS = "extra_image_paths"
        private const val EXTRA_IMAGE_NAMES = "extra_image_names"
        private const val EXTRA_IMAGE_SIZES = "extra_image_sizes"

        /**
         * Launches [ImageViewerActivity] for [path], optionally allowing swiping across [imagePaths].
         */
        fun launch(
            context: Context,
            path: String,
            imagePaths: List<String> = listOf(path),
            imageNames: List<String> = emptyList(),
            imageSizes: List<Long> = emptyList(),
        ) {
            val intent = Intent(context, ImageViewerActivity::class.java).apply {
                putExtra(EXTRA_PATH, path)
                putStringArrayListExtra(EXTRA_IMAGE_PATHS, ArrayList(imagePaths))
                if (imageNames.isNotEmpty()) {
                    putStringArrayListExtra(EXTRA_IMAGE_NAMES, ArrayList(imageNames))
                }
                if (imageSizes.isNotEmpty()) {
                    putExtra(EXTRA_IMAGE_SIZES, imageSizes.toLongArray())
                }
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
        }
    }
}
