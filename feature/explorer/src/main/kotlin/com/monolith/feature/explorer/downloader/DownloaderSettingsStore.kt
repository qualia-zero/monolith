package com.monolith.feature.explorer.downloader

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object DownloaderSettingsStore {
    private const val PREFS_NAME = "monolith_downloader_settings"

    private const val KEY_DEFAULT_MODE = "default_mode"
    private const val KEY_VIDEO_FORMAT_PRESET = "video_format_preset"
    private const val KEY_VIDEO_MAX_HEIGHT = "video_max_height"
    private const val KEY_CUSTOM_VIDEO_FORMAT = "custom_video_format"
    private const val KEY_AUDIO_FORMAT_PRESET = "audio_format_preset"
    private const val KEY_CUSTOM_AUDIO_FORMAT = "custom_audio_format"
    private const val KEY_SPONSORBLOCK_MODE = "sponsorblock_mode"
    private const val KEY_SPONSORBLOCK_CATEGORIES = "sponsorblock_categories"
    private const val KEY_SLEEP_INTERVAL_ENABLED = "sleep_interval_enabled"
    private const val KEY_MIN_SLEEP_INTERVAL_SEC = "min_sleep_interval_sec"
    private const val KEY_MAX_SLEEP_INTERVAL_SEC = "max_sleep_interval_sec"
    private const val KEY_EMBED_METADATA = "embed_metadata"
    private const val KEY_EMBED_CHAPTERS = "embed_chapters"
    private const val KEY_THUMBNAIL_EMBED_MODE = "thumbnail_embed_mode"
    private const val KEY_EMBED_SUBTITLES = "embed_subtitles"
    private const val KEY_SUBTITLE_LANGUAGES = "subtitle_languages"
    private const val KEY_ALLOW_PLAYLISTS = "allow_playlists"
    private const val KEY_OUTPUT_TEMPLATE = "output_template"
    private const val KEY_OUTPUT_DIRECTORY = "output_directory"
    private const val KEY_UPDATE_CHANNEL = "update_channel"
    private const val KEY_AUTO_UPDATE_ENGINE = "auto_update_engine"
    private const val KEY_EXTRA_ARGS = "extra_args"

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _settings = MutableStateFlow(DownloaderSettings())
    val settings: StateFlow<DownloaderSettings> = _settings.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = sp
            _settings.value = loadFromPrefs(sp)
        }
    }

    fun update(transform: (DownloaderSettings) -> DownloaderSettings) {
        synchronized(this) {
            val next = sanitize(transform(_settings.value))
            _settings.value = next
            saveToPrefs(next)
        }
    }

    fun resetToDefaults() {
        synchronized(this) {
            val defaults = DownloaderSettings()
            _settings.value = defaults
            saveToPrefs(defaults)
        }
    }

    fun resolveOutputDirectory(settings: DownloaderSettings): String {
        val configured = settings.outputDirectory.trim()
        if (configured.isNotEmpty()) return configured
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
    }

    private fun sanitize(value: DownloaderSettings): DownloaderSettings {
        val minSleep = value.minSleepIntervalSec.coerceAtLeast(0)
        val maxSleep = value.maxSleepIntervalSec.coerceAtLeast(minSleep)
        val cleanTemplate = value.outputTemplate.trim().ifBlank { "%(title)s.%(ext)s" }
        val cleanSubLangs = value.subtitleLanguages.trim().ifBlank { "de,en" }
        return value.copy(
            minSleepIntervalSec = minSleep,
            maxSleepIntervalSec = maxSleep,
            outputTemplate = cleanTemplate,
            subtitleLanguages = cleanSubLangs,
        )
    }

    private fun loadFromPrefs(sp: SharedPreferences): DownloaderSettings {
        val defaults = DownloaderSettings()
        val categories = if (sp.contains(KEY_SPONSORBLOCK_CATEGORIES)) {
            sp.getString(KEY_SPONSORBLOCK_CATEGORIES, "")
                .orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        } else {
            defaults.sponsorBlockCategories
        }

        val maxHeight = if (sp.contains(KEY_VIDEO_MAX_HEIGHT)) {
            sp.getInt(KEY_VIDEO_MAX_HEIGHT, -1).takeIf { it > 0 }
        } else {
            defaults.videoMaxHeight
        }

        val loaded = DownloaderSettings(
            defaultMode = parseEnum(sp.getString(KEY_DEFAULT_MODE, null), defaults.defaultMode),
            videoFormatPreset = parseEnum(sp.getString(KEY_VIDEO_FORMAT_PRESET, null), defaults.videoFormatPreset),
            videoMaxHeight = maxHeight,
            customVideoFormat = sp.getString(KEY_CUSTOM_VIDEO_FORMAT, defaults.customVideoFormat).orEmpty(),
            audioFormatPreset = parseEnum(sp.getString(KEY_AUDIO_FORMAT_PRESET, null), defaults.audioFormatPreset),
            customAudioFormat = sp.getString(KEY_CUSTOM_AUDIO_FORMAT, defaults.customAudioFormat).orEmpty(),
            sponsorBlockMode = parseEnum(sp.getString(KEY_SPONSORBLOCK_MODE, null), defaults.sponsorBlockMode),
            sponsorBlockCategories = categories,
            sleepIntervalEnabled = sp.getBoolean(KEY_SLEEP_INTERVAL_ENABLED, defaults.sleepIntervalEnabled),
            minSleepIntervalSec = sp.getInt(KEY_MIN_SLEEP_INTERVAL_SEC, defaults.minSleepIntervalSec),
            maxSleepIntervalSec = sp.getInt(KEY_MAX_SLEEP_INTERVAL_SEC, defaults.maxSleepIntervalSec),
            embedMetadata = sp.getBoolean(KEY_EMBED_METADATA, defaults.embedMetadata),
            embedChapters = sp.getBoolean(KEY_EMBED_CHAPTERS, defaults.embedChapters),
            thumbnailEmbedMode = parseEnum(sp.getString(KEY_THUMBNAIL_EMBED_MODE, null), defaults.thumbnailEmbedMode),
            embedSubtitles = sp.getBoolean(KEY_EMBED_SUBTITLES, defaults.embedSubtitles),
            subtitleLanguages = sp.getString(KEY_SUBTITLE_LANGUAGES, defaults.subtitleLanguages)
                ?: defaults.subtitleLanguages,
            allowPlaylists = sp.getBoolean(KEY_ALLOW_PLAYLISTS, defaults.allowPlaylists),
            outputTemplate = sp.getString(KEY_OUTPUT_TEMPLATE, defaults.outputTemplate)
                ?: defaults.outputTemplate,
            outputDirectory = sp.getString(KEY_OUTPUT_DIRECTORY, defaults.outputDirectory).orEmpty(),
            updateChannel = parseEnum(sp.getString(KEY_UPDATE_CHANNEL, null), defaults.updateChannel),
            autoUpdateEngine = sp.getBoolean(KEY_AUTO_UPDATE_ENGINE, defaults.autoUpdateEngine),
            extraArgs = sp.getString(KEY_EXTRA_ARGS, defaults.extraArgs).orEmpty(),
        )
        return sanitize(loaded)
    }

    private fun saveToPrefs(value: DownloaderSettings) {
        val editor = prefs?.edit() ?: return
        editor
            .putString(KEY_DEFAULT_MODE, value.defaultMode.name)
            .putString(KEY_VIDEO_FORMAT_PRESET, value.videoFormatPreset.name)
            .putString(KEY_CUSTOM_VIDEO_FORMAT, value.customVideoFormat)
            .putString(KEY_AUDIO_FORMAT_PRESET, value.audioFormatPreset.name)
            .putString(KEY_CUSTOM_AUDIO_FORMAT, value.customAudioFormat)
            .putString(KEY_SPONSORBLOCK_MODE, value.sponsorBlockMode.name)
            .putString(KEY_SPONSORBLOCK_CATEGORIES, value.sponsorBlockCategories.joinToString(","))
            .putBoolean(KEY_SLEEP_INTERVAL_ENABLED, value.sleepIntervalEnabled)
            .putInt(KEY_MIN_SLEEP_INTERVAL_SEC, value.minSleepIntervalSec)
            .putInt(KEY_MAX_SLEEP_INTERVAL_SEC, value.maxSleepIntervalSec)
            .putBoolean(KEY_EMBED_METADATA, value.embedMetadata)
            .putBoolean(KEY_EMBED_CHAPTERS, value.embedChapters)
            .putString(KEY_THUMBNAIL_EMBED_MODE, value.thumbnailEmbedMode.name)
            .putBoolean(KEY_EMBED_SUBTITLES, value.embedSubtitles)
            .putString(KEY_SUBTITLE_LANGUAGES, value.subtitleLanguages)
            .putBoolean(KEY_ALLOW_PLAYLISTS, value.allowPlaylists)
            .putString(KEY_OUTPUT_TEMPLATE, value.outputTemplate)
            .putString(KEY_OUTPUT_DIRECTORY, value.outputDirectory)
            .putString(KEY_UPDATE_CHANNEL, value.updateChannel.name)
            .putBoolean(KEY_AUTO_UPDATE_ENGINE, value.autoUpdateEngine)
            .putString(KEY_EXTRA_ARGS, value.extraArgs)

        if (value.videoMaxHeight != null && value.videoMaxHeight > 0) {
            editor.putInt(KEY_VIDEO_MAX_HEIGHT, value.videoMaxHeight)
        } else {
            editor.remove(KEY_VIDEO_MAX_HEIGHT)
        }

        editor.apply()
    }

    private inline fun <reified T : Enum<T>> parseEnum(raw: String?, fallback: T): T {
        if (raw.isNullOrBlank()) return fallback
        return runCatching { enumValueOf<T>(raw) }.getOrDefault(fallback)
    }
}
