package com.monolith.core.common

import android.content.Context
import android.content.SharedPreferences
import com.monolith.core.model.AppSettings
import com.monolith.core.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AppSettingsStore {
    private const val PREFS_NAME = "monolith_app_settings"
    private const val KEY_THEME_MODE = "theme_mode"

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = sp
            _settings.value = loadFromPrefs(sp)
        }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        saveToPrefs(next)
    }

    private fun loadFromPrefs(sp: SharedPreferences): AppSettings {
        val defaults = AppSettings()
        val themeName = sp.getString(KEY_THEME_MODE, defaults.themeMode.name)
        val themeMode = runCatching { ThemeMode.valueOf(themeName ?: defaults.themeMode.name) }
            .getOrDefault(defaults.themeMode)
        return AppSettings(themeMode = themeMode)
    }

    private fun saveToPrefs(value: AppSettings) {
        prefs?.edit()
            ?.putString(KEY_THEME_MODE, value.themeMode.name)
            ?.apply()
    }
}
