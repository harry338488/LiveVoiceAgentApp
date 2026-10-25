package com.example.livevoiceagent.network

import android.content.Context
import com.example.livevoiceagent.audio.FullDuplexAudioEngine
import com.example.livevoiceagent.model.*
import com.example.livevoiceagent.tools.CalendarToolManager
import com.example.livevoiceagent.util.TraditionalChineseConverter
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
    DELEGATING("後端搜尋/處理中 (仍可繼續語音聊天)..."),
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
        streamKey: String,
        turnIndex: Int
    )
    fun onSearchCardAdded(result: LinkupSearchResult, turnIndex: Int)
    fun onVoiceClipSaved(turnIndex: Int, clipFilePath: String)
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

    @Volatile
    private var isClientDelegationSession: Boolean = true

    // Turn-based state machine: guarantees 1 unbroken User bubble + 1 unbroken Assistant Voice bubble per turn,
    // with Backend Brain & Linkup Search cards neatly attached below the voice bubble of that turn.
    private var sessionEpochId: String = UUID.randomUUID().toString().take(6)
    @Volatile
    private var currentTurnIndex: Int = 1
    @Volatile
    private var userCharsInTurn: Int = 0
    @Volatile
    private var voiceCharsInTurn: Int = 0
    @Volatile
    private var voiceCharsAfterDelegation: Int = 0
    @Volatile
    private var isDelegationActive: Boolean = false
    @Volatile
    private var isToolCallRunning: Boolean = false
    @Volatile
    private var delegationCompletedInTurn: Boolean = false
    @Volatile
    private var needsVoiceParagraphBreak: Boolean = false
    @Volatile
    private var lastUserDeltaWallMs: Long = 0L
    @Volatile
    private var lastVoiceDeltaWallMs: Long = 0L
    @Volatile
    private var lastDelegationDoneWallMs: Long = 0L
    @Volatile
    private var lastBackendItemId: String = ""

    private var tutorCoachDebounceJob: Job? = null
    private val coachedVoiceTextByTurn = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private var activeNotebookEntries: List<TutorNotebookEntry> = emptyList()

    private val audioClipLock = Any()
    private val currentTurnAudioBuffer = java.io.ByteArrayOutputStream(96000)
    @Volatile
    private var currentAudioTurnIndex: Int = -1
    private var audioClipSaveDebounceJob: Job? = null

    private fun appendOutputAudioAndScheduleClipSave(base64Delta: String) {
        val pcmBytes = audioEngine?.enqueueOutputAudioBase64(base64Delta) ?: return
        val turnIdx = currentTurnIndex
        synchronized(audioClipLock) {
            if (currentAudioTurnIndex != -1 && currentAudioTurnIndex != turnIdx) {
                flushAudioClipLocked(currentAudioTurnIndex)
            }
            currentAudioTurnIndex = turnIdx
            // Cap per-turn clip at ~45 seconds (2.16 MB) to keep cache lightweight
            if (currentTurnAudioBuffer.size() < 2_160_000) {
                currentTurnAudioBuffer.write(pcmBytes)
            }
        }
        audioClipSaveDebounceJob?.cancel()
        audioClipSaveDebounceJob = scope.launch {
            delay(850)
            synchronized(audioClipLock) {
                if (currentAudioTurnIndex == turnIdx) {
                    flushAudioClipLocked(turnIdx)
                }
            }
        }
    }

    private fun flushAudioClipLocked(turnIdx: Int) {
        if (turnIdx <= 0 || currentTurnAudioBuffer.size() < 2400) return
        val bytes = currentTurnAudioBuffer.toByteArray()
        runCatching {
            val dir = java.io.File(context.cacheDir, "voice_clips").apply { mkdirs() }
            val clipFile = java.io.File(dir, "clip_${sessionEpochId}_turn_${turnIdx}.pcm")
            clipFile.writeBytes(bytes)
            listener.onVoiceClipSaved(turnIdx, clipFile.absolutePath)

            // Prune oldest clips if > 45 files
            val allFiles = dir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
            if (allFiles.size > 45) {
                allFiles.drop(45).forEach { runCatching { it.delete() } }
            }
        }
    }

    fun playSavedVoiceClip(filePath: String, speed: Float = 1.0f) {
        val durationMs = FullDuplexAudioEngine.playSavedPcmClip(filePath = filePath, speed = speed)
        if (durationMs > 0L) {
            audioEngine?.extendSpeakerProtectionWindow(durationMs + 450L)
        }
    }

    private fun advanceToNextTurn() {
        synchronized(audioClipLock) {
            if (currentAudioTurnIndex > 0) {
                flushAudioClipLocked(currentAudioTurnIndex)
                currentTurnAudioBuffer.reset()
                currentAudioTurnIndex = -1
            }
        }
        currentTurnIndex++
        userCharsInTurn = 0
        voiceCharsInTurn = 0
        voiceCharsAfterDelegation = 0
        isDelegationActive = false
        isToolCallRunning = false
        delegationCompletedInTurn = false
        needsVoiceParagraphBreak = false
        lastBackendItemId = ""
    }

    private fun scheduleTutorTurnCoachAnalysis(
        turnIdx: Int,
        conversationSnapshotProvider: () -> List<ChatMessage>
    ) {
        if (activeSettings.appMode != AppMode.TUTOR || !activeSettings.tutorAutoCoachEnabled) return
        tutorCoachDebounceJob?.cancel()
        tutorCoachDebounceJob = scope.launch {
            delay(1150)
            val snapshot = conversationSnapshotProvider()
            val aiMsg = snapshot.lastOrNull { it.turnIndex == turnIdx && it.role == MessageRole.ASSISTANT_VOICE }
            val aiText = aiMsg?.text?.trim().orEmpty()
            if (aiText.length < 2) return@launch
            if (coachedVoiceTextByTurn[turnIdx] == aiText) return@launch
            coachedVoiceTextByTurn[turnIdx] = aiText

            val userMsg = snapshot.lastOrNull { it.turnIndex == turnIdx && it.role == MessageRole.USER }
            val userText = userMsg?.text?.trim().orEmpty()

            val res = backendLlmClient.generateTutorCoachNotes(
                settings = activeSettings,
                userUtterance = userText,
                aiUtterance = aiText
            )
            res.onSuccess { coachNotes ->
                if (coachNotes.isNotBlank()) {
                    listener.onTranscriptDelta(
                        role = MessageRole.BACKEND_BRAIN,
                        delta = coachNotes,
                        timelineMs = System.currentTimeMillis(),
                        modelTag = "🎓 AI 家教解析 (${activeSettings.backendBrainModel})",
                        streamKey = "tutor_coach_replace_$turnIdx",
                        turnIndex = turnIdx
                    )
                }
            }
        }
    }

    fun connect(
        settings: AppSettings,
        priorMessages: List<ChatMessage>,
        notebookEntries: List<TutorNotebookEntry> = emptyList(),
        conversationSnapshotProvider: () -> List<ChatMessage>,
        useSpeakerphone: Boolean = true
    ) {
        disconnectImmediate()
        activeSettings = settings
        activeNotebookEntries = notebookEntries
        sessionStarted = false
        isClosing = false

        sessionEpochId = UUID.randomUUID().toString().take(6)
        val maxPriorTurn = priorMessages.maxOfOrNull { it.turnIndex } ?: 0
        currentTurnIndex = maxOf(maxPriorTurn, priorMessages.size) + 1
        userCharsInTurn = 0
        voiceCharsInTurn = 0
        voiceCharsAfterDelegation = 0
        isDelegationActive = false
        isToolCallRunning = false
        delegationCompletedInTurn = false
        needsVoiceParagraphBreak = false
        lastUserDeltaWallMs = 0L
        lastVoiceDeltaWallMs = 0L
        lastDelegationDoneWallMs = 0L
        lastBackendItemId = ""
        tutorCoachDebounceJob?.cancel()
        coachedVoiceTextByTurn.clear()

        val rawFront = settings.frontendVoiceModel.trim().ifBlank { "gpt-live-1" }
        val isTutorMode = settings.appMode == AppMode.TUTOR
        val isTranslateModeRequested =
            !isTutorMode && (settings.appMode == AppMode.TRANSLATE ||
                    settings.protocol == LiveProtocol.OPENAI_TRANSLATE ||
                    rawFront.lowercase().contains("translate"))

        val frontModel = if ((isTutorMode || settings.appMode == AppMode.ASSISTANT) && rawFront.lowercase().contains("translate")) {
            "gpt-live-1"
        } else {
            rawFront
        }

        activeProtocol = when {
            isTranslateModeRequested && settings.usePromptAwareInterpreter ->
                LiveProtocol.OPENAI_LIVE
            isTranslateModeRequested ->
                LiveProtocol.OPENAI_TRANSLATE
            frontModel.lowercase().startsWith("grok") || settings.protocol == LiveProtocol.XAI_REALTIME ->
                LiveProtocol.XAI_REALTIME
            frontModel.lowercase().startsWith("gpt-live") || settings.protocol == LiveProtocol.OPENAI_LIVE ->
                LiveProtocol.OPENAI_LIVE
            else ->
                LiveProtocol.OPENAI_REALTIME
        }

        val apiKey = when (activeProtocol) {
            LiveProtocol.XAI_REALTIME -> settings.xAiApiKey.trim()
            LiveProtocol.OPENAI_LIVE, LiveProtocol.OPENAI_REALTIME, LiveProtocol.OPENAI_TRANSLATE -> settings.openAiApiKey.trim()
        }

        if (apiKey.isBlank()) {
            val keyName = if (activeProtocol == LiveProtocol.XAI_REALTIME) "xAI API Key" else "OpenAI API Key"
            listener.onError("請先至「設定」頁面輸入 $keyName")
            listener.onConnectionStateChanged(ConnectionState.DISCONNECTED, "缺少 $keyName")
            return
        }

        val wsUrl = when (activeProtocol) {
            LiveProtocol.OPENAI_LIVE -> "wss://api.openai.com/v1/live/sessions"
            LiveProtocol.OPENAI_TRANSLATE -> {
                val translateModel = if (frontModel.lowercase().contains("translate")) frontModel else "gpt-realtime-translate"
                "wss://api.openai.com/v1/realtime/translations?model=$translateModel"
            }
            LiveProtocol.OPENAI_REALTIME -> "wss://api.openai.com/v1/realtime?model=$frontModel"
            LiveProtocol.XAI_REALTIME -> "wss://api.x.ai/v1/realtime?model=$frontModel"
        }

        val connectingLabel = when {
            isTutorMode ->
                "正在啟動 AI 外語口說陪練 (${AppSettings.tutorLanguageLabel(settings.tutorTargetLanguage)} · ${settings.tutorLevel.shortLabel})..."
            activeProtocol == LiveProtocol.OPENAI_TRANSLATE ->
                "正在啟動 gpt-realtime-translate 同步口譯 (輸出: ${AppSettings.languageLabel(settings.translationTargetLanguage)})..."
            isTranslateModeRequested && settings.usePromptAwareInterpreter ->
                "正在啟動 Prompt 智慧詞庫口譯 (輸出: ${AppSettings.languageLabel(settings.translationTargetLanguage)})..."
            else ->
                "正在連線至 $frontModel (後端: ${settings.backendBrainModel})..."
        }
        listener.onConnectionStateChanged(ConnectionState.CONNECTING, connectingLabel)

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
                    LiveProtocol.OPENAI_TRANSLATE -> {
                        sendTranslationSessionUpdate(webSocket, settings.translationTargetLanguage)
                        sessionStarted = true
                        engine.start(
                            useSpeakerphone = useSpeakerphone,
                            isSimultaneousTranslation = true
                        )
                        val langLabel = AppSettings.languageLabel(settings.translationTargetLanguage)
                        listener.onConnectionStateChanged(
                            ConnectionState.LIVE,
                            "🌐 同步口譯中：自動偵測 70+ 語言 ➔ $langLabel"
                        )
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
                    LiveProtocol.OPENAI_TRANSLATE -> handleTranslationEvent(
                        event = event
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

    private fun isPromptAwareInterpreterMode(settings: AppSettings): Boolean {
        if (settings.appMode == AppMode.TUTOR || settings.appMode == AppMode.ASSISTANT) return false
        val isTranslateRequested =
            settings.appMode == AppMode.TRANSLATE ||
                    settings.protocol == LiveProtocol.OPENAI_TRANSLATE ||
                    settings.frontendVoiceModel.lowercase().contains("translate")
        return isTranslateRequested && settings.usePromptAwareInterpreter
    }

    private fun buildCustomGlossaryPromptBlock(settings: AppSettings): String {
        val sb = StringBuilder()
        if (settings.translationContextPrompt.isNotBlank()) {
            sb.append("\n【情境背景與翻譯風格提示】：\n${settings.translationContextPrompt.trim()}\n")
        }
        if (settings.customGlossaryTerms.isNotBlank()) {
            sb.append("\n【專屬詞彙庫與專有名詞對照（辨識與輸出時請務必優先採用以下正確詞彙）】：\n${settings.customGlossaryTerms.trim()}\n")
        }
        return sb.toString()
    }

    private fun sendOpenAiLiveSessionStart(
        ws: WebSocket,
        settings: AppSettings,
        priorMessages: List<ChatMessage>
    ) {
        val promptInterpreter = isPromptAwareInterpreterMode(settings)
        val isTutorMode = settings.appMode == AppMode.TUTOR
        val rawFront = settings.frontendVoiceModel.trim().ifBlank { "gpt-live-1" }
        val frontModel = if ((promptInterpreter || isTutorMode || settings.appMode == AppMode.ASSISTANT) &&
            rawFront.lowercase().contains("translate")
        ) {
            "gpt-live-1"
        } else {
            rawFront
        }
        val backModel = settings.backendBrainModel.trim().ifBlank { "gpt-6-luna" }
        val timeContext = CalendarToolManager.buildCurrentTimeContextString()
        val glossaryBlock = buildCustomGlossaryPromptBlock(settings)

        if (promptInterpreter) {
            isClientDelegationSession = true
            val targetLabel = AppSettings.languageLabel(settings.translationTargetLanguage)
            val secondaryLabel = AppSettings.languageLabel(settings.translationSecondaryLanguage)
            val interpreterInstructions = buildString {
                appendLine("你是專業的即時雙向口譯官（Simultaneous & Consecutive Interpreter）。")
                appendLine("1. 核心任務：自動辨識說話者使用的任何語言（支援 70+ 國語言及中英日混講），並立即、精準、自然地口譯為目標輸出語言：【$targetLabel】。")
                appendLine("2. 雙向智慧切換：如果說話者講的語言已經是【$targetLabel】，請自動將其翻譯為第二常用語言：【$secondaryLabel】。")
                appendLine("3. 嚴格口譯守則：絕對不要回答問題、不要順著說話者的話題聊天或解釋，只輸出翻譯後的語句！")
                if (glossaryBlock.isNotBlank()) {
                    appendLine(glossaryBlock)
                }
            }

            val sessionObj = JSONObject().apply {
                put("model", frontModel)
                put("instructions", interpreterInstructions)
                put("audio", JSONObject().apply {
                    put("format", JSONObject().apply {
                        put("type", "audio/pcm")
                        put("rate", 24000)
                    })
                    put("output", JSONObject().apply {
                        put("voice", settings.voiceName.ifBlank { "marin" })
                    })
                })
                put("delegation", JSONObject().apply {
                    put("type", "client")
                })
            }

            ws.send(JSONObject().apply {
                put("type", "session.start")
                put("event_id", "event_start_${UUID.randomUUID().toString().take(8)}")
                put("session", sessionObj)
            }.toString())
            return
        }

        if (isTutorMode) {
            isClientDelegationSession = true
            val tutorInstructions = "$timeContext\n\n${AppSettings.buildTutorVoiceInstructions(settings, activeNotebookEntries)}$glossaryBlock"
            val seedArray = buildSeedInputArray(priorMessages)

            val sessionObj = JSONObject().apply {
                put("model", frontModel)
                put("instructions", tutorInstructions)
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
                put("delegation", JSONObject().apply {
                    put("type", "client")
                })
            }

            ws.send(JSONObject().apply {
                put("type", "session.start")
                put("event_id", "event_start_${UUID.randomUUID().toString().take(8)}")
                put("session", sessionObj)
            }.toString())
            return
        }

        // IMPORTANT: In OpenAI /v1/live/sessions, client-side tools (Linkup API & Android Calendar)
        // MUST use `delegation: { type: "client" }` (`session.thinking.append` + `session.commentary.append`),
        // because `/v1/live/sessions` does not accept `response.item.create` for client function_call_output,
        // and `type: "client"` keeps gpt-live-1 in full-duplex conversation with the user while searching!
        val hasLocalClientTools =
            (settings.linkupSearchEnabled && settings.linkupApiKey.isNotBlank()) ||
                    settings.calendarToolsEnabled

        val useClientDelegation = hasLocalClientTools ||
                settings.delegationMode == DelegationMode.CLIENT ||
                backModel.lowercase().startsWith("grok") ||
                (settings.customBackendBaseUrl.isNotBlank() &&
                        !settings.customBackendBaseUrl.contains("api.openai.com"))

        isClientDelegationSession = useClientDelegation

        val memoryBlock = AppSettings.buildLongTermMemoryBlock(settings)
        val sessionObj = JSONObject().apply {
            put("model", frontModel)
            put("instructions", "$timeContext\n\n${settings.voiceSystemInstructions}$glossaryBlock$memoryBlock\n（補充：當後端正在查詢資料或搜尋時，你可以繼續自然地與使用者閒聊互動，直到收到後端結果為止。）")

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
                if (settings.openAiNativeSearchEnabled) {
                    toolsArr.put(JSONObject().apply { put("type", "web_search") })
                }

                put("delegation", JSONObject().apply {
                    put("type", "responses")
                    put("responses", JSONObject().apply {
                        put("model", backModel)
                        put("instructions", "$timeContext\n\n${settings.backendSystemInstructions}$glossaryBlock$memoryBlock")
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
                val combinedText = buildString {
                    if (msg.text.isNotBlank()) append(msg.text.trim())
                    if (msg.backendThinkingText.isNotBlank()) {
                        if (isNotEmpty()) append("\n[後端重點筆記]: ")
                        append(msg.backendThinkingText.trim())
                    }
                }.trim()
                if (combinedText.isNotEmpty()) {
                    val isUser = msg.role == MessageRole.USER
                    arr.put(JSONObject().apply {
                        put("type", "message")
                        put("role", if (isUser) "user" else "assistant")
                        put("content", JSONArray().put(JSONObject().apply {
                            put("type", if (isUser) "input_text" else "output_text")
                            put("text", combinedText.take(1600))
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
        val promptInterpreter = isPromptAwareInterpreterMode(activeSettings)
        val isTutorMode = activeSettings.appMode == AppMode.TUTOR
        when (event.optString("type")) {
            "session.started" -> {
                sessionStarted = true
                val sessionObj = event.optJSONObject("session")
                val remoteId = sessionObj?.optString("id", "").orEmpty()
                if (remoteId.isNotBlank()) {
                    listener.onRemoteSessionIdResolved(remoteId)
                }
                audioEngine?.start(
                    useSpeakerphone = useSpeakerphone,
                    isSimultaneousTranslation = false
                )
                val statusMsg = when {
                    isTutorMode -> {
                        val langShort = AppSettings.tutorLanguageLabel(activeSettings.tutorTargetLanguage).substringBefore(" (")
                        "🎓 外語陪練中：$langShort (${activeSettings.tutorLevel.shortLabel} · ${activeSettings.tutorScenario.shortLabel})"
                    }
                    promptInterpreter -> {
                        val langLabel = AppSettings.languageLabel(activeSettings.translationTargetLanguage)
                        "🌐 智慧詞庫口譯中：自動偵測語言 ➔ $langLabel"
                    }
                    else -> {
                        "通話中：${settings.frontendVoiceModel} ⇄ ${settings.backendBrainModel}"
                    }
                }
                listener.onConnectionStateChanged(ConnectionState.LIVE, statusMsg)

                // In Tutor Mode, if starting a fresh session, let the AI Tutor warmly greet the user first!
                if (isTutorMode && conversationSnapshotProvider().isEmpty()) {
                    scope.launch {
                        delay(350)
                        if (sessionStarted && !isClosing) {
                            sendTutorQuickAction("start_scenario")
                        }
                    }
                }
            }

            "session.output_audio.delta" -> {
                val delta = event.optString("delta", "")
                if (delta.isNotEmpty()) {
                    appendOutputAudioAndScheduleClipSave(delta)
                }
            }

            "session.input_transcript.delta" -> {
                val rawDelta = event.optString("delta", "")
                val startMs = event.optLong("start_ms", 0L)
                if (rawDelta.isNotEmpty()) {
                    val tradDelta = TraditionalChineseConverter.toTraditional(rawDelta, preserveJapanese = true)
                    val delta = TraditionalChineseConverter.applyCustomGlossary(tradDelta, activeSettings.customGlossaryTerms)
                    val now = System.currentTimeMillis()
                    val silenceSinceVoice = if (lastVoiceDeltaWallMs > 0L) now - lastVoiceDeltaWallMs else 999_999L
                    val silenceSinceUser = if (lastUserDeltaWallMs > 0L) now - lastUserDeltaWallMs else 999_999L
                    val silenceSinceDelegation = if (lastDelegationDoneWallMs > 0L) now - lastDelegationDoneWallMs else 999_999L

                    val previousTurnFinishedWithDelegation =
                        delegationCompletedInTurn && !isDelegationActive && !isToolCallRunning &&
                                (voiceCharsAfterDelegation >= 6 || silenceSinceDelegation > 3500L) &&
                                silenceSinceVoice > 1100L && silenceSinceUser > 1000L

                    val previousTurnFinishedDirectVoice =
                        !delegationCompletedInTurn && !isDelegationActive && !isToolCallRunning &&
                                voiceCharsInTurn >= 8 && silenceSinceVoice > 1200L && silenceSinceUser > 1000L

                    if (previousTurnFinishedWithDelegation || previousTurnFinishedDirectVoice) {
                        advanceToNextTurn()
                    }

                    val formattedDelta = if (userCharsInTurn > 0 && silenceSinceUser > 1400L && !delta.startsWith(" ")) {
                        " $delta"
                    } else {
                        delta
                    }

                    lastUserDeltaWallMs = now
                    userCharsInTurn += delta.length

                    val turnIdx = currentTurnIndex
                    val streamKey = "turn_${sessionEpochId}_${turnIdx}_user"
                    listener.onTranscriptDelta(
                        role = MessageRole.USER,
                        delta = formattedDelta,
                        timelineMs = startMs,
                        modelTag = when {
                            isTutorMode -> "🗣️ 你的口說練習"
                            promptInterpreter -> "🎙️ 原音辨識"
                            else -> "User"
                        },
                        streamKey = streamKey,
                        turnIndex = turnIdx
                    )
                }
            }

            "session.output_transcript.delta" -> {
                val rawDelta = event.optString("delta", "")
                val startMs = event.optLong("start_ms", 0L)
                if (rawDelta.isNotEmpty()) {
                    val tradDelta = TraditionalChineseConverter.toTraditional(rawDelta, preserveJapanese = true)
                    val delta = TraditionalChineseConverter.applyCustomGlossary(tradDelta, activeSettings.customGlossaryTerms)
                    val formattedDelta = if (needsVoiceParagraphBreak && voiceCharsInTurn > 0) {
                        needsVoiceParagraphBreak = false
                        "\n\n$delta"
                    } else {
                        delta
                    }

                    lastVoiceDeltaWallMs = System.currentTimeMillis()
                    voiceCharsInTurn += delta.length
                    if (delegationCompletedInTurn && !isDelegationActive) {
                        voiceCharsAfterDelegation += delta.length
                    }

                    val turnIdx = currentTurnIndex
                    val streamKey = "turn_${sessionEpochId}_${turnIdx}_voice"
                    val tag = when {
                        isTutorMode -> {
                            val langShort = AppSettings.tutorLanguageLabel(activeSettings.tutorTargetLanguage).substringBefore(" (")
                            "🎓 AI 外語家教 ($langShort · ${activeSettings.tutorScenario.shortLabel})"
                        }
                        promptInterpreter -> {
                            val langShort = AppSettings.languageLabel(activeSettings.translationTargetLanguage).substringBefore(" (")
                            "🌐 詞庫口譯 ➔ $langShort"
                        }
                        else -> settings.frontendVoiceModel
                    }
                    listener.onTranscriptDelta(
                        role = MessageRole.ASSISTANT_VOICE,
                        delta = formattedDelta,
                        timelineMs = startMs,
                        modelTag = tag,
                        streamKey = streamKey,
                        turnIndex = turnIdx
                    )
                    listener.onTurnCompleted()
                    if (isTutorMode) {
                        scheduleTutorTurnCoachAnalysis(turnIdx, conversationSnapshotProvider)
                    }
                }
            }

            "session.delegation.created" -> {
                val delegationObj = event.optJSONObject("delegation")
                val delegationId = delegationObj?.optString("id", "")
                    ?.takeIf { it.isNotBlank() }
                    ?: event.optString("delegation_id", "")
                val target = delegationObj?.optString("target", "").orEmpty()

                val delegationHint = listOf("query", "input", "instructions", "description", "prompt")
                    .firstNotNullOfOrNull { key ->
                        delegationObj?.optString(key, "")?.takeIf { it.isNotBlank() }
                    }

                isDelegationActive = true
                if (voiceCharsInTurn > 0) {
                    needsVoiceParagraphBreak = true
                }

                val turnIdx = currentTurnIndex
                listener.onConnectionStateChanged(
                    ConnectionState.DELEGATING,
                    "後端 ${settings.backendBrainModel} 搜尋與推理中（你仍可繼續說話聊天）..."
                )

                if ((isClientDelegationSession || target == "client") && delegationId.isNotBlank()) {
                    // Immediately send initial thinking event so gpt-live-1 stays responsive and can keep chatting with the user
                    runCatching {
                        webSocket.send(JSONObject().apply {
                            put("type", "session.thinking.append")
                            put("event_id", "think_init_${UUID.randomUUID().toString().take(8)}")
                            put("delegation_id", delegationId)
                            put("content", "後端模型 ${settings.backendBrainModel} 已開始查詢資料。若使用者在這期間繼續說話，請自然陪他聊天互動。")
                        }.toString())
                    }

                    scope.launch {
                        // Brief wait so any trailing input_transcript.delta chunks from the user's utterance finish arriving
                        delay(380)
                        val snapshot = conversationSnapshotProvider()

                        val res = backendLlmClient.runDelegatedTask(
                            settings = settings,
                            conversationHistory = snapshot,
                            taskHint = delegationHint,
                            onThinkingStatusUpdate = { statusMsg ->
                                runCatching {
                                    webSocket.send(JSONObject().apply {
                                        put("type", "session.thinking.append")
                                        put("event_id", "think_${UUID.randomUUID().toString().take(8)}")
                                        put("delegation_id", delegationId)
                                        put("content", statusMsg)
                                    }.toString())
                                }
                            },
                            onSearchExecuted = { searchResult ->
                                listener.onSearchCardAdded(searchResult, turnIdx)
                            },
                            onCalendarActionLogged = { calNote ->
                                listener.onTranscriptDelta(
                                    role = MessageRole.SYSTEM,
                                    delta = calNote,
                                    timelineMs = System.currentTimeMillis(),
                                    modelTag = "Calendar",
                                    streamKey = "turn_${sessionEpochId}_${turnIdx}_cal_${UUID.randomUUID().toString().take(6)}",
                                    turnIndex = turnIdx
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
                                    streamKey = "turn_${sessionEpochId}_${turnIdx}_backend",
                                    turnIndex = turnIdx
                                )
                            }
                            val commentaryEvent = JSONObject().apply {
                                put("type", "session.commentary.append")
                                put("event_id", "commentary_${UUID.randomUUID().toString().take(8)}")
                                put("delegation_id", delegationId)
                                put("content", brainResp.spokenSummary.take(1400))
                            }
                            webSocket.send(commentaryEvent.toString())
                            isDelegationActive = false
                            delegationCompletedInTurn = true
                            lastDelegationDoneWallMs = System.currentTimeMillis()
                            if (voiceCharsInTurn > 0) {
                                needsVoiceParagraphBreak = true
                            }
                            listener.onTurnCompleted()
                            listener.onConnectionStateChanged(
                                ConnectionState.LIVE,
                                "通話中：${settings.frontendVoiceModel} ⇄ ${settings.backendBrainModel}"
                            )
                        }.onFailure { err ->
                            isDelegationActive = false
                            delegationCompletedInTurn = true
                            lastDelegationDoneWallMs = System.currentTimeMillis()
                            runCatching {
                                webSocket.send(JSONObject().apply {
                                    put("type", "session.commentary.append")
                                    put("event_id", "commentary_err_${UUID.randomUUID().toString().take(8)}")
                                    put("delegation_id", delegationId)
                                    put("content", "後端查詢發生錯誤：${err.message}，請先以已知資訊回答使用者。")
                                }.toString())
                            }
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
                val delegationId = event.optString("delegation_id", "").ifBlank { "resp_$currentTurnIndex" }
                val inner = event.optJSONObject("event") ?: return
                val innerType = inner.optString("type", "")
                val turnIdx = currentTurnIndex

                when (innerType) {
                    "response.created" -> {
                        isDelegationActive = true
                        if (voiceCharsInTurn > 0) {
                            needsVoiceParagraphBreak = true
                        }
                    }
                    "response.output_text.delta" -> {
                        val rawDelta = inner.optString("delta", "")
                        val itemId = inner.optString("item_id", delegationId)
                        if (rawDelta.isNotEmpty()) {
                            val delta = TraditionalChineseConverter.toTraditional(rawDelta)
                            isDelegationActive = true
                            val prefix = if (lastBackendItemId.isNotEmpty() && lastBackendItemId != itemId) "\n\n" else ""
                            lastBackendItemId = itemId

                            val backendStreamKey = "turn_${sessionEpochId}_${turnIdx}_backend"
                            listener.onTranscriptDelta(
                                role = MessageRole.BACKEND_BRAIN,
                                delta = prefix + delta,
                                timelineMs = System.currentTimeMillis(),
                                modelTag = settings.backendBrainModel,
                                streamKey = backendStreamKey,
                                turnIndex = turnIdx
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
                                isToolCallRunning = true
                                handleResponsesFunctionToolCall(webSocket, delegationId, callId, fnName, argsStr, settings, turnIdx)
                            }
                        }
                    }
                    "response.completed" -> {
                        if (!isToolCallRunning) {
                            isDelegationActive = false
                            delegationCompletedInTurn = true
                            lastDelegationDoneWallMs = System.currentTimeMillis()
                            if (voiceCharsInTurn > 0) {
                                needsVoiceParagraphBreak = true
                            }
                            listener.onTurnCompleted()
                            listener.onConnectionStateChanged(
                                ConnectionState.LIVE,
                                "通話中：${settings.frontendVoiceModel} ⇄ ${settings.backendBrainModel}"
                            )
                        }
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
        delegationId: String,
        callId: String,
        fnName: String,
        argsStr: String,
        settings: AppSettings,
        turnIdx: Int
    ) {
        scope.launch {
            try {
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

                        listener.onSearchCardAdded(searchResult, turnIdx)
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
                            streamKey = "turn_${sessionEpochId}_${turnIdx}_cal_$callId",
                            turnIndex = turnIdx
                        )
                        toolOutputText = calJson
                    }

                    else -> {
                        toolOutputText = "{\"status\":\"error\",\"message\":\"Unknown tool: $fnName\"}"
                    }
                }

                // Feed the tool result back to the live session via session.commentary.append
                webSocket.send(JSONObject().apply {
                    put("type", "session.commentary.append")
                    put("event_id", "tool_comm_${UUID.randomUUID().toString().take(8)}")
                    put("delegation_id", delegationId)
                    put("content", TraditionalChineseConverter.toTraditional(toolOutputText).take(1400))
                }.toString())

                isToolCallRunning = false
                isDelegationActive = false
                delegationCompletedInTurn = true
                lastDelegationDoneWallMs = System.currentTimeMillis()
                if (voiceCharsInTurn > 0) {
                    needsVoiceParagraphBreak = true
                }
            } catch (e: Exception) {
                isToolCallRunning = false
                isDelegationActive = false
            }
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

        val memoryBlock = AppSettings.buildLongTermMemoryBlock(settings)
        val instructionsText = if (settings.appMode == AppMode.TUTOR) {
            "$timeContext\n\n${AppSettings.buildTutorVoiceInstructions(settings, activeNotebookEntries)}"
        } else {
            "$timeContext\n\n${settings.voiceSystemInstructions}$memoryBlock"
        }

        val sessionUpdate = JSONObject().apply {
            put("type", "session.update")
            put("session", JSONObject().apply {
                put("instructions", instructionsText)
                put("voice", settings.voiceName.ifBlank {
                    if (activeProtocol == LiveProtocol.XAI_REALTIME) "eve" else "alloy"
                })
                put("input_audio_format", "pcm16")
                put("output_audio_format", "pcm16")
                put("input_audio_transcription", JSONObject().apply {
                    put("model", "whisper-1")
                    put("prompt", "繁體中文, English, 日本語")
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

    private fun sendTranslationSessionUpdate(ws: WebSocket, targetLang: String) {
        val cleanLang = targetLang.trim().ifBlank { "zh" }
        val updateEvent = JSONObject().apply {
            put("type", "session.update")
            put("session", JSONObject().apply {
                put("audio", JSONObject().apply {
                    put("input", JSONObject().apply {
                        put("noise_reduction", JSONObject().apply {
                            put("type", "near_field")
                        })
                    })
                    put("output", JSONObject().apply {
                        put("language", cleanLang)
                    })
                })
            })
        }
        ws.send(updateEvent.toString())
    }

    /**
     * Dynamically updates the target output language during an active `gpt-realtime-translate` session
     * without reconnecting the WebSocket. Ideal for two-way (A ⇄ B) face-to-face interpretation!
     */
    fun updateTranslationTargetLanguage(newLanguageCode: String) {
        val cleanLang = newLanguageCode.trim().ifBlank { "zh" }
        activeSettings = activeSettings.copy(translationTargetLanguage = cleanLang)
        val ws = webSocket
        if (ws != null && sessionStarted && !isClosing) {
            val langLabel = AppSettings.languageLabel(cleanLang)
            if (activeProtocol == LiveProtocol.OPENAI_TRANSLATE) {
                if (userCharsInTurn > 0 || voiceCharsInTurn > 0) {
                    advanceToNextTurn()
                }
                sendTranslationSessionUpdate(ws, cleanLang)
                listener.onConnectionStateChanged(
                    ConnectionState.LIVE,
                    "🌐 同步口譯中：自動偵測 70+ 語言 ➔ $langLabel"
                )
            } else if (isPromptAwareInterpreterMode(activeSettings) && activeProtocol == LiveProtocol.OPENAI_LIVE) {
                if (userCharsInTurn > 0 || voiceCharsInTurn > 0) {
                    advanceToNextTurn()
                }
                runCatching {
                    ws.send(JSONObject().apply {
                        put("type", "session.thinking.append")
                        put("event_id", "lang_switch_${UUID.randomUUID().toString().take(8)}")
                        put("delegation_id", JSONObject.NULL)
                        put("content", "[系統口譯方向切換] 從現在起，請將使用者說的所有語音立即口譯為目標語言：【$langLabel】。只輸出翻譯後的語音，不要回答問題。")
                    }.toString())
                }
                listener.onConnectionStateChanged(
                    ConnectionState.LIVE,
                    "🌐 智慧詞庫口譯中：自動偵測語言 ➔ $langLabel"
                )
            }
        }
    }

    fun updateTutorLiveSettings(
        newSettings: AppSettings,
        notebookEntries: List<TutorNotebookEntry> = activeNotebookEntries
    ) {
        activeSettings = newSettings
        activeNotebookEntries = notebookEntries
        val ws = webSocket
        if (ws != null && sessionStarted && !isClosing && newSettings.appMode == AppMode.TUTOR) {
            val langShort = AppSettings.tutorLanguageLabel(newSettings.tutorTargetLanguage).substringBefore(" (")
            val updatedRules = AppSettings.buildTutorVoiceInstructions(newSettings, activeNotebookEntries)
            if (activeProtocol == LiveProtocol.OPENAI_LIVE) {
                runCatching {
                    ws.send(JSONObject().apply {
                        put("type", "session.thinking.append")
                        put("event_id", "tutor_cfg_${UUID.randomUUID().toString().take(8)}")
                        put("delegation_id", JSONObject.NULL)
                        put("content", "[外語陪練設定更新]\n$updatedRules")
                    }.toString())
                }
            } else if (activeProtocol == LiveProtocol.OPENAI_REALTIME || activeProtocol == LiveProtocol.XAI_REALTIME) {
                sendRealtimeSessionUpdate(ws, newSettings)
            }
            listener.onConnectionStateChanged(
                ConnectionState.LIVE,
                "🎓 外語陪練中：$langShort (${newSettings.tutorLevel.shortLabel} · ${newSettings.tutorScenario.shortLabel})"
            )
        }
    }

    fun sendTutorQuickAction(actionType: String) {
        val ws = webSocket ?: return
        if (!sessionStarted || isClosing) return
        val langLabel = AppSettings.tutorLanguageLabel(activeSettings.tutorTargetLanguage)
        val scenarioPrompt = if (activeSettings.tutorScenario == TutorScenario.CUSTOM && activeSettings.tutorCustomScenarioPrompt.isNotBlank()) {
            "請根據自訂情境「${activeSettings.tutorCustomScenarioPrompt}」用【$langLabel】主動開口向使用者打招呼並開始角色扮演！"
        } else {
            activeSettings.tutorScenario.openingTriggerPrompt
        }

        val directive = when (actionType) {
            "start_scenario" ->
                "[情境啟動指令] 目標語言：【$langLabel】（程度：${activeSettings.tutorLevel.shortLabel}）。$scenarioPrompt"
            "repeat_slowly" ->
                "[教學輔助指令] 使用者沒聽清楚剛才那句話。請把你剛才說的那句【$langLabel】用非常慢、字正腔圓的語速再重複唸一次，並用簡單的一句繁體中文解釋意思。"
            "give_hint" ->
                "[教學輔助指令] 使用者目前不知道下一句該怎麼用【$langLabel】回答。請用溫和的繁體中文提供 1～2 個簡短好唸的【$langLabel】回答範例，並帶著使用者慢速唸一次！"
            else -> return
        }

        if (userCharsInTurn > 0 || voiceCharsInTurn > 0) {
            advanceToNextTurn()
        }

        when (activeProtocol) {
            LiveProtocol.OPENAI_LIVE -> {
                runCatching {
                    ws.send(JSONObject().apply {
                        put("type", "session.commentary.append")
                        put("event_id", "tutor_act_${UUID.randomUUID().toString().take(8)}")
                        put("delegation_id", JSONObject.NULL)
                        put("content", directive)
                    }.toString())
                }
            }
            LiveProtocol.OPENAI_REALTIME, LiveProtocol.XAI_REALTIME -> {
                runCatching {
                    ws.send(JSONObject().apply {
                        put("type", "conversation.item.create")
                        put("item", JSONObject().apply {
                            put("type", "message")
                            put("role", "user")
                            put("content", JSONArray().put(JSONObject().apply {
                                put("type", "input_text")
                                put("text", directive)
                            }))
                        })
                    }.toString())
                    ws.send(JSONObject().apply { put("type", "response.create") }.toString())
                }
            }
            else -> {}
        }
    }

    private fun handleTranslationEvent(event: JSONObject) {
        when (event.optString("type")) {
            "session.created", "session.updated" -> {
                val id = event.optJSONObject("session")?.optString("id", "").orEmpty()
                if (id.isNotBlank()) listener.onRemoteSessionIdResolved(id)
            }

            "session.output_audio.delta" -> {
                val delta = event.optString("delta", "")
                if (delta.isNotEmpty()) {
                    appendOutputAudioAndScheduleClipSave(delta)
                }
            }

            "session.input_transcript.delta" -> {
                val rawDelta = event.optString("delta", "")
                if (rawDelta.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    val silenceSinceUser = if (lastUserDeltaWallMs > 0L) now - lastUserDeltaWallMs else 999_999L
                    if (userCharsInTurn >= 24 && silenceSinceUser > 2800L) {
                        advanceToNextTurn()
                    }
                    val tradDelta = TraditionalChineseConverter.toTraditional(rawDelta, preserveJapanese = true)
                    val delta = TraditionalChineseConverter.applyCustomGlossary(tradDelta, activeSettings.customGlossaryTerms)
                    lastUserDeltaWallMs = now
                    userCharsInTurn += delta.length
                    val turnIdx = currentTurnIndex
                    listener.onTranscriptDelta(
                        role = MessageRole.USER,
                        delta = delta,
                        timelineMs = now,
                        modelTag = "🎙️ 原音辨識",
                        streamKey = "turn_${sessionEpochId}_${turnIdx}_user",
                        turnIndex = turnIdx
                    )
                }
            }

            "session.output_transcript.delta" -> {
                val rawDelta = event.optString("delta", "")
                if (rawDelta.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    val silenceSinceVoice = if (lastVoiceDeltaWallMs > 0L) now - lastVoiceDeltaWallMs else 999_999L
                    if (voiceCharsInTurn >= 28 && silenceSinceVoice > 2800L) {
                        advanceToNextTurn()
                    }
                    val targetLang = activeSettings.translationTargetLanguage
                    val tradDelta = if (targetLang == "zh") {
                        TraditionalChineseConverter.toTraditional(rawDelta, preserveJapanese = false)
                    } else {
                        rawDelta
                    }
                    val delta = TraditionalChineseConverter.applyCustomGlossary(tradDelta, activeSettings.customGlossaryTerms)
                    lastVoiceDeltaWallMs = now
                    voiceCharsInTurn += delta.length
                    val turnIdx = currentTurnIndex
                    val langShort = AppSettings.languageLabel(targetLang).substringBefore(" (")
                    listener.onTranscriptDelta(
                        role = MessageRole.ASSISTANT_VOICE,
                        delta = delta,
                        timelineMs = now,
                        modelTag = "🌐 同步口譯 ➔ $langShort",
                        streamKey = "turn_${sessionEpochId}_${turnIdx}_translate_${targetLang}",
                        turnIndex = turnIdx
                    )
                    listener.onTurnCompleted()
                }
            }

            "session.closed" -> {
                disconnectImmediate()
            }

            "error" -> {
                val errMsg = event.optJSONObject("error")?.optString("message", event.toString()) ?: event.toString()
                listener.onError("同步口譯 API 錯誤: $errMsg")
            }
        }
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
                val rawTranscript = event.optString("transcript", "")
                if (rawTranscript.isNotBlank()) {
                    val transcript = TraditionalChineseConverter.toTraditional(rawTranscript, preserveJapanese = true)
                    val now = System.currentTimeMillis()
                    if (!isDelegationActive && !isToolCallRunning && voiceCharsInTurn >= 6) {
                        advanceToNextTurn()
                    }
                    val formatted = if (userCharsInTurn > 0) " $transcript" else transcript
                    userCharsInTurn += transcript.length
                    lastUserDeltaWallMs = now

                    val turnIdx = currentTurnIndex
                    listener.onTranscriptDelta(
                        role = MessageRole.USER,
                        delta = formatted,
                        timelineMs = now,
                        modelTag = if (activeSettings.appMode == AppMode.TUTOR) "🗣️ 你的口說練習" else "User",
                        streamKey = "turn_${sessionEpochId}_${turnIdx}_user",
                        turnIndex = turnIdx
                    )
                }
            }

            "response.audio.delta", "response.output_audio.delta" -> {
                val delta = event.optString("delta", "")
                if (delta.isNotEmpty()) {
                    appendOutputAudioAndScheduleClipSave(delta)
                }
            }

            "response.audio_transcript.delta", "response.output_audio_transcript.delta" -> {
                val rawDelta = event.optString("delta", "")
                if (rawDelta.isNotEmpty()) {
                    val delta = TraditionalChineseConverter.toTraditional(rawDelta, preserveJapanese = true)
                    val formatted = if (needsVoiceParagraphBreak && voiceCharsInTurn > 0) {
                        needsVoiceParagraphBreak = false
                        "\n\n$delta"
                    } else {
                        delta
                    }
                    voiceCharsInTurn += delta.length
                    lastVoiceDeltaWallMs = System.currentTimeMillis()
                    val turnIdx = currentTurnIndex
                    val tag = if (activeSettings.appMode == AppMode.TUTOR) {
                        val langShort = AppSettings.tutorLanguageLabel(activeSettings.tutorTargetLanguage).substringBefore(" (")
                        "🎓 AI 外語家教 ($langShort · ${activeSettings.tutorScenario.shortLabel})"
                    } else {
                        settings.frontendVoiceModel
                    }
                    listener.onTranscriptDelta(
                        role = MessageRole.ASSISTANT_VOICE,
                        delta = formatted,
                        timelineMs = System.currentTimeMillis(),
                        modelTag = tag,
                        streamKey = "turn_${sessionEpochId}_${turnIdx}_voice",
                        turnIndex = turnIdx
                    )
                }
            }

            "response.done" -> {
                if (!isToolCallRunning && !isDelegationActive) {
                    listener.onTurnCompleted()
                    if (activeSettings.appMode == AppMode.TUTOR) {
                        scheduleTutorTurnCoachAnalysis(currentTurnIndex, conversationSnapshotProvider)
                    }
                }
            }

            "response.function_call_arguments.done" -> {
                val callId = event.optString("call_id", "")
                val fnName = event.optString("name", "")
                val argsStr = event.optString("arguments", "{}")
                if (callId.isNotBlank()) {
                    isToolCallRunning = true
                    isDelegationActive = true
                    if (voiceCharsInTurn > 0) {
                        needsVoiceParagraphBreak = true
                    }
                    val turnIdx = currentTurnIndex
                    handleRealtimeFunctionCall(
                        webSocket = webSocket,
                        callId = callId,
                        fnName = fnName,
                        argsStr = argsStr,
                        settings = settings,
                        turnIdx = turnIdx,
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
        turnIdx: Int,
        conversationSnapshotProvider: () -> List<ChatMessage>
    ) {
        scope.launch {
            try {
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
                        listener.onSearchCardAdded(res, turnIdx)
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
                            streamKey = "turn_${sessionEpochId}_${turnIdx}_cal_$callId",
                            turnIndex = turnIdx
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
                                listener.onSearchCardAdded(searchResult, turnIdx)
                            },
                            onCalendarActionLogged = { calNote ->
                                listener.onTranscriptDelta(
                                    role = MessageRole.SYSTEM,
                                    delta = calNote,
                                    timelineMs = System.currentTimeMillis(),
                                    modelTag = "Calendar",
                                    streamKey = "turn_${sessionEpochId}_${turnIdx}_cal_${UUID.randomUUID().toString().take(6)}",
                                    turnIndex = turnIdx
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
                                        streamKey = "turn_${sessionEpochId}_${turnIdx}_backend",
                                        turnIndex = turnIdx
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

                isToolCallRunning = false
                isDelegationActive = false
                delegationCompletedInTurn = true
                lastDelegationDoneWallMs = System.currentTimeMillis()
                if (voiceCharsInTurn > 0) {
                    needsVoiceParagraphBreak = true
                }

                val respCreate = JSONObject().apply {
                    put("type", "response.create")
                }
                webSocket.send(respCreate.toString())

                listener.onConnectionStateChanged(
                    ConnectionState.LIVE,
                    "通話中：${settings.frontendVoiceModel} ⇄ ${settings.backendBrainModel}"
                )
            } catch (e: Exception) {
                isToolCallRunning = false
                isDelegationActive = false
            }
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
            LiveProtocol.OPENAI_TRANSLATE -> JSONObject().apply {
                put("type", "session.input_audio_buffer.append")
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

        if (userCharsInTurn > 0 || voiceCharsInTurn > 0) {
            advanceToNextTurn()
        }
        val turnIdx = currentTurnIndex
        userCharsInTurn += cleanText.length
        lastUserDeltaWallMs = System.currentTimeMillis()

        listener.onTranscriptDelta(
            role = MessageRole.USER,
            delta = cleanText,
            timelineMs = System.currentTimeMillis(),
            modelTag = "User (文字輸入)",
            streamKey = "turn_${sessionEpochId}_${turnIdx}_user",
            turnIndex = turnIdx
        )

        val ws = webSocket
        if (ws == null || !sessionStarted) return

        when (activeProtocol) {
            LiveProtocol.OPENAI_LIVE -> {
                if (!isClientDelegationSession && activeSettings.delegationMode == DelegationMode.RESPONSES) {
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
                            onSearchExecuted = { listener.onSearchCardAdded(it, turnIdx) },
                            onCalendarActionLogged = { calNote ->
                                listener.onTranscriptDelta(
                                    role = MessageRole.SYSTEM,
                                    delta = calNote,
                                    timelineMs = System.currentTimeMillis(),
                                    modelTag = "Calendar",
                                    streamKey = "turn_${sessionEpochId}_${turnIdx}_cal_${UUID.randomUUID().toString().take(6)}",
                                    turnIndex = turnIdx
                                )
                            }
                        )
                        res.onSuccess { brain ->
                            listener.onTranscriptDelta(
                                role = MessageRole.BACKEND_BRAIN,
                                delta = brain.detailedNotes,
                                timelineMs = System.currentTimeMillis(),
                                modelTag = activeSettings.backendBrainModel,
                                streamKey = "turn_${sessionEpochId}_${turnIdx}_backend",
                                turnIndex = turnIdx
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

            LiveProtocol.OPENAI_TRANSLATE -> {
                // Translation sessions only accept continuous audio stream, no text turn items
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
        val supportsGracefulClose =
            activeProtocol == LiveProtocol.OPENAI_LIVE || activeProtocol == LiveProtocol.OPENAI_TRANSLATE
        if (ws != null && sessionStarted && supportsGracefulClose && !isClosing) {
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

    fun allocateNextTurnIndexForVision(): Int {
        if (userCharsInTurn > 0 || voiceCharsInTurn > 0) {
            advanceToNextTurn()
        }
        return currentTurnIndex
    }

    fun injectVisionResultIntoActiveVoiceSession(spokenDirectiveOrSummary: String) {
        val ws = webSocket ?: return
        if (!sessionStarted || isClosing || spokenDirectiveOrSummary.isBlank()) return
        when (activeProtocol) {
            LiveProtocol.OPENAI_LIVE -> {
                runCatching {
                    ws.send(JSONObject().apply {
                        put("type", "session.commentary.append")
                        put("event_id", "vision_${UUID.randomUUID().toString().take(8)}")
                        put("delegation_id", JSONObject.NULL)
                        put("content", spokenDirectiveOrSummary.take(1400))
                    }.toString())
                }
            }
            LiveProtocol.OPENAI_REALTIME, LiveProtocol.XAI_REALTIME -> {
                runCatching {
                    ws.send(JSONObject().apply {
                        put("type", "conversation.item.create")
                        put("item", JSONObject().apply {
                            put("type", "message")
                            put("role", "user")
                            put("content", JSONArray().put(JSONObject().apply {
                                put("type", "input_text")
                                put("text", spokenDirectiveOrSummary.take(1400))
                            }))
                        })
                    }.toString())
                    ws.send(JSONObject().apply { put("type", "response.create") }.toString())
                }
            }
            else -> {}
        }
    }

    fun disconnectImmediate() {
        isClosing = false
        sessionStarted = false
        tutorCoachDebounceJob?.cancel()
        audioClipSaveDebounceJob?.cancel()
        synchronized(audioClipLock) {
            if (currentAudioTurnIndex > 0) {
                flushAudioClipLocked(currentAudioTurnIndex)
                currentTurnAudioBuffer.reset()
                currentAudioTurnIndex = -1
            }
        }
        audioEngine?.stop()
        audioEngine = null
        runCatching { webSocket?.close(1000, "User ended session") }
        webSocket = null
        listener.onConnectionStateChanged(ConnectionState.DISCONNECTED, "未連線 (紀錄已自動保存)")
    }
}
