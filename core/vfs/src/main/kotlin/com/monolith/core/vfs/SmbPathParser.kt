package com.monolith.core.vfs

data class ParsedSmbPath(
    val serverId: String,
    val share: String?,
    val relativePath: String,
)

object SmbPathParser {
    const val SCHEME_PREFIX = "smb://"

    fun parse(path: String): ParsedSmbPath? {
        if (!path.startsWith(SCHEME_PREFIX, ignoreCase = true)) return null
        val rest = path.substring(SCHEME_PREFIX.length).trim('/')
        if (rest.isEmpty()) return null
        val segments = rest.split('/').filter { it.isNotEmpty() }
        val serverId = segments.firstOrNull() ?: return null
        val share = segments.getOrNull(1)
        val relative = if (segments.size > 2) {
            segments.drop(2).joinToString("/")
        } else {
            ""
        }
        return ParsedSmbPath(
            serverId = serverId,
            share = share,
            relativePath = relative,
        )
    }

    fun buildPath(
        serverId: String,
        share: String? = null,
        relativePath: String = "",
        isDirectory: Boolean = false,
    ): String {
        val sb = StringBuilder(SCHEME_PREFIX).append(serverId.trim('/'))
        val cleanShare = share?.trim('/')?.takeIf { it.isNotEmpty() }
        if (cleanShare == null) {
            sb.append('/')
            return sb.toString()
        }
        sb.append('/').append(cleanShare)
        val cleanRel = relativePath.trim('/')
        if (cleanRel.isNotEmpty()) {
            sb.append('/').append(cleanRel)
        }
        if (isDirectory) {
            sb.append('/')
        }
        return sb.toString()
    }

    fun parentPath(path: String): String? {
        val parsed = parse(path) ?: return null
        if (parsed.share == null) return null
        if (parsed.relativePath.isEmpty()) {
            return buildPath(parsed.serverId, isDirectory = true)
        }
        val parentRel = parsed.relativePath.substringBeforeLast('/', "")
        return buildPath(parsed.serverId, parsed.share, parentRel, isDirectory = true)
    }

    fun encodeSmbUrl(
        host: String,
        port: Int,
        share: String?,
        relativePath: String,
        isDirectory: Boolean,
    ): String {
        val sb = StringBuilder("smb://").append(host.trim())
        if (port != 445) {
            sb.append(':').append(port)
        }
        sb.append('/')
        val cleanShare = share?.trim('/')?.takeIf { it.isNotEmpty() } ?: return sb.toString()
        sb.append(cleanShare).append('/')
        val cleanRel = relativePath.trim('/')
        if (cleanRel.isNotEmpty()) {
            val rawSegments = cleanRel.split('/')
                .filter { it.isNotEmpty() }
                .joinToString("/")
            sb.append(rawSegments)
            if (isDirectory) {
                sb.append('/')
            }
        }
        return sb.toString()
    }
}
