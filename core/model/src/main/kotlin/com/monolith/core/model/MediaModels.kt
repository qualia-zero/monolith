package com.monolith.core.model

enum class MediaTrackType {
    VIDEO,
    AUDIO,
    SUBTITLE,
}

data class MediaTrack(
    val type: MediaTrackType,
    val id: Int,
    val title: String,
    val language: String,
    val isSelected: Boolean,
)

enum class ResumeMode {
    ASK,
    ALWAYS,
    NEVER,
}

data class PlayerSettings(
    val useHwDecoding: Boolean = true,
    val useBackgroundPlay: Boolean = true,
    val usePipMode: Boolean = false,
    val subtitlesEnabled: Boolean = true,
    val subtitleSize: Float = 22f,
    val resumeMode: ResumeMode = ResumeMode.ASK,
    val useSeekGesture: Boolean = false,
    val useVolumeGesture: Boolean = false,
    val useBrightnessGesture: Boolean = false,
)

data class MediaMetadataInfo(
    val title: String,
    val artist: String = "",
    val album: String = "",
    val genre: String = "",
)

data class PlaybackUiState(
    val mediaUri: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val isVideo: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isEnded: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val cacheDurationSec: Double = 0.0,
    val speed: Float = 1.0f,
    val tracks: List<MediaTrack> = emptyList(),
    val isBackground: Boolean = false,
    val isInPip: Boolean = false,
    val errorMessage: String? = null,
)
