package com.monolith.core.model

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    CYBER_PHOSPHOR,
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val hapticsEnabled: Boolean = true,
    val hapticNavClick: Boolean = true,
    val hapticDragLift: Boolean = true,
    val hapticLaunchItem: Boolean = true,
    val hapticPulseFeedback: Boolean = true,
)

data class DefaultAppAssociation(
    val extension: String,
    val handlerId: String,
    val handlerLabel: String,
    val packageName: String? = null,
    val activityName: String? = null,
)
