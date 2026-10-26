package com.example.livevoiceagent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.livevoiceagent.audio.WakeWordManager
import com.example.livevoiceagent.data.ConversationRepository
import com.example.livevoiceagent.model.*
import com.example.livevoiceagent.network.*
import com.example.livevoiceagent.tools.CalendarToolManager
import com.example.livevoiceagent.tools.LocationMapsToolManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class GoogleMapsLaunchRequest(
    val primaryUri: String,
    val fallbackWebUrl: String,
    val destinationLabel: String
)

data class UiState(
    val settings: AppSettings = AppSettings(),
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val statusBanner: String = "準備就緒 — 點擊下方按鈕開始全雙工語音對話",
    val micLevel: Float = 0f,
    val isMuted: Boolean = false,
    val isSpeakerphoneOn: Boolean = true,
    val voiceUsageSeconds: Int = 0,
    val currentSession: ConversationSession = ConversationSession(
        title = "新語音對話 (${SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date())})",
        frontendModel = "gpt-live-1",
        backendModel = "gpt-6-luna"
    ),
    val savedSessions: List<ConversationSession> = emptyList(),
    val tutorNotebook: List<TutorNotebookEntry> = emptyList(),
    val historySearchQuery: String = "",
    val isFetchingModels: Boolean = false,
    val isExtractingMemory: Boolean = false,
    val isAnalyzingImage: Boolean = false,
    val modelFetchStatus: String = "",
    val isWakeWordStandbyActive: Boolean = false,
    val wakeWordStandbyStatus: String = "語音喚醒待機已關閉",
    val isEnrollingVoiceprint: Boolean = false,
    val voiceprintStatusMessage: String = "",
    val errorToast: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application), LiveSessionListener {

    private val repository = ConversationRepository(application.applicationContext)
    private val modelFetcher = ModelFetcher()
    private val linkupClient = LinkupSearchClient()
    private val calendarToolManager = CalendarToolManager(application.applicationContext)
    private val locationMapsToolManager = LocationMapsToolManager(application.applicationContext, linkupClient)
    private val backendLlmClient = BackendLlmClient(linkupClient, calendarToolManager, locationMapsToolManager)
    private val liveSessionManager = LiveSessionManager(
        context = application.applicationContext,
        linkupClient = linkupClient,
        calendarToolManager = calendarToolManager,
        locationMapsToolManager = locationMapsToolManager,
        backendLlmClient = backendLlmClient,
        listener = this
    )

    private val wakeWordManager = WakeWordManager(
        context = application.applicationContext,
        onWakeWordDetected = { transcript, similarityScore ->
            handleWakeWordTriggered(transcript, similarityScore)
        },
        onStandbyStatusChanged = { isListening, statusMsg ->
            _uiState.update {
                it.copy(
                    isWakeWordStandbyActive = isListening,
                    wakeWordStandbyStatus = statusMsg
                )
            }
        }
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _googleMapsLaunchEvent = MutableSharedFlow<GoogleMapsLaunchRequest>(extraBufferCapacity = 4)
    val googleMapsLaunchEvent: SharedFlow<GoogleMapsLaunchRequest> = _googleMapsLaunchEvent.asSharedFlow()

    private val isGeneratingTitle = AtomicBoolean(false)
    private var pendingAutoHangupJob: Job? = null

    init {
        val loadedSettings = repository.loadSettings()
        _uiState.update { state ->
            state.copy(
                settings = loadedSettings,
                currentSession = state.currentSession.copy(
                    frontendModel = loadedSettings.frontendVoiceModel,
                    backendModel = loadedSettings.backendBrainModel
                )
            )
        }

        // Wire 100% zero-click Google Maps auto-navigation + optional auto-hangup on navigation
        locationMapsToolManager.onAutoLaunchGoogleMaps = { primaryUri, fallbackWebUrl, destLabel ->
            _googleMapsLaunchEvent.tryEmit(
                GoogleMapsLaunchRequest(
                    primaryUri = primaryUri,
                    fallbackWebUrl = fallbackWebUrl,
                    destinationLabel = destLabel
                )
            )
            if (_uiState.value.settings.autoEndCallOnNavigation &&
                _uiState.value.connectionState != ConnectionState.DISCONNECTED
            ) {
                onAutoHangupRequested(
                    reason = "🧭 已自動跳轉 Google Maps 導航至「$destLabel」，並自動結束語音通話（避免與導航語音搶麥克風）",
                    delayMs = 2800L
                )
            }
        }

        // Wire LLM-invoked `end_live_voice_session` tool (voice goodbye / bystander false wake protection)
        locationMapsToolManager.onRequestEndLiveSession = { reason ->
            val isBystander = reason.contains("bystander", ignoreCase = true)
            val delayMs = if (isBystander) 400L else 2800L
            val bannerMsg = if (isBystander) {
                "🛡️ AI 偵測到旁人閒聊誤觸，已靜音秒掛斷並退回 $0 待機"
            } else {
                "👋 AI 已完成道別並自動結束語音通話"
            }
            onAutoHangupRequested(reason = bannerMsg, delayMs = delayMs)
        }

        viewModelScope.launch {
            val sessions = repository.loadAllSessions()
            val notebook = repository.loadNotebookEntries()
            _uiState.update { it.copy(savedSessions = sessions, tutorNotebook = notebook) }
            if (loadedSettings.wakeWordEnabled) {
                wakeWordManager.startStandby(loadedSettings)
            }
        }
    }

    private fun handleWakeWordTriggered(transcript: String, similarityScore: Int) {
        val state = _uiState.value
        if (state.connectionState != ConnectionState.DISCONNECTED) return
        val scorePercent = similarityScore.coerceIn(0, 100)
        val vpTag = if (state.settings.wakeWordVoiceprintEnabled && state.settings.wakeWordVoiceprintData.isNotBlank()) {
            " · 聲紋吻合 $scorePercent%"
        } else {
            ""
        }
        _uiState.update {
            it.copy(
                statusBanner = "🗣️ 語音喚醒成功（「$transcript」$vpTag）— 正在啟動全雙工對話...",
                errorToast = "🗣️ 喚醒成功！「$transcript」$vpTag"
            )
        }
        startLiveCall(isWakeWordSession = true)
    }

    fun updateSettings(newSettings: AppSettings) {
        val oldSettings = _uiState.value.settings
        repository.saveSettings(newSettings)
        _uiState.update { state ->
            state.copy(
                settings = newSettings,
                currentSession = if (state.currentSession.messages.isEmpty()) {
                    state.currentSession.copy(
                        frontendModel = newSettings.frontendVoiceModel,
                        backendModel = newSettings.backendBrainModel
                    )
                } else {
                    state.currentSession
                }
            )
        }
        // Sync WakeWordManager standby if wake word settings changed while disconnected
        if (_uiState.value.connectionState == ConnectionState.DISCONNECTED) {
            if (newSettings.wakeWordEnabled) {
                if (!oldSettings.wakeWordEnabled ||
                    oldSettings.openAiApiKey != newSettings.openAiApiKey ||
                    oldSettings.wakeWordPhrase != newSettings.wakeWordPhrase ||
                    oldSettings.wakeWordVoiceprintEnabled != newSettings.wakeWordVoiceprintEnabled ||
                    oldSettings.wakeWordVoiceprintData != newSettings.wakeWordVoiceprintData
                ) {
                    wakeWordManager.startStandby(newSettings)
                }
            } else if (oldSettings.wakeWordEnabled) {
                wakeWordManager.stopStandby()
            }
        }
    }

    fun setWakeWordEnabled(enabled: Boolean) {
        val updated = _uiState.value.settings.copy(wakeWordEnabled = enabled)
        updateSettings(updated)
    }

    fun enrollUserVoiceprint() {
        if (_uiState.value.isEnrollingVoiceprint) return
        if (_uiState.value.connectionState != ConnectionState.DISCONNECTED) {
            _uiState.update { it.copy(errorToast = "請先結束目前的語音通話，再錄製本人聲紋樣本") }
            return
        }
        val phrase = _uiState.value.settings.wakeWordPhrase.ifBlank { "hey Luna 我需要你" }
        _uiState.update {
            it.copy(
                isEnrollingVoiceprint = true,
                voiceprintStatusMessage = "🎙️ 請現在對著手機自然說出：「$phrase」（錄音 3 秒中...）"
            )
        }
        viewModelScope.launch {
            val res = wakeWordManager.enrollVoiceprintSample(_uiState.value.settings.wakeWordVoiceprintData)
            res.onSuccess { profile ->
                val updatedJson = profile.toJsonString()
                val count = profile.sampleCount
                val pitchHz = profile.meanPitchHz.toInt()
                val newSettings = _uiState.value.settings.copy(
                    wakeWordVoiceprintData = updatedJson,
                    wakeWordVoiceprintEnabled = true
                )
                repository.saveSettings(newSettings)
                _uiState.update {
                    it.copy(
                        settings = newSettings,
                        isEnrollingVoiceprint = false,
                        voiceprintStatusMessage = "✅ 聲紋樣本錄製成功！（已累積 $count 次樣本 · 基頻約 ${pitchHz}Hz）",
                        errorToast = "✅ 已更新本人聲紋鎖（第 $count 次取樣）"
                    )
                }
                if (newSettings.wakeWordEnabled && _uiState.value.connectionState == ConnectionState.DISCONNECTED) {
                    wakeWordManager.startStandby(newSettings)
                }
            }.onFailure { err ->
                val curSettings = _uiState.value.settings
                _uiState.update {
                    it.copy(
                        isEnrollingVoiceprint = false,
                        voiceprintStatusMessage = "⚠️ ${err.message}"
                    )
                }
                if (curSettings.wakeWordEnabled && _uiState.value.connectionState == ConnectionState.DISCONNECTED) {
                    wakeWordManager.startStandby(curSettings)
                }
            }
        }
    }

    fun clearUserVoiceprint() {
        val newSettings = _uiState.value.settings.copy(wakeWordVoiceprintData = "")
        updateSettings(newSettings)
        _uiState.update {
            it.copy(
                voiceprintStatusMessage = "🗑️ 已清除已錄製的聲紋資料",
                errorToast = "已重設本人聲紋鎖資料"
            )
        }
    }

    fun fetchModelsFromApis() {
        val currentSettings = _uiState.value.settings
        _uiState.update { it.copy(isFetchingModels = true, modelFetchStatus = "正在向 API 查詢可用模型清單...") }
        viewModelScope.launch {
            val result = modelFetcher.fetchApplicableModels(currentSettings)
            val updatedSettings = currentSettings.copy(
                fetchedVoiceModels = result.voiceModels,
                fetchedBackendModels = result.backendModels
            )
            repository.saveSettings(updatedSettings)
            _uiState.update {
                it.copy(
                    settings = updatedSettings,
                    isFetchingModels = false,
                    modelFetchStatus = result.statusMessage
                )
            }
        }
    }

    fun toggleLiveCall() {
        val state = _uiState.value
        if (state.connectionState == ConnectionState.DISCONNECTED) {
            startLiveCall(isWakeWordSession = false)
        } else {
            stopLiveCall()
        }
    }

    fun startLiveCall(isWakeWordSession: Boolean = false) {
        pendingAutoHangupJob?.cancel()
        wakeWordManager.stopStandby()
        val state = _uiState.value
        _uiState.update { it.copy(isMuted = false, voiceUsageSeconds = 0) }
        liveSessionManager.connect(
            settings = state.settings,
            priorMessages = state.currentSession.messages,
            notebookEntries = state.tutorNotebook,
            conversationSnapshotProvider = { _uiState.value.currentSession.messages },
            useSpeakerphone = state.isSpeakerphoneOn,
            isWakeWordSession = isWakeWordSession
        )
    }

    fun stopLiveCall() {
        pendingAutoHangupJob?.cancel()
        liveSessionManager.disconnectGracefully()
        triggerAiTitleGenerationIfNeeded()
        triggerBackgroundMemoryExtractionIfNeeded()
        persistCurrentSession()
    }

    fun toggleMute() {
        val newMuted = !_uiState.value.isMuted
        _uiState.update { it.copy(isMuted = newMuted) }
        liveSessionManager.setMuted(newMuted)
    }

    fun toggleSpeakerphone() {
        val newSpeaker = !_uiState.value.isSpeakerphoneOn
        _uiState.update { it.copy(isSpeakerphoneOn = newSpeaker) }
        liveSessionManager.setSpeakerphone(newSpeaker)
    }

    fun setAppMode(mode: AppMode) {
        val current = _uiState.value.settings
        if (current.appMode == mode) return
        val wasConnected = _uiState.value.connectionState != ConnectionState.DISCONNECTED

        val updated = when (mode) {
            AppMode.TRANSLATE -> current.copy(
                appMode = AppMode.TRANSLATE,
                protocol = LiveProtocol.OPENAI_TRANSLATE,
                frontendVoiceModel = "gpt-realtime-translate"
            )
            AppMode.ASSISTANT, AppMode.TUTOR -> {
                val restoredVoiceModel = if (current.frontendVoiceModel.lowercase().contains("translate")) {
                    "gpt-live-1"
                } else {
                    current.frontendVoiceModel.ifBlank { "gpt-live-1" }
                }
                val restoredProtocol = when {
                    restoredVoiceModel.lowercase().startsWith("grok") -> LiveProtocol.XAI_REALTIME
                    restoredVoiceModel.lowercase().startsWith("gpt-live") -> LiveProtocol.OPENAI_LIVE
                    else -> LiveProtocol.OPENAI_REALTIME
                }
                current.copy(
                    appMode = mode,
                    protocol = restoredProtocol,
                    frontendVoiceModel = restoredVoiceModel
                )
            }
        }

        updateSettings(updated)
        if (wasConnected) {
            liveSessionManager.disconnectImmediate()
            startLiveCall()
        } else {
            val banner = when (mode) {
                AppMode.TRANSLATE ->
                    "🌐 已切換至 gpt-realtime-translate 同步口譯模式（目標：${AppSettings.languageLabel(updated.translationTargetLanguage)}）"
                AppMode.TUTOR ->
                    "🎓 已切換至 AI 外語口說陪練（${AppSettings.tutorLanguageLabel(updated.tutorTargetLanguage)} · ${updated.tutorLevel.shortLabel} · ${updated.tutorScenario.shortLabel}）"
                AppMode.ASSISTANT ->
                    "🎙️ 已切換至全雙工 AI 語音助理模式 (${updated.frontendVoiceModel} ⇄ ${updated.backendBrainModel})"
            }
            _uiState.update { it.copy(statusBanner = banner) }
        }
    }

    fun setTranslationMode(enabled: Boolean) {
        setAppMode(if (enabled) AppMode.TRANSLATE else AppMode.ASSISTANT)
    }

    fun updateTutorConfig(newSettings: AppSettings, restartScenarioGreeting: Boolean = false) {
        updateSettings(newSettings)
        liveSessionManager.updateTutorLiveSettings(newSettings, _uiState.value.tutorNotebook)
        if (restartScenarioGreeting && _uiState.value.connectionState != ConnectionState.DISCONNECTED) {
            liveSessionManager.sendTutorQuickAction("start_scenario")
        } else if (_uiState.value.connectionState == ConnectionState.DISCONNECTED && newSettings.appMode == AppMode.TUTOR) {
            _uiState.update {
                it.copy(
                    statusBanner = "🎓 外語陪練就緒：${AppSettings.tutorLanguageLabel(newSettings.tutorTargetLanguage)} (${newSettings.tutorLevel.shortLabel} · ${newSettings.tutorScenario.shortLabel})"
                )
            }
        }
    }

    fun triggerTutorQuickAction(actionType: String) {
        liveSessionManager.sendTutorQuickAction(actionType)
    }

    fun playMessageVoiceClip(filePath: String, speed: Float = 1.0f) {
        liveSessionManager.playSavedVoiceClip(filePath = filePath, speed = speed)
    }

    fun saveMessageToTutorNotebook(assistantMessage: ChatMessage) {
        val state = _uiState.value
        val userMsg = state.currentSession.messages.lastOrNull {
            it.turnIndex == assistantMessage.turnIndex && it.role == MessageRole.USER
        }
        val entry = TutorNotebookEntry(
            languageCode = state.settings.tutorTargetLanguage,
            aiSentence = assistantMessage.text.trim(),
            userSentence = userMsg?.text?.trim().orEmpty(),
            coachNotes = assistantMessage.backendThinkingText.trim()
        )
        viewModelScope.launch {
            val updated = repository.saveNotebookEntry(entry)
            _uiState.update {
                it.copy(
                    tutorNotebook = updated,
                    errorToast = "⭐ 已收錄至外語複習本！（共 ${updated.size} 筆）"
                )
            }
        }
    }

    fun deleteTutorNotebookEntry(entryId: String) {
        viewModelScope.launch {
            val updated = repository.deleteNotebookEntry(entryId)
            _uiState.update { it.copy(tutorNotebook = updated) }
        }
    }

    fun exportNotebookToAnki(entries: List<TutorNotebookEntry> = _uiState.value.tutorNotebook): String {
        return repository.exportNotebookToAnkiTsv(entries)
    }

    fun extractMemoryFromCurrentConversation(silent: Boolean = false) {
        val state = _uiState.value
        val msgs = state.currentSession.messages
        if (msgs.isEmpty()) {
            if (!silent) {
                _uiState.update { it.copy(errorToast = "目前對話尚無內容可供提煉記憶") }
            }
            return
        }
        if (state.isExtractingMemory) return
        _uiState.update { it.copy(isExtractingMemory = true) }
        viewModelScope.launch {
            val res = backendLlmClient.extractAndMergeUserMemory(
                settings = state.settings,
                messages = msgs
            )
            res.onSuccess { mergedMemory ->
                val newSettings = _uiState.value.settings.copy(userMemoryProfile = mergedMemory)
                repository.saveSettings(newSettings)
                _uiState.update {
                    it.copy(
                        settings = newSettings,
                        isExtractingMemory = false,
                        errorToast = if (silent) null else "🧠 已從對話自動提煉並更新長期記憶庫！"
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isExtractingMemory = false,
                        errorToast = if (silent) null else "提煉記憶失敗: ${err.message}"
                    )
                }
            }
        }
    }

    private fun triggerBackgroundMemoryExtractionIfNeeded() {
        val state = _uiState.value
        if (!state.settings.longTermMemoryEnabled) return
        val userTurns = state.currentSession.messages.count { it.role == MessageRole.USER && it.text.length >= 4 }
        if (userTurns >= 3) {
            extractMemoryFromCurrentConversation(silent = true)
        }
    }

    fun sendImageMessage(bitmap: android.graphics.Bitmap, userPrompt: String) {
        val state = _uiState.value
        if (state.isAnalyzingImage) return
        _uiState.update { it.copy(isAnalyzingImage = true, statusBanner = "📷 正在壓縮並傳送圖片至後端視覺模型 (${state.settings.backendBrainModel})...") }

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // Scale bitmap down to max 1152px on longest side for fast Vision upload
                val maxDim = 1152
                val w = bitmap.width.coerceAtLeast(1)
                val h = bitmap.height.coerceAtLeast(1)
                val scaledBitmap = if (w > maxDim || h > maxDim) {
                    val ratio = minOf(maxDim.toFloat() / w, maxDim.toFloat() / h)
                    android.graphics.Bitmap.createScaledBitmap(
                        bitmap,
                        (w * ratio).toInt().coerceAtLeast(1),
                        (h * ratio).toInt().coerceAtLeast(1),
                        true
                    )
                } else {
                    bitmap
                }

                val baos = java.io.ByteArrayOutputStream()
                scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 84, baos)
                val jpegBytes = baos.toByteArray()
                val base64Jpeg = android.util.Base64.encodeToString(jpegBytes, android.util.Base64.NO_WRAP)

                val imgDir = java.io.File(getApplication<Application>().filesDir, "chat_images").apply { mkdirs() }
                val imgFile = java.io.File(imgDir, "img_${System.currentTimeMillis()}.jpg")
                imgFile.writeBytes(jpegBytes)
                // Prune old images if > 40
                val allImgs = imgDir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
                if (allImgs.size > 40) {
                    allImgs.drop(40).forEach { runCatching { it.delete() } }
                }

                val isConnected = _uiState.value.connectionState != ConnectionState.DISCONNECTED
                val turnIdx = if (isConnected) {
                    liveSessionManager.allocateNextTurnIndexForVision()
                } else {
                    (_uiState.value.currentSession.messages.maxOfOrNull { it.turnIndex } ?: _uiState.value.currentSession.messages.size) + 1
                }

                val mode = state.settings.appMode
                val displayPrompt = userPrompt.trim().ifBlank {
                    when (mode) {
                        AppMode.TUTOR -> "📷 [拍照/傳圖] 請看這張圖跟我練習外語對話，並幫我標註讀音與翻譯"
                        AppMode.TRANSLATE -> "📷 [拍照/傳圖] 請翻譯圖片中的所有文字"
                        AppMode.ASSISTANT -> "📷 [拍照/傳圖] 請解析這張圖片內容"
                    }
                }

                // Add User bubble with attached imageLocalPath
                _uiState.update { cur ->
                    val msgs = cur.currentSession.messages.toMutableList()
                    msgs.add(
                        ChatMessage(
                            streamKey = "vision_user_$turnIdx",
                            turnIndex = turnIdx,
                            role = MessageRole.USER,
                            text = displayPrompt,
                            imageLocalPath = imgFile.absolutePath,
                            modelTag = "📷 視覺輸入"
                        )
                    )
                    cur.copy(
                        currentSession = cur.currentSession.copy(
                            updatedAt = System.currentTimeMillis(),
                            messages = sortMessagesByTurn(msgs)
                        )
                    )
                }
                persistCurrentSession()

                val visionTaskPrompt = when (mode) {
                    AppMode.TUTOR -> {
                        val langLabel = AppSettings.tutorLanguageLabel(state.settings.tutorTargetLanguage)
                        val isJa = state.settings.tutorTargetLanguage == "ja"
                        val readingNote = if (isJa) "（所有日文漢字請務必加上括號標註平假名讀音，如：本日(ほんじつ)のおすすめ）" else ""
                        """使用者正在練習【$langLabel】（程度：${state.settings.tutorLevel.shortLabel}），並傳送了一張照片。
使用者補充需求：「${userPrompt.ifBlank { "請解析圖片中的外語內容並與我進行情境對話練習" }}」
請用繁體中文整理以下三個區塊：
📖 【圖片外語文字辨識、標音與中譯】$readingNote
• 列出圖片中的重點外語文字／菜單／路牌內容、讀音與繁體中文翻譯。

🔑 【圖片重點單字與實用知識】
• 挑出圖片中最值得學的 2～3 個單字或文化小知識。

🗣️ 【看著這張圖，你可以這樣開口說（可直接照唸！）】
• 提供 2 句符合這張圖片情境（例如點這張菜單上的菜、詢問這張告示牌）的【$langLabel】造句範例$readingNote 與中文意思！"""
                    }
                    AppMode.TRANSLATE -> {
                        val targetLabel = AppSettings.languageLabel(state.settings.translationTargetLanguage)
                        """請辨識這張圖片中的所有文字，並完整、清晰地翻譯為目標語言：【$targetLabel】。
請以「原文 ➔ 翻譯」對照方式排版（若包含日文漢字，請保留原文日文漢字並附上讀音）。
使用者補充說明：${userPrompt.ifBlank { "無" }}"""
                    }
                    AppMode.ASSISTANT -> {
                        userPrompt.ifBlank {
                            "請詳細解析這張圖片的內容與重點；若圖片包含活動日期、時間或行程，請清楚列出（若使用者要求加入行事曆，請直接呼叫行事曆工具）。"
                        }
                    }
                }

                val res = backendLlmClient.runDelegatedTask(
                    settings = state.settings,
                    conversationHistory = _uiState.value.currentSession.messages,
                    taskHint = visionTaskPrompt,
                    imageBase64 = base64Jpeg,
                    onSearchExecuted = { searchRes ->
                        onSearchCardAdded(searchRes, turnIdx)
                    },
                    onCalendarActionLogged = { calNote ->
                        onTranscriptDelta(
                            role = MessageRole.SYSTEM,
                            delta = calNote,
                            timelineMs = System.currentTimeMillis(),
                            modelTag = "Android Calendar",
                            streamKey = "vision_cal_$turnIdx",
                            turnIndex = turnIdx
                        )
                    }
                )

                res.onSuccess { brain ->
                    if (mode == AppMode.TUTOR) {
                        onTranscriptDelta(
                            role = MessageRole.BACKEND_BRAIN,
                            delta = brain.detailedNotes,
                            timelineMs = System.currentTimeMillis(),
                            modelTag = "🎓 圖片外語解析 (${state.settings.backendBrainModel})",
                            streamKey = "tutor_coach_replace_$turnIdx",
                            turnIndex = turnIdx
                        )
                        if (_uiState.value.connectionState != ConnectionState.DISCONNECTED) {
                            val langLabel = AppSettings.tutorLanguageLabel(state.settings.tutorTargetLanguage)
                            liveSessionManager.injectVisionResultIntoActiveVoiceSession(
                                "[視覺情境輸入] 使用者剛才傳了一張照片，內容解析如下：\n${brain.detailedNotes.take(900)}\n請立刻根據這張照片的內容，用自然的【$langLabel】（程度：${state.settings.tutorLevel.shortLabel}）跟使用者開口互動或扮演店員/當地人提問！"
                            )
                        } else {
                            onTranscriptDelta(
                                role = MessageRole.ASSISTANT_VOICE,
                                delta = "📷 已為你解析圖片中的外語內容與造句提示（請見上方家教卡片）！點擊下方「開始外語口說陪練」即可直接針對這張圖片跟 AI 開口對練。",
                                timelineMs = System.currentTimeMillis(),
                                modelTag = "🎓 AI 外語家教",
                                streamKey = "vision_voice_$turnIdx",
                                turnIndex = turnIdx
                            )
                        }
                    } else {
                        onTranscriptDelta(
                            role = MessageRole.BACKEND_BRAIN,
                            delta = brain.detailedNotes,
                            timelineMs = System.currentTimeMillis(),
                            modelTag = "📷 視覺解析 (${state.settings.backendBrainModel})",
                            streamKey = "vision_backend_$turnIdx",
                            turnIndex = turnIdx
                        )
                        if (_uiState.value.connectionState != ConnectionState.DISCONNECTED &&
                            state.settings.protocol != LiveProtocol.OPENAI_TRANSLATE
                        ) {
                            liveSessionManager.injectVisionResultIntoActiveVoiceSession(
                                "[圖片解析結果] 使用者傳了一張圖片，內容為：\n${brain.spokenSummary.take(1000)}\n請用自然簡潔的口語向使用者說明重點。"
                            )
                        } else {
                            onTranscriptDelta(
                                role = MessageRole.ASSISTANT_VOICE,
                                delta = brain.spokenSummary,
                                timelineMs = System.currentTimeMillis(),
                                modelTag = if (mode == AppMode.TRANSLATE) "🌐 圖片翻譯" else state.settings.backendBrainModel,
                                streamKey = "vision_voice_$turnIdx",
                                turnIndex = turnIdx
                            )
                        }
                    }
                    onTurnCompleted()
                    _uiState.update { it.copy(isAnalyzingImage = false, statusBanner = "📷 圖片解析完成") }
                }.onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isAnalyzingImage = false,
                            statusBanner = "圖片解析失敗: ${err.message}",
                            errorToast = "圖片解析失敗: ${err.message}"
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isAnalyzingImage = false,
                        errorToast = "處理圖片時發生錯誤: ${e.message}"
                    )
                }
            }
        }
    }

    fun setTranslationTargetLanguage(langCode: String) {
        val updated = _uiState.value.settings.copy(translationTargetLanguage = langCode)
        updateSettings(updated)
        liveSessionManager.updateTranslationTargetLanguage(langCode)
    }

    fun setTranslationSecondaryLanguage(langCode: String) {
        val updated = _uiState.value.settings.copy(translationSecondaryLanguage = langCode)
        updateSettings(updated)
    }

    fun swapTranslationLanguages() {
        val s = _uiState.value.settings
        val newTarget = s.translationSecondaryLanguage
        val newSecondary = s.translationTargetLanguage
        val updated = s.copy(
            translationTargetLanguage = newTarget,
            translationSecondaryLanguage = newSecondary
        )
        updateSettings(updated)
        liveSessionManager.updateTranslationTargetLanguage(newTarget)
    }

    fun sendTypedMessage(text: String) {
        if (text.isBlank()) return
        val state = _uiState.value
        if (state.connectionState == ConnectionState.LIVE || state.connectionState == ConnectionState.DELEGATING) {
            liveSessionManager.sendTypedUserMessage(
                text = text,
                conversationSnapshotProvider = { _uiState.value.currentSession.messages }
            )
        } else {
            val offlineTurnIdx = (state.currentSession.messages.maxOfOrNull { it.turnIndex } ?: state.currentSession.messages.size) + 1
            onTranscriptDelta(
                role = MessageRole.USER,
                delta = text.trim(),
                timelineMs = System.currentTimeMillis(),
                modelTag = "User (離線文字提問)",
                streamKey = "offline_user_${offlineTurnIdx}",
                turnIndex = offlineTurnIdx
            )
            _uiState.update {
                it.copy(statusBanner = "正在呼叫後端模型 ${state.settings.backendBrainModel}...")
            }
            viewModelScope.launch {
                val res = backendLlmClient.runDelegatedTask(
                    settings = state.settings,
                    conversationHistory = _uiState.value.currentSession.messages,
                    onSearchExecuted = { searchRes ->
                        onSearchCardAdded(searchRes, offlineTurnIdx)
                    },
                    onCalendarActionLogged = { calNote ->
                        onTranscriptDelta(
                            role = MessageRole.SYSTEM,
                            delta = calNote,
                            timelineMs = System.currentTimeMillis(),
                            modelTag = "Android Calendar",
                            streamKey = "offline_cal_${offlineTurnIdx}",
                            turnIndex = offlineTurnIdx
                        )
                    }
                )
                res.onSuccess { brain ->
                    onTranscriptDelta(
                        role = MessageRole.BACKEND_BRAIN,
                        delta = brain.detailedNotes,
                        timelineMs = System.currentTimeMillis(),
                        modelTag = state.settings.backendBrainModel,
                        streamKey = "offline_backend_${offlineTurnIdx}",
                        turnIndex = offlineTurnIdx
                    )
                    onTranscriptDelta(
                        role = MessageRole.ASSISTANT_VOICE,
                        delta = brain.spokenSummary,
                        timelineMs = System.currentTimeMillis(),
                        modelTag = state.settings.backendBrainModel,
                        streamKey = "offline_voice_${offlineTurnIdx}",
                        turnIndex = offlineTurnIdx
                    )
                    onTurnCompleted()
                    _uiState.update { it.copy(statusBanner = "後端回覆完成（已自動儲存至對話紀錄）") }
                }.onFailure { err ->
                    onError("離線文字請求失敗: ${err.message}")
                    _uiState.update { it.copy(statusBanner = "請求失敗: ${err.message}") }
                }
            }
        }
    }

    fun startNewConversationSession() {
        if (_uiState.value.connectionState != ConnectionState.DISCONNECTED) {
            liveSessionManager.disconnectImmediate()
        }
        triggerBackgroundMemoryExtractionIfNeeded()
        persistCurrentSession()
        val s = _uiState.value.settings
        val timeLabel = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date())
        _uiState.update {
            it.copy(
                currentSession = ConversationSession(
                    title = "新語音對話 ($timeLabel)",
                    frontendModel = s.frontendVoiceModel,
                    backendModel = s.backendBrainModel
                ),
                statusBanner = "已開啟新對話紀錄，隨時可開始通話"
            )
        }
    }

    fun loadSavedSession(session: ConversationSession) {
        if (_uiState.value.connectionState != ConnectionState.DISCONNECTED) {
            liveSessionManager.disconnectImmediate()
        }
        persistCurrentSession()
        _uiState.update {
            it.copy(
                currentSession = session,
                statusBanner = "已載入「${session.title}」(${session.messages.size} 則紀錄)，點擊通話即可帶入上下文接續對話！"
            )
        }
    }

    fun deleteSavedSession(sessionId: String) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
            val updated = repository.loadAllSessions()
            _uiState.update { it.copy(savedSessions = updated) }
        }
    }

    fun updateHistorySearchQuery(query: String) {
        _uiState.update { it.copy(historySearchQuery = query) }
    }

    fun exportSessionMarkdown(session: ConversationSession): String {
        return repository.exportSessionToMarkdown(session)
    }

    fun clearErrorToast() {
        _uiState.update { it.copy(errorToast = null) }
    }

    private fun triggerAiTitleGenerationIfNeeded() {
        val current = _uiState.value.currentSession
        if (current.isTitleAiGenerated) return
        val hasUser = current.messages.any { it.role == MessageRole.USER && it.text.length >= 2 }
        val hasAi = current.messages.any {
            it.role != MessageRole.USER && (it.text.length >= 6 || it.backendThinkingText.length >= 6)
        }
        if (!hasUser || !hasAi) return
        if (!isGeneratingTitle.compareAndSet(false, true)) return

        val targetSessionId = current.id
        val settingsSnapshot = _uiState.value.settings
        val messagesSnapshot = current.messages

        viewModelScope.launch {
            try {
                val aiTitle = backendLlmClient.generateConversationTitle(settingsSnapshot, messagesSnapshot)
                if (!aiTitle.isNullOrBlank()) {
                    _uiState.update { state ->
                        if (state.currentSession.id == targetSessionId) {
                            state.copy(
                                currentSession = state.currentSession.copy(
                                    title = aiTitle,
                                    isTitleAiGenerated = true
                                )
                            )
                        } else {
                            state
                        }
                    }
                    persistCurrentSession()
                }
            } finally {
                isGeneratingTitle.set(false)
            }
        }
    }

    private fun persistCurrentSession() {
        val current = _uiState.value.currentSession
        if (current.messages.isEmpty()) return
        viewModelScope.launch {
            repository.saveSession(current)
            val all = repository.loadAllSessions()
            _uiState.update { it.copy(savedSessions = all) }
        }
    }

    // --- LiveSessionListener callbacks ---

    override fun onConnectionStateChanged(state: ConnectionState, statusText: String) {
        _uiState.update {
            it.copy(connectionState = state, statusBanner = statusText)
        }
        if (state == ConnectionState.DISCONNECTED) {
            viewModelScope.launch {
                delay(650L)
                val current = _uiState.value
                if (current.connectionState == ConnectionState.DISCONNECTED &&
                    current.settings.wakeWordEnabled &&
                    !current.isEnrollingVoiceprint
                ) {
                    wakeWordManager.startStandby(current.settings)
                }
            }
        }
    }

    override fun onAutoHangupRequested(reason: String, delayMs: Long) {
        pendingAutoHangupJob?.cancel()
        pendingAutoHangupJob = viewModelScope.launch {
            if (delayMs > 0L) {
                delay(delayMs)
            }
            if (_uiState.value.connectionState != ConnectionState.DISCONNECTED) {
                wakeWordManager.playHangupToneAndVibrate()
                stopLiveCall()
                _uiState.update {
                    it.copy(
                        statusBanner = reason,
                        errorToast = reason
                    )
                }
            }
        }
    }

    override fun onRemoteSessionIdResolved(sessionId: String) {
        _uiState.update { state ->
            state.copy(
                currentSession = state.currentSession.copy(remoteLiveSessionId = sessionId)
            )
        }
        persistCurrentSession()
    }

    private fun sortMessagesByTurn(msgs: List<ChatMessage>): List<ChatMessage> {
        return msgs.sortedWith { a, b ->
            if (a.turnIndex > 0 && b.turnIndex > 0) {
                val turnCmp = a.turnIndex.compareTo(b.turnIndex)
                if (turnCmp != 0) turnCmp else a.role.sortPriorityInTurn.compareTo(b.role.sortPriorityInTurn)
            } else {
                0
            }
        }
    }

    /**
     * Strict Turn + Thinking-Mode Drawer Merger:
     * - Each turn has at most ONE `USER` bubble and ONE `ASSISTANT_VOICE` bubble.
     * - Backend Brain reasoning/notes (`BACKEND_BRAIN`), Linkup searches (`WEB_SEARCH`), and Calendar actions (`SYSTEM`)
     *   are embedded directly inside that turn's `ASSISTANT_VOICE` bubble as a collapsible "Thinking & Tools" drawer!
     * - This guarantees User speech and Assistant Voice speech are NEVER interrupted or split into fragments.
     */
    override fun onTranscriptDelta(
        role: MessageRole,
        delta: String,
        timelineMs: Long,
        modelTag: String,
        streamKey: String,
        turnIndex: Int
    ) {
        _uiState.update { state ->
            val currentMsgs = state.currentSession.messages.toMutableList()
            val frontModelName = state.settings.frontendVoiceModel
            val backModelName = state.settings.backendBrainModel

            when (role) {
                MessageRole.USER -> {
                    val existingIdx = currentMsgs.indexOfLast {
                        (turnIndex > 0 && it.turnIndex == turnIndex && it.role == MessageRole.USER) ||
                                it.streamKey == streamKey
                    }
                    if (existingIdx >= 0) {
                        val existing = currentMsgs[existingIdx]
                        currentMsgs[existingIdx] = existing.copy(
                            text = existing.text + delta,
                            timestampMs = System.currentTimeMillis()
                        )
                    } else {
                        currentMsgs.add(
                            ChatMessage(
                                streamKey = streamKey,
                                turnIndex = turnIndex,
                                role = MessageRole.USER,
                                text = delta,
                                sessionTimelineMs = timelineMs,
                                modelTag = modelTag
                            )
                        )
                    }
                }

                MessageRole.ASSISTANT_VOICE -> {
                    val existingIdx = currentMsgs.indexOfLast {
                        (turnIndex > 0 && it.turnIndex == turnIndex && it.role == MessageRole.ASSISTANT_VOICE) ||
                                it.streamKey == streamKey
                    }
                    if (existingIdx >= 0) {
                        val existing = currentMsgs[existingIdx]
                        currentMsgs[existingIdx] = existing.copy(
                            text = existing.text + delta,
                            modelTag = existing.modelTag.ifBlank { modelTag },
                            timestampMs = System.currentTimeMillis()
                        )
                    } else {
                        currentMsgs.add(
                            ChatMessage(
                                streamKey = streamKey,
                                turnIndex = turnIndex,
                                role = MessageRole.ASSISTANT_VOICE,
                                text = delta,
                                backendModelTag = backModelName,
                                sessionTimelineMs = timelineMs,
                                modelTag = modelTag.ifBlank { frontModelName }
                            )
                        )
                    }
                }

                MessageRole.BACKEND_BRAIN -> {
                    // Attach Backend Brain output directly into the collapsible Thinking Drawer of this turn's Assistant bubble!
                    val existingIdx = currentMsgs.indexOfLast {
                        turnIndex > 0 && it.turnIndex == turnIndex && it.role == MessageRole.ASSISTANT_VOICE
                    }
                    val isReplace = streamKey.startsWith("tutor_coach_replace_")
                    if (existingIdx >= 0) {
                        val existing = currentMsgs[existingIdx]
                        currentMsgs[existingIdx] = existing.copy(
                            backendThinkingText = if (isReplace) delta else existing.backendThinkingText + delta,
                            backendModelTag = modelTag.ifBlank { backModelName },
                            timestampMs = System.currentTimeMillis()
                        )
                    } else {
                        currentMsgs.add(
                            ChatMessage(
                                streamKey = "turn_voice_$turnIndex",
                                turnIndex = turnIndex,
                                role = MessageRole.ASSISTANT_VOICE,
                                text = "",
                                backendThinkingText = delta,
                                backendModelTag = modelTag.ifBlank { backModelName },
                                sessionTimelineMs = timelineMs,
                                modelTag = frontModelName
                            )
                        )
                    }
                }

                MessageRole.SYSTEM, MessageRole.WEB_SEARCH -> {
                    // Attach Calendar / Tool logs directly into the collapsible Thinking Drawer of this turn's Assistant bubble!
                    val existingIdx = currentMsgs.indexOfLast {
                        turnIndex > 0 && it.turnIndex == turnIndex && it.role == MessageRole.ASSISTANT_VOICE
                    }
                    if (existingIdx >= 0) {
                        val existing = currentMsgs[existingIdx]
                        currentMsgs[existingIdx] = existing.copy(
                            calendarActionLogs = existing.calendarActionLogs + delta,
                            backendModelTag = existing.backendModelTag.ifBlank { backModelName },
                            timestampMs = System.currentTimeMillis()
                        )
                    } else {
                        currentMsgs.add(
                            ChatMessage(
                                streamKey = "turn_voice_$turnIndex",
                                turnIndex = turnIndex,
                                role = MessageRole.ASSISTANT_VOICE,
                                text = "",
                                backendModelTag = backModelName,
                                calendarActionLogs = listOf(delta),
                                sessionTimelineMs = timelineMs,
                                modelTag = frontModelName
                            )
                        )
                    }
                }
            }

            val orderedMsgs = sortMessagesByTurn(currentMsgs)

            // Immediate fallback title before AI title finishes generating
            val firstUserText = orderedMsgs.firstOrNull { it.role == MessageRole.USER }?.text?.trim().orEmpty()
            val updatedTitle = if (!state.currentSession.isTitleAiGenerated &&
                firstUserText.length >= 4 &&
                (state.currentSession.title.startsWith("新語音對話") || state.currentSession.title.startsWith("語音對話 ("))
            ) {
                firstUserText.take(20)
            } else {
                state.currentSession.title
            }

            state.copy(
                currentSession = state.currentSession.copy(
                    title = updatedTitle,
                    updatedAt = System.currentTimeMillis(),
                    frontendModel = state.settings.frontendVoiceModel,
                    backendModel = state.settings.backendBrainModel,
                    messages = orderedMsgs
                )
            )
        }
        persistCurrentSession()
    }

    override fun onSearchCardAdded(result: LinkupSearchResult, turnIndex: Int) {
        _uiState.update { state ->
            val currentMsgs = state.currentSession.messages.toMutableList()
            val frontModelName = state.settings.frontendVoiceModel
            val backModelName = state.settings.backendBrainModel
            val summaryEntry = "🔍 搜尋關鍵字：「${result.query}」\n${result.answer}"

            val existingIdx = currentMsgs.indexOfLast {
                turnIndex > 0 && it.turnIndex == turnIndex && it.role == MessageRole.ASSISTANT_VOICE
            }
            if (existingIdx >= 0) {
                val existing = currentMsgs[existingIdx]
                currentMsgs[existingIdx] = existing.copy(
                    searchQueriesAndSummaries = existing.searchQueriesAndSummaries + summaryEntry,
                    sources = (existing.sources + result.sources).distinctBy { it.url },
                    backendModelTag = existing.backendModelTag.ifBlank { backModelName },
                    timestampMs = System.currentTimeMillis()
                )
            } else {
                currentMsgs.add(
                    ChatMessage(
                        streamKey = "turn_voice_$turnIndex",
                        turnIndex = turnIndex,
                        role = MessageRole.ASSISTANT_VOICE,
                        text = "",
                        backendModelTag = backModelName,
                        searchQueriesAndSummaries = listOf(summaryEntry),
                        sources = result.sources,
                        modelTag = frontModelName
                    )
                )
            }

            val orderedMsgs = sortMessagesByTurn(currentMsgs)
            state.copy(
                currentSession = state.currentSession.copy(
                    updatedAt = System.currentTimeMillis(),
                    messages = orderedMsgs
                )
            )
        }
        persistCurrentSession()
    }

    override fun onVoiceClipSaved(turnIndex: Int, clipFilePath: String) {
        _uiState.update { state ->
            val currentMsgs = state.currentSession.messages.toMutableList()
            val idx = currentMsgs.indexOfLast {
                it.turnIndex == turnIndex && it.role == MessageRole.ASSISTANT_VOICE
            }
            if (idx >= 0) {
                currentMsgs[idx] = currentMsgs[idx].copy(audioClipPath = clipFilePath)
                state.copy(
                    currentSession = state.currentSession.copy(
                        messages = currentMsgs
                    )
                )
            } else {
                state
            }
        }
        persistCurrentSession()
    }

    override fun onTurnCompleted() {
        triggerAiTitleGenerationIfNeeded()
    }

    override fun onMicLevelChanged(level: Float) {
        _uiState.update { it.copy(micLevel = level) }
    }

    override fun onVoiceUsageUpdated(seconds: Int) {
        _uiState.update { it.copy(voiceUsageSeconds = seconds) }
    }

    override fun onError(message: String) {
        _uiState.update { it.copy(errorToast = message) }
    }

    override fun onCleared() {
        super.onCleared()
        pendingAutoHangupJob?.cancel()
        wakeWordManager.stopStandby()
        liveSessionManager.disconnectImmediate()
    }
}
