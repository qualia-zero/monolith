package com.monolith.core.media

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists resume positions for long-form media items.
 */
object PlaybackHistoryStore {

    private const val PREFS_NAME = "monolith_playback_history"
    const val MIN_DURATION_MS = 240_000L
    const val MIN_POS_MS = 10_000L
    const val TAIL_MARGIN_MS = 30_000L

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            synchronized(this) {
                if (prefs == null) {
                    prefs = context.applicationContext
                        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                }
            }
        }
    }

    fun savePosition(uri: String, positionMs: Long, durationMs: Long) {
        if (uri.isBlank()) return
        if (durationMs < MIN_DURATION_MS) return
        if (positionMs < MIN_POS_MS || positionMs > durationMs - TAIL_MARGIN_MS) {
            clearPosition(uri)
            return
        }
        prefs?.edit()?.putLong(uri, positionMs)?.apply()
    }

    fun getSavedPosition(uri: String): Long {
        if (uri.isBlank()) return 0L
        return prefs?.getLong(uri, 0L) ?: 0L
    }

    fun clearPosition(uri: String) {
        if (uri.isBlank()) return
        prefs?.edit()?.remove(uri)?.apply()
    }
}
