package com.monolith.feature.explorer.downloader

enum class DownloadMode(val label: String) {
    VIDEO("Video"),
    AUDIO("Audio"),
}

enum class VideoFormatPreset(
    val label: String,
    val subtitle: String,
) {
    AUTO_VP9_H264(
        label = "Auto (VP9 / H.264)",
        subtitle = "Standard: WebM VP9 oder MP4 H.264 je nach Quelle.",
    ),
    MP4_H264(
        label = "MP4 (H.264 + AAC)",
        subtitle = "Hohe Kompatibilität auf allen Geräten.",
    ),
    WEBM_VP9(
        label = "WebM (VP9 + Opus)",
        subtitle = "Kompakter VP9-Videostream mit Opus-Audio.",
    ),
    BEST_ANY(
        label = "Bestmöglich (alle Codecs)",
        subtitle = "Beste Qualität inkl. AV1 und beliebiger Container.",
    ),
    CUSTOM(
        label = "Benutzerdefiniert (-f)",
        subtitle = "Eigenen yt-dlp-Formatausdruck angeben.",
    ),
}

enum class AudioFormatPreset(
    val label: String,
    val subtitle: String,
) {
    M4A_BEST(
        label = "M4A (bestaudio)",
        subtitle = "Standard: Direkter M4A-Audiostream ohne Neukodierung.",
    ),
    OPUS_WEBM(
        label = "WebM / Opus",
        subtitle = "Originaler Opus-Audiostream im WebM-Container.",
    ),
    MP3_CONVERT(
        label = "MP3 (konvertieren)",
        subtitle = "Per FFmpeg in MP3 (höchste VBR-Qualität).",
    ),
    FLAC_CONVERT(
        label = "FLAC (verlustfrei)",
        subtitle = "Per FFmpeg verlustfrei in FLAC umwandeln.",
    ),
    BEST_ORIGINAL(
        label = "Bestes Originalformat",
        subtitle = "Bester verfügbarer Audiostream unverändert.",
    ),
    CUSTOM(
        label = "Benutzerdefiniert (-f)",
        subtitle = "Eigenen yt-dlp-Formatausdruck angeben.",
    ),
}

enum class SponsorBlockMode(
    val label: String,
    val subtitle: String,
) {
    REMOVE(
        label = "Entfernen",
        subtitle = "Ausgewählte Segmente direkt herausschneiden.",
    ),
    MARK(
        label = "Als Kapitel markieren",
        subtitle = "Segmente behalten und als Kapitel kennzeichnen.",
    ),
    OFF(
        label = "Aus",
        subtitle = "SponsorBlock für Downloads deaktivieren.",
    ),
}

enum class ThumbnailEmbedMode(
    val label: String,
    val subtitle: String,
) {
    AUDIO_ONLY(
        label = "Nur bei Audio",
        subtitle = "Vorschaubild in Audiodateien einbetten, nicht in Videos.",
    ),
    ALWAYS(
        label = "Immer",
        subtitle = "Vorschaubild in Audio- und Videodateien einbetten.",
    ),
    NEVER(
        label = "Nie",
        subtitle = "Kein Vorschaubild einbetten.",
    ),
}

enum class YtDlpChannel(
    val label: String,
    val subtitle: String,
) {
    NIGHTLY(
        label = "Nightly",
        subtitle = "Tägliche Updates mit aktuellen Extractor-Fixes (Standard).",
    ),
    STABLE(
        label = "Stable",
        subtitle = "Geprüfte stabile Release-Version.",
    ),
    MASTER(
        label = "Master",
        subtitle = "Direkt vom Entwicklungs-Branch.",
    ),
}

enum class DownloadTaskStatus(val label: String) {
    QUEUED("Warteschlange"),
    INITIALIZING("Initialisiere…"),
    PROBING("Prüfe Format…"),
    DOWNLOADING("Lade herunter…"),
    POST_PROCESSING("Verarbeite…"),
    COMPLETED("Fertig"),
    FAILED("Fehlgeschlagen"),
    CANCELLED("Abgebrochen"),
    ;

    val isActive: Boolean
        get() = this == QUEUED ||
            this == INITIALIZING ||
            this == PROBING ||
            this == DOWNLOADING ||
            this == POST_PROCESSING

    val isFinished: Boolean
        get() = !isActive
}

data class SponsorBlockCategory(
    val id: String,
    val label: String,
)

val SPONSORBLOCK_CATEGORIES: List<SponsorBlockCategory> = listOf(
    SponsorBlockCategory("sponsor", "Sponsoring"),
    SponsorBlockCategory("selfpromo", "Eigenwerbung"),
    SponsorBlockCategory("interaction", "Interaktion / Abo"),
    SponsorBlockCategory("intro", "Intro"),
    SponsorBlockCategory("outro", "Outro / Abspann"),
    SponsorBlockCategory("preview", "Vorschau / Rückblick"),
    SponsorBlockCategory("music_offtopic", "Musik: Nicht-Musik-Teil"),
    SponsorBlockCategory("filler", "Füllmaterial / Abschweifungen"),
)

val VIDEO_HEIGHT_OPTIONS: List<Int?> = listOf(null, 2160, 1440, 1080, 720, 480)

val OUTPUT_TEMPLATE_PRESETS: List<Pair<String, String>> = listOf(
    "%(title)s.%(ext)s" to "Titel",
    "%(uploader)s - %(title)s.%(ext)s" to "Kanal - Titel",
    "%(playlist_index)02d - %(title)s.%(ext)s" to "Playlist-Nr. - Titel",
)

data class DownloaderSettings(
    val defaultMode: DownloadMode = DownloadMode.VIDEO,
    val videoFormatPreset: VideoFormatPreset = VideoFormatPreset.AUTO_VP9_H264,
    val videoMaxHeight: Int? = null,
    val customVideoFormat: String = "",
    val audioFormatPreset: AudioFormatPreset = AudioFormatPreset.M4A_BEST,
    val customAudioFormat: String = "",
    val sponsorBlockMode: SponsorBlockMode = SponsorBlockMode.REMOVE,
    val sponsorBlockCategories: Set<String> = setOf("sponsor", "selfpromo"),
    val sleepIntervalEnabled: Boolean = true,
    val minSleepIntervalSec: Int = 3,
    val maxSleepIntervalSec: Int = 12,
    val embedMetadata: Boolean = true,
    val embedChapters: Boolean = true,
    val thumbnailEmbedMode: ThumbnailEmbedMode = ThumbnailEmbedMode.AUDIO_ONLY,
    val embedSubtitles: Boolean = false,
    val subtitleLanguages: String = "de,en",
    val allowPlaylists: Boolean = false,
    val outputTemplate: String = "%(title)s.%(ext)s",
    val outputDirectory: String = "",
    val updateChannel: YtDlpChannel = YtDlpChannel.NIGHTLY,
    val autoUpdateEngine: Boolean = true,
    val extraArgs: String = "",
)

data class DownloadTaskItem(
    val id: Long,
    val url: String,
    val mode: DownloadMode,
    val outputDirectory: String,
    val status: DownloadTaskStatus = DownloadTaskStatus.QUEUED,
    val title: String? = null,
    val progressPercent: Float = -1f,
    val etaSeconds: Long = -1L,
    val statusDetail: String? = null,
    val outputFilePath: String? = null,
    val errorMessage: String? = null,
    val logs: List<String> = emptyList(),
    val createdAtMs: Long = System.currentTimeMillis(),
)

data class YtDlpEngineState(
    val isInitialized: Boolean = false,
    val isInitializing: Boolean = false,
    val isUpdating: Boolean = false,
    val version: String? = null,
    val versionName: String? = null,
    val activeChannel: YtDlpChannel = YtDlpChannel.NIGHTLY,
    val lastUpdateMessage: String? = null,
    val errorMessage: String? = null,
)
