package com.monolith.core.vfs

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SmbServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val host: String,
    val port: Int = 445,
    val share: String = "",
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    val isGuest: Boolean = true,
) {
    fun rootPath(): String {
        val cleanShare = share.trim('/')
        return if (cleanShare.isNotEmpty()) {
            "smb://$id/$cleanShare/"
        } else {
            "smb://$id/"
        }
    }

    fun effectiveDisplayName(): String =
        displayName.trim().ifEmpty { host.trim() }

    fun endpointKey(): String =
        "${host.trim().lowercase()}:$port/${share.trim('/').lowercase()}"
}

object SmbConnectionStore {
    private const val PREFS_NAME = "monolith_smb_connections"
    private const val KEY_SERVERS_JSON = "smb_servers_json"
    private const val KEY_ACTIVE_ID = "active_smb_id"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getServers(context: Context): List<SmbServerConfig> {
        val raw = prefs(context).getString(KEY_SERVERS_JSON, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val host = obj.optString("host", "").trim()
                    val id = obj.optString("id", "").trim()
                    if (host.isEmpty() || id.isEmpty()) continue
                    add(
                        SmbServerConfig(
                            id = id,
                            displayName = obj.optString("displayName", host),
                            host = host,
                            port = obj.optInt("port", 445).coerceIn(1, 65535),
                            share = obj.optString("share", ""),
                            username = obj.optString("username", ""),
                            password = obj.optString("password", ""),
                            domain = obj.optString("domain", ""),
                            isGuest = obj.optBoolean("isGuest", true),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun getServer(context: Context, id: String): SmbServerConfig? =
        getServers(context).firstOrNull { it.id == id }

    fun getActiveServerId(context: Context): String? {
        val servers = getServers(context)
        if (servers.isEmpty()) return null
        val saved = prefs(context).getString(KEY_ACTIVE_ID, null)
        return if (saved != null && servers.any { it.id == saved }) saved else servers.first().id
    }

    fun getActiveServer(context: Context): SmbServerConfig? {
        val id = getActiveServerId(context) ?: return null
        return getServer(context, id)
    }

    fun setActiveServerId(context: Context, id: String) {
        prefs(context).edit().putString(KEY_ACTIVE_ID, id).apply()
    }

    fun addOrUpdateServer(context: Context, config: SmbServerConfig): SmbServerConfig {
        val current = getServers(context).toMutableList()
        val normalized = config.copy(
            displayName = config.effectiveDisplayName(),
            host = config.host.trim(),
            port = config.port.coerceIn(1, 65535),
            share = config.share.trim('/'),
            username = config.username.trim(),
            domain = config.domain.trim(),
        )
        val existingIdx = current.indexOfFirst { it.id == normalized.id }
        if (existingIdx >= 0) {
            current[existingIdx] = normalized
        } else {
            current.add(normalized)
        }
        saveServers(context, current)
        setActiveServerId(context, normalized.id)
        return normalized
    }

    fun addServersIfMissing(context: Context, configs: List<SmbServerConfig>): List<SmbServerConfig> {
        if (configs.isEmpty()) return emptyList()
        val current = getServers(context).toMutableList()
        val existingKeys = current.map { it.endpointKey() }.toHashSet()
        val added = mutableListOf<SmbServerConfig>()
        for (raw in configs) {
            val normalized = raw.copy(
                displayName = raw.effectiveDisplayName(),
                host = raw.host.trim(),
                port = raw.port.coerceIn(1, 65535),
                share = raw.share.trim('/'),
                username = raw.username.trim(),
                domain = raw.domain.trim(),
            )
            if (normalized.host.isEmpty()) continue
            if (existingKeys.add(normalized.endpointKey())) {
                current.add(normalized)
                added.add(normalized)
            }
        }
        if (added.isNotEmpty()) {
            saveServers(context, current)
            if (prefs(context).getString(KEY_ACTIVE_ID, null).isNullOrBlank()) {
                setActiveServerId(context, added.first().id)
            }
        }
        return added
    }

    fun removeServer(context: Context, id: String) {
        val updated = getServers(context).filterNot { it.id == id }
        saveServers(context, updated)
        val active = prefs(context).getString(KEY_ACTIVE_ID, null)
        if (active == id) {
            val next = updated.firstOrNull()?.id
            prefs(context).edit().putString(KEY_ACTIVE_ID, next).apply()
        }
    }

    fun saveServerOrder(context: Context, orderedIds: List<String>) {
        val current = getServers(context)
        val byId = current.associateBy { it.id }
        val ordered = orderedIds.mapNotNull { byId[it] }
        val remaining = current.filter { it.id !in orderedIds.toSet() }
        saveServers(context, ordered + remaining)
    }

    private fun saveServers(context: Context, servers: List<SmbServerConfig>) {
        val arr = JSONArray()
        servers.forEach { s ->
            arr.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("displayName", s.displayName)
                    put("host", s.host)
                    put("port", s.port)
                    put("share", s.share)
                    put("username", s.username)
                    put("password", s.password)
                    put("domain", s.domain)
                    put("isGuest", s.isGuest)
                },
            )
        }
        prefs(context).edit().putString(KEY_SERVERS_JSON, arr.toString()).apply()
    }
}
