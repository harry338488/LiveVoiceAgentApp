package com.example.livevoiceagent.data

import android.content.Context
import com.example.livevoiceagent.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConversationRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("live_voice_agent_prefs", Context.MODE_PRIVATE)
    private val sessionsFile = File(context.filesDir, "conversation_sessions.json")
    private val notebookFile = File(context.filesDir, "tutor_notebook.json")
    private val fileMutex = Mutex()
    private val notebookMutex = Mutex()

    fun loadSettings(): AppSettings {
        val protocolName = prefs.getString("protocol", LiveProtocol.OPENAI_LIVE.name) ?: LiveProtocol.OPENAI_LIVE.name
        val parsedProtocol = runCatching { LiveProtocol.valueOf(protocolName) }.getOrDefault(LiveProtocol.OPENAI_LIVE)
        val defaultAppMode = if (parsedProtocol == LiveProtocol.OPENAI_TRANSLATE) AppMode.TRANSLATE else AppMode.ASSISTANT
        val appModeName = prefs.getString("appMode", defaultAppMode.name) ?: defaultAppMode.name
        val delegationName = prefs.getString("delegationMode", DelegationMode.RESPONSES.name) ?: DelegationMode.RESPONSES.name
        val tutorLevelName = prefs.getString("tutorLevel", TutorLevel.BEGINNER.name) ?: TutorLevel.BEGINNER.name
        val tutorScenarioName = prefs.getString("tutorScenario", TutorScenario.FREE_TALK.name) ?: TutorScenario.FREE_TALK.name

        val savedVoiceModels = prefs.getString("fetchedVoiceModels", null)
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?: AppSettings.DEFAULT_VOICE_MODELS

        val savedBackendModels = prefs.getString("fetchedBackendModels", null)
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?: AppSettings.DEFAULT_BACKEND_MODELS

        return AppSettings(
            openAiApiKey = prefs.getString("openAiApiKey", "") ?: "",
            xAiApiKey = prefs.getString("xAiApiKey", "") ?: "",
            linkupApiKey = prefs.getString("linkupApiKey", "") ?: "",
            customBackendBaseUrl = prefs.getString("customBackendBaseUrl", "https://api.openai.com/v1") ?: "https://api.openai.com/v1",
            customBackendApiKey = prefs.getString("customBackendApiKey", "") ?: "",
            appMode = runCatching { AppMode.valueOf(appModeName) }.getOrDefault(defaultAppMode),
            protocol = parsedProtocol,
            frontendVoiceModel = prefs.getString("frontendVoiceModel", "gpt-live-1") ?: "gpt-live-1",
            backendBrainModel = prefs.getString("backendBrainModel", "gpt-6-luna") ?: "gpt-6-luna",
            delegationMode = runCatching { DelegationMode.valueOf(delegationName) }.getOrDefault(DelegationMode.RESPONSES),
            voiceName = prefs.getString("voiceName", "marin") ?: "marin",
            linkupSearchEnabled = prefs.getBoolean("linkupSearchEnabled", true),
            linkupSearchDepth = prefs.getString("linkupSearchDepth", "standard") ?: "standard",
            openAiNativeSearchEnabled = prefs.getBoolean("openAiNativeSearchEnabled", false),
            calendarToolsEnabled = prefs.getBoolean("calendarToolsEnabled", true),
            locationToolsEnabled = prefs.getBoolean("locationToolsEnabled", true),
            googleMapsApiKey = prefs.getString("googleMapsApiKey", "") ?: "",
            autoEndCallOnNavigation = prefs.getBoolean("autoEndCallOnNavigation", true),
            wakeWordEnabled = prefs.getBoolean("wakeWordEnabled", false),
            wakeWordPhrase = prefs.getString("wakeWordPhrase", "hey Luna 我需要你") ?: "hey Luna 我需要你",
            wakeWordVoiceprintEnabled = prefs.getBoolean("wakeWordVoiceprintEnabled", true),
            wakeWordVoiceprintData = prefs.getString("wakeWordVoiceprintData", "") ?: "",
            wakeWordFirstUtteranceTimeoutSec = prefs.getInt("wakeWordFirstUtteranceTimeoutSec", 8),
            wakeWordIdleTimeoutSec = prefs.getInt("wakeWordIdleTimeoutSec", 25),
            wakeWordMaxDurationSec = prefs.getInt("wakeWordMaxDurationSec", 90),
            translationTargetLanguage = prefs.getString("translationTargetLanguage", "zh") ?: "zh",
            translationSecondaryLanguage = prefs.getString("translationSecondaryLanguage", "ja") ?: "ja",
            customGlossaryTerms = prefs.getString("customGlossaryTerms", "") ?: "",
            translationContextPrompt = prefs.getString("translationContextPrompt", "") ?: "",
            usePromptAwareInterpreter = prefs.getBoolean("usePromptAwareInterpreter", false),
            tutorTargetLanguage = prefs.getString("tutorTargetLanguage", "ja") ?: "ja",
            tutorLevel = runCatching { TutorLevel.valueOf(tutorLevelName) }.getOrDefault(TutorLevel.BEGINNER),
            tutorScenario = runCatching { TutorScenario.valueOf(tutorScenarioName) }.getOrDefault(TutorScenario.FREE_TALK),
            tutorCustomScenarioPrompt = prefs.getString("tutorCustomScenarioPrompt", "") ?: "",
            tutorAutoCoachEnabled = prefs.getBoolean("tutorAutoCoachEnabled", true),
            tutorInjectNotebookWeaknesses = prefs.getBoolean("tutorInjectNotebookWeaknesses", true),
            userMemoryProfile = prefs.getString("userMemoryProfile", "") ?: "",
            longTermMemoryEnabled = prefs.getBoolean("longTermMemoryEnabled", true),
            voiceSystemInstructions = prefs.getString(
                "voiceSystemInstructions",
                AppSettings.DEFAULT_FRONTEND_VOICE_PROMPT
            ) ?: AppSettings.DEFAULT_FRONTEND_VOICE_PROMPT,
            backendSystemInstructions = prefs.getString(
                "backendSystemInstructions",
                AppSettings.DEFAULT_BACKEND_BRAIN_PROMPT
            ) ?: AppSettings.DEFAULT_BACKEND_BRAIN_PROMPT,
            fetchedVoiceModels = savedVoiceModels,
            fetchedBackendModels = savedBackendModels
        )
    }

    fun saveSettings(settings: AppSettings) {
        prefs.edit().apply {
            putString("openAiApiKey", settings.openAiApiKey.trim())
            putString("xAiApiKey", settings.xAiApiKey.trim())
            putString("linkupApiKey", settings.linkupApiKey.trim())
            putString("customBackendBaseUrl", settings.customBackendBaseUrl.trim())
            putString("customBackendApiKey", settings.customBackendApiKey.trim())
            putString("appMode", settings.appMode.name)
            putString("protocol", settings.protocol.name)
            putString("frontendVoiceModel", settings.frontendVoiceModel.trim())
            putString("backendBrainModel", settings.backendBrainModel.trim())
            putString("delegationMode", settings.delegationMode.name)
            putString("voiceName", settings.voiceName.trim())
            putBoolean("linkupSearchEnabled", settings.linkupSearchEnabled)
            putString("linkupSearchDepth", settings.linkupSearchDepth)
            putBoolean("openAiNativeSearchEnabled", settings.openAiNativeSearchEnabled)
            putBoolean("calendarToolsEnabled", settings.calendarToolsEnabled)
            putBoolean("locationToolsEnabled", settings.locationToolsEnabled)
            putString("googleMapsApiKey", settings.googleMapsApiKey.trim())
            putBoolean("autoEndCallOnNavigation", settings.autoEndCallOnNavigation)
            putBoolean("wakeWordEnabled", settings.wakeWordEnabled)
            putString("wakeWordPhrase", settings.wakeWordPhrase.trim().ifBlank { "hey Luna 我需要你" })
            putBoolean("wakeWordVoiceprintEnabled", settings.wakeWordVoiceprintEnabled)
            putString("wakeWordVoiceprintData", settings.wakeWordVoiceprintData)
            putInt("wakeWordFirstUtteranceTimeoutSec", settings.wakeWordFirstUtteranceTimeoutSec.coerceIn(5, 30))
            putInt("wakeWordIdleTimeoutSec", settings.wakeWordIdleTimeoutSec.coerceIn(10, 120))
            putInt("wakeWordMaxDurationSec", settings.wakeWordMaxDurationSec.coerceIn(30, 600))
            putString("translationTargetLanguage", settings.translationTargetLanguage.trim())
            putString("translationSecondaryLanguage", settings.translationSecondaryLanguage.trim())
            putString("customGlossaryTerms", settings.customGlossaryTerms)
            putString("translationContextPrompt", settings.translationContextPrompt)
            putBoolean("usePromptAwareInterpreter", settings.usePromptAwareInterpreter)
            putString("tutorTargetLanguage", settings.tutorTargetLanguage.trim())
            putString("tutorLevel", settings.tutorLevel.name)
            putString("tutorScenario", settings.tutorScenario.name)
            putString("tutorCustomScenarioPrompt", settings.tutorCustomScenarioPrompt)
            putBoolean("tutorAutoCoachEnabled", settings.tutorAutoCoachEnabled)
            putBoolean("tutorInjectNotebookWeaknesses", settings.tutorInjectNotebookWeaknesses)
            putString("userMemoryProfile", settings.userMemoryProfile)
            putBoolean("longTermMemoryEnabled", settings.longTermMemoryEnabled)
            putString("voiceSystemInstructions", settings.voiceSystemInstructions)
            putString("backendSystemInstructions", settings.backendSystemInstructions)
            putString("fetchedVoiceModels", settings.fetchedVoiceModels.distinct().joinToString(","))
            putString("fetchedBackendModels", settings.fetchedBackendModels.distinct().joinToString(","))
            apply()
        }
    }

    suspend fun loadAllSessions(): List<ConversationSession> = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            if (!sessionsFile.exists()) return@withLock emptyList()
            runCatching {
                val content = sessionsFile.readText()
                val arr = JSONArray(content)
                val list = mutableListOf<ConversationSession>()
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { list.add(ConversationSession.fromJson(it)) }
                }
                list.sortedByDescending { it.updatedAt }
            }.getOrDefault(emptyList())
        }
    }

    suspend fun saveSession(session: ConversationSession) = withContext(Dispatchers.IO) {
        val current = loadAllSessions().toMutableList()
        val index = current.indexOfFirst { it.id == session.id }
        if (index >= 0) {
            current[index] = session
        } else {
            current.add(0, session)
        }
        writeSessionsInternal(current.sortedByDescending { it.updatedAt })
    }

    suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        val current = loadAllSessions().filterNot { it.id == sessionId }
        writeSessionsInternal(current)
    }

    private suspend fun writeSessionsInternal(sessions: List<ConversationSession>) {
        fileMutex.withLock {
            val arr = JSONArray()
            sessions.forEach { arr.put(it.toJson()) }
            sessionsFile.writeText(arr.toString(2))
        }
    }

    suspend fun loadNotebookEntries(): List<TutorNotebookEntry> = withContext(Dispatchers.IO) {
        notebookMutex.withLock {
            if (!notebookFile.exists()) return@withLock emptyList()
            runCatching {
                val content = notebookFile.readText()
                val arr = JSONArray(content)
                val list = mutableListOf<TutorNotebookEntry>()
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { list.add(TutorNotebookEntry.fromJson(it)) }
                }
                list.sortedByDescending { it.createdAt }
            }.getOrDefault(emptyList())
        }
    }

    suspend fun saveNotebookEntry(entry: TutorNotebookEntry): List<TutorNotebookEntry> = withContext(Dispatchers.IO) {
        val current = loadNotebookEntries().toMutableList()
        val existingIdx = current.indexOfFirst {
            it.id == entry.id || (it.aiSentence == entry.aiSentence && it.languageCode == entry.languageCode)
        }
        if (existingIdx >= 0) {
            current[existingIdx] = entry
        } else {
            current.add(0, entry)
        }
        val sorted = current.sortedByDescending { it.createdAt }
        notebookMutex.withLock {
            val arr = JSONArray()
            sorted.forEach { arr.put(it.toJson()) }
            notebookFile.writeText(arr.toString(2))
        }
        sorted
    }

    suspend fun deleteNotebookEntry(entryId: String): List<TutorNotebookEntry> = withContext(Dispatchers.IO) {
        val updated = loadNotebookEntries().filterNot { it.id == entryId }
        notebookMutex.withLock {
            val arr = JSONArray()
            updated.forEach { arr.put(it.toJson()) }
            notebookFile.writeText(arr.toString(2))
        }
        updated
    }

    fun exportNotebookToAnkiTsv(entries: List<TutorNotebookEntry>): String {
        val sb = StringBuilder()
        sb.appendLine("#separator:tab")
        sb.appendLine("#html:true")
        entries.forEach { entry ->
            val front = entry.aiSentence.replace("\t", " ").replace("\n", "<br>")
            val back = buildString {
                if (entry.userSentence.isNotBlank()) {
                    append("【我的原句】${entry.userSentence}<br><br>")
                }
                append(entry.coachNotes.replace("\t", " ").replace("\n", "<br>"))
            }
            val tag = "LiveVoiceTutor_${entry.languageCode}"
            sb.appendLine("$front\t$back\t$tag")
        }
        return sb.toString()
    }

    fun exportSessionToMarkdown(session: ConversationSession): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()
        sb.appendLine("# ${session.title}")
        sb.appendLine("- **時間**: ${sdf.format(Date(session.updatedAt))}")
        sb.appendLine("- **前端語音模型**: `${session.frontendModel}`")
        sb.appendLine("- **後端任務模型**: `${session.backendModel}`")
        if (!session.remoteLiveSessionId.isNullOrBlank()) {
            sb.appendLine("- **Live Session ID**: `${session.remoteLiveSessionId}`")
        }
        sb.appendLine("\n---\n")

        for (msg in session.messages) {
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(msg.timestampMs))
            val roleHeader = when (msg.role) {
                MessageRole.USER -> "🗣️ **使用者** ($timeStr)"
                MessageRole.ASSISTANT_VOICE -> "🎧 **語音助手 (`${msg.modelTag.ifBlank { session.frontendModel }}`)** ($timeStr)"
                MessageRole.BACKEND_BRAIN -> "🧠 **後端模型詳情 (`${msg.modelTag.ifBlank { session.backendModel }}`)** ($timeStr)"
                MessageRole.WEB_SEARCH -> "🌐 **Linkup 網路搜尋結果** ($timeStr)"
                MessageRole.SYSTEM -> "⚙️ **系統紀錄** ($timeStr)"
            }
            sb.appendLine("### $roleHeader")
            if (msg.text.isNotBlank()) {
                sb.appendLine(msg.text.trim())
            }
            if (msg.backendThinkingText.isNotBlank()) {
                sb.appendLine("\n> 🧠 **後端思考與重點詳情 (`${msg.backendModelTag.ifBlank { session.backendModel }}`)：**")
                msg.backendThinkingText.trim().lines().forEach { line ->
                    sb.appendLine("> $line")
                }
            }
            if (msg.searchQueriesAndSummaries.isNotEmpty()) {
                sb.appendLine("\n> 🌐 **Linkup 網路搜尋摘要：**")
                msg.searchQueriesAndSummaries.forEach { s ->
                    s.lines().forEach { line -> sb.appendLine("> $line") }
                }
            }
            if (msg.calendarActionLogs.isNotEmpty()) {
                sb.appendLine("\n> 📅 **行事曆執行紀錄：**")
                msg.calendarActionLogs.forEach { c ->
                    c.lines().forEach { line -> sb.appendLine("> $line") }
                }
            }
            if (msg.sources.isNotEmpty()) {
                sb.appendLine("\n**參考來源 (Linkup Sources):**")
                msg.sources.forEachIndexed { idx, src ->
                    sb.appendLine("${idx + 1}. [${src.title}](${src.url})")
                    if (src.snippet.isNotBlank()) {
                        sb.appendLine("   > ${src.snippet.take(180)}")
                    }
                }
            }
            sb.appendLine()
        }
        return sb.toString()
    }
}
