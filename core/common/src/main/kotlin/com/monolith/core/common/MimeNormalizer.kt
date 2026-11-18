package com.monolith.core.common

import com.monolith.core.model.FileCategory
import java.util.Locale

/**
 * MIME resolution and container normalization across video, audio, image, document, and archive formats.
 */
object MimeNormalizer {
    private val videoExtensions = mapOf(
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "mkv" to "video/x-matroska",
        "mk3d" to "video/x-matroska",
        "webm" to "video/webm",
        "avi" to "video/x-msvideo",
        "mov" to "video/quicktime",
        "wmv" to "video/x-ms-wmv",
        "asf" to "video/x-ms-asf",
        "flv" to "video/x-flv",
        "ts" to "video/mp2t",
        "m2ts" to "video/mp2t",
        "mts" to "video/mp2t",
        "mpg" to "video/mpeg",
        "mpeg" to "video/mpeg",
        "mpeg4" to "video/mp4",
        "divx" to "video/divx",
        "rm" to "video/vnd.rn-realmedia",
        "rmvb" to "video/vnd.rn-realmedia-vbr",
        "3gp" to "video/3gpp",
        "3gpp" to "video/3gpp",
        "3g2" to "video/3gpp2",
        "3gpp2" to "video/3gpp2",
        "ogv" to "video/ogg",
    )

    private val audioExtensions = mapOf(
        "mp3" to "audio/mpeg",
        "flac" to "audio/flac",
        "m4a" to "audio/mp4",
        "aac" to "audio/aac",
        "ogg" to "audio/ogg",
        "oga" to "audio/ogg",
        "opus" to "audio/opus",
        "wav" to "audio/wav",
        "wma" to "audio/x-ms-wma",
        "alac" to "audio/alac",
        "aiff" to "audio/aiff",
        "dsf" to "audio/dsd",
    )

    private val imageExtensions = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif", "dng", "raw",
    )

    private val archiveExtensions = setOf(
        "zip", "7z", "rar", "tar", "gz", "bz2", "xz", "tgz", "tbz2", "jar", "001", "z01", "r00",
    )

    private val multiPartArchiveSuffixes = listOf(
        ".tar.gz",
        ".tar.xz",
        ".tar.bz2",
        ".zip.001",
        ".7z.001",
        ".tar.gz.001",
        ".tar.xz.001",
        ".tar.bz2.001",
        ".part1.rar",
    )

    private val textExtensions = setOf(
        "txt", "md", "json", "xml", "yaml", "yml", "kt", "java", "py", "sh", "log",
        "ini", "conf", "properties", "csv", "html", "css", "js", "ts", "sql",
    )

    private val videoContainerMimes = setOf(
        "application/x-matroska",
        "application/vnd.rn-realmedia-vbr",
        "application/vnd.rn-realmedia",
        "application/mp2t",
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
        "application/ogg",
    )

    fun extensionOf(fileName: String): String =
        fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun isArchiveFileName(fileName: String): Boolean {
        val lower = fileName.lowercase(Locale.ROOT)
        if (multiPartArchiveSuffixes.any { lower.endsWith(it) }) return true
        val ext = extensionOf(lower)
        if (ext in archiveExtensions) return true
        if (ext.matches(Regex("^(z\\d{2,}|r\\d{2,}|\\d{3})$"))) return true
        if (lower.matches(Regex("^.+\\.part\\d+\\.rar$"))) return true
        return false
    }

    fun isVideoContainerMime(mime: String?): Boolean {
        if (mime.isNullOrBlank()) return false
        val normalized = mime.lowercase(Locale.ROOT)
        return normalized.startsWith("video/") || normalized in videoContainerMimes
    }

    fun normalizeMime(fileName: String, systemMime: String? = null): String {
        val ext = extensionOf(fileName)
        videoExtensions[ext]?.let { return it }
        audioExtensions[ext]?.let { return it }
        if (ext in imageExtensions) return "image/$ext"
        if (isArchiveFileName(fileName)) return if (ext == "zip") "application/zip" else "application/x-$ext"
        if (ext in textExtensions) return "text/plain"
        if (!systemMime.isNullOrBlank()) {
            if (isVideoContainerMime(systemMime)) return "video/*"
            return systemMime
        }
        return "application/octet-stream"
    }

    fun categorize(fileName: String, isDirectory: Boolean, mimeType: String? = null): FileCategory {
        if (isDirectory) return FileCategory.DIRECTORY
        val ext = extensionOf(fileName)
        return when {
            ext in videoExtensions || isVideoContainerMime(mimeType) -> FileCategory.VIDEO
            ext in audioExtensions || mimeType?.startsWith("audio/") == true -> FileCategory.AUDIO
            ext in imageExtensions || mimeType?.startsWith("image/") == true -> FileCategory.IMAGE
            isArchiveFileName(fileName) -> FileCategory.ARCHIVE
            ext == "apk" -> FileCategory.APK
            ext in textExtensions || mimeType?.startsWith("text/") == true -> FileCategory.TEXT
            ext in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx") -> FileCategory.DOCUMENT
            else -> FileCategory.OTHER
        }
    }
}
