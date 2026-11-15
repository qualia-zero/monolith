package com.monolith.feature.viewers

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.monolith.core.ui.theme.MonolithTheme

/**
 * Activity hosting the built-in Material 3 text editor for local files.
 */
class TextEditorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        if (path.isBlank()) {
            finish()
            return
        }

        setContent {
            MonolithTheme {
                TextEditorScreen(
                    path = path,
                    onNavigateBack = { finish() },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_PATH = "extra_path"

        /**
         * Launches [TextEditorActivity] to view or edit the file at [path].
         */
        fun launch(context: Context, path: String) {
            val intent = Intent(context, TextEditorActivity::class.java).apply {
                putExtra(EXTRA_PATH, path)
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
        }
    }
}
