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

    fun sendTypedMessage(text: String) {
        if (text.isBlank()) return
        val state = _uiState.value
        if (state.connectionState == ConnectionState.LIVE || state.connectionState == ConnectionState.DELEGATING) {
            liveSessionManager.sendTypedUserMessage(
                text = text,
                conversationSnapshotProvider = { _uiState.value.currentSession.messages }
            )
        } else {
            onTranscriptDelta(
                role = MessageRole.USER,
                delta = text.trim(),
                timelineMs = System.currentTimeMillis(),
                modelTag = "User (離線文字提問)",
                forceNewBubble = true
            )
            _uiState.update {
                it.copy(statusBanner = "正在呼叫後端模型 ${state.settings.backendBrainModel}...")
            }
            viewModelScope.launch {
                val res = backendLlmClient.runDelegatedTask(
                    settings = state.settings,
                    conversationHistory = _uiState.value.currentSession.messages,
                    onSearchExecuted = { searchRes ->
                        onSearchCardAdded(searchRes)
                    },
                    onCalendarActionLogged = { calNote ->
                        onTranscriptDelta(
                            role = MessageRole.SYSTEM,
                            delta = calNote,
                            timelineMs = System.currentTimeMillis(),
                            modelTag = "Android Calendar",
                            forceNewBubble = true
                        )
                    }
                )
                res.onSuccess { brain ->
                    onTranscriptDelta(
                        role = MessageRole.BACKEND_BRAIN,
                        delta = brain.detailedNotes,
                        timelineMs = System.currentTimeMillis(),
                        modelTag = state.settings.backendBrainModel,
                        forceNewBubble = true
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
            (it.role == MessageRole.ASSISTANT_VOICE || it.role == MessageRole.BACKEND_BRAIN) && it.text.length >= 6
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

    override fun onTranscriptDelta(
        role: MessageRole,
        delta: String,
        timelineMs: Long,
        modelTag: String,
        forceNewBubble: Boolean
    ) {
        _uiState.update { state ->
            val currentMsgs = state.currentSession.messages.toMutableList()
            val lastMsg = currentMsgs.lastOrNull()

            if (!forceNewBubble && lastMsg != null && lastMsg.role == role) {
                currentMsgs[currentMsgs.lastIndex] = lastMsg.copy(
                    text = lastMsg.text + delta,
                    timestampMs = System.currentTimeMillis()
                )
            } else {
                currentMsgs.add(
                    ChatMessage(
                        role = role,
                        text = delta,
                        sessionTimelineMs = timelineMs,
                        modelTag = modelTag
                    )
                )
            }

            // Immediate fallback title before AI title finishes generating
            val firstUserText = currentMsgs.firstOrNull { it.role == MessageRole.USER }?.text?.trim().orEmpty()
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
                    messages = currentMsgs
                )
            )
        }
        persistCurrentSession()
    }

    override fun onSearchCardAdded(result: LinkupSearchResult) {
        _uiState.update { state ->
            val currentMsgs = state.currentSession.messages.toMutableList()
            currentMsgs.add(
                ChatMessage(
                    role = MessageRole.WEB_SEARCH,
                    text = "🔍 搜尋關鍵字：「${result.query}」\n\n${result.answer}",
                    modelTag = "Linkup Search",
                    sources = result.sources
                )
            )
            state.copy(
                currentSession = state.currentSession.copy(
                    updatedAt = System.currentTimeMillis(),
                    messages = currentMsgs
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
