package com.example.livevoiceagent.network

import com.example.livevoiceagent.model.AppSettings
import com.example.livevoiceagent.model.ChatMessage
import com.example.livevoiceagent.model.MessageRole
import com.example.livevoiceagent.tools.CalendarToolManager
import com.example.livevoiceagent.util.TraditionalChineseConverter
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
        .readTimeout(65, TimeUnit.SECONDS)
        .build()

    /**
     * Executes a backend reasoning task using the user's chosen Backend LLM (e.g., gpt-6-luna, grok-4, etc.)
     * Supports both OpenAI's `/v1/responses` API (primary for `gpt-6-luna` etc.) and `/v1/chat/completions` (fallback),
     * while emitting live `onThinkingStatusUpdate` events so the frontend voice model can keep chatting with the user!
     */
    suspend fun runDelegatedTask(
        settings: AppSettings,
        conversationHistory: List<ChatMessage>,
        taskHint: String? = null,
        onThinkingStatusUpdate: (String) -> Unit = {},
        onSearchExecuted: (LinkupSearchResult) -> Unit = {},
        onCalendarActionLogged: (String) -> Unit = {}
    ): Result<BackendBrainResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val (baseUrl, apiKey, model) = resolveBackendCredentials(settings)
            val timeContext = CalendarToolManager.buildCurrentTimeContextString()
            val systemInstructions = "$timeContext\n\n${settings.backendSystemInstructions}"

            val collectedSearches = mutableListOf<LinkupSearchResult>()
            val canUseLinkup = settings.linkupSearchEnabled && settings.linkupApiKey.isNotBlank()
            val canUseCalendar = settings.calendarToolsEnabled

            onThinkingStatusUpdate("後端模型 $model 正在分析問題與檢索資料，若使用者說話請自然陪他聊天...")

            val isOpenAiEndpoint = baseUrl.contains("api.openai.com")

            val finalContent = if (isOpenAiEndpoint) {
                runCatching {
                    runViaOpenAiResponsesApi(
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        model = model,
                        systemInstructions = systemInstructions,
                        conversationHistory = conversationHistory,
                        taskHint = taskHint,
                        settings = settings,
                        canUseLinkup = canUseLinkup,
                        canUseCalendar = canUseCalendar,
                        collectedSearches = collectedSearches,
                        onThinkingStatusUpdate = onThinkingStatusUpdate,
                        onSearchExecuted = onSearchExecuted,
                        onCalendarActionLogged = onCalendarActionLogged
                    )
                }.getOrElse {
                    runViaChatCompletionsApi(
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        model = model,
                        systemInstructions = systemInstructions,
                        conversationHistory = conversationHistory,
                        taskHint = taskHint,
                        settings = settings,
                        canUseLinkup = canUseLinkup,
                        canUseCalendar = canUseCalendar,
                        collectedSearches = collectedSearches,
                        onThinkingStatusUpdate = onThinkingStatusUpdate,
                        onSearchExecuted = onSearchExecuted,
                        onCalendarActionLogged = onCalendarActionLogged
                    )
                }
            } else {
                runViaChatCompletionsApi(
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    model = model,
                    systemInstructions = systemInstructions,
                    conversationHistory = conversationHistory,
                    taskHint = taskHint,
                    settings = settings,
                    canUseLinkup = canUseLinkup,
                    canUseCalendar = canUseCalendar,
                    collectedSearches = collectedSearches,
                    onThinkingStatusUpdate = onThinkingStatusUpdate,
                    onSearchExecuted = onSearchExecuted,
                    onCalendarActionLogged = onCalendarActionLogged
                )
            }

            val tradContent = TraditionalChineseConverter.toTraditional(finalContent.trim())
            val spokenSummary = if (tradContent.length > 800) {
                tradContent.take(800) + "...（完整細節已留存在對話紀錄的思考重點中）"
            } else {
                tradContent
            }

            BackendBrainResponse(
                spokenSummary = spokenSummary,
                detailedNotes = tradContent,
                searchResults = collectedSearches
            )
        }
    }

    private suspend fun runViaOpenAiResponsesApi(
        baseUrl: String,
        apiKey: String,
        model: String,
        systemInstructions: String,
        conversationHistory: List<ChatMessage>,
        taskHint: String?,
        settings: AppSettings,
        canUseLinkup: Boolean,
        canUseCalendar: Boolean,
        collectedSearches: MutableList<LinkupSearchResult>,
        onThinkingStatusUpdate: (String) -> Unit,
        onSearchExecuted: (LinkupSearchResult) -> Unit,
        onCalendarActionLogged: (String) -> Unit
    ): String {
        val inputArr = JSONArray()
        conversationHistory
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT_VOICE }
            .takeLast(14)
            .forEach { msg ->
                val isUser = msg.role == MessageRole.USER
                val text = buildString {
                    if (msg.text.isNotBlank()) append(msg.text.trim())
                    if (msg.backendThinkingText.isNotBlank()) {
                        if (isNotEmpty()) append("\n")
                        append(msg.backendThinkingText.trim())
                    }
                }.trim()
                if (text.isNotEmpty()) {
                    inputArr.put(JSONObject().apply {
                        put("type", "message")
                        put("role", if (isUser) "user" else "assistant")
                        put("content", JSONArray().put(JSONObject().apply {
                            put("type", if (isUser) "input_text" else "output_text")
                            put("text", text.take(1500))
                        }))
                    })
                }
            }

        if (!taskHint.isNullOrBlank()) {
            inputArr.put(JSONObject().apply {
                put("type", "message")
                put("role", "user")
                put("content", JSONArray().put(JSONObject().apply {
                    put("type", "input_text")
                    put("text", taskHint)
                }))
            })
        }

        val toolsArr = JSONArray()
        if (canUseLinkup) {
            toolsArr.put(LinkupSearchClient.buildResponsesToolSchema())
        }
        if (canUseCalendar) {
            CalendarToolManager.buildResponsesCalendarToolSchemas().forEach { toolsArr.put(it) }
        }
        if (settings.openAiNativeSearchEnabled) {
            toolsArr.put(JSONObject().apply { put("type", "web_search") })
        }

        var finalText = ""
        for (round in 0 until 4) {
            val payload = JSONObject().apply {
                put("model", model)
                put("instructions", systemInstructions)
                put("input", inputArr)
                if (round < 3 && toolsArr.length() > 0) {
                    put("tools", toolsArr)
                    put("tool_choice", "auto")
                }
            }

            val req = Request.Builder()
                .url("$baseUrl/responses")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val respJson = httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Responses API HTTP ${resp.code}: ${body.take(200)}")
                }
                JSONObject(body)
            }

            val outputArr = respJson.optJSONArray("output") ?: JSONArray()
            val functionCalls = mutableListOf<JSONObject>()
            val textParts = StringBuilder()

            for (i in 0 until outputArr.length()) {
                val item = outputArr.optJSONObject(i) ?: continue
                when (item.optString("type")) {
                    "function_call" -> {
                        functionCalls.add(item)
                        inputArr.put(item)
                    }
                    "message" -> {
                        val contentArr = item.optJSONArray("content")
                        if (contentArr != null) {
                            for (j in 0 until contentArr.length()) {
                                val c = contentArr.optJSONObject(j) ?: continue
                                val t = c.optString("text", "")
                                if (t.isNotBlank()) {
                                    if (textParts.isNotEmpty()) textParts.append("\n")
                                    textParts.append(t)
                                }
                            }
                        }
                    }
                }
            }

            if (functionCalls.isEmpty()) {
                finalText = textParts.toString().ifBlank { respJson.optString("output_text", "") }
                break
            }

            for (fc in functionCalls) {
                val callId = fc.optString("call_id", "")
                val fnName = fc.optString("name", "")
                val argsStr = fc.optString("arguments", "{}")
                val argsJson = runCatching { JSONObject(argsStr) }.getOrDefault(JSONObject())

                val toolOutput: String = when {
                    fnName == "linkup_web_search" -> {
                        val query = argsJson.optString("query", taskHint ?: "")
                        val depth = argsJson.optString("depth", settings.linkupSearchDepth)
                        onThinkingStatusUpdate("正在透過 Linkup 搜尋最新資料：「$query」，若使用者說話請自然陪他聊天...")
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
                        onThinkingStatusUpdate("已完成 Linkup 搜尋「$query」，後端模型 $model 正在整理重點...")
                        searchRes.rawFormattedForLlm
                    }

                    CalendarToolManager.CALENDAR_TOOL_NAMES.contains(fnName) -> {
                        onThinkingStatusUpdate("正在執行手機行事曆操作 ($fnName)...")
                        val calRes = calendarToolManager.executeCalendarTool(fnName, argsJson)
                        onCalendarActionLogged("📅 行事曆操作 (`$fnName`):\n$calRes")
                        calRes
                    }

                    else -> "{\"status\":\"error\",\"message\":\"Unsupported tool: $fnName\"}"
                }

                inputArr.put(JSONObject().apply {
                    put("type", "function_call_output")
                    put("call_id", callId)
                    put("output", toolOutput)
                })
            }
        }
        return finalText
    }

    private suspend fun runViaChatCompletionsApi(
        baseUrl: String,
        apiKey: String,
        model: String,
        systemInstructions: String,
        conversationHistory: List<ChatMessage>,
        taskHint: String?,
        settings: AppSettings,
        canUseLinkup: Boolean,
        canUseCalendar: Boolean,
        collectedSearches: MutableList<LinkupSearchResult>,
        onThinkingStatusUpdate: (String) -> Unit,
        onSearchExecuted: (LinkupSearchResult) -> Unit,
        onCalendarActionLogged: (String) -> Unit
    ): String {
        val messagesArr = JSONArray()
        messagesArr.put(JSONObject().apply {
            put("role", "system")
            put("content", systemInstructions)
        })

        conversationHistory
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT_VOICE }
            .takeLast(14)
            .forEach { msg ->
                val roleStr = if (msg.role == MessageRole.USER) "user" else "assistant"
                val text = buildString {
                    if (msg.text.isNotBlank()) append(msg.text.trim())
                    if (msg.backendThinkingText.isNotBlank()) {
                        if (isNotEmpty()) append("\n")
                        append(msg.backendThinkingText.trim())
                    }
                }.trim()
                if (text.isNotEmpty()) {
                    messagesArr.put(JSONObject().apply {
                        put("role", roleStr)
                        put("content", text)
                    })
                }
            }

        if (!taskHint.isNullOrBlank()) {
            messagesArr.put(JSONObject().apply {
                put("role", "user")
                put("content", taskHint)
            })
        }

        val toolsArray = JSONArray()
        if (canUseLinkup) {
            toolsArray.put(LinkupSearchClient.buildChatCompletionsToolSchema())
        }
        if (canUseCalendar) {
            CalendarToolManager.buildChatCompletionsCalendarToolSchemas().forEach { toolsArray.put(it) }
        }

        var finalContent = ""
        for (round in 0 until 4) {
            val respJson = callChatCompletions(
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model,
                messages = messagesArr,
                tools = if (round < 3 && toolsArray.length() > 0) toolsArray else null
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
                        onThinkingStatusUpdate("正在透過 Linkup 搜尋最新資料：「$query」，若使用者說話請自然陪他聊天...")
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
                        onThinkingStatusUpdate("已完成 Linkup 搜尋「$query」，後端模型 $model 正在整理重點...")
                        searchRes.rawFormattedForLlm
                    }

                    CalendarToolManager.CALENDAR_TOOL_NAMES.contains(fnName) -> {
                        onThinkingStatusUpdate("正在執行手機行事曆操作 ($fnName)...")
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
        return finalContent
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
                .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT_VOICE }
                .take(4)
                .joinToString("\n") {
                    val content = it.text.ifBlank { it.backendThinkingText }
                    "${if (it.role == MessageRole.USER) "使用者" else "AI"}: ${content.take(150)}"
                }

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

            val rawTitle = resp.optJSONArray("choices")
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

            rawTitle?.let { TraditionalChineseConverter.toTraditional(it) }
        }.getOrNull()
    }

    /**
     * Silently analyzes a language-learning conversation turn in the background using the Backend Brain LLM.
     * Produces Furigana/pronunciation readings, Traditional Chinese translation, gentle grammar/naturalness
     * coaching on the user's utterance, key vocabulary, and 2 ready-to-read next-turn reply suggestions!
     */
    suspend fun generateTutorCoachNotes(
        settings: AppSettings,
        userUtterance: String,
        aiUtterance: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val (baseUrl, apiKey, model) = resolveBackendCredentials(settings)
            val langLabel = AppSettings.tutorLanguageLabel(settings.tutorTargetLanguage)
            val levelLabel = settings.tutorLevel.shortLabel
            val isJapanese = settings.tutorTargetLanguage == "ja"

            val readingInstruction = if (isJapanese) {
                "請將 AI 原句中的所有日文漢字後方加上括號標註平假名讀音（例如：今日(きょう)は何(なに)を食(た)べたいですか？），切勿遺漏任何漢字讀音！"
            } else {
                "請標註重點連音、重音或發音提示，幫助學習者正確跟讀。"
            }

            val systemPrompt = buildString {
                appendLine("你是專業、親切的【$langLabel】口說家教後端教練（學生程度：$levelLabel）。")
                appendLine("請針對剛才這一輪「使用者說的話」與「AI 陪練員的回覆」，用台灣繁體中文（日文內容請務必保留標準日文漢字與平假名/片假名）輸出簡潔、一眼秒懂的即時學習小抄卡。")
                appendLine("請嚴格按照以下四個區塊排版（不要加多餘寒暄）：")
                appendLine()
                appendLine("📖 【AI 原句標音與中譯】")
                appendLine("• 讀音：$readingInstruction")
                appendLine("• 中譯：（自然的台灣繁體中文翻譯）")
                appendLine()
                if (userUtterance.isNotBlank()) {
                    appendLine("✍️ 【你的口說點評與道地改寫】")
                    appendLine("• 點評使用者剛才說的：「$userUtterance」。若使用者夾雜了中文詞彙或助詞/文法不夠自然，請直接教他『💡 母語者道地說法（若為日文請附漢字平假名讀音）』並用一句話說明原因；若已經很自然，請給予肯定並提供一個進階/口語替換說法。")
                    appendLine()
                }
                appendLine("🔑 【本輪關鍵單字／句型】")
                appendLine("• 挑選本輪對話中最實用的 2 個單字、助詞用法或慣用句型（附讀音與中文解釋）。")
                appendLine()
                appendLine("🗣️ 【下一句你可以這樣回（可直接照著唸！）】")
                appendLine("• 根據 AI 剛才的提問或情境，提供 2 個最自然、符合學生程度（$levelLabel）的下一句外語回答範例（若為日文，所有漢字務必加上括號平假名讀音，並在下一行附上 👉 繁體中文意思），讓使用者不知道怎麼接話時可以直接照著唸出來！")
            }

            val userContent = buildString {
                if (userUtterance.isNotBlank()) {
                    appendLine("【使用者剛才說的話】：$userUtterance")
                } else {
                    appendLine("【使用者剛才說的話】：（本輪為 AI 開場白，使用者尚未發言）")
                }
                appendLine("【AI 陪練員剛才說的話】：$aiUtterance")
            }

            val isOpenAiEndpoint = baseUrl.contains("api.openai.com")
            val rawOutput: String = if (isOpenAiEndpoint) {
                runCatching {
                    val payload = JSONObject().apply {
                        put("model", model)
                        put("instructions", systemPrompt)
                        put("input", userContent)
                    }
                    val req = Request.Builder()
                        .url("$baseUrl/responses")
                        .addHeader("Authorization", "Bearer $apiKey")
                        .addHeader("Content-Type", "application/json")
                        .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()

                    val respJson = httpClient.newCall(req).execute().use { resp ->
                        val body = resp.body?.string() ?: ""
                        if (!resp.isSuccessful) {
                            throw IllegalStateException("Responses API HTTP ${resp.code}: ${body.take(200)}")
                        }
                        JSONObject(body)
                    }

                    val outputArr = respJson.optJSONArray("output") ?: JSONArray()
                    val textParts = StringBuilder()
                    for (i in 0 until outputArr.length()) {
                        val item = outputArr.optJSONObject(i) ?: continue
                        if (item.optString("type") == "message") {
                            val contentArr = item.optJSONArray("content")
                            if (contentArr != null) {
                                for (j in 0 until contentArr.length()) {
                                    val c = contentArr.optJSONObject(j) ?: continue
                                    val t = c.optString("text", "")
                                    if (t.isNotBlank()) {
                                        if (textParts.isNotEmpty()) textParts.append("\n")
                                        textParts.append(t)
                                    }
                                }
                            }
                        }
                    }
                    val extracted = textParts.toString().ifBlank { respJson.optString("output_text", "") }
                    if (extracted.isBlank()) throw IllegalStateException("Empty Responses API output")
                    extracted
                }.getOrElse {
                    val msgs = JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", systemPrompt)
                        })
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", userContent)
                        })
                    }
                    val resp = callChatCompletions(baseUrl, apiKey, model, msgs, null)
                    resp.optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content", "")
                        .orEmpty()
                }
            } else {
                val msgs = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", userContent)
                    })
                }
                val resp = callChatCompletions(baseUrl, apiKey, model, msgs, null)
                resp.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", "")
                    .orEmpty()
            }

            TraditionalChineseConverter.toTraditional(rawOutput.trim(), preserveJapanese = true)
        }
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
