package com.example.livevoiceagent.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class LiveProtocol(val label: String) {
    OPENAI_LIVE("OpenAI GPT-Live (/v1/live/sessions)"),
    OPENAI_REALTIME("OpenAI Realtime (/v1/realtime)"),
    XAI_REALTIME("xAI Grok Voice (wss://api.x.ai/v1/realtime)")
}

enum class DelegationMode(val apiValue: String, val label: String) {
    RESPONSES("responses", "Responses 雲端原生委派 (OpenAI 自動路由後端模型)"),
    CLIENT("client", "Client 本機跨廠牌委派 (App 攔截並呼叫任意後端 API)")
}

enum class MessageRole {
    USER,
    ASSISTANT_VOICE,
    BACKEND_BRAIN,
    WEB_SEARCH,
    SYSTEM
}

data class SearchSource(
    val title: String,
    val url: String,
    val snippet: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("title", title)
        put("url", url)
        put("snippet", snippet)
    }

    companion object {
        fun fromJson(obj: JSONObject): SearchSource = SearchSource(
            title = obj.optString("title", "Source"),
            url = obj.optString("url", ""),
            snippet = obj.optString("snippet", "")
        )
    }
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val streamKey: String = id,
    val role: MessageRole,
    val text: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val sessionTimelineMs: Long = 0L,
    val modelTag: String = "",
    val sources: List<SearchSource> = emptyList(),
    val isStreaming: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("streamKey", streamKey)
        put("role", role.name)
        put("text", text)
        put("timestampMs", timestampMs)
        put("sessionTimelineMs", sessionTimelineMs)
        put("modelTag", modelTag)
        val srcArr = JSONArray()
        sources.forEach { srcArr.put(it.toJson()) }
        put("sources", srcArr)
    }

    companion object {
        fun fromJson(obj: JSONObject): ChatMessage {
            val srcList = mutableListOf<SearchSource>()
            val srcArr = obj.optJSONArray("sources")
            if (srcArr != null) {
                for (i in 0 until srcArr.length()) {
                    srcArr.optJSONObject(i)?.let { srcList.add(SearchSource.fromJson(it)) }
                }
            }
            val roleStr = obj.optString("role", MessageRole.ASSISTANT_VOICE.name)
            val parsedRole = runCatching { MessageRole.valueOf(roleStr) }
                .getOrDefault(MessageRole.ASSISTANT_VOICE)
            val msgId = obj.optString("id", UUID.randomUUID().toString())

            return ChatMessage(
                id = msgId,
                streamKey = obj.optString("streamKey", msgId),
                role = parsedRole,
                text = obj.optString("text", ""),
                timestampMs = obj.optLong("timestampMs", System.currentTimeMillis()),
                sessionTimelineMs = obj.optLong("sessionTimelineMs", 0L),
                modelTag = obj.optString("modelTag", ""),
                sources = srcList,
                isStreaming = false
            )
        }
    }
}

data class ConversationSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val isTitleAiGenerated: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val frontendModel: String,
    val backendModel: String,
    val remoteLiveSessionId: String? = null,
    val messages: List<ChatMessage> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("isTitleAiGenerated", isTitleAiGenerated)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("frontendModel", frontendModel)
        put("backendModel", backendModel)
        if (remoteLiveSessionId != null) put("remoteLiveSessionId", remoteLiveSessionId)
        val msgArr = JSONArray()
        messages.forEach { msgArr.put(it.toJson()) }
        put("messages", msgArr)
    }

    companion object {
        fun fromJson(obj: JSONObject): ConversationSession {
            val msgs = mutableListOf<ChatMessage>()
            val msgArr = obj.optJSONArray("messages")
            if (msgArr != null) {
                for (i in 0 until msgArr.length()) {
                    msgArr.optJSONObject(i)?.let { msgs.add(ChatMessage.fromJson(it)) }
                }
            }
            return ConversationSession(
                id = obj.optString("id", UUID.randomUUID().toString()),
                title = obj.optString("title", "語音對話"),
                isTitleAiGenerated = obj.optBoolean("isTitleAiGenerated", false),
                createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
                frontendModel = obj.optString("frontendModel", "gpt-live-1"),
                backendModel = obj.optString("backendModel", "gpt-6-luna"),
                remoteLiveSessionId = obj.optString("remoteLiveSessionId", "").takeIf { it.isNotEmpty() },
                messages = msgs
            )
        }
    }
}

data class AppSettings(
    val openAiApiKey: String = "",
    val xAiApiKey: String = "",
    val linkupApiKey: String = "",
    val customBackendBaseUrl: String = "https://api.openai.com/v1",
    val customBackendApiKey: String = "",
    val protocol: LiveProtocol = LiveProtocol.OPENAI_LIVE,
    val frontendVoiceModel: String = "gpt-live-1",
    val backendBrainModel: String = "gpt-6-luna",
    val delegationMode: DelegationMode = DelegationMode.RESPONSES,
    val voiceName: String = "marin",
    val linkupSearchEnabled: Boolean = true,
    val linkupSearchDepth: String = "standard",
    val openAiNativeSearchEnabled: Boolean = false,
    val calendarToolsEnabled: Boolean = true,
    val voiceSystemInstructions: String = DEFAULT_FRONTEND_VOICE_PROMPT,
    val backendSystemInstructions: String = DEFAULT_BACKEND_BRAIN_PROMPT,
    val fetchedVoiceModels: List<String> = DEFAULT_VOICE_MODELS,
    val fetchedBackendModels: List<String> = DEFAULT_BACKEND_MODELS
) {
    companion object {
        const val DEFAULT_FRONTEND_VOICE_PROMPT =
            """你是負責全雙工即時語音互動的前端語音助理（如 gpt-live-1）。
1. 語言與風格：請一律使用自然、口語化的繁體中文與使用者交談，語氣親切、精煉，避免一次長篇大論唸出複雜的網址或代碼。
2. 任務委派原則：當使用者詢問需要最新網路資訊、深度推理、複雜任務整理，或是要求「查詢、新增、修改、刪除手機行事曆」時，請立即委派（Delegate）給後端模型處理。
3. 播報方式：收到後端模型的處理結果後，請用簡潔好懂的口語告訴使用者核心結論與行動狀態（例如行事曆是否已成功新增/修改），並提醒使用者完整細節與參考連結已同步留存在畫面的對話紀錄中。"""

        const val DEFAULT_BACKEND_BRAIN_PROMPT =
            """你是負責深度推理、知識檢索、網路搜尋與手機行事曆管理的後端大腦模型（如 gpt-6-luna）。
1. 語音對話協作：你正在支援一個即時語音助理。使用者的語音轉錄文字可能包含口語省略或同音字誤差，請結合上下文與最新查證的事實進行推理。
2. 工具使用規範：
   - 網路搜尋：當需要最新資訊、新聞、即時數據或查證事實時，請主動呼叫 `linkup_web_search`。
   - 手機行事曆：當使用者提到排程、提醒、會議、預約，或要求新增/更改/刪除行程時，請根據系統提供的「目前當地日期與時間」換算確切時間（yyyy-MM-dd HH:mm），並呼叫 `list_calendar_events`、`create_calendar_event`、`update_calendar_event` 或 `delete_calendar_event`。在修改或刪除行程前，若不確定 event_id，請先呼叫 `list_calendar_events` 查詢。
3. 輸出結構：請使用繁體中文回傳清晰、條理分明的重點、數據與操作確認結果，這份輸出將同時提供給前端語音模型口語轉述，並直接保存在使用者的 App 對話筆記中供隨時查閱。"""

        val DEFAULT_VOICE_MODELS = listOf(
            "gpt-live-1",
            "gpt-live-1.1",
            "gpt-live-1-mini",
            "gpt-realtime-2.1",
            "grok-voice-latest",
            "grok-voice-3.0"
        )

        val DEFAULT_BACKEND_MODELS = listOf(
            "gpt-6-luna",
            "gpt-6-sol",
            "gpt-6-astra",
            "gpt-5.6-luna",
            "gpt-5.5",
            "grok-4",
            "grok-3-mini"
        )

        val AVAILABLE_VOICES = listOf(
            "marin" to "Marin (GPT-Live 預設自然女聲)",
            "quartz" to "Quartz (澳洲清晰女聲)",
            "ripple" to "Ripple (澳洲自然男聲)",
            "vesper" to "Vesper (英式沉穩男聲)",
            "willow" to "Willow (愛爾蘭柔和女聲)",
            "stone" to "Stone (愛爾蘭男聲)",
            "gleam" to "Gleam (北美自然女聲)",
            "meridian" to "Meridian (北美自然男聲)",
            "alloy" to "Alloy (經典中性聲線)",
            "shimmer" to "Shimmer (溫暖女聲)",
            "echo" to "Echo (清晰男聲)",
            "eve" to "Eve (xAI Grok 預設聲線)",
            "ara" to "Ara (xAI Grok 女聲)",
            "rex" to "Rex (xAI Grok 男聲)"
        )
    }
}
