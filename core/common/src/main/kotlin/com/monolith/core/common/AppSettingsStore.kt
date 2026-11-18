package com.monolith.core.common

import android.content.Context
import android.content.SharedPreferences
import com.monolith.core.model.AppSettings
import com.monolith.core.model.GeminiApiKeyEntry
import com.monolith.core.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object AppSettingsStore {
    private const val PREFS_NAME = "monolith_app_settings"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_HAPTICS_ENABLED = "haptics_enabled"
    private const val KEY_HAPTIC_NAV_CLICK = "haptic_nav_click"
    private const val KEY_HAPTIC_DRAG = "haptic_drag_lift"
    private const val KEY_HAPTIC_LAUNCH = "haptic_launch_item"
    private const val KEY_HAPTIC_PULSE = "haptic_pulse_feedback"
    private const val KEY_HAPTIC_PULL_REFRESH = "haptic_pull_refresh"
    private const val KEY_GEMINI_API_KEY = "gemini_api_key"
    private const val KEY_GEMINI_API_KEYS = "gemini_api_keys"
    private const val KEY_ACTIVE_GEMINI_API_KEY_ID = "active_gemini_api_key_id"
    private const val KEY_AI_MODEL_ID = "ai_model_id"
    private const val KEY_AI_THINKING_LEVEL = "ai_thinking_level"
    private const val KEY_AI_SYSTEM_PROMPT = "ai_system_prompt"

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

    fun setActiveGeminiApiKeyValue(newValue: String) {
        update { current ->
            val trimmed = newValue.trim()
            val activeId = current.activeGeminiApiKeyId
            val existingActive = current.geminiApiKeys.firstOrNull { it.id == activeId }
                ?: current.geminiApiKeys.firstOrNull()

            if (existingActive != null) {
                val updatedList = current.geminiApiKeys.map { entry ->
                    if (entry.id == existingActive.id) entry.copy(key = trimmed) else entry
                }
                current.copy(
                    geminiApiKey = trimmed,
                    geminiApiKeys = updatedList,
                    activeGeminiApiKeyId = existingActive.id,
                )
            } else if (trimmed.isNotEmpty()) {
                val newEntry = GeminiApiKeyEntry(
                    id = UUID.randomUUID().toString(),
                    label = "Standard",
                    key = trimmed,
                )
                current.copy(
                    geminiApiKey = trimmed,
                    geminiApiKeys = listOf(newEntry),
                    activeGeminiApiKeyId = newEntry.id,
                )
            } else {
                current.copy(geminiApiKey = "")
            }
        }
    }

    fun addGeminiApiKey(label: String, key: String) {
        val trimmedKey = key.trim()
        if (trimmedKey.isEmpty()) return
        update { current ->
            val cleanLabel = label.trim().ifEmpty { "Key ${current.geminiApiKeys.size + 1}" }
            val newEntry = GeminiApiKeyEntry(
                id = UUID.randomUUID().toString(),
                label = cleanLabel,
                key = trimmedKey,
            )
            current.copy(
                geminiApiKey = trimmedKey,
                geminiApiKeys = current.geminiApiKeys + newEntry,
                activeGeminiApiKeyId = newEntry.id,
            )
        }
    }

    fun selectGeminiApiKey(id: String) {
        update { current ->
            val target = current.geminiApiKeys.firstOrNull { it.id == id } ?: return@update current
            current.copy(
                geminiApiKey = target.key,
                activeGeminiApiKeyId = target.id,
            )
        }
    }

    fun removeGeminiApiKey(id: String) {
        update { current ->
            val remaining = current.geminiApiKeys.filterNot { it.id == id }
            val nextActive = if (current.activeGeminiApiKeyId == id) {
                remaining.firstOrNull()
            } else {
                remaining.firstOrNull { it.id == current.activeGeminiApiKeyId } ?: remaining.firstOrNull()
            }
            current.copy(
                geminiApiKeys = remaining,
                activeGeminiApiKeyId = nextActive?.id,
                geminiApiKey = nextActive?.key.orEmpty(),
            )
        }
    }

    private fun loadFromPrefs(sp: SharedPreferences): AppSettings {
        val defaults = AppSettings()
        val themeName = sp.getString(KEY_THEME_MODE, defaults.themeMode.name)
        val themeMode = runCatching { ThemeMode.valueOf(themeName ?: defaults.themeMode.name) }
            .getOrDefault(defaults.themeMode)

        val legacyKey = sp.getString(KEY_GEMINI_API_KEY, defaults.geminiApiKey).orEmpty()
        val parsedKeys = parseKeysJson(sp.getString(KEY_GEMINI_API_KEYS, null))
        var activeId = sp.getString(KEY_ACTIVE_GEMINI_API_KEY_ID, null)

        val finalKeys = if (parsedKeys.isEmpty() && legacyKey.isNotBlank()) {
            val migratedId = UUID.randomUUID().toString()
            activeId = migratedId
            listOf(GeminiApiKeyEntry(id = migratedId, label = "Standard", key = legacyKey))
        } else {
            if (activeId == null || parsedKeys.none { it.id == activeId }) {
                activeId = parsedKeys.firstOrNull()?.id
            }
            parsedKeys
        }

        return AppSettings(
            themeMode = themeMode,
            hapticsEnabled = sp.getBoolean(KEY_HAPTICS_ENABLED, defaults.hapticsEnabled),
            hapticNavClick = sp.getBoolean(KEY_HAPTIC_NAV_CLICK, defaults.hapticNavClick),
            hapticDragLift = sp.getBoolean(KEY_HAPTIC_DRAG, defaults.hapticDragLift),
            hapticLaunchItem = sp.getBoolean(KEY_HAPTIC_LAUNCH, defaults.hapticLaunchItem),
            hapticPulseFeedback = sp.getBoolean(KEY_HAPTIC_PULSE, defaults.hapticPulseFeedback),
            hapticPullRefresh = sp.getBoolean(KEY_HAPTIC_PULL_REFRESH, defaults.hapticPullRefresh),
            geminiApiKey = finalKeys.firstOrNull { it.id == activeId }?.key ?: legacyKey,
            geminiApiKeys = finalKeys,
            activeGeminiApiKeyId = activeId,
            aiModelId = sp.getString(KEY_AI_MODEL_ID, defaults.aiModelId) ?: defaults.aiModelId,
            aiThinkingLevel = sp.getString(KEY_AI_THINKING_LEVEL, defaults.aiThinkingLevel)
                ?: defaults.aiThinkingLevel,
            aiSystemPrompt = sp.getString(KEY_AI_SYSTEM_PROMPT, defaults.aiSystemPrompt)
                ?: defaults.aiSystemPrompt,
        )
    }

    private fun saveToPrefs(value: AppSettings) {
        val keysArray = JSONArray().apply {
            value.geminiApiKeys.forEach { entry ->
                put(
                    JSONObject().apply {
                        put("id", entry.id)
                        put("label", entry.label)
                        put("key", entry.key)
                    },
                )
            }
        }
        prefs?.edit()
            ?.putString(KEY_THEME_MODE, value.themeMode.name)
            ?.putBoolean(KEY_HAPTICS_ENABLED, value.hapticsEnabled)
            ?.putBoolean(KEY_HAPTIC_NAV_CLICK, value.hapticNavClick)
            ?.putBoolean(KEY_HAPTIC_DRAG, value.hapticDragLift)
            ?.putBoolean(KEY_HAPTIC_LAUNCH, value.hapticLaunchItem)
            ?.putBoolean(KEY_HAPTIC_PULSE, value.hapticPulseFeedback)
            ?.putBoolean(KEY_HAPTIC_PULL_REFRESH, value.hapticPullRefresh)
            ?.putString(KEY_GEMINI_API_KEY, value.activeGeminiApiKey)
            ?.putString(KEY_GEMINI_API_KEYS, keysArray.toString())
            ?.putString(KEY_ACTIVE_GEMINI_API_KEY_ID, value.activeGeminiApiKeyId)
            ?.putString(KEY_AI_MODEL_ID, value.aiModelId)
            ?.putString(KEY_AI_THINKING_LEVEL, value.aiThinkingLevel)
            ?.putString(KEY_AI_SYSTEM_PROMPT, value.aiSystemPrompt)
            ?.apply()
    }

    private fun parseKeysJson(raw: String?): List<GeminiApiKeyEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id").ifBlank { UUID.randomUUID().toString() }
                    val label = obj.optString("label").ifBlank { "Key ${i + 1}" }
                    val key = obj.optString("key")
                    if (key.isNotBlank()) {
                        add(GeminiApiKeyEntry(id = id, label = label, key = key))
                    }
                }
            }
        }.getOrDefault(emptyList())
    }
}
