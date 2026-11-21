package com.monolith.feature.player

import android.Manifest
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.monolith.core.common.AppSettingsStore
import com.monolith.core.media.MediaPlaybackService
import com.monolith.core.media.MpvEngine
import com.monolith.core.media.PlaybackHistoryStore
import com.monolith.core.media.PlayerSettingsStore
import com.monolith.core.model.ResumeMode
import com.monolith.core.ui.theme.MonolithTheme
import kotlinx.coroutines.flow.MutableStateFlow

class PlayerActivity : ComponentActivity() {

    private lateinit var powerManager: PowerManager

    private val isInPipFlow = MutableStateFlow(false)
    private var pendingResumePositionMs by mutableStateOf<Long?>(null)
    private var videoControlsVisible by mutableStateOf(true)
    private var notificationPermissionRequested = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra(EXTRA_IS_VIDEO, false) == true || MpvEngine.uiState.value.isVideo) {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            )
        } else {
            enableEdgeToEdge()
        }

        AppSettingsStore.init(applicationContext)
        PlayerSettingsStore.init(applicationContext)
        PlaybackHistoryStore.init(applicationContext)
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        requestNotificationPermissionIfNeeded()

        if (savedInstanceState == null) {
            handleLaunchIntent(intent)
        }

        setContent {
            val state by MpvEngine.uiState.collectAsState()
            val settings by PlayerSettingsStore.settings.collectAsState()
            val appSettings by AppSettingsStore.settings.collectAsState()
            val isInPip by isInPipFlow.collectAsState()
            val isSystemDark = isSystemInDarkTheme()
            val effectiveInPip = isInPip || state.isInPip

            LaunchedEffect(state.isVideo) {
                if (state.isVideo) {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                        navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                    )
                }
            }

            LaunchedEffect(state.isVideo, effectiveInPip, isSystemDark, videoControlsVisible) {
                updateSystemBars(
                    isVideo = state.isVideo,
                    isInPip = effectiveInPip,
                    isSystemDark = isSystemDark,
                    controlsVisible = videoControlsVisible,
                )
            }

            MonolithTheme(
                darkTheme = if (state.isVideo) true else isSystemDark,
                themeMode = appSettings.themeMode,
            ) {
                PlayerScreen(
                    state = state,
                    settings = settings,
                    isInPip = effectiveInPip,
                    controlsVisible = videoControlsVisible,
                    onControlsVisibleChange = { videoControlsVisible = it },
                    pendingResumePositionMs = pendingResumePositionMs,
                    onConfirmResume = { posMs ->
                        pendingResumePositionMs = null
                        MpvEngine.seekTo(posMs)
                    },
                    onDismissResume = {
                        pendingResumePositionMs = null
                        if (state.mediaUri.isNotBlank()) {
                            PlaybackHistoryStore.clearPosition(state.mediaUri)
                        }
                    },
                    onBack = { finish() },
                    onEnterPip = {
                        if (state.isVideo) {
                            enterPictureInPictureMode(PictureInPictureParams.Builder().build())
                        }
                    },
                    onUpdateSettings = { transform ->
                        PlayerSettingsStore.update(transform)
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        if (!isInPictureInPictureMode) {
            MpvEngine.onBackgroundChanged(false)
        }
        isInPipFlow.value = isInPictureInPictureMode
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val state = MpvEngine.uiState.value
        val settings = PlayerSettingsStore.settings.value
        if (!isFinishing && settings.usePipMode && state.isVideo && state.isPlaying && powerManager.isInteractive) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().build())
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipFlow.value = isInPictureInPictureMode
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && MpvEngine.uiState.value.isVideo && !isInPipFlow.value) {
            updateSystemBars(
                isVideo = true,
                isInPip = false,
                isSystemDark = true,
                controlsVisible = videoControlsVisible,
            )
        }
    }

    private fun updateSystemBars(
        isVideo: Boolean,
        isInPip: Boolean,
        isSystemDark: Boolean,
        controlsVisible: Boolean,
    ) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (isVideo && !isInPip) {
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
            if (controlsVisible) {
                controller.show(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }
        } else if (!isVideo) {
            controller.isAppearanceLightStatusBars = !isSystemDark
            controller.isAppearanceLightNavigationBars = !isSystemDark
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onStop() {
        super.onStop()
        val state = MpvEngine.uiState.value
        val settings = PlayerSettingsStore.settings.value
        if (!isFinishing && !isChangingConfigurations) {
            if (!settings.useBackgroundPlay) {
                if (state.isPlaying) {
                    MpvEngine.pause()
                }
            } else if (state.isPlaying) {
                MediaPlaybackService.startOrUpdate(this)
                saveCurrentHistory()
            }
        }
        if (!isInPictureInPictureMode) {
            MpvEngine.onBackgroundChanged(true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            saveCurrentHistory()
            val state = MpvEngine.uiState.value
            val settings = PlayerSettingsStore.settings.value
            if (!settings.useBackgroundPlay || !state.isPlaying) {
                MpvEngine.stopAndReset()
                MediaPlaybackService.stopService(this)
            }
        }
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent == null || intent.getBooleanExtra(EXTRA_REOPEN, false)) {
            return
        }
        val uri = intent.getStringExtra(EXTRA_URI).orEmpty()
        if (uri.isBlank()) return

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)
        val playlistUris = intent.getStringArrayListExtra(EXTRA_PLAYLIST_URIS).orEmpty()
        val playlistTitles = intent.getStringArrayListExtra(EXTRA_PLAYLIST_TITLES).orEmpty()
        val playlist = playlistUris.mapIndexed { index, itemUri ->
            itemUri to playlistTitles.getOrElse(index) { "" }
        }

        val savedPos = PlaybackHistoryStore.getSavedPosition(uri)
        val resumeMode = PlayerSettingsStore.settings.value.resumeMode

        val initialPos = when (resumeMode) {
            ResumeMode.ALWAYS -> savedPos
            ResumeMode.NEVER -> 0L
            ResumeMode.ASK -> {
                if (savedPos > 0L) {
                    pendingResumePositionMs = savedPos
                }
                0L
            }
        }

        MpvEngine.loadMedia(
            context = applicationContext,
            uri = uri,
            title = title,
            isVideo = isVideo,
            startPosMs = initialPos,
            playlist = playlist,
        )
        MediaPlaybackService.startOrUpdate(applicationContext)
    }

    private fun saveCurrentHistory() {
        val state = MpvEngine.uiState.value
        if (state.mediaUri.isNotBlank() && state.durationMs > 0L) {
            PlaybackHistoryStore.savePosition(state.mediaUri, state.positionMs, state.durationMs)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (notificationPermissionRequested) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionRequested = true
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    companion object {
        const val EXTRA_REOPEN = "monolith_reopen"
        private const val EXTRA_URI = "monolith_uri"
        private const val EXTRA_TITLE = "monolith_title"
        private const val EXTRA_IS_VIDEO = "monolith_is_video"
        private const val EXTRA_PLAYLIST_URIS = "monolith_playlist_uris"
        private const val EXTRA_PLAYLIST_TITLES = "monolith_playlist_titles"

        fun launch(
            context: Context,
            uri: String,
            title: String,
            isVideo: Boolean,
            playlist: List<Pair<String, String>> = emptyList(),
        ) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_URI, uri)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_IS_VIDEO, isVideo)
                if (playlist.isNotEmpty()) {
                    putStringArrayListExtra(
                        EXTRA_PLAYLIST_URIS,
                        ArrayList(playlist.map { it.first }),
                    )
                    putStringArrayListExtra(
                        EXTRA_PLAYLIST_TITLES,
                        ArrayList(playlist.map { it.second }),
                    )
                }
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
        }

        fun reopen(context: Context) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_REOPEN, true)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
        }
    }
}
