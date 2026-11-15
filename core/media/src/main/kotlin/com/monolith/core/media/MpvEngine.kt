package com.monolith.core.media

import android.content.Context
import android.net.Uri
import android.view.Surface
import com.monolith.core.common.MonolithDiagnostics
import com.monolith.core.model.MediaTrack
import com.monolith.core.model.MediaTrackType
import com.monolith.core.model.PlaybackUiState
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Unified libmpv playback engine singleton managing [PlaybackUiState].
 */
object MpvEngine : MPVLib.EventObserver, MPVLib.LogObserver {

    private const val TAG = "MpvEngine"
    private const val CACERT_ASSET = "cacert.pem"
    private const val HISTORY_SAVE_INTERVAL_MS = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initLock = Any()

    @Volatile
    private var initialized = false

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var currentSurface: Surface? = null

    @Volatile
    private var playlistItems: List<Pair<String, String>> = emptyList()

    @Volatile
    private var currentIndex: Int = -1

    @Volatile
    private var lastHistorySaveWallMs: Long = 0L

    private val _uiState = MutableStateFlow(PlaybackUiState())
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            val appCtx = context.applicationContext
            appContext = appCtx

            MonolithDiagnostics.init(appCtx)
            PlaybackHistoryStore.init(appCtx)
            PlayerSettingsStore.init(appCtx)

            val caFile = ensureCaCertFile(appCtx)
            val settings = PlayerSettingsStore.settings.value

            MPVLib.create(appCtx)

            if (caFile != null) {
                MPVLib.setOptionString("tls-ca-file", caFile.absolutePath)
                MPVLib.setOptionString("tls-verify", "yes")
            }

            MPVLib.setOptionString("vo", "gpu")
            MPVLib.setOptionString("gpu-context", "android")
            MPVLib.setOptionString("opengl-es", "yes")
            MPVLib.setOptionString("ao", "audiotrack,opensles")
            MPVLib.setOptionString(
                "hwdec",
                if (settings.useHwDecoding) "mediacodec,mediacodec-copy" else "no",
            )
            MPVLib.setOptionString("save-position-on-quit", "no")
            MPVLib.setOptionString("keep-open", "yes")
            MPVLib.setOptionString("force-window", "yes")
            MPVLib.setOptionString("cache", "yes")
            MPVLib.setOptionString("cache-pause", "yes")
            MPVLib.setOptionString("cache-pause-wait", "4")
            MPVLib.setOptionString("demuxer-max-bytes", "64MiB")
            MPVLib.setOptionString("demuxer-max-back-bytes", "16MiB")
            MPVLib.setOptionString("demuxer-readahead-secs", "30")
            MPVLib.setOptionString("stream-buffer-size", "1MiB")
            MPVLib.setOptionString("demuxer-seekable-cache", "yes")
            MPVLib.setOptionString("hr-seek-framedrop", "yes")
            MPVLib.setOptionString("framedrop", "vo")
            MPVLib.setOptionString("video-sync", "audio")
            MPVLib.setOptionString("network-timeout", "30")
            MPVLib.setOptionString(
                "demuxer-lavf-o",
                "reconnect=1,reconnect_streamed=1,reconnect_delay_max=5",
            )
            MPVLib.setOptionString("sub-font-size", settings.subtitleSize.toInt().toString())

            MPVLib.init()
            MPVLib.addObserver(this)
            MPVLib.addLogObserver(this)

            MPVLib.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            MPVLib.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            MPVLib.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            MPVLib.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            MPVLib.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            MPVLib.observeProperty("demuxer-cache-duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)

            initialized = true
            MonolithDiagnostics.logEvent("MPV_INIT")
        }
    }

    fun loadMedia(
        context: Context,
        uri: String,
        title: String = "",
        isVideo: Boolean = true,
        startPosMs: Long = 0L,
        playlist: List<Pair<String, String>> = emptyList(),
    ) {
        val appCtx = context.applicationContext
        ensureInitialized(appCtx)
        persistCurrentPosition()

        if (playlist.isNotEmpty()) {
            playlistItems = playlist
            currentIndex = playlist.indexOfFirst { it.first == uri }.coerceAtLeast(0)
        } else {
            playlistItems = listOf(uri to title)
            currentIndex = 0
        }

        val target = runCatching { resolveMediaTarget(appCtx, uri) }.getOrElse { err ->
            MonolithDiagnostics.logError(TAG, "Failed to resolve media target: $uri", err)
            _uiState.update {
                it.copy(
                    mediaUri = uri,
                    title = title,
                    isVideo = isVideo,
                    isPlaying = false,
                    isBuffering = false,
                    errorMessage = err.message ?: "Unable to open media",
                )
            }
            return
        }

        val inBackground = _uiState.value.isBackground
        MPVLib.setPropertyString("vid", if (isVideo && !inBackground) "auto" else "no")

        if (startPosMs > 0L) {
            MPVLib.setOptionString("start", String.format(Locale.US, "%.3f", startPosMs / 1000.0))
        } else {
            MPVLib.setOptionString("start", "none")
        }

        _uiState.update {
            it.copy(
                mediaUri = uri,
                title = title,
                artist = "",
                album = "",
                isVideo = isVideo,
                isPlaying = true,
                isBuffering = true,
                isEnded = false,
                positionMs = startPosMs,
                durationMs = 0L,
                cacheDurationSec = 0.0,
                tracks = emptyList(),
                errorMessage = null,
            )
        }

        lastHistorySaveWallMs = System.currentTimeMillis()
        MPVLib.command(arrayOf("loadfile", target, "replace"))
        MPVLib.setPropertyBoolean("pause", false)

        scope.launch {
            val mkvInfo = MkvTagParser.parse(appCtx, uri)
            if (mkvInfo != null) {
                _uiState.update { state ->
                    if (state.mediaUri != uri) state
                    else state.copy(
                        title = state.title.ifBlank { mkvInfo.title },
                        artist = state.artist.ifBlank { mkvInfo.artist },
                        album = state.album.ifBlank { mkvInfo.album },
                    )
                }
            }
            MediaPlaybackService.startOrUpdate(appCtx)
        }

        MediaPlaybackService.startOrUpdate(appCtx)
    }

    fun play() {
        if (!initialized) return
        if (_uiState.value.isEnded) {
            seekTo(0L)
        }
        MPVLib.setPropertyBoolean("pause", false)
    }

    fun pause() {
        if (!initialized) return
        MPVLib.setPropertyBoolean("pause", true)
        persistCurrentPosition()
    }

    fun togglePlayPause() {
        if (!initialized) return
        val state = _uiState.value
        if (state.isEnded) {
            seekTo(0L)
            play()
        } else {
            MPVLib.setPropertyBoolean("pause", state.isPlaying)
        }
    }

    fun seekTo(ms: Long) {
        if (!initialized) return
        val sec = ms.coerceAtLeast(0L) / 1000.0
        MPVLib.command(arrayOf("seek", String.format(Locale.US, "%.3f", sec), "absolute"))
        _uiState.update { it.copy(positionMs = ms.coerceAtLeast(0L), isEnded = false) }
    }

    fun seekBy(deltaMs: Long) {
        if (!initialized) return
        val sec = deltaMs / 1000.0
        MPVLib.command(arrayOf("seek", String.format(Locale.US, "%.3f", sec), "relative"))
    }

    fun setSpeed(speed: Float) {
        if (!initialized) return
        val clamped = speed.coerceIn(0.25f, 4.0f)
        MPVLib.setPropertyDouble("speed", clamped.toDouble())
        _uiState.update { it.copy(speed = clamped) }
    }

    fun setSubtitleSize(size: Float) {
        PlayerSettingsStore.update { it.copy(subtitleSize = size) }
        if (!initialized) return
        MPVLib.setPropertyDouble("sub-font-size", size.toDouble())
    }

    fun selectAudioTrack(id: Int) {
        if (!initialized) return
        MPVLib.setPropertyInt("aid", id)
        refreshTracks()
    }

    fun selectSubtitleTrack(id: Int) {
        if (!initialized) return
        if (id <= 0) {
            MPVLib.setPropertyString("sid", "no")
        } else {
            MPVLib.setPropertyInt("sid", id)
        }
        refreshTracks()
    }

    fun hasNext(): Boolean = currentIndex in 0 until (playlistItems.size - 1)

    fun hasPrevious(): Boolean = currentIndex > 0

    fun skipNext() {
        val ctx = appContext ?: return
        if (!hasNext()) return
        currentIndex += 1
        val (nextUri, nextTitle) = playlistItems[currentIndex]
        loadMedia(
            context = ctx,
            uri = nextUri,
            title = nextTitle,
            isVideo = _uiState.value.isVideo,
            startPosMs = 0L,
            playlist = playlistItems,
        )
    }

    fun skipPrevious() {
        val ctx = appContext ?: return
        if (_uiState.value.positionMs > 5_000L || !hasPrevious()) {
            seekTo(0L)
            return
        }
        currentIndex -= 1
        val (prevUri, prevTitle) = playlistItems[currentIndex]
        loadMedia(
            context = ctx,
            uri = prevUri,
            title = prevTitle,
            isVideo = _uiState.value.isVideo,
            startPosMs = 0L,
            playlist = playlistItems,
        )
    }

    fun onSurfaceCreated(surface: Surface) {
        currentSurface = surface
        if (!initialized) return
        if (!_uiState.value.isBackground) {
            MPVLib.attachSurface(surface)
            MPVLib.setPropertyString("vo", "gpu")
        }
    }

    fun onSurfaceChanged(w: Int, h: Int) {
        if (!initialized || w <= 0 || h <= 0) return
        MPVLib.setPropertyString("android-surface-size", "${w}x${h}")
    }

    fun onSurfaceDestroyed() {
        currentSurface = null
        if (!initialized) return
        MPVLib.detachSurface()
    }

    fun onBackgroundChanged(inBackground: Boolean) {
        _uiState.update { it.copy(isBackground = inBackground) }
        if (!initialized) return
        if (inBackground) {
            MPVLib.setPropertyString("vid", "no")
            MPVLib.setPropertyString("force-window", "no")
            MPVLib.detachSurface()
        } else {
            MPVLib.setPropertyString("force-window", "yes")
            if (_uiState.value.isVideo) {
                MPVLib.setPropertyString("vid", "auto")
            }
            currentSurface?.let { MPVLib.attachSurface(it) }
            MPVLib.setPropertyString("vo", "gpu")
        }
    }

    fun stopAndReset() {
        persistCurrentPosition()
        if (initialized) {
            MPVLib.command(arrayOf("stop"))
        }
        playlistItems = emptyList()
        currentIndex = -1
        _uiState.value = PlaybackUiState()
        appContext?.let { MediaPlaybackService.stopService(it) }
    }

    fun resolveMediaTarget(context: Context, uriString: String): String {
        return when {
            uriString.startsWith("file://") -> {
                Uri.parse(uriString).path ?: uriString
            }
            uriString.startsWith("content://") -> {
                val pfd = context.contentResolver.openFileDescriptor(Uri.parse(uriString), "r")
                    ?: throw IllegalStateException("Cannot open content URI: $uriString")
                val fd = pfd.detachFd()
                "fd://$fd"
            }
            else -> uriString
        }
    }

    override fun eventProperty(property: String) {
        // No-op
    }

    override fun eventProperty(property: String, value: Long) {
        // No-op
    }

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            "pause" -> _uiState.update { it.copy(isPlaying = !value) }
            "eof-reached" -> {
                _uiState.update {
                    it.copy(
                        isEnded = value,
                        isPlaying = if (value) false else it.isPlaying,
                    )
                }
                if (value) {
                    val state = _uiState.value
                    if (state.mediaUri.isNotBlank()) {
                        PlaybackHistoryStore.clearPosition(state.mediaUri)
                    }
                }
            }
            "paused-for-cache" -> _uiState.update { it.copy(isBuffering = value) }
        }
    }

    override fun eventProperty(property: String, value: String) {
        // No-op
    }

    override fun eventProperty(property: String, value: Double) {
        when (property) {
            "duration" -> {
                val ms = (value * 1000.0).toLong().coerceAtLeast(0L)
                _uiState.update { it.copy(durationMs = ms) }
            }
            "time-pos" -> {
                val ms = (value * 1000.0).toLong().coerceAtLeast(0L)
                _uiState.update { it.copy(positionMs = ms) }
                val now = System.currentTimeMillis()
                if (now - lastHistorySaveWallMs >= HISTORY_SAVE_INTERVAL_MS) {
                    lastHistorySaveWallMs = now
                    persistCurrentPosition()
                }
            }
            "demuxer-cache-duration" -> {
                _uiState.update { it.copy(cacheDurationSec = value.coerceAtLeast(0.0)) }
            }
        }
    }

    override fun event(eventId: Int) {
        when (eventId) {
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                refreshTracks()
                readMpvMetadataFallback()
                _uiState.update { it.copy(isBuffering = false, isEnded = false) }
            }
            MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                _uiState.update { it.copy(isBuffering = false, isEnded = false) }
            }
            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                persistCurrentPosition()
                _uiState.update { it.copy(isBuffering = false) }
            }
        }
    }

    override fun logMessage(prefix: String, level: Int, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (level <= 20) {
            MonolithDiagnostics.logError("mpv:$prefix", trimmed)
        } else if (level <= 30) {
            MonolithDiagnostics.logWarn("mpv:$prefix", trimmed)
        }
    }

    private fun refreshTracks() {
        val count = MPVLib.getPropertyInt("track-list/count") ?: 0
        val tracks = ArrayList<MediaTrack>(count)
        for (i in 0 until count) {
            val typeStr = MPVLib.getPropertyString("track-list/$i/type") ?: continue
            val type = when (typeStr) {
                "video" -> MediaTrackType.VIDEO
                "audio" -> MediaTrackType.AUDIO
                "sub" -> MediaTrackType.SUBTITLE
                else -> continue
            }
            val id = MPVLib.getPropertyInt("track-list/$i/id") ?: continue
            val title = MPVLib.getPropertyString("track-list/$i/title").orEmpty()
            val lang = MPVLib.getPropertyString("track-list/$i/lang").orEmpty()
            val selected = MPVLib.getPropertyBoolean("track-list/$i/selected") ?: false
            tracks.add(
                MediaTrack(
                    type = type,
                    id = id,
                    title = title,
                    language = lang,
                    isSelected = selected,
                ),
            )
        }
        _uiState.update { it.copy(tracks = tracks) }
    }

    private fun readMpvMetadataFallback() {
        val mpvTitle = MPVLib.getPropertyString("media-title").orEmpty()
        val mpvArtist = MPVLib.getPropertyString("metadata/by-key/artist")
            ?: MPVLib.getPropertyString("metadata/by-key/ARTIST")
            ?: ""
        val mpvAlbum = MPVLib.getPropertyString("metadata/by-key/album")
            ?: MPVLib.getPropertyString("metadata/by-key/ALBUM")
            ?: ""

        _uiState.update { state ->
            state.copy(
                title = state.title.ifBlank { mpvTitle },
                artist = state.artist.ifBlank { mpvArtist },
                album = state.album.ifBlank { mpvAlbum },
            )
        }
    }

    private fun persistCurrentPosition() {
        val state = _uiState.value
        if (state.mediaUri.isNotBlank() && state.durationMs > 0L) {
            PlaybackHistoryStore.savePosition(state.mediaUri, state.positionMs, state.durationMs)
        }
    }

    private fun ensureCaCertFile(context: Context): File? {
        val outFile = File(context.filesDir, CACERT_ASSET)
        if (outFile.exists() && outFile.length() > 0L) return outFile
        return runCatching {
            context.assets.open(CACERT_ASSET).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            outFile
        }.onFailure { err ->
            MonolithDiagnostics.logError(TAG, "Failed to copy $CACERT_ASSET", err)
        }.getOrNull()
    }
}
