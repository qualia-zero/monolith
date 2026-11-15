package com.monolith.core.model

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val useInternalImageViewer: Boolean = true,
    val useInternalVideoPlayer: Boolean = true,
    val useInternalAudioPlayer: Boolean = true,
    val useInternalTextEditor: Boolean = true,
)
