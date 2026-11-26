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

    private fun canonicalKey(uri: String): String =
        ThumbnailEngine.resolveMediaPath(uri)

    fun savePosition(uri: String, positionMs: Long, durationMs: Long) {
        if (uri.isBlank()) return
        if (durationMs < MIN_DURATION_MS) return
        val key = canonicalKey(uri)
        if (positionMs < MIN_POS_MS || positionMs > durationMs - TAIL_MARGIN_MS) {
            clearPosition(uri)
            return
        }
        prefs?.edit()?.putLong(key, positionMs)?.apply()
    }

    fun getSavedPosition(uri: String): Long {
        if (uri.isBlank()) return 0L
        val key = canonicalKey(uri)
        val sp = prefs ?: return 0L
        if (sp.contains(key)) return sp.getLong(key, 0L)
        return if (key != uri) sp.getLong(uri, 0L) else 0L
    }

    fun clearPosition(uri: String) {
        if (uri.isBlank()) return
        val key = canonicalKey(uri)
        prefs?.edit()?.apply {
            remove(key)
            if (key != uri) remove(uri)
            apply()
        }
    }
}
