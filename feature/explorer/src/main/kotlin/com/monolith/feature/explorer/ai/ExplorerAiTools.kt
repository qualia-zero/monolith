package com.monolith.feature.explorer.ai

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read-only local file explorer tools exposed to Gemini via function calling.
 */
object ExplorerAiTools {

    private const val MAX_LIST_ITEMS = 80
    private const val MAX_SEARCH_RESULTS = 40
    private const val MAX_READ_CHARS = 24_000
    private const val MAX_SEARCH_DEPTH = 4

    fun toolDeclarationsJson(): JSONArray {
        return JSONArray().apply {
            put(
                JSONObject().apply {
                    put("type", "function")
                    put("name", "list_directory")
                    put(
                        "description",
                        "Lists files and subdirectories in a local directory. " +
                            "Use this when the user asks about files or folders on their device. " +
                            "If path is omitted or empty, lists the user's current directory in Monolith.",
                    )
                    put(
                        "parameters",
                        JSONObject().apply {
                            put("type", "object")
                            put(
                                "properties",
                                JSONObject().apply {
                                    put(
                                        "path",
                                        JSONObject().apply {
                                            put("type", "string")
                                            put("description", "Absolute directory path. Leave empty for the current directory.")
                                        },
                                    )
                                    put(
                                        "include_hidden",
                                        JSONObject().apply {
                                            put("type", "boolean")
                                            put("description", "Whether to include hidden dot-files. Default false.")
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
            put(
                JSONObject().apply {
                    put("type", "function")
                    put("name", "read_text_file")
                    put(
                        "description",
                        "Reads the UTF-8 text content of a local file (e.g. txt, md, json, xml, log, code). " +
                            "Truncates very large files.",
                    )
                    put(
                        "parameters",
                        JSONObject().apply {
                            put("type", "object")
                            put(
                                "properties",
                                JSONObject().apply {
                                    put(
                                        "path",
                                        JSONObject().apply {
                                            put("type", "string")
                                            put("description", "Absolute file path to read.")
                                        },
                                    )
                                    put(
                                        "max_chars",
                                        JSONObject().apply {
                                            put("type", "integer")
                                            put("description", "Maximum characters to read (default 12000, max 24000).")
                                        },
                                    )
                                },
                            )
                            put("required", JSONArray().put("path"))
                        },
                    )
                },
            )
            put(
                JSONObject().apply {
                    put("type", "function")
                    put("name", "get_file_info")
                    put(
                        "description",
                        "Returns compact metadata (size, last modified, mime type, and for media/images: " +
                            "duration, resolution, title, artist, album, codec/bitrate) without long text fields.",
                    )
                    put(
                        "parameters",
                        JSONObject().apply {
                            put("type", "object")
                            put(
                                "properties",
                                JSONObject().apply {
                                    put(
                                        "path",
                                        JSONObject().apply {
                                            put("type", "string")
                                            put("description", "Absolute file or directory path.")
                                        },
                                    )
                                },
                            )
                            put("required", JSONArray().put("path"))
                        },
                    )
                },
            )
            put(
                JSONObject().apply {
                    put("type", "function")
                    put("name", "search_files")
                    put(
                        "description",
                        "Searches for files and directories whose names contain the given query string.",
                    )
                    put(
                        "parameters",
                        JSONObject().apply {
                            put("type", "object")
                            put(
                                "properties",
                                JSONObject().apply {
                                    put(
                                        "query",
                                        JSONObject().apply {
                                            put("type", "string")
                                            put("description", "Case-insensitive substring to match in file/folder names.")
                                        },
                                    )
                                    put(
                                        "directory",
                                        JSONObject().apply {
                                            put("type", "string")
                                            put("description", "Starting directory path. Leave empty for the current directory.")
                                        },
                                    )
                                },
                            )
                            put("required", JSONArray().put("query"))
                        },
                    )
                },
            )
        }
    }

    fun execute(
        name: String,
        arguments: JSONObject,
        currentDirectory: String,
    ): String {
        val defaultDir = currentDirectory.ifBlank {
            Environment.getExternalStorageDirectory().absolutePath
        }
        return runCatching {
            when (name) {
                "list_directory" -> {
                    val rawPath = arguments.optString("path").trim()
                    val path = resolvePath(rawPath, defaultDir)
                    val includeHidden = arguments.optBoolean("include_hidden", false)
                    listDirectory(path, includeHidden)
                }
                "read_text_file" -> {
                    val rawPath = arguments.optString("path").trim()
                    val path = if (rawPath.isEmpty()) "" else resolvePath(rawPath, defaultDir)
                    val maxChars = arguments.optInt("max_chars", 12_000).coerceIn(256, MAX_READ_CHARS)
                    readTextFile(path, maxChars)
                }
                "get_file_info" -> {
                    val rawPath = arguments.optString("path").trim()
                    val path = if (rawPath.isEmpty()) "" else resolvePath(rawPath, defaultDir)
                    getFileInfo(path)
                }
                "search_files" -> {
                    val query = arguments.optString("query")
                    val rawDir = arguments.optString("directory").trim()
                    val dir = resolvePath(rawDir, defaultDir)
                    searchFiles(query, dir)
                }
                else -> JSONObject().put("error", "Unknown tool: $name").toString()
            }
        }.getOrElse { err ->
            JSONObject().put("error", err.message ?: "Tool execution failed").toString()
        }
    }

    private fun resolvePath(rawPath: String, defaultDir: String): String {
        if (rawPath.isBlank()) return defaultDir
        val candidate = if (rawPath.startsWith("/")) {
            File(rawPath)
        } else {
            File(defaultDir, rawPath)
        }
        if (candidate.exists()) return candidate.absolutePath
        // Fallback: case-insensitive match in parent directory
        val parent = candidate.parentFile
        val targetName = candidate.name
        if (parent != null && parent.isDirectory) {
            val match = parent.listFiles()?.firstOrNull {
                it.name.equals(targetName, ignoreCase = true)
            }
            if (match != null) return match.absolutePath
        }
        return candidate.absolutePath
    }

    private fun listDirectory(path: String, includeHidden: Boolean): String {
        val dir = File(path)
        if (!dir.exists()) return JSONObject().put("error", "Path does not exist: $path").toString()
        if (!dir.isDirectory) return JSONObject().put("error", "Path is not a directory: $path").toString()

        val children = dir.listFiles()
            ?.filter { includeHidden || !it.name.startsWith(".") }
            ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?: return JSONObject().put("error", "Permission denied or unreadable directory: $path").toString()

        val items = JSONArray()
        children.take(MAX_LIST_ITEMS).forEach { file ->
            val entry = if (file.isDirectory) {
                "D ${file.name}"
            } else {
                "F ${file.name} (${formatCompactSize(file.length())})"
            }
            items.put(entry)
        }

        return JSONObject().apply {
            put("dir", dir.absolutePath)
            put("total", children.size)
            if (children.size > MAX_LIST_ITEMS) put("truncated", true)
            put("entries", items)
        }.toString()
    }

    private fun readTextFile(path: String, maxChars: Int): String {
        if (path.isBlank()) return JSONObject().put("error", "Missing path").toString()
        val file = File(path)
        if (!file.exists() || !file.isFile) {
            return JSONObject().put("error", "File not found: $path").toString()
        }
        val text = file.bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(maxChars)
            val read = reader.read(buffer)
            if (read <= 0) "" else String(buffer, 0, read)
        }
        return JSONObject().apply {
            put("path", file.absolutePath)
            put("size_bytes", file.length())
            put("truncated", file.length() > maxChars)
            put("content", text)
        }.toString()
    }

    private fun getFileInfo(path: String): String {
        if (path.isBlank()) return JSONObject().put("error", "Missing path").toString()
        val file = File(path)
        if (!file.exists()) return JSONObject().put("error", "Path does not exist: $path").toString()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val ext = file.extension.lowercase(Locale.US)
        val mimeType = if (file.isFile && ext.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        } else {
            null
        }

        return JSONObject().apply {
            put("path", file.absolutePath)
            put("type", if (file.isDirectory) "dir" else "file")
            if (ext.isNotEmpty()) put("ext", ext)
            if (!mimeType.isNullOrBlank()) put("mime", mimeType)
            put("size", formatCompactSize(file.length()))
            put("modified", dateFormat.format(Date(file.lastModified())))
            if (!file.canRead()) put("readable", false)
            if (!file.canWrite()) put("writable", false)

            if (file.isFile && mimeType != null) {
                when {
                    mimeType.startsWith("image/") -> {
                        runCatching {
                            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(file.absolutePath, opts)
                            if (opts.outWidth > 0 && opts.outHeight > 0) {
                                put("width", opts.outWidth)
                                put("height", opts.outHeight)
                            }
                        }
                    }
                    mimeType.startsWith("audio/") || mimeType.startsWith("video/") -> {
                        val retriever = MediaMetadataRetriever()
                        runCatching {
                            retriever.setDataSource(file.absolutePath)
                            fun putShort(key: String, metaKey: Int) {
                                retriever.extractMetadata(metaKey)
                                    ?.trim()
                                    ?.takeIf { it.isNotEmpty() }
                                    ?.take(120)
                                    ?.let { put(key, it) }
                            }
                            putShort("title", MediaMetadataRetriever.METADATA_KEY_TITLE)
                            putShort("artist", MediaMetadataRetriever.METADATA_KEY_ARTIST)
                            putShort("album", MediaMetadataRetriever.METADATA_KEY_ALBUM)
                            putShort("year", MediaMetadataRetriever.METADATA_KEY_YEAR)
                            putShort("genre", MediaMetadataRetriever.METADATA_KEY_GENRE)
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                ?.toLongOrNull()
                                ?.let { put("duration_ms", it) }
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                                ?.toLongOrNull()
                                ?.let { put("bitrate", it) }
                            if (mimeType.startsWith("video/")) {
                                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                                    ?.toIntOrNull()
                                    ?.let { put("width", it) }
                                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                                    ?.toIntOrNull()
                                    ?.let { put("height", it) }
                            }
                        }
                        runCatching { retriever.release() }
                    }
                }
            }
        }.toString()
    }

    private fun searchFiles(query: String, directory: String): String {
        if (query.isBlank()) return JSONObject().put("error", "Query must not be empty").toString()
        val root = File(directory)
        if (!root.exists() || !root.isDirectory) {
            return JSONObject().put("error", "Directory not found: $directory").toString()
        }

        val matches = JSONArray()
        val lowerQuery = query.lowercase()
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.add(root to 0)

        val rootPrefix = root.absolutePath.trimEnd('/') + "/"
        while (queue.isNotEmpty() && matches.length() < MAX_SEARCH_RESULTS) {
            val (dir, depth) = queue.removeFirst()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (child.name.startsWith(".")) continue
                if (child.name.lowercase().contains(lowerQuery)) {
                    val relPath = child.absolutePath.removePrefix(rootPrefix)
                    val entry = if (child.isDirectory) {
                        "D $relPath"
                    } else {
                        "F $relPath (${formatCompactSize(child.length())})"
                    }
                    matches.put(entry)
                    if (matches.length() >= MAX_SEARCH_RESULTS) break
                }
                if (child.isDirectory && depth < MAX_SEARCH_DEPTH) {
                    queue.add(child to (depth + 1))
                }
            }
        }

        return JSONObject().apply {
            put("root", root.absolutePath)
            put("query", query)
            put("count", matches.length())
            put("results", matches)
        }.toString()
    }

    private fun formatCompactSize(bytes: Long): String {
        return when {
            bytes < 1024L -> "${bytes}B"
            bytes < 1024L * 1024L -> "${bytes / 1024L}KB"
            bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1fMB", bytes / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.2fGB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
