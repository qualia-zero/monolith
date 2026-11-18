package com.monolith.core.model

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    CYBER_PHOSPHOR,
    OBSIDIAN,
}

data class GeminiApiKeyEntry(
    val id: String,
    val label: String,
    val key: String,
)

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val hapticsEnabled: Boolean = true,
    val hapticNavClick: Boolean = true,
    val hapticDragLift: Boolean = true,
    val hapticLaunchItem: Boolean = true,
    val hapticPulseFeedback: Boolean = true,
    val geminiApiKey: String = "",
    val geminiApiKeys: List<GeminiApiKeyEntry> = emptyList(),
    val activeGeminiApiKeyId: String? = null,
    val aiModelId: String = "gemini-4-hydrogen",
    val aiThinkingLevel: String = "medium",
) {
    val activeGeminiApiKey: String
        get() = geminiApiKeys.firstOrNull { it.id == activeGeminiApiKeyId }?.key
            ?: geminiApiKeys.firstOrNull()?.key
            ?: geminiApiKey
}

data class DefaultAppAssociation(
    val extension: String,
    val handlerId: String,
    val handlerLabel: String,
    val packageName: String? = null,
    val activityName: String? = null,
)
