package com.monolith.core.common

import android.content.Context
import android.content.SharedPreferences
import com.monolith.core.model.DefaultAppAssociation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

object DefaultAppsStore {
    private const val PREFS_NAME = "monolith_default_apps"
    private const val KEY_ASSOCIATIONS = "associations_json"

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _associations = MutableStateFlow<List<DefaultAppAssociation>>(emptyList())
    val associations: StateFlow<List<DefaultAppAssociation>> = _associations.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = sp
            _associations.value = loadFromPrefs(sp)
        }
    }

    fun getForExtension(extension: String): DefaultAppAssociation? {
        val norm = normalizeExt(extension)
        if (norm.isEmpty()) return null
        return _associations.value.firstOrNull { it.extension == norm }
    }

    fun setForExtension(association: DefaultAppAssociation) {
        val norm = normalizeExt(association.extension)
        if (norm.isEmpty()) return
        val normalized = association.copy(extension = norm)
        val updated = (_associations.value.filterNot { it.extension == norm } + normalized)
            .sortedBy { it.extension }
        _associations.value = updated
        saveToPrefs(updated)
    }

    fun removeForExtension(extension: String) {
        val norm = normalizeExt(extension)
        val updated = _associations.value.filterNot { it.extension == norm }
        _associations.value = updated
        saveToPrefs(updated)
    }

    fun clearAll() {
        _associations.value = emptyList()
        saveToPrefs(emptyList())
    }

    private fun normalizeExt(ext: String): String =
        ext.trim().removePrefix(".").lowercase(Locale.ROOT)

    private fun loadFromPrefs(sp: SharedPreferences): List<DefaultAppAssociation> {
        val raw = sp.getString(KEY_ASSOCIATIONS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val ext = normalizeExt(obj.optString("extension"))
                    val handlerId = obj.optString("handlerId")
                    val handlerLabel = obj.optString("handlerLabel")
                    if (ext.isNotEmpty() && handlerId.isNotEmpty() && handlerLabel.isNotEmpty()) {
                        add(
                            DefaultAppAssociation(
                                extension = ext,
                                handlerId = handlerId,
                                handlerLabel = handlerLabel,
                                packageName = obj.optString("packageName").takeIf { it.isNotBlank() },
                                activityName = obj.optString("activityName").takeIf { it.isNotBlank() },
                            ),
                        )
                    }
                }
            }.sortedBy { it.extension }
        }.getOrDefault(emptyList())
    }

    private fun saveToPrefs(list: List<DefaultAppAssociation>) {
        val arr = JSONArray()
        list.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("extension", item.extension)
                    put("handlerId", item.handlerId)
                    put("handlerLabel", item.handlerLabel)
                    if (!item.packageName.isNullOrBlank()) put("packageName", item.packageName)
                    if (!item.activityName.isNullOrBlank()) put("activityName", item.activityName)
                },
            )
        }
        prefs?.edit()?.putString(KEY_ASSOCIATIONS, arr.toString())?.apply()
    }
}
