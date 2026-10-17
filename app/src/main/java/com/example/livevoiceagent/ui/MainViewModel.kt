package com.example.livevoiceagent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.livevoiceagent.data.ConversationRepository
import com.example.livevoiceagent.model.*
import com.example.livevoiceagent.network.*
import com.example.livevoiceagent.tools.CalendarToolManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

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
    val historySearchQuery: String = "",
    val isFetchingModels: Boolean = false,
    val modelFetchStatus: String = "",
    val errorToast: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application), LiveSessionListener {

    private val repository = ConversationRepository(application.applicationContext)
    private val modelFetcher = ModelFetcher()
    private val linkupClient = LinkupSearchClient()
    private val calendarToolManager = CalendarToolManager(application.applicationContext)
    private val backendLlmClient = BackendLlmClient(linkupClient, calendarToolManager)
    private val liveSessionManager = LiveSessionManager(
        context = application.applicationContext,
        linkupClient = linkupClient,
        calendarToolManager = calendarToolManager,
        backendLlmClient = backendLlmClient,
        listener = this
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val isGeneratingTitle = AtomicBoolean(false)

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
        viewModelScope.launch {
            val sessions = repository.loadAllSessions()
            _uiState.update { it.copy(savedSessions = sessions) }
        }
    }

    fun updateSettings(newSettings: AppSettings) {
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
            startLiveCall()
        } else {
            stopLiveCall()
        }
    }

    fun startLiveCall() {
        val state = _uiState.value
        _uiState.update { it.copy(isMuted = false, voiceUsageSeconds = 0) }
        liveSessionManager.connect(
            settings = state.settings,
            priorMessages = state.currentSession.messages,
            conversationSnapshotProvider = { _uiState.value.currentSession.messages },
            useSpeakerphone = state.isSpeakerphoneOn
        )
    }

    fun stopLiveCall() {
        liveSessionManager.disconnectGracefully()
        triggerAiTitleGenerationIfNeeded()
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

    fun setTranslationMode(enabled: Boolean) {
        val current = _uiState.value.settings
        val wasConnected = _uiState.value.connectionState != ConnectionState.DISCONNECTED
        val updated = if (enabled) {
            current.copy(
                protocol = LiveProtocol.OPENAI_TRANSLATE,
                frontendVoiceModel = "gpt-realtime-translate"
            )
        } else {
            val restoredVoiceModel = if (current.frontendVoiceModel.contains("translate")) {
                "gpt-live-1"
            } else {
                current.frontendVoiceModel
            }
            val restoredProtocol = when {
                restoredVoiceModel.lowercase().startsWith("grok") -> LiveProtocol.XAI_REALTIME
                restoredVoiceModel.lowercase().startsWith("gpt-live") -> LiveProtocol.OPENAI_LIVE
                else -> LiveProtocol.OPENAI_REALTIME
            }
            current.copy(
                protocol = restoredProtocol,
                frontendVoiceModel = restoredVoiceModel
            )
        }
        updateSettings(updated)
        if (wasConnected) {
            liveSessionManager.disconnectImmediate()
            startLiveCall()
        } else {
            val banner = if (enabled) {
                "🌐 已切換至 gpt-realtime-translate 同步口譯模式（目標：${AppSettings.languageLabel(updated.translationTargetLanguage)}）"
            } else {
                "🎙️ 已切換至全雙工 AI 語音助理模式 (${updated.frontendVoiceModel} ⇄ ${updated.backendBrainModel})"
            }
            _uiState.update { it.copy(statusBanner = banner) }
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
                    if (existingIdx >= 0) {
                        val existing = currentMsgs[existingIdx]
                        currentMsgs[existingIdx] = existing.copy(
                            backendThinkingText = existing.backendThinkingText + delta,
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
        liveSessionManager.disconnectImmediate()
    }
}
