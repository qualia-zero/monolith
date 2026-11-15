package com.monolith.core.model

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
)

data class DefaultAppAssociation(
    val extension: String,
    val handlerId: String,
    val handlerLabel: String,
    val packageName: String? = null,
    val activityName: String? = null,
)
