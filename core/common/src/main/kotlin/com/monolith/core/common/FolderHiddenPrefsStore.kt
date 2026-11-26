package com.monolith.core.common

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

object FolderHiddenRules {
    fun normalize(path: String): String {
        val trimmed = path.trim()
        if (trimmed == "/") return "/"
        return trimmed.trimEnd('/')
    }

    fun isShowHidden(
        rules: Map<String, Boolean>,
        path: String,
        ancestorPaths: List<String> = emptyList(),
    ): Boolean {
        for (ancestor in ancestorPaths.asReversed()) {
            val key = normalize(ancestor)
            if (key.isNotEmpty()) {
                rules[key]?.let { return it }
            }
        }

        var current = normalize(path)
        while (current.isNotEmpty()) {
            rules[current]?.let { return it }
            if (current == "/" || current == "gdrive://root") break
            val slashIdx = current.lastIndexOf('/')
            if (slashIdx < 0) break
            if (current.startsWith("smb://") || current.startsWith("gdrive://")) {
                val schemeEnd = current.indexOf("://") + 3
                if (slashIdx <= schemeEnd) break
                current = current.substring(0, slashIdx)
            } else {
                current = if (slashIdx == 0) "/" else current.substring(0, slashIdx)
            }
        }
        return false
    }

    fun setShowHidden(
        rules: MutableMap<String, Boolean>,
        path: String,
        showHidden: Boolean,
    ) {
        val normalized = normalize(path)
        if (normalized.isEmpty()) return
        val childPrefix = if (normalized == "/") "/" else "$normalized/"
        val iter = rules.keys.iterator()
        while (iter.hasNext()) {
            val existing = iter.next()
            if (existing.startsWith(childPrefix)) {
                iter.remove()
            }
        }
        rules[normalized] = showHidden
    }
}

object FolderHiddenPrefsStore {
    private const val PREFS_NAME = "monolith_folder_hidden_prefs"
    private const val KEY_RULES_JSON = "folder_hidden_rules_json"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun isShowHidden(
        context: Context,
        path: String,
        ancestorPaths: List<String> = emptyList(),
    ): Boolean {
        if (path.isBlank()) return false
        return FolderHiddenRules.isShowHidden(loadRules(context), path, ancestorPaths)
    }

    @Synchronized
    fun setShowHidden(
        context: Context,
        path: String,
        showHidden: Boolean,
    ) {
        if (path.isBlank()) return
        val rules = loadRules(context).toMutableMap()
        FolderHiddenRules.setShowHidden(rules, path, showHidden)
        saveRules(context, rules)
    }

    private fun loadRules(context: Context): Map<String, Boolean> {
        val raw = prefs(context).getString(KEY_RULES_JSON, null).orEmpty()
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            buildMap {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    put(k, obj.optBoolean(k, false))
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun saveRules(context: Context, rules: Map<String, Boolean>) {
        val obj = JSONObject()
        rules.forEach { (k, v) -> obj.put(k, v) }
        prefs(context).edit().putString(KEY_RULES_JSON, obj.toString()).apply()
    }
}
