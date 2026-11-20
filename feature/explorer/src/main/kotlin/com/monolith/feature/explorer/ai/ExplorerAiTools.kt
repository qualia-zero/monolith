package com.monolith.feature.explorer.ai

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.webkit.MimeTypeMap
import com.monolith.feature.explorer.archive.ArchiveEngine
import com.monolith.feature.explorer.archive.formatArchiveBytes
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Local file explorer and optional write/edit/terminal tools exposed to Gemini via function calling.
 */
object ExplorerAiTools {

    private const val MAX_LIST_ITEMS = 80
    private const val MAX_SEARCH_RESULTS = 40
    private const val MAX_READ_CHARS = 24_000
    private const val MAX_SEARCH_DEPTH = 4

    fun toolDeclarationsJson(
        writeToolsEnabled: Boolean = false,
        terminalEnabled: Boolean = false,
    ): JSONArray {
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
            put(
                JSONObject().apply {
                    put("type", "function")
                    put("name", "list_archive_contents")
                    put(
                        "description",
                        "Lists entries and multi-volume/encryption metadata inside a local archive " +
                            "(ZIP, 7z, RAR, TAR.GZ, TAR.XZ, or split parts like .z01/.001/.part1.rar).",
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
                                            put("description", "Absolute or relative path to the archive file.")
                                        },
                                    )
                                    put(
                                        "password",
                                        JSONObject().apply {
                                            put("type", "string")
                                            put("description", "Optional password for encrypted archives.")
                                        },
                                    )
                                },
                            )
                            put("required", JSONArray().put("path"))
                        },
                    )
                },
            )
            if (writeToolsEnabled) {
                put(
                    JSONObject().apply {
                        put("type", "function")
                        put("name", "write_text_file")
                        put(
                            "description",
                            "Creates, overwrites, or appends to a local UTF-8 text file (creating parent directories if needed).",
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
                                                put("description", "Absolute or relative file path.")
                                            },
                                        )
                                        put(
                                            "content",
                                            JSONObject().apply {
                                                put("type", "string")
                                                put("description", "UTF-8 text content to write.")
                                            },
                                        )
                                        put(
                                            "append",
                                            JSONObject().apply {
                                                put("type", "boolean")
                                                put("description", "If true, appends to the file instead of overwriting.")
                                            },
                                        )
                                    },
                                )
                                put("required", JSONArray().put("path").put("content"))
                            },
                        )
                    },
                )
                put(
                    JSONObject().apply {
                        put("type", "function")
                        put("name", "edit_text_file")
                        put(
                            "description",
                            "Replaces old_text with new_text inside an existing local UTF-8 text file.",
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
                                                put("description", "Absolute or relative file path.")
                                            },
                                        )
                                        put(
                                            "old_text",
                                            JSONObject().apply {
                                                put("type", "string")
                                                put("description", "Exact existing text substring to replace.")
                                            },
                                        )
                                        put(
                                            "new_text",
                                            JSONObject().apply {
                                                put("type", "string")
                                                put("description", "Replacement text.")
                                            },
                                        )
                                        put(
                                            "replace_all",
                                            JSONObject().apply {
                                                put("type", "boolean")
                                                put("description", "If true, replaces all occurrences; otherwise only the first.")
                                            },
                                        )
                                    },
                                )
                                put("required", JSONArray().put("path").put("old_text").put("new_text"))
                            },
                        )
                    },
                )
                put(
                    JSONObject().apply {
                        put("type", "function")
                        put("name", "create_directory")
                        put("description", "Creates a local directory including parent directories.")
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
                                                put("description", "Absolute or relative directory path.")
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
                        put("name", "move_or_rename_path")
                        put("description", "Moves or renames a local file or directory.")
                        put(
                            "parameters",
                            JSONObject().apply {
                                put("type", "object")
                                put(
                                    "properties",
                                    JSONObject().apply {
                                        put(
                                            "source_path",
                                            JSONObject().apply {
                                                put("type", "string")
                                                put("description", "Existing source file or directory path.")
                                            },
                                        )
                                        put(
                                            "destination_path",
                                            JSONObject().apply {
                                                put("type", "string")
                                                put("description", "Target destination path.")
                                            },
                                        )
                                    },
                                )
                                put("required", JSONArray().put("source_path").put("destination_path"))
                            },
                        )
                    },
                )
                put(
                    JSONObject().apply {
                        put("type", "function")
                        put("name", "delete_path")
                        put("description", "Deletes a local file or directory.")
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
                                                put("description", "File or directory path to delete.")
                                            },
                                        )
                                        put(
                                            "recursive",
                                            JSONObject().apply {
                                                put("type", "boolean")
                                                put("description", "Set true to delete non-empty directories recursively.")
                                            },
                                        )
                                    },
                                )
                                put("required", JSONArray().put("path"))
                            },
                        )
                    },
                )
                if (terminalEnabled) {
                    put(
                        JSONObject().apply {
                            put("type", "function")
                            put("name", "run_terminal_command")
                            put(
                                "description",
                                "Executes an Android shell command via /system/bin/sh -c and returns exit_code, stdout, and stderr.",
                            )
                            put(
                                "parameters",
                                JSONObject().apply {
                                    put("type", "object")
                                    put(
                                        "properties",
                                    JSONObject().apply {
                                            put(
                                                "command",
                                                JSONObject().apply {
                                                    put("type", "string")
                                                    put("description", "Shell command string to execute.")
                                                },
                                            )
                                            put(
                                                "working_dir",
                                                JSONObject().apply {
                                                    put("type", "string")
                                                    put("description", "Optional working directory (defaults to current directory).")
                                                },
                                            )
                                            put(
                                                "timeout_sec",
                                                JSONObject().apply {
                                                    put("type", "integer")
                                                    put("description", "Optional timeout in seconds (1..30, default 15).")
                                                },
                                            )
                                        },
                                    )
                                    put("required", JSONArray().put("command"))
                                },
                            )
                        },
                    )
                }
            }
        }
    }

    suspend fun execute(
        name: String,
        arguments: JSONObject,
        currentDirectory: String,
        writeToolsEnabled: Boolean = false,
        terminalEnabled: Boolean = false,
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
                "list_archive_contents" -> {
                    val rawPath = arguments.optString("path").trim()
                    val path = if (rawPath.isEmpty()) "" else resolvePath(rawPath, defaultDir)
                    val password = arguments.optString("password").takeIf { it.isNotEmpty() }
                    listArchiveContents(path, password)
                }
                "write_text_file" -> {
                    if (!writeToolsEnabled) return@runCatching JSONObject().put("error", "Write tools are disabled in Settings").toString()
                    val rawPath = arguments.optString("path").trim()
                    val path = if (rawPath.isEmpty()) "" else resolveTargetPath(rawPath, defaultDir)
                    val content = arguments.optString("content")
                    val append = arguments.optBoolean("append", false)
                    writeTextFile(path, content, append)
                }
                "edit_text_file" -> {
                    if (!writeToolsEnabled) return@runCatching JSONObject().put("error", "Write tools are disabled in Settings").toString()
                    val rawPath = arguments.optString("path").trim()
                    val path = if (rawPath.isEmpty()) "" else resolvePath(rawPath, defaultDir)
                    val oldText = arguments.optString("old_text")
                    val newText = arguments.optString("new_text")
                    val replaceAll = arguments.optBoolean("replace_all", false)
                    editTextFile(path, oldText, newText, replaceAll)
                }
                "create_directory" -> {
                    if (!writeToolsEnabled) return@runCatching JSONObject().put("error", "Write tools are disabled in Settings").toString()
                    val rawPath = arguments.optString("path").trim()
                    val path = if (rawPath.isEmpty()) "" else resolveTargetPath(rawPath, defaultDir)
                    createDirectory(path)
                }
                "move_or_rename_path" -> {
                    if (!writeToolsEnabled) return@runCatching JSONObject().put("error", "Write tools are disabled in Settings").toString()
                    val rawSource = arguments.optString("source_path").trim()
                    val rawDest = arguments.optString("destination_path").trim()
                    val sourcePath = if (rawSource.isEmpty()) "" else resolvePath(rawSource, defaultDir)
                    val destPath = if (rawDest.isEmpty()) "" else resolveTargetPath(rawDest, defaultDir)
                    moveOrRenamePath(sourcePath, destPath)
                }
                "delete_path" -> {
                    if (!writeToolsEnabled) return@runCatching JSONObject().put("error", "Write tools are disabled in Settings").toString()
                    val rawPath = arguments.optString("path").trim()
                    val path = if (rawPath.isEmpty()) "" else resolvePath(rawPath, defaultDir)
                    val recursive = arguments.optBoolean("recursive", false)
                    deletePath(path, recursive)
                }
                "run_terminal_command" -> {
                    if (!writeToolsEnabled || !terminalEnabled) {
                        return@runCatching JSONObject().put("error", "Terminal access is disabled in Settings").toString()
                    }
                    val command = arguments.optString("command")
                    val rawWorkingDir = arguments.optString("working_dir").trim()
                    val workingDir = if (rawWorkingDir.isEmpty()) defaultDir else resolvePath(rawWorkingDir, defaultDir)
                    val timeoutSec = arguments.optInt("timeout_sec", 15).coerceIn(1, 30)
                    runTerminalCommand(command, workingDir, timeoutSec)
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

    private suspend fun listArchiveContents(path: String, password: String?): String {
        if (path.isBlank()) return JSONObject().put("error", "Missing archive path").toString()
        val file = File(path)
        if (!file.exists()) return JSONObject().put("error", "Archive not found: $path").toString()
        val summary = ArchiveEngine.inspectArchive(file, password).getOrElse { err ->
            return JSONObject().put("error", err.message ?: "Archive inspection failed").toString()
        }
        val items = JSONArray()
        summary.entries.take(MAX_LIST_ITEMS).forEach { entry ->
            val prefix = if (entry.isDirectory) "D" else "F"
            val lock = if (entry.isEncrypted) " [enc]" else ""
            val sizeInfo = if (entry.isDirectory) "" else " (${formatArchiveBytes(entry.uncompressedSize)})"
            items.put("$prefix ${entry.path}$sizeInfo$lock")
        }
        return JSONObject().apply {
            put("archive", summary.group.displayName)
            put("format", summary.group.format.label)
            put("parts", summary.group.parts.size)
            put("missing_parts", summary.group.isMissingParts)
            put("encrypted", summary.isEncrypted)
            put("requires_password_to_list", summary.requiresPasswordToList)
            put("files", summary.fileCount)
            put("folders", summary.folderCount)
            put("total_uncompressed", formatArchiveBytes(summary.totalUncompressedBytes))
            if (summary.entries.size > MAX_LIST_ITEMS) put("truncated", true)
            put("entries", items)
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

    private fun resolveTargetPath(rawPath: String, defaultDir: String): String {
        if (rawPath.isBlank()) return defaultDir
        return if (rawPath.startsWith("/")) {
            File(rawPath).absolutePath
        } else {
            File(defaultDir, rawPath).absolutePath
        }
    }

    private fun writeTextFile(path: String, content: String, append: Boolean): String {
        if (path.isBlank()) return JSONObject().put("error", "Missing path").toString()
        val file = File(path)
        file.parentFile?.mkdirs()
        if (append) {
            file.appendText(content, Charsets.UTF_8)
        } else {
            file.writeText(content, Charsets.UTF_8)
        }
        return JSONObject().apply {
            put("status", "ok")
            put("path", file.absolutePath)
            put("append", append)
            put("size_bytes", file.length())
        }.toString()
    }

    private fun editTextFile(path: String, oldText: String, newText: String, replaceAll: Boolean): String {
        if (path.isBlank()) return JSONObject().put("error", "Missing path").toString()
        if (oldText.isEmpty()) return JSONObject().put("error", "old_text must not be empty").toString()
        val file = File(path)
        if (!file.exists() || !file.isFile) {
            return JSONObject().put("error", "File not found: $path").toString()
        }
        val original = file.readText(Charsets.UTF_8)
        if (!original.contains(oldText)) {
            return JSONObject().put("error", "old_text not found in file: $path").toString()
        }
        var replacements = 0
        val updated = if (replaceAll) {
            replacements = original.split(oldText).size - 1
            original.replace(oldText, newText)
        } else {
            replacements = 1
            original.replaceFirst(oldText, newText)
        }
        file.writeText(updated, Charsets.UTF_8)
        return JSONObject().apply {
            put("status", "ok")
            put("path", file.absolutePath)
            put("replacements", replacements)
            put("size_bytes", file.length())
        }.toString()
    }

    private fun createDirectory(path: String): String {
        if (path.isBlank()) return JSONObject().put("error", "Missing path").toString()
        val dir = File(path)
        val existed = dir.isDirectory
        val created = dir.mkdirs() || dir.isDirectory
        if (!created) {
            return JSONObject().put("error", "Failed to create directory: $path").toString()
        }
        return JSONObject().apply {
            put("status", "ok")
            put("path", dir.absolutePath)
            put("already_existed", existed)
        }.toString()
    }

    private fun moveOrRenamePath(sourcePath: String, destPath: String): String {
        if (sourcePath.isBlank() || destPath.isBlank()) {
            return JSONObject().put("error", "Missing source_path or destination_path").toString()
        }
        val src = File(sourcePath)
        if (!src.exists()) {
            return JSONObject().put("error", "Source does not exist: $sourcePath").toString()
        }
        val dst = File(destPath)
        dst.parentFile?.mkdirs()
        val moved = src.renameTo(dst) || runCatching {
            src.copyRecursively(dst, overwrite = true) && src.deleteRecursively()
        }.getOrDefault(false)
        if (!moved) {
            return JSONObject().put("error", "Failed to move $sourcePath to $destPath").toString()
        }
        return JSONObject().apply {
            put("status", "ok")
            put("source_path", src.absolutePath)
            put("destination_path", dst.absolutePath)
        }.toString()
    }

    private fun deletePath(path: String, recursive: Boolean): String {
        if (path.isBlank()) return JSONObject().put("error", "Missing path").toString()
        val target = File(path)
        if (!target.exists()) {
            return JSONObject().put("error", "Path does not exist: $path").toString()
        }
        if (target.isDirectory && !recursive && (target.listFiles()?.isNotEmpty() == true)) {
            return JSONObject().put("error", "Directory is not empty (set recursive=true to delete): $path").toString()
        }
        val deleted = if (recursive) target.deleteRecursively() else target.delete()
        if (!deleted) {
            return JSONObject().put("error", "Failed to delete: $path").toString()
        }
        return JSONObject().apply {
            put("status", "ok")
            put("deleted", target.absolutePath)
        }.toString()
    }

    private fun runTerminalCommand(command: String, workingDir: String, timeoutSec: Int): String {
        if (command.isBlank()) return JSONObject().put("error", "Missing command").toString()
        val workDirFile = File(workingDir).takeIf { it.isDirectory }
            ?: Environment.getExternalStorageDirectory()
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .directory(workDirFile)
            .start()
        val stdoutBuf = StringBuilder()
        val stderrBuf = StringBuilder()
        val outThread = thread(start = true, name = "monolith-sh-stdout") {
            runCatching {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val buf = CharArray(2048)
                    var n: Int
                    while (reader.read(buf).also { n = it } != -1) {
                        if (stdoutBuf.length < MAX_READ_CHARS) {
                            stdoutBuf.append(buf, 0, minOf(n, MAX_READ_CHARS - stdoutBuf.length))
                        }
                    }
                }
            }
        }
        val errThread = thread(start = true, name = "monolith-sh-stderr") {
            runCatching {
                process.errorStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val buf = CharArray(2048)
                    var n: Int
                    while (reader.read(buf).also { n = it } != -1) {
                        if (stderrBuf.length < MAX_READ_CHARS) {
                            stderrBuf.append(buf, 0, minOf(n, MAX_READ_CHARS - stderrBuf.length))
                        }
                    }
                }
            }
        }
        val finished = process.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
        }
        outThread.join(1000L)
        errThread.join(1000L)
        return JSONObject().apply {
            put("working_dir", workDirFile.absolutePath)
            put("timed_out", !finished)
            put("exit_code", if (finished) process.exitValue() else -1)
            put("stdout", stdoutBuf.toString())
            put("stderr", stderrBuf.toString())
        }.toString()
    }
}
