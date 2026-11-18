package com.monolith.feature.explorer.storage

import com.monolith.feature.explorer.ai.GeminiInteractionsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt

enum class StorageAiPreset(
    val label: String,
    val promptInstruction: String,
) {
    CLEANUP_RECOMMENDATIONS(
        label = "Aufräum-Tipps",
        promptInstruction = "Gib priorisierte Aufräum-Empfehlungen mit konkretem Speichergewinn (MB/GB). Nenne sichere Einsparungen zuerst (Duplikate, Temp/Cache, alte APKs/Archive, große Dateien) und weise auf sensible System-/App-Ordner hin.",
    ),
    EXPLAIN_FOLDERS(
        label = "Ordner erklären",
        promptInstruction = "Erkläre kurz und verständlich, wofür die größten genannten Ordner typischerweise dienen und welche davon sicher bereinigt, komprimiert oder ausgelagert werden können.",
    ),
    ARCHIVE_SUGGESTIONS(
        label = "Archivierungs-Potenzial",
        promptInstruction = "Bewerte, welche Ordner oder Dateigruppen sich besonders gut zum Packen/Archivieren eignen (hohe Komprimierbarkeit bzw. selten genutzte Daten) und wo Packen wenig Platz spart.",
    ),
}

/**
 * Ultra-low-token single-turn Gemini Interactions client for storage analysis.
 * Sends a compact plaintext digest instead of running multi-turn filesystem tool loops.
 */
object StorageAiAdvisor {

    private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"
    private const val API_REVISION = "2026-05-20"
    private const val MAX_DIGEST_CHARS = 1780

    private val STORAGE_SYSTEM_INSTRUCTION = """
        Du bist Monolith Speicher-Analyst (Android). Antworte auf Deutsch in prägnantem Markdown mit konkreten Größenangaben aus dem beigefügten Speicher-Report. Keine Tool-Aufrufe, keine erfundenen Pfade.
    """.trimIndent()

    /**
     * Builds a compact plaintext digest (~1,200–1,800 chars, ~350–500 estimated tokens)
     * summarizing capacity, categories, cleanup counters, top 12 disjoint folders,
     * top 12 largest files (with age in days), and top 5 duplicate groups.
     */
    fun buildCompactDigest(scan: StorageScanResult): Pair<String, Int> {
        val nowMs = System.currentTimeMillis()
        val digest = buildDigestWithLimits(
            scan = scan,
            nowMs = nowMs,
            maxPathLen = 40,
            folderLimit = 12,
            fileLimit = 12,
            dupLimit = 5,
        ).let { initial ->
            if (initial.length <= MAX_DIGEST_CHARS) {
                initial
            } else {
                val tighterPaths = buildDigestWithLimits(
                    scan = scan,
                    nowMs = nowMs,
                    maxPathLen = 28,
                    folderLimit = 12,
                    fileLimit = 12,
                    dupLimit = 5,
                )
                if (tighterPaths.length <= MAX_DIGEST_CHARS) {
                    tighterPaths
                } else {
                    buildDigestWithLimits(
                        scan = scan,
                        nowMs = nowMs,
                        maxPathLen = 24,
                        folderLimit = 9,
                        fileLimit = 9,
                        dupLimit = 4,
                    ).take(MAX_DIGEST_CHARS)
                }
            }
        }

        val estimatedTokens = (digest.length / 3.8).toInt().coerceAtLeast(1)
        return digest to estimatedTokens
    }

    suspend fun streamStorageAdvice(
        apiKey: String,
        model: String,
        scan: StorageScanResult,
        preset: StorageAiPreset?,
        customQuestion: String?,
        onTextDelta: (String) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanKey = apiKey.trim()
            require(cleanKey.isNotEmpty()) { "Kein Gemini-API-Key konfiguriert." }

            val (digest, _) = buildCompactDigest(scan)
            val question = buildString {
                preset?.let { append(it.promptInstruction) }
                val custom = customQuestion?.trim().orEmpty()
                if (custom.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\nFrage: ")
                    append(custom)
                }
                if (isEmpty()) {
                    append(StorageAiPreset.CLEANUP_RECOMMENDATIONS.promptInstruction)
                }
            }

            val resolvedModel = model.trim().ifEmpty { GeminiInteractionsClient.DEFAULT_MODEL }
            val body = JSONObject().apply {
                put("model", resolvedModel)
                put("stream", true)
                put(
                    "generation_config",
                    JSONObject().apply {
                        put("thinking_level", "low")
                        put("thinking_summaries", "none")
                    },
                )
                put("system_instruction", STORAGE_SYSTEM_INSTRUCTION)
                put("input", "$question\n\n[Speicher-Report]\n$digest")
            }

            executeSingleTurnStream(
                apiKey = cleanKey,
                requestBody = body,
                onTextDelta = onTextDelta,
            )
        }
    }

    private suspend fun executeSingleTurnStream(
        apiKey: String,
        requestBody: JSONObject,
        onTextDelta: (String) -> Unit,
    ) {
        val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
        val url = URL("$ENDPOINT?key=$encodedKey")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 90_000
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Api-Revision", API_REVISION)
        }

        try {
            conn.outputStream.use { os ->
                os.write(requestBody.toString().toByteArray(Charsets.UTF_8))
                os.flush()
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val errorBody = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val parsedMessage = runCatching {
                    JSONObject(errorBody).optJSONObject("error")?.optString("message")
                }.getOrNull()?.takeIf { it.isNotBlank() }
                throw IllegalStateException(parsedMessage ?: "Gemini API Fehler ($code)")
            }

            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                val dataBuffer = StringBuilder()
                while (currentCoroutineContext().isActive) {
                    val line = reader.readLine()
                    if (line == null) {
                        if (dataBuffer.isNotEmpty()) {
                            handleSseEvent(dataBuffer.toString(), onTextDelta)
                            dataBuffer.setLength(0)
                        }
                        break
                    }
                    if (line.startsWith("data:")) {
                        val payload = line.removePrefix("data:").trimStart()
                        if (payload == "[DONE]") continue
                        dataBuffer.append(payload)
                    } else if (line.isBlank() && dataBuffer.isNotEmpty()) {
                        val rawJson = dataBuffer.toString()
                        dataBuffer.setLength(0)
                        handleSseEvent(rawJson, onTextDelta)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun handleSseEvent(
        rawJson: String,
        onTextDelta: (String) -> Unit,
    ) {
        val eventObj = runCatching { JSONObject(rawJson) }.getOrNull() ?: return
        when (eventObj.optString("event_type")) {
            "step.start" -> {
                val step = eventObj.optJSONObject("step") ?: return
                if (step.optString("type") == "model_output") {
                    val content = step.optJSONArray("content") ?: return
                    for (i in 0 until content.length()) {
                        val part = content.optJSONObject(i) ?: continue
                        if (part.optString("type") == "text") {
                            val text = part.optString("text")
                            if (text.isNotEmpty()) onTextDelta(text)
                        }
                    }
                }
            }
            "step.delta" -> {
                val delta = eventObj.optJSONObject("delta") ?: return
                if (delta.optString("type") == "text") {
                    val text = delta.optString("text")
                    if (text.isNotEmpty()) onTextDelta(text)
                }
            }
            "error" -> {
                val errMsg = eventObj.optJSONObject("error")?.optString("message")
                    ?.takeIf { it.isNotBlank() }
                    ?: eventObj.optString("message").ifBlank { "Unbekannter Streaming-Fehler" }
                throw IllegalStateException(errMsg)
            }
        }
    }

    private fun buildDigestWithLimits(
        scan: StorageScanResult,
        nowMs: Long,
        maxPathLen: Int,
        folderLimit: Int,
        fileLimit: Int,
        dupLimit: Int,
    ): String = buildString {
        val vol = scan.volumeStats
        append("Vol:").append(vol.label)
            .append("(").append(truncateMiddle(scan.rootPath, 34)).append(")")
            .append(" Bel:").append(compactBytes(vol.usedCapacityBytes))
            .append("/").append(compactBytes(vol.totalCapacityBytes))
            .append(" Frei:").append(compactBytes(vol.freeCapacityBytes))
            .append(" Scan:").append(compactBytes(scan.scannedBytes))
            .append(" D:").append(scan.totalFilesCount)
            .append(" O:").append(scan.totalFoldersCount)
            .append('\n')

        append("Kat:")
        if (scan.categoryBreakdowns.isEmpty()) {
            append("keine")
        } else {
            append(
                scan.categoryBreakdowns.joinToString(",") {
                    "${it.label}=${compactBytes(it.totalBytes)}(${it.fileCount})"
                },
            )
        }
        append('\n')

        append("Hinw:>100MB=").append(scan.largeFilesOver100MbCount)
            .append("(").append(compactBytes(scan.largeFilesOver100MbBytes)).append(")")
            .append(" Dup=").append(scan.duplicateGroups.size).append("Grp(")
            .append(compactBytes(scan.reclaimableDuplicatesBytes)).append(")")
            .append(" Arch/APK=").append(scan.archiveAndApkCount)
            .append("(").append(compactBytes(scan.archiveAndApkBytes)).append(")")
            .append(" Tmp/Cache=").append(scan.tempAndCacheCount)
            .append("(").append(compactBytes(scan.tempAndCacheBytes)).append(")")
            .append('\n')

        val topFolders = scan.disjointTopFolders.take(folderLimit)
        if (topFolders.isNotEmpty()) {
            append("Ordner(").append(topFolders.size).append("):\n")
            topFolders.forEach { f ->
                val pct = (f.fractionOfScanned * 100f).roundToInt()
                val exts = f.dominantExtensions.take(3).joinToString(",")
                append("- ").append(truncateMiddle(f.relativePath, maxPathLen))
                    .append("|").append(compactBytes(f.totalBytes))
                    .append("|").append(pct).append("%")
                    .append("|").append(f.fileCount).append("D")
                if (exts.isNotEmpty()) append("|").append(exts)
                append('\n')
            }
        }

        val topFiles = scan.largestFiles.take(fileLimit)
        if (topFiles.isNotEmpty()) {
            append("Dateien(").append(topFiles.size).append("):\n")
            topFiles.forEach { f ->
                val ageDays = if (f.lastModifiedMs > 0L && nowMs >= f.lastModifiedMs) {
                    ((nowMs - f.lastModifiedMs) / 86_400_000L).coerceAtLeast(0L)
                } else {
                    -1L
                }
                append("- ").append(truncateMiddle(f.relativePath, maxPathLen))
                    .append("|").append(compactBytes(f.sizeBytes))
                    .append("|").append(f.category.name)
                    .append("|").append(if (ageDays >= 0L) "${ageDays}d" else "?")
                if (f.isDuplicateCandidate) append("|DUP")
                append('\n')
            }
        }

        val topDups = scan.duplicateGroups.take(dupLimit)
        if (topDups.isNotEmpty()) {
            append("Duplikate(").append(topDups.size).append("):\n")
            topDups.forEach { g ->
                val samplePaths = g.files.take(2).joinToString(",") {
                    truncateMiddle(it.relativePath, (maxPathLen * 2) / 3)
                }
                append("- ").append(truncateMiddle(g.fileName, 24))
                    .append("|").append(g.files.size).append("x").append(compactBytes(g.eachSizeBytes))
                    .append("|sparb:").append(compactBytes(g.reclaimableBytes))
                    .append("|").append(samplePaths)
                    .append('\n')
            }
        }
    }.trimEnd()

    private fun compactBytes(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        if (safe < 1024L) return "${safe}B"
        val kb = safe / 1024.0
        if (kb < 1024.0) return String.format(Locale.ROOT, "%.0fKB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.ROOT, "%.1fMB", mb)
        val gb = mb / 1024.0
        if (gb < 1024.0) return String.format(Locale.ROOT, "%.2fGB", gb)
        return String.format(Locale.ROOT, "%.2fTB", gb / 1024.0)
    }

    private fun truncateMiddle(value: String, maxLen: Int): String {
        if (value.length <= maxLen || maxLen < 5) return value
        val head = (maxLen - 1) / 2
        val tail = maxLen - 1 - head
        return value.take(head) + "…" + value.takeLast(tail)
    }
}
