package com.monolith.core.media

import android.content.Context
import android.content.SharedPreferences
import com.monolith.core.model.PlayerSettings
import com.monolith.core.model.ResumeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * SharedPreferences-backed reactive store for [PlayerSettings].
 */
object PlayerSettingsStore {

    private const val PREFS_NAME = "monolith_player_settings"
    private const val KEY_HW_DECODING = "use_hw_decoding"
    private const val KEY_BACKGROUND_PLAY = "use_background_play"
    private const val KEY_PIP_MODE = "use_pip_mode"
    private const val KEY_SUBTITLES_ENABLED = "subtitles_enabled"
    private const val KEY_SUBTITLE_SIZE = "subtitle_size"
    private const val KEY_RESUME_MODE = "resume_mode"
    private const val KEY_SEEK_GESTURE = "use_seek_gesture"
    private const val KEY_VOLUME_GESTURE = "use_volume_gesture"
    private const val KEY_BRIGHTNESS_GESTURE = "use_brightness_gesture"

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _settings = MutableStateFlow(PlayerSettings())
    val settings: StateFlow<PlayerSettings> = _settings.asStateFlow()

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

    fun update(transform: (PlayerSettings) -> PlayerSettings) {
        var updatedValue: PlayerSettings? = null
        _settings.update { current ->
            transform(current).also { updatedValue = it }
        }
        updatedValue?.let { persist(it) }
    }

    private fun readFromPrefs(sp: SharedPreferences): PlayerSettings {
        val defaults = PlayerSettings()
        val resumeStr = sp.getString(KEY_RESUME_MODE, defaults.resumeMode.name)
        val resumeMode = runCatching {
            ResumeMode.valueOf(resumeStr ?: defaults.resumeMode.name)
        }.getOrDefault(defaults.resumeMode)

        return PlayerSettings(
            useHwDecoding = sp.getBoolean(KEY_HW_DECODING, defaults.useHwDecoding),
            useBackgroundPlay = sp.getBoolean(KEY_BACKGROUND_PLAY, defaults.useBackgroundPlay),
            usePipMode = sp.getBoolean(KEY_PIP_MODE, defaults.usePipMode),
            subtitlesEnabled = sp.getBoolean(KEY_SUBTITLES_ENABLED, defaults.subtitlesEnabled),
            subtitleSize = sp.getFloat(KEY_SUBTITLE_SIZE, defaults.subtitleSize),
            resumeMode = resumeMode,
            useSeekGesture = sp.getBoolean(KEY_SEEK_GESTURE, defaults.useSeekGesture),
            useVolumeGesture = sp.getBoolean(KEY_VOLUME_GESTURE, defaults.useVolumeGesture),
            useBrightnessGesture = sp.getBoolean(KEY_BRIGHTNESS_GESTURE, defaults.useBrightnessGesture),
        )
    }

    private fun persist(value: PlayerSettings) {
        prefs?.edit()
            ?.putBoolean(KEY_HW_DECODING, value.useHwDecoding)
            ?.putBoolean(KEY_BACKGROUND_PLAY, value.useBackgroundPlay)
            ?.putBoolean(KEY_PIP_MODE, value.usePipMode)
            ?.putBoolean(KEY_SUBTITLES_ENABLED, value.subtitlesEnabled)
            ?.putFloat(KEY_SUBTITLE_SIZE, value.subtitleSize)
            ?.putString(KEY_RESUME_MODE, value.resumeMode.name)
            ?.putBoolean(KEY_SEEK_GESTURE, value.useSeekGesture)
            ?.putBoolean(KEY_VOLUME_GESTURE, value.useVolumeGesture)
            ?.putBoolean(KEY_BRIGHTNESS_GESTURE, value.useBrightnessGesture)
            ?.apply()
    }
}
