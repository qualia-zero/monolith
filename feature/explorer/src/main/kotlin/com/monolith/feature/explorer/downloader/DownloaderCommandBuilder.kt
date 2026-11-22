package com.monolith.feature.explorer.downloader

data class BuiltYtDlpCommand(
    val options: List<Pair<String, String?>>,
    val extraTokens: List<String>,
)

object DownloaderCommandBuilder {

    private val MERGER_REGEX = Regex("""^\[Merger]\s+Merging formats into\s+"(.+)"\s*$""")
    private val MOVE_FILES_REGEX = Regex("""^\[MoveFiles]\s+Moving file\s+".+?"\s+to\s+"(.+)"\s*$""")
    private val REMUXER_REGEX = Regex("""^\[VideoRemuxer]\s+Remuxing video from\s+".+?"\s+to\s+"(.+)"\s*$""")
    private val EXTRACT_AUDIO_REGEX = Regex("""^\[ExtractAudio]\s+Destination:\s+(.+?)\s*$""")
    private val DOWNLOAD_DEST_REGEX = Regex("""^\[download]\s+Destination:\s+(.+?)\s*$""")
    private val ALREADY_DOWNLOADED_REGEX = Regex("""^\[download]\s+(.+?)\s+has already been downloaded\s*$""")
    private val SLEEP_SECONDS_REGEX = Regex("""Sleeping\s+([0-9]+(?:\.[0-9]+)?)\s+seconds""", RegexOption.IGNORE_CASE)
    private val DOWNLOAD_METRICS_REGEX = Regex(
        """^\[download]\s+\d+(?:\.\d+)?%\s+of\s+~?\s*([0-9.]+\s*[KMGTP]?i?B)(?:\s+at\s+([0-9.]+\s*[KMGTP]?i?B/s|Unknown\s+B/s))?""",
        RegexOption.IGNORE_CASE,
    )
    private val FORMAT_ID_SUFFIX_REGEX = Regex("""\.f\d+$""")
    private val FILE_EXTENSION_SUFFIX_REGEX = Regex("""\.[a-zA-Z0-9]{2,5}$""")

    fun needsVideoFormatProbe(mode: DownloadMode, settings: DownloaderSettings): Boolean {
        return mode == DownloadMode.VIDEO && settings.videoFormatPreset == VideoFormatPreset.AUTO_VP9_H264
    }

    fun buildVideoProbeOptions(settings: DownloaderSettings): List<Pair<String, String?>> {
        val maxHeight = settings.videoMaxHeight?.takeIf { it > 0 }
        val probeFormat = if (maxHeight == null) {
            "bv[ext~='^(webm|mp4)$'][vcodec~='^(vp0?9|avc|h264)']/bv/b"
        } else {
            "bv[height<=$maxHeight][ext~='^(webm|mp4)$'][vcodec~='^(vp0?9|avc|h264)']/bv[height<=$maxHeight]/b[height<=$maxHeight]/bv/b"
        }
        return buildList {
            add("--skip-download" to null)
            add("--no-warnings" to null)
            add("--print" to "%(ext)s")
            if (settings.allowPlaylists) {
                add("--playlist-items" to "1")
            } else {
                add("--no-playlist" to null)
            }
            add("-f" to probeFormat)
        }
    }

    fun parseProbedExtension(stdout: String): String? {
        val token = stdout
            .trim()
            .split(Regex("""\s+"""))
            .firstOrNull { it.isNotEmpty() }
            ?.lowercase()
            ?: return null
        if (token == "na" || token == "none") return null
        return token
    }

    fun resolveVideoFormatExpression(settings: DownloaderSettings, probedExt: String?): String {
        val h = settings.videoMaxHeight?.takeIf { it > 0 }
        val ext = probedExt?.trim()?.lowercase()
        val bestFallback = if (h == null) {
            "bv*+ba/b"
        } else {
            "bv*[height<=$h]+ba/b[height<=$h]/b"
        }

        return when (settings.videoFormatPreset) {
            VideoFormatPreset.AUTO_VP9_H264 -> {
                val webmExpr = if (h == null) {
                    "bv[ext=webm][vcodec~='^vp0?9']+ba[ext=webm]"
                } else {
                    "bv[height<=$h][ext=webm][vcodec~='^vp0?9']+ba[ext=webm]"
                }
                val mp4Expr = if (h == null) {
                    "bv[ext=mp4][vcodec~='^(avc|h264)']+ba[ext=m4a]"
                } else {
                    "bv[height<=$h][ext=mp4][vcodec~='^(avc|h264)']+ba[ext=m4a]"
                }
                when (ext) {
                    "webm" -> "$webmExpr/$mp4Expr/$bestFallback"
                    "mp4" -> "$mp4Expr/$webmExpr/$bestFallback"
                    else -> "$webmExpr/$mp4Expr/$bestFallback"
                }
            }
            VideoFormatPreset.MP4_H264 -> {
                if (h == null) {
                    "bv[ext=mp4][vcodec~='^(avc|h264)']+ba[ext=m4a]/b[ext=mp4]/$bestFallback"
                } else {
                    "bv[height<=$h][ext=mp4][vcodec~='^(avc|h264)']+ba[ext=m4a]/b[height<=$h][ext=mp4]/$bestFallback"
                }
            }
            VideoFormatPreset.WEBM_VP9 -> {
                if (h == null) {
                    "bv[ext=webm][vcodec~='^vp0?9']+ba[ext=webm]/b[ext=webm]/$bestFallback"
                } else {
                    "bv[height<=$h][ext=webm][vcodec~='^vp0?9']+ba[ext=webm]/b[height<=$h][ext=webm]/$bestFallback"
                }
            }
            VideoFormatPreset.BEST_ANY -> bestFallback
            VideoFormatPreset.CUSTOM -> settings.customVideoFormat.trim().ifBlank { bestFallback }
        }
    }

    fun resolveAudioFormatOptions(settings: DownloaderSettings): List<Pair<String, String?>> {
        return when (settings.audioFormatPreset) {
            AudioFormatPreset.M4A_BEST -> listOf(
                "-f" to "bestaudio[ext=m4a]/bestaudio",
            )
            AudioFormatPreset.OPUS_WEBM -> listOf(
                "-f" to "bestaudio[ext=webm]/bestaudio",
            )
            AudioFormatPreset.MP3_CONVERT -> listOf(
                "-f" to "bestaudio/best",
                "-x" to null,
                "--audio-format" to "mp3",
                "--audio-quality" to "0",
            )
            AudioFormatPreset.FLAC_CONVERT -> listOf(
                "-f" to "bestaudio/best",
                "-x" to null,
                "--audio-format" to "flac",
            )
            AudioFormatPreset.BEST_ORIGINAL -> listOf(
                "-f" to "bestaudio/best",
            )
            AudioFormatPreset.CUSTOM -> listOf(
                "-f" to settings.customAudioFormat.trim().ifBlank { "bestaudio/best" },
            )
        }
    }

    fun buildDownloadCommand(
        mode: DownloadMode,
        settings: DownloaderSettings,
        outputDirPath: String,
        probedExt: String? = null,
    ): BuiltYtDlpCommand {
        val options = mutableListOf<Pair<String, String?>>()
        options.add("--newline" to null)

        val canonicalIds = SPONSORBLOCK_CATEGORIES.map { it.id }
        val orderedCategories = canonicalIds.filter { it in settings.sponsorBlockCategories } +
            settings.sponsorBlockCategories.filter { it !in canonicalIds }.sorted()

        if (orderedCategories.isNotEmpty()) {
            val joinedCategories = orderedCategories.joinToString(",")
            when (settings.sponsorBlockMode) {
                SponsorBlockMode.REMOVE -> options.add("--sponsorblock-remove" to joinedCategories)
                SponsorBlockMode.MARK -> options.add("--sponsorblock-mark" to joinedCategories)
                SponsorBlockMode.OFF -> Unit
            }
        }

        if (settings.sleepIntervalEnabled && settings.maxSleepIntervalSec > 0) {
            val minSleep = settings.minSleepIntervalSec.coerceAtLeast(0)
            val maxSleep = settings.maxSleepIntervalSec.coerceAtLeast(minSleep)
            options.add("--min-sleep-interval" to minSleep.toString())
            options.add("--max-sleep-interval" to maxSleep.toString())
        }

        when (mode) {
            DownloadMode.VIDEO -> {
                options.add("-f" to resolveVideoFormatExpression(settings, probedExt))
            }
            DownloadMode.AUDIO -> {
                options.addAll(resolveAudioFormatOptions(settings))
            }
        }

        if (settings.embedMetadata) {
            options.add("--embed-metadata" to null)
        }

        val embedThumbnail = when (settings.thumbnailEmbedMode) {
            ThumbnailEmbedMode.AUDIO_ONLY -> mode == DownloadMode.AUDIO
            ThumbnailEmbedMode.ALWAYS -> true
            ThumbnailEmbedMode.NEVER -> false
        }
        if (embedThumbnail) {
            options.add("--embed-thumbnail" to null)
        }

        if (settings.embedChapters) {
            options.add("--embed-chapters" to null)
        }

        if (settings.embedSubtitles && mode == DownloadMode.VIDEO) {
            options.add("--embed-subs" to null)
            val subLangs = settings.subtitleLanguages.trim()
            if (subLangs.isNotEmpty()) {
                options.add("--sub-langs" to subLangs)
            }
        }

        if (!settings.allowPlaylists) {
            options.add("--no-playlist" to null)
        }

        val template = settings.outputTemplate.trim().ifBlank { "%(title)s.%(ext)s" }
        val cleanDir = outputDirPath.trimEnd('/', '\\')
        val outputSpec = when {
            outputDirPath == "/" -> "/$template"
            cleanDir.isBlank() -> template
            else -> "$cleanDir/$template"
        }
        options.add("-o" to outputSpec)

        return BuiltYtDlpCommand(
            options = options,
            extraTokens = tokenizeExtraArgs(settings.extraArgs),
        )
    }

    fun tokenizeExtraArgs(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inSingleQuote = false
        var inDoubleQuote = false
        var hasToken = false

        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            when {
                inSingleQuote -> {
                    if (ch == '\'') {
                        inSingleQuote = false
                    } else {
                        current.append(ch)
                    }
                }
                inDoubleQuote -> {
                    when {
                        ch == '"' -> inDoubleQuote = false
                        ch == '\\' && i + 1 < raw.length -> {
                            val next = raw[i + 1]
                            if (next == '"' || next == '\\') {
                                current.append(next)
                                i++
                            } else {
                                current.append(ch)
                            }
                        }
                        else -> current.append(ch)
                    }
                }
                else -> {
                    when {
                        ch.isWhitespace() -> {
                            if (hasToken) {
                                tokens.add(current.toString())
                                current.setLength(0)
                                hasToken = false
                            }
                        }
                        ch == '\'' -> {
                            inSingleQuote = true
                            hasToken = true
                        }
                        ch == '"' -> {
                            inDoubleQuote = true
                            hasToken = true
                        }
                        ch == '\\' && i + 1 < raw.length -> {
                            current.append(raw[i + 1])
                            hasToken = true
                            i++
                        }
                        else -> {
                            current.append(ch)
                            hasToken = true
                        }
                    }
                }
            }
            i++
        }

        if (hasToken) {
            tokens.add(current.toString())
        }
        return tokens
    }

    fun parseOutputFilePathFromLine(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        MERGER_REGEX.find(trimmed)?.let { return it.groupValues[1].trim().ifEmpty { null } }
        MOVE_FILES_REGEX.find(trimmed)?.let { return it.groupValues[1].trim().ifEmpty { null } }
        REMUXER_REGEX.find(trimmed)?.let { return it.groupValues[1].trim().ifEmpty { null } }
        EXTRACT_AUDIO_REGEX.find(trimmed)?.let { return it.groupValues[1].trim().ifEmpty { null } }
        DOWNLOAD_DEST_REGEX.find(trimmed)?.let { return it.groupValues[1].trim().ifEmpty { null } }
        ALREADY_DOWNLOADED_REGEX.find(trimmed)?.let { return it.groupValues[1].trim().ifEmpty { null } }
        return null
    }

    fun parseTitleFromPathOrLine(path: String?): String? {
        val trimmed = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val extracted = parseOutputFilePathFromLine(trimmed)
        val candidate = extracted ?: run {
            if (trimmed.startsWith("[") && !trimmed.contains('/') && !trimmed.contains('\\')) {
                if (!FILE_EXTENSION_SUFFIX_REGEX.containsMatchIn(trimmed)) return null
            }
            trimmed
        }
        val fileName = candidate.substringAfterLast('/').substringAfterLast('\\').trim()
        if (fileName.isEmpty()) return null
        val withoutExt = fileName.substringBeforeLast('.', missingDelimiterValue = fileName)
            .ifBlank { fileName }
        return withoutExt.replace(FORMAT_ID_SUFFIX_REGEX, "").ifBlank { withoutExt }
    }

    fun parseStatusDetailFromLine(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        return when {
            trimmed.contains("[SponsorBlock]", ignoreCase = true) -> "SponsorBlock wird angewendet…"
            trimmed.contains("[Merger]", ignoreCase = true) -> "Streams werden zusammengeführt…"
            trimmed.contains("[Metadata]", ignoreCase = true) -> "Metadaten werden eingebettet…"
            trimmed.contains("[EmbedThumbnail]", ignoreCase = true) -> "Vorschaubild wird eingebettet…"
            trimmed.contains("[ExtractAudio]", ignoreCase = true) -> "Audio wird extrahiert…"
            trimmed.contains("[EmbedSubtitle]", ignoreCase = true) -> "Untertitel werden eingebettet…"
            trimmed.contains("[ModifyChapters]", ignoreCase = true) -> "Kapitel werden bearbeitet…"
            trimmed.contains("[MoveFiles]", ignoreCase = true) -> "Datei wird verschoben…"
            trimmed.contains("[Fixup", ignoreCase = true) -> "Datei wird repariert…"
            trimmed.contains("[download] Sleeping", ignoreCase = true) -> {
                val sec = SLEEP_SECONDS_REGEX.find(trimmed)?.groupValues?.getOrNull(1)
                if (sec != null) {
                    "Wartezeit (${sec} s)…"
                } else {
                    "Wartezeit (Sleep-Intervall)…"
                }
            }
            else -> null
        }
    }

    fun isSleepLine(line: String): Boolean {
        return line.trim().contains("[download] Sleeping", ignoreCase = true)
    }

    fun parseDownloadMetricsFromLine(line: String): String? {
        val match = DOWNLOAD_METRICS_REGEX.find(line.trim()) ?: return null
        val size = match.groupValues[1].trim()
        val speed = match.groupValues.getOrNull(2)?.trim().orEmpty()
        val cleanSpeed = if (speed.isEmpty() || speed.startsWith("Unknown", ignoreCase = true)) {
            null
        } else {
            speed
        }
        return listOfNotNull(cleanSpeed, size).joinToString(" · ").ifEmpty { null }
    }

    fun detectStreamLabel(
        destinationPath: String,
        mode: DownloadMode,
        streamIndex: Int,
    ): String {
        if (mode == DownloadMode.AUDIO) return "Audio-Stream"
        val lower = destinationPath.lowercase()
        if (lower.endsWith(".m4a") || lower.endsWith(".opus") || lower.endsWith(".mp3") || lower.endsWith(".flac")) {
            return "Audio-Stream"
        }
        return if (streamIndex >= 2) "Audio-Stream" else "Video-Stream"
    }

    fun isPostProcessingLine(line: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.startsWith("[")) return false
        return trimmed.startsWith("[Merger]", ignoreCase = true) ||
            trimmed.startsWith("[ExtractAudio]", ignoreCase = true) ||
            trimmed.startsWith("[SponsorBlock]", ignoreCase = true) ||
            trimmed.startsWith("[Metadata]", ignoreCase = true) ||
            trimmed.startsWith("[EmbedThumbnail]", ignoreCase = true) ||
            trimmed.startsWith("[EmbedSubtitle]", ignoreCase = true) ||
            trimmed.startsWith("[ModifyChapters]", ignoreCase = true) ||
            trimmed.startsWith("[MoveFiles]", ignoreCase = true) ||
            trimmed.startsWith("[VideoRemuxer]", ignoreCase = true) ||
            trimmed.startsWith("[Fixup", ignoreCase = true)
    }

    fun parseUrls(rawInput: String): List<String> {
        if (rawInput.isBlank()) return emptyList()
        return rawInput
            .trim()
            .split(Regex("""\s+"""))
            .asSequence()
            .map { it.trim() }
            .filter { token ->
                (token.startsWith("http://", ignoreCase = true) && token.length > "http://".length) ||
                    (token.startsWith("https://", ignoreCase = true) && token.length > "https://".length)
            }
            .distinct()
            .toList()
    }
}
