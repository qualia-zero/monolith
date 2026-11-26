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
        sb.append(encodeSegment(cleanShare)).append('/')
        val cleanRel = relativePath.trim('/')
        if (cleanRel.isNotEmpty()) {
            val encodedSegments = cleanRel.split('/')
                .filter { it.isNotEmpty() }
                .joinToString("/") { encodeSegment(it) }
            sb.append(encodedSegments)
            if (isDirectory) {
                sb.append('/')
            }
        }
        return sb.toString()
    }

    private fun encodeSegment(segment: String): String {
        val bytes = segment.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (isUnreserved(c)) {
                out.append(c.toChar())
            } else {
                out.append('%')
                out.append(HEX[(c ushr 4) and 0x0F])
                out.append(HEX[c and 0x0F])
            }
        }
        return out.toString()
    }

    private fun isUnreserved(c: Int): Boolean =
        (c in 'a'.code..'z'.code) ||
            (c in 'A'.code..'Z'.code) ||
            (c in '0'.code..'9'.code) ||
            c == '-'.code ||
            c == '.'.code ||
            c == '_'.code ||
            c == '~'.code

    private val HEX = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'A', 'B', 'C', 'D', 'E', 'F',
    )
}
