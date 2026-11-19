package com.monolith.core.model

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    CYBER_PHOSPHOR,
    OBSIDIAN,
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val hapticsEnabled: Boolean = true,
    val hapticFolderOpen: Boolean = true,
    val hapticBackNav: Boolean = false,
    val hapticMonolithMenuOpen: Boolean = true,
    val hapticCornerMenuOpen: Boolean = false,
    val hapticDragLift: Boolean = true,
    val hapticLaunchItem: Boolean = false,
    val hapticPullRefresh: Boolean = true,
)

data class DefaultAppAssociation(
    val extension: String,
    val handlerId: String,
    val handlerLabel: String,
    val packageName: String? = null,
    val activityName: String? = null,
)
