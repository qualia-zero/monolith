package com.monolith.core.common

import android.content.Context
import android.content.SharedPreferences
import com.monolith.core.model.AppSettings
import com.monolith.core.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * SharedPreferences-backed reactive store for [AppSettings].
 */
object AppSettingsStore {

    private const val PREFS_NAME = "monolith_app_settings"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_INTERNAL_IMAGE_VIEWER = "use_internal_image_viewer"
    private const val KEY_INTERNAL_VIDEO_PLAYER = "use_internal_video_player"
    private const val KEY_INTERNAL_AUDIO_PLAYER = "use_internal_audio_player"
    private const val KEY_INTERNAL_TEXT_EDITOR = "use_internal_text_editor"

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val sp = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = sp
            _settings.value = readFromPrefs(sp)
        }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        var updatedValue: AppSettings? = null
        _settings.update { current ->
            transform(current).also { updatedValue = it }
        }
        updatedValue?.let { persist(it) }
    }

    private fun readFromPrefs(sp: SharedPreferences): AppSettings {
        val defaults = AppSettings()
        val themeStr = sp.getString(KEY_THEME_MODE, defaults.themeMode.name)
        val themeMode = runCatching {
            ThemeMode.valueOf(themeStr ?: defaults.themeMode.name)
        }.getOrDefault(defaults.themeMode)

        return AppSettings(
            themeMode = themeMode,
            useInternalImageViewer = sp.getBoolean(KEY_INTERNAL_IMAGE_VIEWER, defaults.useInternalImageViewer),
            useInternalVideoPlayer = sp.getBoolean(KEY_INTERNAL_VIDEO_PLAYER, defaults.useInternalVideoPlayer),
            useInternalAudioPlayer = sp.getBoolean(KEY_INTERNAL_AUDIO_PLAYER, defaults.useInternalAudioPlayer),
            useInternalTextEditor = sp.getBoolean(KEY_INTERNAL_TEXT_EDITOR, defaults.useInternalTextEditor),
        )
    }

    private fun persist(value: AppSettings) {
        prefs?.edit()
            ?.putString(KEY_THEME_MODE, value.themeMode.name)
            ?.putBoolean(KEY_INTERNAL_IMAGE_VIEWER, value.useInternalImageViewer)
            ?.putBoolean(KEY_INTERNAL_VIDEO_PLAYER, value.useInternalVideoPlayer)
            ?.putBoolean(KEY_INTERNAL_AUDIO_PLAYER, value.useInternalAudioPlayer)
            ?.putBoolean(KEY_INTERNAL_TEXT_EDITOR, value.useInternalTextEditor)
            ?.apply()
    }
}
