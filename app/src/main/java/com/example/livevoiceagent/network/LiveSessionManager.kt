package com.example.livevoiceagent.network

import android.content.Context
import com.example.livevoiceagent.audio.FullDuplexAudioEngine
import com.example.livevoiceagent.model.*
import com.example.livevoiceagent.tools.CalendarToolManager
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class ConnectionState(val label: String) {
    DISCONNECTED("未連線"),
    CONNECTING("連線建立中..."),
    LIVE("即時通話中 (Full-Duplex)"),
    DELEGATING("後端大腦處理/搜尋/行事曆操作中..."),
    CLOSING("正在儲存並結束通話...")
}

interface LiveSessionListener {
    fun onConnectionStateChanged(state: ConnectionState, statusText: String)
    fun onRemoteSessionIdResolved(sessionId: String)
    fun onTranscriptDelta(
        role: MessageRole,
        delta: String,
        timelineMs: Long,
        modelTag: String,
        streamKey: String
    )
    fun onSearchCardAdded(result: LinkupSearchResult)
    fun onTurnCompleted()
    fun onMicLevelChanged(level: Float)
    fun onVoiceUsageUpdated(seconds: Int)
    fun onError(message: String)
}

class LiveSessionManager(
    private val context: Context,
    private val linkupClient: LinkupSearchClient,
    private val calendarToolManager: CalendarToolManager,
    private val backendLlmClient: BackendLlmClient,
    private val listener: LiveSessionListener
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var audioEngine: FullDuplexAudioEngine? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var activeSettings: AppSettings = AppSettings()
    private var activeProtocol: LiveProtocol = LiveProtocol.OPENAI_LIVE

    @Volatile
    private var sessionStarted: Boolean = false

    @Volatile
    private var isClosing: Boolean = false

    // Independent stream trackers so User speech, Assistant Voice, Backend Brain, and Linkup Search NEVER split or interrupt each other!
    private var sessionEpochId: String = UUID.randomUUID().toString().take(6)
    private var userTurnCounter: Int = 1
    private var voiceTurnCounter: Int = 1
    private var backendTurnCounter: Int = 1

    private var lastUserTranscriptEndMs: Long = -10_000L
    private var lastAssistantTranscriptEndMs: Long = -10_000L
    private var assistantCharsInCurrentTurn: Int = 0
    private var userCharsInCurrentTurn: Int = 0

    fun connect(
        settings: AppSettings,
        priorMessages: List<ChatMessage>,
        conversationSnapshotProvider: () -> List<ChatMessage>,
        useSpeakerphone: Boolean = true
    ) {
        disconnectImmediate()
        activeSettings = settings
        sessionStarted = false
        isClosing = false

        sessionEpochId = UUID.randomUUID().toString().take(6)
        userTurnCounter = 1
        voiceTurnCounter = 1
        backendTurnCounter = 1
        lastUserTranscriptEndMs = -10_000L
        lastAssistantTranscriptEndMs = -10_000L
        assistantCharsInCurrentTurn = 0
        userCharsInCurrentTurn = 0

        val frontModel = settings.frontendVoiceModel.trim().ifBlank { "gpt-live-1" }
        activeProtocol = when {
            frontModel.lowercase().startsWith("grok") || settings.protocol == LiveProtocol.XAI_REALTIME ->
                LiveProtocol.XAI_REALTIME
            frontModel.lowercase().startsWith("gpt-live") || settings.protocol == LiveProtocol.OPENAI_LIVE ->
                LiveProtocol.OPENAI_LIVE
            else ->
                LiveProtocol.OPENAI_REALTIME
        }

        val apiKey = when (activeProtocol) {
            LiveProtocol.XAI_REALTIME -> settings.xAiApiKey.trim()
            LiveProtocol.OPENAI_LIVE, LiveProtocol.OPENAI_REALTIME -> settings.openAiApiKey.trim()
        }

        if (apiKey.isBlank()) {
            val keyName = if (activeProtocol == LiveProtocol.XAI_REALTIME) "xAI API Key" else "OpenAI API Key"
            listener.onError("請先至「設定」頁面輸入 $keyName")
            listener.onConnectionStateChanged(ConnectionState.DISCONNECTED, "缺少 $keyName")
            return
        }

        val wsUrl = when (activeProtocol) {
            LiveProtocol.OPENAI_LIVE -> "wss://api.openai.com/v1/live/sessions"
            LiveProtocol.OPENAI_REALTIME -> "wss://api.openai.com/v1/realtime?model=$frontModel"
            LiveProtocol.XAI_REALTIME -> "wss://api.x.ai/v1/realtime?model=$frontModel"
        }

        listener.onConnectionStateChanged(
            ConnectionState.CONNECTING,
            "正在連線至 $frontModel (後端: ${settings.backendBrainModel})..."
        )

        val requestBuilder = Request.Builder()
            .url(wsUrl)
            .addHeader("Authorization", "Bearer $apiKey")

        if (activeProtocol == LiveProtocol.OPENAI_REALTIME) {
            requestBuilder.addHeader("OpenAI-Beta", "realtime=v1")
        }

        val engine = FullDuplexAudioEngine(
            context = context,
            sampleRate = 24000,
            onPcmBase64Captured = { base64Chunk ->
                sendAudioChunk(base64Chunk)
            },
            onMicLevelChanged = { level ->
                listener.onMicLevelChanged(level)
            }
        )
        audioEngine = engine

        webSocket = httpClient.newWebSocket(requestBuilder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                when (activeProtocol) {
                    LiveProtocol.OPENAI_LIVE -> {
                        sendOpenAiLiveSessionStart(webSocket, settings, priorMessages)
                    }
                    LiveProtocol.OPENAI_REALTIME, LiveProtocol.XAI_REALTIME -> {
                        sendRealtimeSessionUpdate(webSocket, settings)
                        sessionStarted = true
                        engine.start(useSpeakerphone = useSpeakerphone)
                        listener.onConnectionStateChanged(
                            ConnectionState.LIVE,
                            "已連線：$frontModel ⇄ ${settings.backendBrainModel}"
                        )
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (activeProtocol) {
                    LiveProtocol.OPENAI_LIVE -> handleOpenAiLiveEvent(
                        webSocket = webSocket,
                        event = event,
                        settings = settings,
                        useSpeakerphone = useSpeakerphone,
                        conversationSnapshotProvider = conversationSnapshotProvider
                    )
                    LiveProtocol.OPENAI_REALTIME, LiveProtocol.XAI_REALTIME -> handleRealtimeEvent(
                        webSocket = webSocket,
                        event = event,
                        settings = settings,
                        conversationSnapshotProvider = conversationSnapshotProvider
                    )
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val errBody = runCatching { response?.body?.string() }.getOrNull().orEmpty()
                val msg = "連線中斷: ${t.message ?: "未知錯誤"} ${if (errBody.isNotBlank()) "($errBody)" else ""}"
                listener.onError(msg.trim())
                disconnectImmediate()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                disconnectImmediate()
            }
        })
    }

    private fun sendOpenAiLiveSessionStart(
        ws: WebSocket,
        settings: AppSettings,
        priorMessages: List<ChatMessage>
    ) {
        val frontModel = settings.frontendVoiceModel.trim().ifBlank { "gpt-live-1" }
        val backModel = settings.backendBrainModel.trim().ifBlank { "gpt-6-luna" }
        val timeContext = CalendarToolManager.buildCurrentTimeContextString()

        val useClientDelegation = settings.delegationMode == DelegationMode.CLIENT ||
                backModel.lowercase().startsWith("grok") ||
                (settings.customBackendBaseUrl.isNotBlank() &&
                        !settings.customBackendBaseUrl.contains("api.openai.com"))

        val sessionObj = JSONObject().apply {
            put("model", frontModel)
            put("instructions", "$timeContext\n\n${settings.voiceSystemInstructions}")

            val seedArray = buildSeedInputArray(priorMessages)
            if (seedArray.length() > 0) {
                put("input", seedArray)
            }

            put("audio", JSONObject().apply {
                put("format", JSONObject().apply {
                    put("type", "audio/pcm")
                    put("rate", 24000)
                })
                put("output", JSONObject().apply {
                    put("voice", settings.voiceName.ifBlank { "marin" })
                })
            })

            if (useClientDelegation) {
                put("delegation", JSONObject().apply {
                    put("type", "client")
                })
            } else {
                val toolsArr = JSONArray()
                if (settings.linkupSearchEnabled && settings.linkupApiKey.isNotBlank()) {
                    toolsArr.put(LinkupSearchClient.buildResponsesToolSchema())
                }
                if (settings.calendarToolsEnabled) {
                    CalendarToolManager.buildResponsesCalendarToolSchemas().forEach { toolsArr.put(it) }
                }
                if (settings.openAiNativeSearchEnabled) {
                    toolsArr.put(JSONObject().apply { put("type", "web_search") })
                }

                put("delegation", JSONObject().apply {
                    put("type", "responses")
                    put("responses", JSONObject().apply {
                        put("model", backModel)
                        put("instructions", "$timeContext\n\n${settings.backendSystemInstructions}")
                        if (toolsArr.length() > 0) {
                            put("tools", toolsArr)
                            put("tool_choice", "auto")
                            put("parallel_tool_calls", true)
                        }
                    })
                })
            }
        }

        val startEvent = JSONObject().apply {
            put("type", "session.start")
            put("event_id", "event_start_${UUID.randomUUID().toString().take(8)}")
            put("session", sessionObj)
        }

        ws.send(startEvent.toString())
    }

    private fun buildSeedInputArray(priorMessages: List<ChatMessage>): JSONArray {
        val arr = JSONArray()
        priorMessages
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT_VOICE || it.role == MessageRole.BACKEND_BRAIN }
            .takeLast(64)
            .forEach { msg ->
                val text = msg.text.trim()
                if (text.isNotEmpty()) {
                    val isUser = msg.role == MessageRole.USER
                    arr.put(JSONObject().apply {
                        put("type", "message")
                        put("role", if (isUser) "user" else "assistant")
                        put("content", JSONArray().put(JSONObject().apply {
                            put("type", if (isUser) "input_text" else "output_text")
                            put("text", text.take(1500))
                        }))
                    })
                }
            }
        return arr
    }

    private fun handleOpenAiLiveEvent(
        webSocket: WebSocket,
        event: JSONObject,
        settings: AppSettings,
        useSpeakerphone: Boolean,
        conversationSnapshotProvider: () -> List<ChatMessage>
    ) {
        when (event.optString("type")) {
            "session.started" -> {
                sessionStarted = true
                val sessionObj = event.optJSONObject("session")
                val remoteId = sessionObj?.optString("id", "").orEmpty()
                if (remoteId.isNotBlank()) {
                    listener.onRemoteSessionIdResolved(remoteId)
                }
                audioEngine?.start(useSpeakerphone = useSpeakerphone)
                listener.onConnectionStateChanged(
                    ConnectionState.LIVE,
                    "通話中：${settings.frontendVoiceModel} ⇄ ${settings.backendBrainModel}"
                )
            }

            "session.output_audio.delta" -> {
                val delta = event.optString("delta", "")
                if (delta.isNotEmpty()) {
                    audioEngine?.enqueueOutputAudioBase64(delta)
                }
            }

            "session.input_transcript.delta" -> {
                val delta = event.optString("delta", "")
                val startMs = event.optLong("start_ms", 0L)
                val endMs = event.optLong("end_ms", startMs)
                if (delta.isNotEmpty()) {
                    val gapMs = startMs - lastUserTranscriptEndMs
                    // Start a new user bubble ONLY if the user paused for > 5.5s, or if the AI already gave a full reply (> 25 chars) and user paused > 2.2s
                    if (lastUserTranscriptEndMs >= 0L && (gapMs > 5500L || (assistantCharsInCurrentTurn > 25 && gapMs > 2200L))) {
                        userTurnCounter++
                        userCharsInCurrentTurn = 0
                        assistantCharsInCurrentTurn = 0
                    }
                    lastUserTranscriptEndMs = maxOf(lastUserTranscriptEndMs, endMs)
                    userCharsInCurrentTurn += delta.length

                    val streamKey = "user_${sessionEpochId}_$userTurnCounter"
                    listener.onTranscriptDelta(
                        role = MessageRole.USER,
                        delta = delta,
                        timelineMs = startMs,
                        modelTag = "User",
                        streamKey = streamKey
                    )
                }
            }

            "session.output_transcript.delta" -> {
                val delta = event.optString("delta", "")
                val startMs = event.optLong("start_ms", 0L)
                val endMs = event.optLong("end_ms", startMs)
                if (delta.isNotEmpty()) {
                    val gapMs = startMs - lastAssistantTranscriptEndMs
                    // Start a new assistant voice bubble ONLY if the assistant paused for > 5s, or if user spoke a new turn (> 6 chars) and assistant paused > 1.8s
                    if (lastAssistantTranscriptEndMs >= 0L && (gapMs > 5000L || (userCharsInCurrentTurn > 6 && gapMs > 1800L))) {
                        voiceTurnCounter++
                        userCharsInCurrentTurn = 0
                    }
                    lastAssistantTranscriptEndMs = maxOf(lastAssistantTranscriptEndMs, endMs)
                    assistantCharsInCurrentTurn += delta.length

                    val streamKey = "voice_${sessionEpochId}_$voiceTurnCounter"
                    listener.onTranscriptDelta(
                        role = MessageRole.ASSISTANT_VOICE,
                        delta = delta,
                        timelineMs = startMs,
                        modelTag = settings.frontendVoiceModel,
                        streamKey = streamKey
                    )
                    listener.onTurnCompleted()
                }
            }

            "session.delegation.created" -> {
                val delegationObj = event.optJSONObject("delegation")
                val delegationId = delegationObj?.optString("id", "").orEmpty()
                val target = delegationObj?.optString("target", "responses").orEmpty()

                listener.onConnectionStateChanged(
                    ConnectionState.DELEGATING,
                    "後端 ${settings.backendBrainModel} 正在推理/搜尋/處理任務..."
                )

                if (target == "client" && delegationId.isNotBlank()) {
                    scope.launch {
                        val snapshot = conversationSnapshotProvider()
                        val res = backendLlmClient.runDelegatedTask(
                            settings = settings,
                            conversationHistory = snapshot,
                            onSearchExecuted = { searchResult ->
                                listener.onSearchCardAdded(searchResult)
                            },
                            onCalendarActionLogged = { calNote ->
                                listener.onTranscriptDelta(
                                    role = MessageRole.SYSTEM,
                                    delta = calNote,
                                    timelineMs = System.currentTimeMillis(),
                                    modelTag = "Calendar",
                                    streamKey = "cal_${UUID.randomUUID().toString().take(8)}"
                                )
                            }
                        )
                        res.onSuccess { brainResp ->
                            if (brainResp.detailedNotes.isNotBlank()) {
                                listener.onTranscriptDelta(
                                    role = MessageRole.BACKEND_BRAIN,
                                    delta = brainResp.detailedNotes,
                                    timelineMs = System.currentTimeMillis(),
                                    modelTag = settings.backendBrainModel,
                                    streamKey = "backend_$delegationId"
                                )
                            }
                            val commentaryEvent = JSONObject().apply {
                                put("type", "session.commentary.append")
                                put("event_id", "commentary_${UUID.randomUUID().toString().take(8)}")
                                put("delegation_id", delegationId)
                                put("content", brainResp.spokenSummary.take(1200))
                            }
                            webSocket.send(commentaryEvent.toString())
                            listener.onTurnCompleted()
                            listener.onConnectionStateChanged(
                                ConnectionState.LIVE,
                                "通話中：${settings.frontendVoiceModel} ⇄ ${settings.backendBrainModel}"
                            )
                        }.onFailure { err ->
                            listener.onError("後端委派執行失敗: ${err.message}")
                            listener.onConnectionStateChanged(
                                ConnectionState.LIVE,
                                "通話中：${settings.frontendVoiceModel} ⇄ ${settings.backendBrainModel}"
                            )
                        }
                    }
                }
            }

            "response.event" -> {
                val delegationId = event.optString("delegation_id", "").ifBlank { "resp_$backendTurnCounter" }
                val inner = event.optJSONObject("event") ?: return
                val innerType = inner.optString("type", "")
                when (innerType) {
                    "response.created" -> {
                        backendTurnCounter++
                    }
                    "response.output_text.delta" -> {
                        val delta = inner.optString("delta", "")
                        val itemId = inner.optString("item_id", delegationId)
                        if (delta.isNotEmpty()) {
                            // Uses its own dedicated streamKey ("backend_<delegationId>_<itemId>") so it NEVER collides with voice or user text!
                            val backendStreamKey = "backend_${delegationId}_$itemId"
                            listener.onTranscriptDelta(
                                role = MessageRole.BACKEND_BRAIN,
                                delta = delta,
                                timelineMs = System.currentTimeMillis(),
                                modelTag = settings.backendBrainModel,
                                streamKey = backendStreamKey
                            )
                        }
                    }
                    "response.output_item.done" -> {
                        val item = inner.optJSONObject("item") ?: return
                        if (item.optString("type") == "function_call") {
                            val callId = item.optString("call_id", "")
                            val fnName = item.optString("name", "")
                            val argsStr = item.optString("arguments", "{}")
                            if (callId.isNotBlank()) {
                                handleResponsesFunctionToolCall(webSocket, callId, fnName, argsStr, settings)
                            }
                        }
                    }
                    "response.completed" -> {
                        listener.onTurnCompleted()
                        listener.onConnectionStateChanged(
                            ConnectionState.LIVE,
                            "通話中：${settings.frontendVoiceModel} ⇄ ${settings.backendBrainModel}"
                        )
                    }
                }
            }

            "session.usage.updated" -> {
                val usageSeconds = event.optJSONObject("usage")?.optInt("seconds", 0) ?: 0
                listener.onVoiceUsageUpdated(usageSeconds)
            }

            "session.closed" -> {
                val usageSeconds = event.optJSONObject("usage")?.optInt("seconds", 0) ?: 0
                if (usageSeconds > 0) listener.onVoiceUsageUpdated(usageSeconds)
                disconnectImmediate()
            }

            "error" -> {
                val errObj = event.optJSONObject("error")
                val errMsg = errObj?.optString("message", event.toString()) ?: event.toString()
                listener.onError("Live API 錯誤: $errMsg")
            }
        }
    }

    private fun handleResponsesFunctionToolCall(
        webSocket: WebSocket,
        callId: String,
        fnName: String,
        argsStr: String,
        settings: AppSettings
    ) {
        scope.launch {
            val argsJson = runCatching { JSONObject(argsStr) }.getOrDefault(JSONObject())
            val toolOutputText: String

            when {
                fnName == "linkup_web_search" -> {
                    val query = argsJson.optString("query", "")
                    val depth = argsJson.optString("depth", settings.linkupSearchDepth)

                    listener.onConnectionStateChanged(
                        ConnectionState.DELEGATING,
                        "正在透過 Linkup 搜尋: $query ..."
                    )

                    val searchResult = linkupClient.search(
                        apiKey = settings.linkupApiKey,
                        query = query,
                        depth = depth
                    ).getOrElse { err ->
                        LinkupSearchResult(
                            query = query,
                            answer = "Linkup 搜尋錯誤: ${err.message}",
                            sources = emptyList(),
                            rawFormattedForLlm = "Linkup 搜尋失敗: ${err.message}"
                        )
                    }

                    listener.onSearchCardAdded(searchResult)
                    toolOutputText = searchResult.rawFormattedForLlm
                }

                CalendarToolManager.CALENDAR_TOOL_NAMES.contains(fnName) -> {
                    listener.onConnectionStateChanged(
                        ConnectionState.DELEGATING,
                        "正在執行手機行事曆操作 ($fnName)..."
                    )
                    val calJson = calendarToolManager.executeCalendarTool(fnName, argsJson)
                    listener.onTranscriptDelta(
                        role = MessageRole.SYSTEM,
                        delta = "📅 行事曆操作 (`$fnName`):\n$calJson",
                        timelineMs = System.currentTimeMillis(),
                        modelTag = "Android Calendar",
                        streamKey = "cal_$callId"
                    )
                    toolOutputText = calJson
                }

                else -> {
                    toolOutputText = "{\"status\":\"error\",\"message\":\"Unknown tool: $fnName\"}"
                }
            }

            val itemCreateEvent = JSONObject().apply {
                put("type", "response.item.create")
                put("event_id", "tool_res_${UUID.randomUUID().toString().take(8)}")
                put("item", JSONObject().apply {
                    put("type", "function_call_output")
                    put("call_id", callId)
                    put("output", toolOutputText)
                })
            }
            webSocket.send(itemCreateEvent.toString())

            val continueEvent = JSONObject().apply {
                put("type", "response.create")
                put("event_id", "continue_${UUID.randomUUID().toString().take(8)}")
            }
            webSocket.send(continueEvent.toString())
        }
    }

    private fun sendRealtimeSessionUpdate(ws: WebSocket, settings: AppSettings) {
        val timeContext = CalendarToolManager.buildCurrentTimeContextString()
        val toolsArr = JSONArray()

        toolsArr.put(JSONObject().apply {
            put("type", "function")
            put("name", "delegate_to_backend_brain")
            put(
                "description",
                "Delegate complex reasoning, detailed explanations, multi-step analysis, calendar management, or web research tasks to the backend LLM (${settings.backendBrainModel})."
            )
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("task_query", JSONObject().apply {
                        put("type", "string")
                        put("description", "The question or task to be solved by the backend brain LLM.")
                    })
                })
                put("required", JSONArray().put("task_query"))
            })
        })

        if (settings.linkupSearchEnabled && settings.linkupApiKey.isNotBlank()) {
            toolsArr.put(LinkupSearchClient.buildResponsesToolSchema())
        }

        if (settings.calendarToolsEnabled) {
            CalendarToolManager.buildResponsesCalendarToolSchemas().forEach { toolsArr.put(it) }
        }

        val sessionUpdate = JSONObject().apply {
            put("type", "session.update")
            put("session", JSONObject().apply {
                put("instructions", "$timeContext\n\n${settings.voiceSystemInstructions}")
                put("voice", settings.voiceName.ifBlank {
                    if (activeProtocol == LiveProtocol.XAI_REALTIME) "eve" else "alloy"
                })
                put("input_audio_format", "pcm16")
                put("output_audio_format", "pcm16")
                put("input_audio_transcription", JSONObject().apply {
                    put("model", "whisper-1")
                })
                put("turn_detection", JSONObject().apply {
                    put("type", "server_vad")
                })
                put("tools", toolsArr)
                put("tool_choice", "auto")
            })
        }
        ws.send(sessionUpdate.toString())
    }

    private fun handleRealtimeEvent(
        webSocket: WebSocket,
        event: JSONObject,
        settings: AppSettings,
        conversationSnapshotProvider: () -> List<ChatMessage>
    ) {
        when (event.optString("type")) {
            "session.created", "session.updated" -> {
                val id = event.optJSONObject("session")?.optString("id", "").orEmpty()
                if (id.isNotBlank()) listener.onRemoteSessionIdResolved(id)
            }

            "input_audio_buffer.speech_started" -> {
                audioEngine?.clearPlaybackBuffer()
            }

            "conversation.item.input_audio_transcription.completed" -> {
                val transcript = event.optString("transcript", "")
                val itemId = event.optString("item_id", "${userTurnCounter++}")
                if (transcript.isNotBlank()) {
                    listener.onTranscriptDelta(
                        role = MessageRole.USER,
                        delta = transcript,
                        timelineMs = System.currentTimeMillis(),
                        modelTag = "User",
                        streamKey = "rt_user_$itemId"
                    )
                }
            }

            "response.audio.delta", "response.output_audio.delta" -> {
                val delta = event.optString("delta", "")
                if (delta.isNotEmpty()) {
                    audioEngine?.enqueueOutputAudioBase64(delta)
                }
            }

            "response.audio_transcript.delta", "response.output_audio_transcript.delta" -> {
                val delta = event.optString("delta", "")
                val itemId = event.optString("item_id", event.optString("response_id", "$voiceTurnCounter"))
                if (delta.isNotEmpty()) {
                    listener.onTranscriptDelta(
                        role = MessageRole.ASSISTANT_VOICE,
                        delta = delta,
                        timelineMs = System.currentTimeMillis(),
                        modelTag = settings.frontendVoiceModel,
                        streamKey = "rt_voice_$itemId"
                    )
                }
            }

            "response.done" -> {
                voiceTurnCounter++
                listener.onTurnCompleted()
            }

            "response.function_call_arguments.done" -> {
                val callId = event.optString("call_id", "")
                val fnName = event.optString("name", "")
                val argsStr = event.optString("arguments", "{}")
                if (callId.isNotBlank()) {
                    handleRealtimeFunctionCall(
                        webSocket = webSocket,
                        callId = callId,
                        fnName = fnName,
                        argsStr = argsStr,
                        settings = settings,
                        conversationSnapshotProvider = conversationSnapshotProvider
                    )
                }
            }

            "error" -> {
                val errMsg = event.optJSONObject("error")?.optString("message", event.toString()) ?: event.toString()
                listener.onError("Realtime API 錯誤: $errMsg")
            }
        }
    }

    private fun handleRealtimeFunctionCall(
        webSocket: WebSocket,
        callId: String,
        fnName: String,
        argsStr: String,
        settings: AppSettings,
        conversationSnapshotProvider: () -> List<ChatMessage>
    ) {
        scope.launch {
            val argsJson = runCatching { JSONObject(argsStr) }.getOrDefault(JSONObject())
            val outputText: String

            when {
                fnName == "linkup_web_search" -> {
                    val query = argsJson.optString("query", "")
                    val depth = argsJson.optString("depth", settings.linkupSearchDepth)
                    listener.onConnectionStateChanged(ConnectionState.DELEGATING, "Linkup 搜尋中: $query")

                    val res = linkupClient.search(settings.linkupApiKey, query, depth).getOrElse { err ->
                        LinkupSearchResult(query, "搜尋失敗: ${err.message}", emptyList(), "搜尋失敗: ${err.message}")
                    }
                    listener.onSearchCardAdded(res)
                    outputText = res.rawFormattedForLlm
                }

                CalendarToolManager.CALENDAR_TOOL_NAMES.contains(fnName) -> {
                    listener.onConnectionStateChanged(ConnectionState.DELEGATING, "正在執行行事曆操作 ($fnName)...")
                    val calJson = calendarToolManager.executeCalendarTool(fnName, argsJson)
                    listener.onTranscriptDelta(
                        role = MessageRole.SYSTEM,
                        delta = "📅 行事曆操作 (`$fnName`):\n$calJson",
                        timelineMs = System.currentTimeMillis(),
                        modelTag = "Android Calendar",
                        streamKey = "cal_$callId"
                    )
                    outputText = calJson
                }

                else -> {
                    val taskQuery = argsJson.optString("task_query", "")
                    listener.onConnectionStateChanged(
                        ConnectionState.DELEGATING,
                        "委派後端 ${settings.backendBrainModel} 處理中..."
                    )
                    val res = backendLlmClient.runDelegatedTask(
                        settings = settings,
                        conversationHistory = conversationSnapshotProvider(),
                        taskHint = taskQuery,
                        onSearchExecuted = { searchResult ->
                            listener.onSearchCardAdded(searchResult)
                        },
                        onCalendarActionLogged = { calNote ->
                            listener.onTranscriptDelta(
                                role = MessageRole.SYSTEM,
                                delta = calNote,
                                timelineMs = System.currentTimeMillis(),
                                modelTag = "Calendar",
                                streamKey = "cal_${UUID.randomUUID().toString().take(8)}"
                            )
                        }
                    )
                    outputText = res.fold(
                        onSuccess = { brainResp ->
                            if (brainResp.detailedNotes.isNotBlank()) {
                                listener.onTranscriptDelta(
                                    role = MessageRole.BACKEND_BRAIN,
                                    delta = brainResp.detailedNotes,
                                    timelineMs = System.currentTimeMillis(),
                                    modelTag = settings.backendBrainModel,
                                    streamKey = "backend_$callId"
                                )
                            }
                            brainResp.spokenSummary
                        },
                        onFailure = { err -> "後端處理錯誤: ${err.message}" }
                    )
                }
            }

            val itemCreate = JSONObject().apply {
                put("type", "conversation.item.create")
                put("item", JSONObject().apply {
                    put("type", "function_call_output")
                    put("call_id", callId)
                    put("output", outputText)
                })
            }
            webSocket.send(itemCreate.toString())

            val respCreate = JSONObject().apply {
                put("type", "response.create")
            }
            webSocket.send(respCreate.toString())

            listener.onConnectionStateChanged(
                ConnectionState.LIVE,
                "通話中：${settings.frontendVoiceModel} ⇄ ${settings.backendBrainModel}"
            )
        }
    }

    private fun sendAudioChunk(base64Pcm: String) {
        val ws = webSocket ?: return
        if (!sessionStarted || isClosing) return

        val event = when (activeProtocol) {
            LiveProtocol.OPENAI_LIVE -> JSONObject().apply {
                put("type", "session.input_audio.append")
                put("audio", base64Pcm)
            }
            LiveProtocol.OPENAI_REALTIME, LiveProtocol.XAI_REALTIME -> JSONObject().apply {
                put("type", "input_audio_buffer.append")
                put("audio", base64Pcm)
            }
        }
        ws.send(event.toString())
    }

    fun sendTypedUserMessage(text: String, conversationSnapshotProvider: () -> List<ChatMessage>) {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return

        userTurnCounter++
        listener.onTranscriptDelta(
            role = MessageRole.USER,
            delta = cleanText,
            timelineMs = System.currentTimeMillis(),
            modelTag = "User (文字輸入)",
            streamKey = "typed_${UUID.randomUUID().toString().take(8)}"
        )

        val ws = webSocket
        if (ws == null || !sessionStarted) return

        when (activeProtocol) {
            LiveProtocol.OPENAI_LIVE -> {
                if (activeSettings.delegationMode == DelegationMode.RESPONSES) {
                    val itemCreate = JSONObject().apply {
                        put("type", "response.item.create")
                        put("event_id", "typed_${UUID.randomUUID().toString().take(8)}")
                        put("item", JSONObject().apply {
                            put("type", "message")
                            put("role", "user")
                            put("content", JSONArray().put(JSONObject().apply {
                                put("type", "input_text")
                                put("text", cleanText)
                            }))
                        })
                    }
                    ws.send(itemCreate.toString())
                    ws.send(JSONObject().apply {
                        put("type", "response.create")
                        put("event_id", "resp_${UUID.randomUUID().toString().take(8)}")
                    }.toString())
                } else {
                    ws.send(JSONObject().apply {
                        put("type", "session.thinking.append")
                        put("event_id", "think_${UUID.randomUUID().toString().take(8)}")
                        put("delegation_id", JSONObject.NULL)
                        put("content", "User typed: $cleanText")
                    }.toString())

                    scope.launch {
                        val res = backendLlmClient.runDelegatedTask(
                            settings = activeSettings,
                            conversationHistory = conversationSnapshotProvider(),
                            taskHint = cleanText,
                            onSearchExecuted = { listener.onSearchCardAdded(it) },
                            onCalendarActionLogged = { calNote ->
                                listener.onTranscriptDelta(
                                    role = MessageRole.SYSTEM,
                                    delta = calNote,
                                    timelineMs = System.currentTimeMillis(),
                                    modelTag = "Calendar",
                                    streamKey = "cal_${UUID.randomUUID().toString().take(8)}"
                                )
                            }
                        )
                        res.onSuccess { brain ->
                            listener.onTranscriptDelta(
                                role = MessageRole.BACKEND_BRAIN,
                                delta = brain.detailedNotes,
                                timelineMs = System.currentTimeMillis(),
                                modelTag = activeSettings.backendBrainModel,
                                streamKey = "backend_typed_${UUID.randomUUID().toString().take(8)}"
                            )
                            ws.send(JSONObject().apply {
                                put("type", "session.commentary.append")
                                put("event_id", "comm_${UUID.randomUUID().toString().take(8)}")
                                put("delegation_id", JSONObject.NULL)
                                put("content", brain.spokenSummary.take(1200))
                            }.toString())
                            listener.onTurnCompleted()
                        }
                    }
                }
            }

            LiveProtocol.OPENAI_REALTIME, LiveProtocol.XAI_REALTIME -> {
                val itemCreate = JSONObject().apply {
                    put("type", "conversation.item.create")
                    put("item", JSONObject().apply {
                        put("type", "message")
                        put("role", "user")
                        put("content", JSONArray().put(JSONObject().apply {
                            put("type", "input_text")
                            put("text", cleanText)
                        }))
                    })
                }
                ws.send(itemCreate.toString())
                ws.send(JSONObject().apply { put("type", "response.create") }.toString())
            }
        }
    }

    fun setMuted(muted: Boolean) {
        audioEngine?.isMuted = muted
        val ws = webSocket ?: return
        if (sessionStarted && activeProtocol == LiveProtocol.OPENAI_LIVE) {
            val typeStr = if (muted) "session.input_audio.mute" else "session.input_audio.unmute"
            ws.send(JSONObject().apply {
                put("type", typeStr)
                put("event_id", "mute_${UUID.randomUUID().toString().take(6)}")
            }.toString())
        }
    }

    fun setSpeakerphone(enabled: Boolean) {
        audioEngine?.setSpeakerphone(enabled)
    }

    fun disconnectGracefully() {
        val ws = webSocket
        if (ws != null && sessionStarted && activeProtocol == LiveProtocol.OPENAI_LIVE && !isClosing) {
            isClosing = true
            audioEngine?.stop()
            audioEngine = null
            listener.onConnectionStateChanged(ConnectionState.CLOSING, "正在等待 session.closed 並儲存紀錄...")
            runCatching {
                ws.send(JSONObject().apply { put("type", "session.close") }.toString())
            }
            scope.launch {
                delay(2500)
                if (isClosing) {
                    disconnectImmediate()
                }
            }
        } else {
            disconnectImmediate()
        }
    }

    fun disconnectImmediate() {
        isClosing = false
        sessionStarted = false
        audioEngine?.stop()
        audioEngine = null
        runCatching { webSocket?.close(1000, "User ended session") }
        webSocket = null
        listener.onConnectionStateChanged(ConnectionState.DISCONNECTED, "未連線 (紀錄已自動保存)")
    }
}
