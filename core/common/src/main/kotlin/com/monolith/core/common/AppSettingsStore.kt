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
    private const val KEY_HAPTICS_ENABLED = "haptics_enabled"
    private const val KEY_HAPTIC_FOLDER_OPEN = "haptic_folder_open"
    private const val KEY_HAPTIC_BACK_NAV = "haptic_back_nav"
    private const val KEY_HAPTIC_MONOLITH_MENU = "haptic_monolith_menu_open"
    private const val KEY_HAPTIC_CORNER_MENU = "haptic_corner_menu_open"
    private const val KEY_HAPTIC_DRAG = "haptic_drag_lift"
    private const val KEY_HAPTIC_LAUNCH = "haptic_launch_item"
    private const val KEY_HAPTIC_PULL_REFRESH = "haptic_pull_refresh"

    private val LEGACY_AI_KEYS = listOf(
        "gemini_api_key",
        "gemini_api_keys",
        "active_gemini_api_key_id",
        "ai_model_id",
        "ai_thinking_level",
        "ai_system_prompt",
    )

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (LEGACY_AI_KEYS.any { sp.contains(it) }) {
                sp.edit().apply {
                    LEGACY_AI_KEYS.forEach { remove(it) }
                }.apply()
            }
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

        return AppSettings(
            themeMode = themeMode,
            hapticsEnabled = sp.getBoolean(KEY_HAPTICS_ENABLED, defaults.hapticsEnabled),
            hapticFolderOpen = sp.getBoolean(KEY_HAPTIC_FOLDER_OPEN, defaults.hapticFolderOpen),
            hapticBackNav = sp.getBoolean(KEY_HAPTIC_BACK_NAV, defaults.hapticBackNav),
            hapticMonolithMenuOpen = sp.getBoolean(KEY_HAPTIC_MONOLITH_MENU, defaults.hapticMonolithMenuOpen),
            hapticCornerMenuOpen = sp.getBoolean(KEY_HAPTIC_CORNER_MENU, defaults.hapticCornerMenuOpen),
            hapticDragLift = sp.getBoolean(KEY_HAPTIC_DRAG, defaults.hapticDragLift),
            hapticLaunchItem = sp.getBoolean(KEY_HAPTIC_LAUNCH, defaults.hapticLaunchItem),
            hapticPullRefresh = sp.getBoolean(KEY_HAPTIC_PULL_REFRESH, defaults.hapticPullRefresh),
        )
    }

    private fun saveToPrefs(value: AppSettings) {
        prefs?.edit()
            ?.putString(KEY_THEME_MODE, value.themeMode.name)
            ?.putBoolean(KEY_HAPTICS_ENABLED, value.hapticsEnabled)
            ?.putBoolean(KEY_HAPTIC_FOLDER_OPEN, value.hapticFolderOpen)
            ?.putBoolean(KEY_HAPTIC_BACK_NAV, value.hapticBackNav)
            ?.putBoolean(KEY_HAPTIC_MONOLITH_MENU, value.hapticMonolithMenuOpen)
            ?.putBoolean(KEY_HAPTIC_CORNER_MENU, value.hapticCornerMenuOpen)
            ?.putBoolean(KEY_HAPTIC_DRAG, value.hapticDragLift)
            ?.putBoolean(KEY_HAPTIC_LAUNCH, value.hapticLaunchItem)
            ?.putBoolean(KEY_HAPTIC_PULL_REFRESH, value.hapticPullRefresh)
            ?.apply()
    }
}
