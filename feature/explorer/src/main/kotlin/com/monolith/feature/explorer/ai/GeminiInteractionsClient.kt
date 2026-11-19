package com.monolith.feature.explorer.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class ToolCallTrace(
    val name: String,
    val arguments: String,
    val resultPreview: String,
)

/**
 * Lightweight streaming client for the Gemini Interactions API (`v1beta/interactions`),
 * supporting multi-turn state via `previous_interaction_id`, thinking summaries, and explorer tools.
 */
object GeminiInteractionsClient {

    const val DEFAULT_MODEL = "gemini-4-hydrogen"
    const val DEFAULT_THINKING_LEVEL = "medium"
    const val CURRENT_DIR_PLACEHOLDER = "{current_dir}"

    val DEFAULT_SYSTEM_PROMPT = """
        Du bist Monolith, ein hilfreicher Assistent im Monolith-Dateiexplorer (Android). Antworte auf Deutsch, klar und prägnant.
        Aktueller Ordner: $CURRENT_DIR_PLACEHOLDER
        Nutze deine Dateisystem-Tools (`list_directory`, `read_text_file`, `get_file_info`, `search_files`, `analyze_storage_summary`, `list_archive_contents`) nur bei Bedarf (z. B. `list_directory({"path": "$CURRENT_DIR_PLACEHOLDER/Download", "include_hidden": true})`). Unterordner öffnest du über `<dir>/<name>`.
    """.trimIndent()

    private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"
    private const val API_REVISION = "2026-05-20"
    private const val MAX_TOOL_ROUNDS = 6

    private data class PendingFunctionCall(
        val id: String,
        val name: String,
        val argsBuffer: StringBuilder = StringBuilder(),
        var directArguments: JSONObject? = null,
    )

    private data class TurnResult(
        val interactionId: String?,
        val status: String?,
        val functionCalls: List<PendingFunctionCall>,
    )

    suspend fun sendMessage(
        apiKey: String,
        userMessage: String,
        previousInteractionId: String?,
        currentDirectory: String,
        model: String = DEFAULT_MODEL,
        thinkingLevel: String = DEFAULT_THINKING_LEVEL,
        systemPromptTemplate: String = DEFAULT_SYSTEM_PROMPT,
        onTextDelta: (String) -> Unit,
        onThoughtDelta: (String) -> Unit = {},
        onToolCallTrace: (ToolCallTrace) -> Unit = {},
        onToolStatus: (String?) -> Unit,
    ): Result<String?> = withContext(Dispatchers.IO) {
        runCatching {
            val resolvedPrompt = systemPromptTemplate
                .ifBlank { DEFAULT_SYSTEM_PROMPT }
                .replace(CURRENT_DIR_PLACEHOLDER, currentDirectory)

            var activePrevId = previousInteractionId
            var currentInput: Any = userMessage
            var round = 0

            while (round < MAX_TOOL_ROUNDS && currentCoroutineContext().isActive) {
                round++
                val body = JSONObject().apply {
                    put("model", model)
                    put("stream", true)
                    put(
                        "generation_config",
                        JSONObject().apply {
                            put("thinking_level", thinkingLevel)
                            put("thinking_summaries", "auto")
                        },
                    )
                    put("tools", ExplorerAiTools.toolDeclarationsJson())
                    if (activePrevId == null) {
                        put("system_instruction", resolvedPrompt)
                    } else {
                        put("previous_interaction_id", activePrevId)
                    }
                    put("input", currentInput)
                }

                val turn = executeStreamingTurn(
                    apiKey = apiKey,
                    requestBody = body,
                    onTextDelta = onTextDelta,
                    onThoughtDelta = onThoughtDelta,
                )

                if (turn.interactionId != null) {
                    activePrevId = turn.interactionId
                }

                if (turn.functionCalls.isEmpty()) {
                    onToolStatus(null)
                    return@runCatching activePrevId
                }

                val toolResults = JSONArray()
                for (call in turn.functionCalls) {
                    onToolStatus("Tool: ${call.name}…")
                    val bufferedRaw = call.argsBuffer.toString().trim()
                    val argsJson = if (bufferedRaw.isNotEmpty()) {
                        runCatching { JSONObject(bufferedRaw) }.getOrElse {
                            call.directArguments ?: JSONObject()
                        }
                    } else {
                        call.directArguments ?: JSONObject()
                    }

                    val outputText = ExplorerAiTools.execute(
                        name = call.name,
                        arguments = argsJson,
                        currentDirectory = currentDirectory,
                    )

                    onToolCallTrace(
                        ToolCallTrace(
                            name = call.name,
                            arguments = argsJson.toString(),
                            resultPreview = outputText.take(260),
                        ),
                    )

                    toolResults.put(
                        JSONObject().apply {
                            put("type", "function_result")
                            put("name", call.name)
                            put("call_id", call.id)
                            put(
                                "result",
                                JSONObject().apply {
                                    put(
                                        "content",
                                        JSONArray().put(
                                            JSONObject().apply {
                                                put("type", "text")
                                                put("text", outputText)
                                            },
                                        ),
                                    )
                                },
                            )
                        },
                    )
                }

                onToolStatus(null)
                currentInput = toolResults
            }

            onToolStatus(null)
            activePrevId
        }
    }

    private suspend fun executeStreamingTurn(
        apiKey: String,
        requestBody: JSONObject,
        onTextDelta: (String) -> Unit,
        onThoughtDelta: (String) -> Unit,
    ): TurnResult {
        val encodedKey = URLEncoder.encode(apiKey.trim(), "UTF-8")
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

            var interactionId: String? = null
            var status: String? = null
            val callsByIndex = mutableMapOf<Int, PendingFunctionCall>()

            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                val dataBuffer = StringBuilder()
                while (currentCoroutineContext().isActive) {
                    val line = reader.readLine() ?: break
                    if (line.startsWith("data:")) {
                        val payload = line.removePrefix("data:").trimStart()
                        if (payload == "[DONE]") continue
                        dataBuffer.append(payload)
                    } else if (line.isBlank() && dataBuffer.isNotEmpty()) {
                        val rawJson = dataBuffer.toString()
                        dataBuffer.setLength(0)
                        val eventObj = runCatching { JSONObject(rawJson) }.getOrNull() ?: continue
                        val eventType = eventObj.optString("event_type")

                        when (eventType) {
                            "interaction.created" -> {
                                val interaction = eventObj.optJSONObject("interaction")
                                interaction?.optString("id")?.takeIf { it.isNotBlank() }?.let {
                                    interactionId = it
                                }
                            }
                            "interaction.status_update" -> {
                                eventObj.optString("interaction_id").takeIf { it.isNotBlank() }?.let {
                                    interactionId = it
                                }
                                eventObj.optString("status").takeIf { it.isNotBlank() }?.let {
                                    status = it
                                }
                            }
                            "interaction.completed" -> {
                                val interaction = eventObj.optJSONObject("interaction")
                                interaction?.optString("id")?.takeIf { it.isNotBlank() }?.let {
                                    interactionId = it
                                }
                                interaction?.optString("status")?.takeIf { it.isNotBlank() }?.let {
                                    status = it
                                }
                            }
                            "step.start" -> {
                                val index = eventObj.optInt("index", -1)
                                val step = eventObj.optJSONObject("step") ?: continue
                                when (step.optString("type")) {
                                    "function_call" -> {
                                        val callId = step.optString("id")
                                        val callName = step.optString("name")
                                        val directArgs = step.optJSONObject("arguments")
                                            ?.takeIf { it.length() > 0 }
                                        if (index >= 0 && callId.isNotBlank() && callName.isNotBlank()) {
                                            callsByIndex[index] = PendingFunctionCall(
                                                id = callId,
                                                name = callName,
                                                directArguments = directArgs,
                                            )
                                        }
                                    }
                                    "thought" -> {
                                        val summary = step.optJSONArray("summary")
                                        if (summary != null) {
                                            for (i in 0 until summary.length()) {
                                                val part = summary.optJSONObject(i) ?: continue
                                                val t = part.optString("text")
                                                if (t.isNotEmpty()) onThoughtDelta(t)
                                            }
                                        }
                                    }
                                    "model_output" -> {
                                        val content = step.optJSONArray("content")
                                        if (content != null) {
                                            for (i in 0 until content.length()) {
                                                val part = content.optJSONObject(i) ?: continue
                                                if (part.optString("type") == "text") {
                                                    val t = part.optString("text")
                                                    if (t.isNotEmpty()) onTextDelta(t)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            "step.delta" -> {
                                val index = eventObj.optInt("index", -1)
                                val delta = eventObj.optJSONObject("delta") ?: continue
                                when (delta.optString("type")) {
                                    "text" -> {
                                        val text = delta.optString("text")
                                        if (text.isNotEmpty()) {
                                            onTextDelta(text)
                                        }
                                    }
                                    "thought_summary", "thought" -> {
                                        val text = delta.optJSONObject("content")?.optString("text")
                                            ?.takeIf { it.isNotEmpty() }
                                            ?: delta.optString("text")
                                        if (text.isNotEmpty()) {
                                            onThoughtDelta(text)
                                        }
                                    }
                                    "arguments_delta" -> {
                                        val argsObj = delta.optJSONObject("arguments")
                                        if (argsObj != null && argsObj.length() > 0) {
                                            callsByIndex[index]?.directArguments = argsObj
                                        } else {
                                            val argsDelta = delta.optString("arguments")
                                                .ifEmpty { delta.optString("arguments_delta") }
                                            if (argsDelta.isNotEmpty()) {
                                                callsByIndex[index]?.argsBuffer?.append(argsDelta)
                                            }
                                        }
                                    }
                                }
                            }
                            "error" -> {
                                val errMsg = eventObj.optJSONObject("error")?.optString("message")
                                    ?: eventObj.optString("message")
                                    .ifBlank { "Unbekannter Streaming-Fehler" }
                                throw IllegalStateException(errMsg)
                            }
                        }
                    }
                }
            }

            return TurnResult(
                interactionId = interactionId,
                status = status,
                functionCalls = callsByIndex.entries.sortedBy { it.key }.map { it.value },
            )
        } finally {
            conn.disconnect()
        }
    }
}
