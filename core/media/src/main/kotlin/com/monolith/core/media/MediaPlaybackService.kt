package com.monolith.core.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.monolith.core.model.PlaybackUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground media playback service hosting [MediaSessionCompat], [MediaStyle] notifications,
 * audio focus, noisy-headset handling, and wake/Wi-Fi locks.
 */
class MediaPlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "monolith_media_playback"
        const val NOTIF_ID = 0x4D4D

        private const val ACTION_PLAY = "com.monolith.core.media.ACTION_PLAY"
        private const val ACTION_PAUSE = "com.monolith.core.media.ACTION_PAUSE"
        private const val ACTION_PLAY_PAUSE = "com.monolith.core.media.ACTION_PLAY_PAUSE"
        private const val ACTION_PREV = "com.monolith.core.media.ACTION_PREV"
        private const val ACTION_NEXT = "com.monolith.core.media.ACTION_NEXT"
        private const val ACTION_STOP = "com.monolith.core.media.ACTION_STOP"

        fun startOrUpdate(context: Context) {
            val intent = Intent(context, MediaPlaybackService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopService(context: Context) {
            context.stopService(Intent(context, MediaPlaybackService::class.java))
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var notificationManager: NotificationManager
    private lateinit var audioManager: AudioManager

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private var pausedByTransientFocusLoss = false
    private var lastLoadedArtUri: String = ""
    private var albumArt480p: Bitmap? = null
    private var artJob: Job? = null
    private var stateJob: Job? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                MpvEngine.pause()
            }
        }
    }

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                pausedByTransientFocusLoss = false
                MpvEngine.pause()
                abandonAudioFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (MpvEngine.uiState.value.isPlaying) {
                    pausedByTransientFocusLoss = true
                    MpvEngine.pause()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (pausedByTransientFocusLoss) {
                    pausedByTransientFocusLoss = false
                    MpvEngine.play()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        audioManager = getSystemService(AudioManager::class.java)
        createNotificationChannel()

        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "monolith:MediaWakeLock").apply {
            setReferenceCounted(false)
        }

        val wm = applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "monolith:MediaWifiLock").apply {
            setReferenceCounted(false)
        }

        mediaSession = MediaSessionCompat(this, "MonolithMediaPlayer").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    requestAudioFocus()
                    MpvEngine.play()
                }

                override fun onPause() {
                    pausedByTransientFocusLoss = false
                    MpvEngine.pause()
                }

                override fun onSeekTo(pos: Long) {
                    MpvEngine.seekTo(pos)
                }

                override fun onSkipToPrevious() {
                    MpvEngine.skipPrevious()
                }

                override fun onSkipToNext() {
                    MpvEngine.skipNext()
                }

                override fun onStop() {
                    pausedByTransientFocusLoss = false
                    MpvEngine.stopAndReset()
                    stopSelf()
                }
            })
            isActive = true
        }

        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        stateJob = serviceScope.launch {
            MpvEngine.uiState.collectLatest { state ->
                onPlaybackStateChanged(state)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = MpvEngine.uiState.value
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification(state),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            },
        )

        when (intent?.action) {
            ACTION_PLAY -> {
                requestAudioFocus()
                MpvEngine.play()
            }
            ACTION_PAUSE -> {
                pausedByTransientFocusLoss = false
                MpvEngine.pause()
            }
            ACTION_PLAY_PAUSE -> {
                if (!state.isPlaying) requestAudioFocus()
                MpvEngine.togglePlayPause()
            }
            ACTION_PREV -> MpvEngine.skipPrevious()
            ACTION_NEXT -> MpvEngine.skipNext()
            ACTION_STOP -> {
                pausedByTransientFocusLoss = false
                MpvEngine.stopAndReset()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stateJob?.cancel()
        artJob?.cancel()
        serviceScope.cancel()
        runCatching { unregisterReceiver(noisyReceiver) }
        abandonAudioFocus()
        releaseLocks()
        mediaSession.isActive = false
        mediaSession.release()
        albumArt480p = null
        super.onDestroy()
    }

    private fun onPlaybackStateChanged(state: PlaybackUiState) {
        if (state.isPlaying) {
            requestAudioFocus()
            acquireLocks(state.mediaUri)
        } else {
            releaseLocks()
        }

        if (state.mediaUri.isNotBlank() && state.mediaUri != lastLoadedArtUri) {
            lastLoadedArtUri = state.mediaUri
            loadArtworkAndTags(state.mediaUri, state.isVideo)
        } else if (state.mediaUri.isBlank()) {
            lastLoadedArtUri = ""
            albumArt480p = null
        }

        updateMediaSession(state)
        notificationManager.notify(NOTIF_ID, buildNotification(state))
    }

    private fun loadArtworkAndTags(uri: String, isVideo: Boolean) {
        artJob?.cancel()
        artJob = serviceScope.launch(Dispatchers.IO) {
            val art = ThumbnailEngine.loadMediaThumbnail(this@MediaPlaybackService, uri, isVideo)
            val mkvInfo = MkvTagParser.parse(this@MediaPlaybackService, uri)

            launch(Dispatchers.Main.immediate) {
                if (MpvEngine.uiState.value.mediaUri != uri) return@launch
                albumArt480p = art
                val current = MpvEngine.uiState.value
                val enriched = if (mkvInfo != null) {
                    current.copy(
                        title = mkvInfo.title.ifBlank { current.title },
                        artist = current.artist.ifBlank { mkvInfo.artist },
                        album = current.album.ifBlank { mkvInfo.album },
                    )
                } else {
                    current
                }
                updateMediaSession(enriched)
                notificationManager.notify(NOTIF_ID, buildNotification(enriched))
            }
        }
    }

    private fun updateMediaSession(state: PlaybackUiState) {
        val pbState = when {
            state.isBuffering -> PlaybackStateCompat.STATE_BUFFERING
            state.isPlaying -> PlaybackStateCompat.STATE_PLAYING
            state.isEnded -> PlaybackStateCompat.STATE_STOPPED
            state.mediaUri.isBlank() -> PlaybackStateCompat.STATE_NONE
            else -> PlaybackStateCompat.STATE_PAUSED
        }

        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_STOP

        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(pbState, state.positionMs, state.speed)
                .build(),
        )

        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, state.title.ifBlank { "Wiedergabe" })
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, state.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, state.album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, state.durationMs)

        albumArt480p?.let {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, it)
        }

        mediaSession.setMetadata(metadataBuilder.build())
    }

    private fun buildNotification(state: PlaybackUiState): Notification {
        val smallIcon = if (state.isVideo) {
            R.drawable.ic_notification_video
        } else {
            R.drawable.ic_notification_audio
        }
        val playPauseIcon = if (state.isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        val playPauseLabel = if (state.isPlaying) "Pause" else "Abspielen"

        val contentIntent = packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
            PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setContentTitle(state.title.ifBlank { "Wiedergabe" })
            .setContentText(
                listOf(state.artist, state.album)
                    .filter { it.isNotBlank() }
                    .joinToString(" - ")
                    .ifBlank { if (state.isVideo) "Video" else "Audio" },
            )
            .setContentIntent(contentIntent)
            .setLargeIcon(albumArt480p)
            .setOngoing(state.isPlaying)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setWhen(System.currentTimeMillis() - state.positionMs.coerceAtLeast(0L))
            .setUsesChronometer(state.isPlaying)
            .setShowWhen(state.isPlaying)
            .addAction(
                android.R.drawable.ic_media_previous,
                "Vorheriger Titel",
                serviceActionIntent(ACTION_PREV, 1),
            )
            .addAction(
                playPauseIcon,
                playPauseLabel,
                serviceActionIntent(ACTION_PLAY_PAUSE, 2),
            )
            .addAction(
                android.R.drawable.ic_media_next,
                "Nächster Titel",
                serviceActionIntent(ACTION_NEXT, 3),
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Beenden",
                serviceActionIntent(ACTION_STOP, 4),
            )
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )

        return builder.build()
    }

    private fun serviceActionIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, MediaPlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Medienwiedergabe",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Steuerung für aktive Audio- und Videowiedergabe"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun requestAudioFocus() {
        if (audioFocusRequest == null) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()
        }
        audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    }

    private fun acquireLocks(uriString: String) {
        wakeLock?.let { if (!it.isHeld) it.acquire(4 * 60 * 60 * 1000L) }
        val lower = uriString.lowercase()
        val isNetwork = lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("rtsp://") ||
            lower.startsWith("rtmp://")
        if (isNetwork) {
            wifiLock?.let { if (!it.isHeld) it.acquire() }
        } else {
            wifiLock?.let { if (it.isHeld) it.release() }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
    }
}
