package com.example.livevoiceagent.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class LiveProtocol(val label: String) {
    OPENAI_LIVE("OpenAI GPT-Live (/v1/live/sessions)"),
    OPENAI_REALTIME("OpenAI Realtime (/v1/realtime)"),
    OPENAI_TRANSLATE("OpenAI 即時同步口譯 (/v1/realtime/translations)"),
    XAI_REALTIME("xAI Grok Voice (wss://api.x.ai/v1/realtime)")
}

enum class AppMode(val label: String) {
    ASSISTANT("全雙工 AI 語音助理"),
    TRANSLATE("即時同步口譯"),
    TUTOR("AI 外語口說家教")
}

enum class TutorLevel(
    val code: String,
    val shortLabel: String,
    val detailLabel: String,
    val promptInstruction: String
) {
    BEGINNER(
        code = "beginner",
        shortLabel = "🌱 初學 (N5-N4)",
        detailLabel = "慢速短句・允許中日/中英混講・主動提示",
        promptInstruction = "使用者為初學者（約日檢 JLPT N5~N4 或 CEFR A1~A2 程度）。請放慢語速、使用基礎高頻單字與簡短句型（每次 1~2 句話）。若使用者卡詞或用中文夾雜外語回答，請完全理解並在自然接話時，順帶用簡短溫和的中文教他剛才那個詞的外語怎麼說。"
    ),
    INTERMEDIATE(
        code = "intermediate",
        shortLabel = "💬 日常 (N3-N2)",
        detailLabel = "自然日常口語/丁寧語・訓練流暢聽說",
        promptInstruction = "使用者為中級程度（約日檢 JLPT N3~N2 或 CEFR B1~B2 程度）。請使用自然的日常會話語速與常用句型（日語預設使用自然的丁寧語 です/ます 或依情境切換朋友口語），90% 以上使用目標外語交談，僅在使用者主動用中文求助時才用中文輔助解釋。"
    ),
    ADVANCED(
        code = "advanced",
        shortLabel = "💼 商務/母語 (N1)",
        detailLabel = "道地母語語速・慣用語與職場敬語特訓",
        promptInstruction = "使用者為高階學習者（約日檢 JLPT N1 或 CEFR C1 程度）。請使用 100% 道地母語者的自然語速、豐富慣用語，以及精準的社交／商務敬語（如日語的尊敬語、謙譲語與委婉表達），進行高擬真的母語實戰對談。"
    )
}

enum class TutorScenario(
    val code: String,
    val shortLabel: String,
    val roleDescription: String,
    val openingTriggerPrompt: String
) {
    FREE_TALK(
        code = "free_talk",
        shortLabel = "☕ 自由閒聊",
        roleDescription = "你是使用者親切的母語朋友兼口說陪練夥伴，輕鬆聊聊日常生活、興趣、美食、旅遊或最近發生的事。",
        openingTriggerPrompt = "請用親切自然的目標語言主動向使用者打招呼，並問一個簡單好回答的日常問題（例如今天過得如何、最近有什麼有趣的事或喜歡吃什麼美食）來開啟對話。"
    ),
    IZAKAYA_ORDER(
        code = "izakaya",
        shortLabel = "🏮 餐廳/居酒屋點餐",
        roleDescription = "你扮演熱情的當地餐廳／日本居酒屋店員，使用者是剛進店的客人。請引導客人完成：確認人數與座位、介紹招牌推薦菜色與飲品、確認點餐內容到結帳的實戰對話。",
        openingTriggerPrompt = "請立刻進入「熱情的餐廳／日本居酒屋店員」角色，元氣滿滿地歡迎客人進店（例如日語：いらっしゃいませ！），並詢問今天有幾位客人、想坐吧檯還是桌席。"
    ),
    TRAVEL_HOTEL(
        code = "travel",
        shortLabel = "🧳 旅遊/飯店/問路",
        roleDescription = "你扮演當地的飯店櫃檯人員、車站站務員或藥妝店員，協助身為旅客的使用者辦理飯店 Check-in、寄放行李、詢問搭車轉乘路線或退稅購物。",
        openingTriggerPrompt = "請立刻進入「當地飯店櫃檯人員」角色，禮貌地向剛抵達櫃檯的旅客問好，詢問預訂大名並引導辦理入住（Check-in）或詢問是否需要寄放行李。"
    ),
    ANIME_CULTURE(
        code = "anime",
        shortLabel = "🎬 動漫/影視/朋友口語",
        roleDescription = "你是熱愛動漫、遊戲、音樂與影視流行文化的當地年輕朋友，使用最自然的年輕朋友口語（如日語タメ口與流行語：めっちゃ、推し、ヤバい、神作）與使用者暢聊喜愛的作品。",
        openingTriggerPrompt = "請用當地年輕朋友熱情的口語（如日語タメ口）主動打招呼，問使用者最近在追什麼動漫、日劇、遊戲，或是有沒有最喜歡的「推し（推的角色或作品）」！"
    ),
    BUSINESS_INTERVIEW(
        code = "business",
        shortLabel = "💼 外商/日商面試敬語",
        roleDescription = "你扮演當地企業的面試官或商務客戶，與使用者練習求職面試自我介紹、專案溝通，以及得體的職場商務禮儀與敬語應對。",
        openingTriggerPrompt = "請進入「企業面試官／商務主管」角色，用標準且禮貌的商務敬語向使用者問好，並請使用者先做一段簡短的自我介紹與應徵動機。"
    ),
    CUSTOM(
        code = "custom",
        shortLabel = "✨ 自訂情境",
        roleDescription = "請依照使用者自訂的角色與情境設定，與使用者進行沉浸式口說角色扮演練習。",
        openingTriggerPrompt = "請根據使用者設定的自訂情境，主動開口向使用者打招呼並開始角色扮演。"
    )
}

enum class DelegationMode(val apiValue: String, val label: String) {
    RESPONSES("responses", "Responses 雲端原生委派 (OpenAI 自動路由後端模型)"),
    CLIENT("client", "Client 本機跨廠牌委派 (App 攔截並呼叫任意後端 API)")
}

enum class MessageRole(val sortPriorityInTurn: Int) {
    USER(1),
    ASSISTANT_VOICE(2),
    BACKEND_BRAIN(3),
    WEB_SEARCH(4),
    SYSTEM(5)
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
    val turnIndex: Int = 0,
    val role: MessageRole,
    val text: String,
    val backendThinkingText: String = "",
    val backendModelTag: String = "",
    val searchQueriesAndSummaries: List<String> = emptyList(),
    val calendarActionLogs: List<String> = emptyList(),
    val timestampMs: Long = System.currentTimeMillis(),
    val sessionTimelineMs: Long = 0L,
    val modelTag: String = "",
    val sources: List<SearchSource> = emptyList(),
    val isStreaming: Boolean = false
) {
    val hasThinkingDetails: Boolean
        get() = backendThinkingText.isNotBlank() ||
                searchQueriesAndSummaries.isNotEmpty() ||
                calendarActionLogs.isNotEmpty() ||
                sources.isNotEmpty()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("streamKey", streamKey)
        put("turnIndex", turnIndex)
        put("role", role.name)
        put("text", text)
        put("backendThinkingText", backendThinkingText)
        put("backendModelTag", backendModelTag)
        val searchArr = JSONArray()
        searchQueriesAndSummaries.forEach { searchArr.put(it) }
        put("searchQueriesAndSummaries", searchArr)
        val calArr = JSONArray()
        calendarActionLogs.forEach { calArr.put(it) }
        put("calendarActionLogs", calArr)
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
            val searchList = mutableListOf<String>()
            val searchArr = obj.optJSONArray("searchQueriesAndSummaries")
            if (searchArr != null) {
                for (i in 0 until searchArr.length()) {
                    val s = searchArr.optString(i, "")
                    if (s.isNotBlank()) searchList.add(s)
                }
            }
            val calList = mutableListOf<String>()
            val calArr = obj.optJSONArray("calendarActionLogs")
            if (calArr != null) {
                for (i in 0 until calArr.length()) {
                    val c = calArr.optString(i, "")
                    if (c.isNotBlank()) calList.add(c)
                }
            }
            val roleStr = obj.optString("role", MessageRole.ASSISTANT_VOICE.name)
            val parsedRole = runCatching { MessageRole.valueOf(roleStr) }
                .getOrDefault(MessageRole.ASSISTANT_VOICE)
            val msgId = obj.optString("id", UUID.randomUUID().toString())

            return ChatMessage(
                id = msgId,
                streamKey = obj.optString("streamKey", msgId),
                turnIndex = obj.optInt("turnIndex", 0),
                role = parsedRole,
                text = obj.optString("text", ""),
                backendThinkingText = obj.optString("backendThinkingText", ""),
                backendModelTag = obj.optString("backendModelTag", ""),
                searchQueriesAndSummaries = searchList,
                calendarActionLogs = calList,
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
    val appMode: AppMode = AppMode.ASSISTANT,
    val protocol: LiveProtocol = LiveProtocol.OPENAI_LIVE,
    val frontendVoiceModel: String = "gpt-live-1",
    val backendBrainModel: String = "gpt-6-luna",
    val delegationMode: DelegationMode = DelegationMode.RESPONSES,
    val voiceName: String = "marin",
    val linkupSearchEnabled: Boolean = true,
    val linkupSearchDepth: String = "standard",
    val openAiNativeSearchEnabled: Boolean = false,
    val calendarToolsEnabled: Boolean = true,
    val translationTargetLanguage: String = "zh",
    val translationSecondaryLanguage: String = "ja",
    val customGlossaryTerms: String = "",
    val translationContextPrompt: String = "",
    val usePromptAwareInterpreter: Boolean = false,
    val tutorTargetLanguage: String = "ja",
    val tutorLevel: TutorLevel = TutorLevel.BEGINNER,
    val tutorScenario: TutorScenario = TutorScenario.FREE_TALK,
    val tutorCustomScenarioPrompt: String = "",
    val tutorAutoCoachEnabled: Boolean = true,
    val voiceSystemInstructions: String = DEFAULT_FRONTEND_VOICE_PROMPT,
    val backendSystemInstructions: String = DEFAULT_BACKEND_BRAIN_PROMPT,
    val fetchedVoiceModels: List<String> = DEFAULT_VOICE_MODELS,
    val fetchedBackendModels: List<String> = DEFAULT_BACKEND_MODELS
) {
    companion object {
        const val DEFAULT_FRONTEND_VOICE_PROMPT =
            """你是負責全雙工即時語音互動的前端語音助理（如 gpt-live-1）。
1. 語言與風格：預設使用自然、口語化的台灣繁體中文與使用者交談；若使用者在語音中夾雜英文、日文（日本語）或其他語言，請自然理解並流暢應對（保留日文漢字與假名、英文專有名詞）。
2. 任務委派原則：當使用者詢問需要最新網路資訊、深度推理、複雜任務整理，或是要求「查詢、新增、修改、刪除手機行事曆」時，請立即委派（Delegate）給後端模型處理。
3. 播報方式：收到後端模型的處理結果後，請用簡潔好懂的口語告訴使用者核心結論與行動狀態（例如行事曆是否已成功新增/修改），並提醒使用者完整細節與參考連結已同步留存在畫面的對話紀錄中。"""

        const val DEFAULT_BACKEND_BRAIN_PROMPT =
            """你是負責深度推理、知識檢索、網路搜尋與手機行事曆管理的後端大腦模型（如 gpt-6-luna）。
1. 語音對話協作：你正在支援一個即時語音助理。使用者的語音可能夾雜中、英、日文等多國語言，請結合上下文與最新查證的事實進行推理。
2. 工具使用規範：
   - 網路搜尋：當需要最新資訊、新聞、即時數據或查證事實時，請主動呼叫 `linkup_web_search`。
   - 手機行事曆：當使用者提到排程、提醒、會議、預約，或要求新增/更改/刪除行程時，請根據系統提供的「目前當地日期與時間」換算確切時間（yyyy-MM-dd HH:mm），並呼叫 `list_calendar_events`、`create_calendar_event`、`update_calendar_event` 或 `delete_calendar_event`。在修改或刪除行程前，若不確定 event_id，請先呼叫 `list_calendar_events` 查詢。
3. 輸出結構：請使用繁體中文（專有名詞或日文內容保留原文漢字與假名）回傳清晰、條理分明的重點、數據與操作確認結果，這份輸出將同時提供給前端語音模型口語轉述，並直接保存在使用者的 App 對話筆記中供隨時查閱。"""

        val DEFAULT_VOICE_MODELS = listOf(
            "gpt-live-1",
            "gpt-live-1.1",
            "gpt-live-1-mini",
            "gpt-realtime-2.1",
            "gpt-realtime-translate",
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

        val AVAILABLE_TRANSLATION_LANGUAGES = listOf(
            "zh" to "🇹🇼 繁體中文 (Chinese)",
            "ja" to "🇯🇵 日文 (Japanese)",
            "en" to "🇺🇸 英文 (English)",
            "ko" to "🇰🇷 韓文 (Korean)",
            "es" to "🇪🇸 西班牙文 (Spanish)",
            "fr" to "🇫🇷 法文 (French)",
            "de" to "🇩🇪 德文 (German)",
            "vi" to "🇻🇳 越南文 (Vietnamese)",
            "id" to "🇮🇩 印尼文 (Indonesian)",
            "pt" to "🇵🇹 葡萄牙文 (Portuguese)",
            "it" to "🇮🇹 義大利文 (Italian)",
            "ru" to "🇷🇺 俄文 (Russian)",
            "hi" to "🇮🇳 印地文 (Hindi)"
        )

        val AVAILABLE_TUTOR_LANGUAGES = listOf(
            "ja" to "🇯🇵 日語 (日本語)",
            "en" to "🇺🇸 英語 (English)",
            "ko" to "🇰🇷 韓語 (한국어)",
            "es" to "🇪🇸 西語 (Español)",
            "fr" to "🇫🇷 法語 (Français)",
            "de" to "🇩🇪 德語 (Deutsch)"
        )

        fun languageLabel(code: String): String {
            return AVAILABLE_TRANSLATION_LANGUAGES.firstOrNull { it.first == code }?.second ?: code
        }

        fun tutorLanguageLabel(code: String): String {
            return AVAILABLE_TUTOR_LANGUAGES.firstOrNull { it.first == code }?.second
                ?: languageLabel(code)
        }

        fun buildTutorVoiceInstructions(settings: AppSettings): String {
            val langLabel = tutorLanguageLabel(settings.tutorTargetLanguage)
            val scenarioDesc = if (settings.tutorScenario == TutorScenario.CUSTOM && settings.tutorCustomScenarioPrompt.isNotBlank()) {
                "自訂練習情境：${settings.tutorCustomScenarioPrompt.trim()}"
            } else {
                "${settings.tutorScenario.shortLabel} — ${settings.tutorScenario.roleDescription}"
            }

            return buildString {
                appendLine("你是專屬的沉浸式 AI 外語口說陪練家教（目標學習語言：【$langLabel】）。")
                appendLine("1. 【學生程度與語速控制】：${settings.tutorLevel.promptInstruction}")
                appendLine("2. 【當前情境與角色扮演】：$scenarioDesc")
                appendLine("3. 【中英日混講（卡詞救星）支援】：使用者母語為台灣繁體中文。當使用者在說目標外語時，如果遇到不會講的單字而混用中文（例如在日文句子中夾雜中文詞彙，或直接用中文問某個詞怎麼說），請完全理解，並在你自然接話的開頭，用溫和簡短的一句話教他該詞彙的正確【$langLabel】說法，然後繼續推進情境對話！")
                appendLine("4. 【嚴禁在語音中長篇大論說教】：請保持真實對話的節奏感！每次發言請控制在 1～3 句自然的【$langLabel】，並在結尾拋出一個簡單的互動提問讓使用者容易接話。詳細的漢字假名標音、中文翻譯與文法糾錯卡片已經由後端大腦自動顯示在手機畫面上了，你不需要在語音裡逐字唸出文法解析，除非使用者主動要求你用中文解釋。")
                if (settings.tutorTargetLanguage == "ja") {
                    appendLine("5. 【日文文字規範】：日語輸出請使用標準日文漢字與平假名／片假名。")
                }
            }
        }

        val AVAILABLE_VOICES = listOf(
            "marin" to "Marin (GPT-Live 預設自然女聲)",
            "quartz" to "Quartz (澳洲清晰女聲)",
            "ripple" to "Ripple (澳洲自然男聲)",
            "vesper" to "Vesper (英式沉穩男聲)",
            "willow" to "Willow (愛爾蘭柔和女聲)",
            "stone" to "Stone (愛爾蘭男聲)",
            "gleam" to "Gleam (北美自然女聲)",
            "meridian" to "Meridian (北美自然男聲)",
            "harema" to "Harema (日系自然女聲)",
            "shida" to "Shida (日系自然男聲)",
            "noeul" to "Noeul (韓系自然女聲)",
            "alloy" to "Alloy (經典中性聲線)",
            "shimmer" to "Shimmer (溫暖女聲)",
            "echo" to "Echo (清晰男聲)",
            "eve" to "Eve (xAI Grok 預設聲線)",
            "ara" to "Ara (xAI Grok 女聲)",
            "rex" to "Rex (xAI Grok 男聲)"
        )
    }
}
