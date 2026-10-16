package com.example.livevoiceagent.network

import com.example.livevoiceagent.model.AppSettings
import com.example.livevoiceagent.model.ChatMessage
import com.example.livevoiceagent.model.MessageRole
import com.example.livevoiceagent.tools.CalendarToolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class BackendBrainResponse(
    val spokenSummary: String,
    val detailedNotes: String,
    val searchResults: List<LinkupSearchResult> = emptyList()
)

class BackendLlmClient(
    private val linkupClient: LinkupSearchClient,
    private val calendarToolManager: CalendarToolManager
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Executes a backend reasoning task using the user's chosen Backend LLM (e.g., gpt-6-luna, grok-4, etc.)
     * Automatically injects real-time Date/Time/Timezone context and supports Linkup Web Search + Android Calendar tools.
     */
    suspend fun runDelegatedTask(
        settings: AppSettings,
        conversationHistory: List<ChatMessage>,
        taskHint: String? = null,
        onSearchExecuted: (LinkupSearchResult) -> Unit = {},
        onCalendarActionLogged: (String) -> Unit = {}
    ): Result<BackendBrainResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val (baseUrl, apiKey, model) = resolveBackendCredentials(settings)
            val timeContext = CalendarToolManager.buildCurrentTimeContextString()

            val messagesArr = JSONArray()
            messagesArr.put(JSONObject().apply {
                put("role", "system")
                put(
                    "content",
                    """
                    $timeContext
                    
                    ${settings.backendSystemInstructions}
                    """.trimIndent()
                )
            })

            // Include recent conversation history for context
            conversationHistory
                .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT_VOICE || it.role == MessageRole.BACKEND_BRAIN }
                .takeLast(14)
                .forEach { msg ->
                    val roleStr = if (msg.role == MessageRole.USER) "user" else "assistant"
                    if (msg.text.isNotBlank()) {
                        messagesArr.put(JSONObject().apply {
                            put("role", roleStr)
                            put("content", msg.text)
                        })
                    }
                }

            if (!taskHint.isNullOrBlank()) {
                messagesArr.put(JSONObject().apply {
                    put("role", "user")
                    put("content", taskHint)
                })
            }

            val collectedSearches = mutableListOf<LinkupSearchResult>()
            val canUseLinkup = settings.linkupSearchEnabled && settings.linkupApiKey.isNotBlank()
            val canUseCalendar = settings.calendarToolsEnabled

            val toolsArray = JSONArray()
            if (canUseLinkup) {
                toolsArray.put(LinkupSearchClient.buildChatCompletionsToolSchema())
            }
            if (canUseCalendar) {
                CalendarToolManager.buildChatCompletionsCalendarToolSchemas().forEach { toolsArray.put(it) }
            }

            // Up to 3 tool-call rounds (e.g. list_calendar_events -> update_calendar_event -> final answer)
            var finalContent = ""
            for (round in 0 until 3) {
                val respJson = callChatCompletions(
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    model = model,
                    messages = messagesArr,
                    tools = if (round < 2 && toolsArray.length() > 0) toolsArray else null
                )

                val choice = respJson.optJSONArray("choices")?.optJSONObject(0)
                    ?: throw IllegalStateException("後端模型未回傳有效 choices")
                val messageObj = choice.optJSONObject("message")
                    ?: throw IllegalStateException("後端模型未回傳有效 message")

                val toolCalls = messageObj.optJSONArray("tool_calls")
                if (toolCalls == null || toolCalls.length() == 0) {
                    finalContent = messageObj.optString("content", "")
                    break
                }

                // Append assistant message containing tool_calls
                messagesArr.put(messageObj)

                for (i in 0 until toolCalls.length()) {
                    val tc = toolCalls.optJSONObject(i) ?: continue
                    val callId = tc.optString("id", "call_${round}_$i")
                    val fnObj = tc.optJSONObject("function")
                    val fnName = fnObj?.optString("name", "") ?: ""
                    val argsStr = fnObj?.optString("arguments", "{}") ?: "{}"
                    val argsJson = runCatching { JSONObject(argsStr) }.getOrDefault(JSONObject())

                    val toolOutput: String = when {
                        fnName == "linkup_web_search" -> {
                            val query = argsJson.optString("query", taskHint ?: "")
                            val depth = argsJson.optString("depth", settings.linkupSearchDepth)
                            val searchRes = linkupClient.search(
                                apiKey = settings.linkupApiKey,
                                query = query,
                                depth = depth
                            ).getOrElse { err ->
                                LinkupSearchResult(
                                    query = query,
                                    answer = "搜尋失敗: ${err.message}",
                                    sources = emptyList(),
                                    rawFormattedForLlm = "Linkup 搜尋錯誤: ${err.message}"
                                )
                            }
                            collectedSearches.add(searchRes)
                            onSearchExecuted(searchRes)
                            searchRes.rawFormattedForLlm
                        }

                        CalendarToolManager.CALENDAR_TOOL_NAMES.contains(fnName) -> {
                            val calRes = calendarToolManager.executeCalendarTool(fnName, argsJson)
                            onCalendarActionLogged("📅 行事曆操作 (`$fnName`):\n$calRes")
                            calRes
                        }

                        else -> "{\"status\":\"error\",\"message\":\"Unsupported tool: $fnName\"}"
                    }

                    messagesArr.put(JSONObject().apply {
                        put("role", "tool")
                        put("tool_call_id", callId)
                        put("content", toolOutput)
                    })
                }
            }

            val spokenSummary = if (finalContent.length > 700) {
                finalContent.take(700) + "...（完整細節已留存在對話紀錄中）"
            } else {
                finalContent
            }

            BackendBrainResponse(
                spokenSummary = spokenSummary,
                detailedNotes = finalContent,
                searchResults = collectedSearches
            )
        }
    }

    /**
     * Automatically generates a concise, descriptive Traditional Chinese conversation title using the Backend LLM.
     */
    suspend fun generateConversationTitle(
        settings: AppSettings,
        messages: List<ChatMessage>
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val (baseUrl, apiKey, model) = resolveBackendCredentials(settings)
            val snippet = messages
                .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT_VOICE || it.role == MessageRole.BACKEND_BRAIN }
                .take(4)
                .joinToString("\n") { "${if (it.role == MessageRole.USER) "使用者" else "AI"}: ${it.text.take(150)}" }

            if (snippet.isBlank()) return@withContext null

            val promptArr = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "請根據以下對話內容，產生一個簡短、具體的繁體中文對話標題（6 到 14 個字以內，不要加引號、句號或任何前綴說明）。")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", snippet)
                })
            }

            val resp = callChatCompletions(
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model,
                messages = promptArr,
                tools = null
            )

            resp.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                ?.trim()
                ?.replace("\"", "")
                ?.replace("「", "")
                ?.replace("」", "")
                ?.lines()
                ?.firstOrNull()
                ?.take(22)
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun resolveBackendCredentials(settings: AppSettings): Triple<String, String, String> {
        val model = settings.backendBrainModel.trim().ifBlank { "gpt-6-luna" }
        val isGrokBackend = model.lowercase().startsWith("grok")

        val baseUrl = when {
            isGrokBackend -> "https://api.x.ai/v1"
            settings.customBackendBaseUrl.isNotBlank() -> settings.customBackendBaseUrl.trim().removeSuffix("/")
            else -> "https://api.openai.com/v1"
        }

        val apiKey = when {
            isGrokBackend && settings.xAiApiKey.isNotBlank() -> settings.xAiApiKey.trim()
            settings.customBackendApiKey.isNotBlank() -> settings.customBackendApiKey.trim()
            else -> settings.openAiApiKey.trim()
        }

        if (apiKey.isBlank()) {
            throw IllegalStateException("後端模型 ($model) 缺少對應的 API Key，請至設定頁面填寫。")
        }
        return Triple(baseUrl, apiKey, model)
    }

    private fun callChatCompletions(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: JSONArray,
        tools: JSONArray?
    ): JSONObject {
        val payload = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            if (tools != null && tools.length() > 0) {
                put("tools", tools)
                put("tool_choice", "auto")
            }
        }

        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw IllegalStateException("Backend LLM ($model) HTTP ${response.code}: ${bodyStr.take(220)}")
            }
            return JSONObject(bodyStr)
        }
    }
}
