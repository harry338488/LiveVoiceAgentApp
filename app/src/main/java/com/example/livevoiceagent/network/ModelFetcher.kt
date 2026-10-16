package com.example.livevoiceagent.network

import com.example.livevoiceagent.model.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class FetchedModelsResult(
    val voiceModels: List<String>,
    val backendModels: List<String>,
    val statusMessage: String
)

class ModelFetcher {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetchApplicableModels(settings: AppSettings): FetchedModelsResult = withContext(Dispatchers.IO) {
        val discoveredVoice = linkedSetOf<String>()
        val discoveredBackend = linkedSetOf<String>()
        val statusParts = mutableListOf<String>()

        // Always keep core defaults available at the top so user can select them even if an account lists aliases differently
        discoveredVoice.addAll(AppSettings.DEFAULT_VOICE_MODELS)
        discoveredBackend.addAll(AppSettings.DEFAULT_BACKEND_MODELS)

        // 1. Fetch from OpenAI (/v1/models)
        if (settings.openAiApiKey.isNotBlank()) {
            val result = fetchFromEndpoint(
                baseUrl = "https://api.openai.com/v1",
                apiKey = settings.openAiApiKey.trim()
            )
            result.onSuccess { ids ->
                var voiceCount = 0
                var backendCount = 0
                ids.sorted().forEach { id ->
                    val lower = id.lowercase()
                    if (lower.contains("live") || lower.contains("realtime") || lower.contains("audio")) {
                        discoveredVoice.add(id)
                        voiceCount++
                    } else if (
                        lower.startsWith("gpt-6") ||
                        lower.startsWith("gpt-5") ||
                        lower.startsWith("gpt-4") ||
                        lower.startsWith("o1") ||
                        lower.startsWith("o3") ||
                        lower.startsWith("o4") ||
                        lower.contains("luna") ||
                        lower.contains("sol") ||
                        lower.contains("astra")
                    ) {
                        if (!lower.contains("embedding") && !lower.contains("tts") && !lower.contains("whisper") && !lower.contains("moderation")) {
                            discoveredBackend.add(id)
                            backendCount++
                        }
                    }
                }
                statusParts.add("OpenAI: 成功抓取 ($voiceCount 語音 / $backendCount 後端模型)")
            }.onFailure { e ->
                statusParts.add("OpenAI 抓取失敗: ${e.message}")
            }
        }

        // 2. Fetch from xAI (https://api.x.ai/v1/models)
        if (settings.xAiApiKey.isNotBlank()) {
            val result = fetchFromEndpoint(
                baseUrl = "https://api.x.ai/v1",
                apiKey = settings.xAiApiKey.trim()
            )
            result.onSuccess { ids ->
                var count = 0
                ids.sorted().forEach { id ->
                    val lower = id.lowercase()
                    if (lower.contains("voice") || lower.contains("realtime") || lower.contains("live")) {
                        discoveredVoice.add(id)
                    } else {
                        discoveredBackend.add(id)
                    }
                    count++
                }
                statusParts.add("xAI: 成功抓取 $count 個模型")
            }.onFailure { e ->
                statusParts.add("xAI 抓取失敗: ${e.message}")
            }
        }

        // 3. Fetch from Custom Backend URL if different from OpenAI/xAI
        val customUrl = settings.customBackendBaseUrl.trim().removeSuffix("/")
        val customKey = settings.customBackendApiKey.trim().ifBlank { settings.openAiApiKey.trim() }
        if (customUrl.isNotBlank() &&
            customUrl != "https://api.openai.com/v1" &&
            customUrl != "https://api.x.ai/v1" &&
            customKey.isNotBlank()
        ) {
            val result = fetchFromEndpoint(baseUrl = customUrl, apiKey = customKey)
            result.onSuccess { ids ->
                ids.sorted().forEach { id -> discoveredBackend.add(id) }
                statusParts.add("自訂後端: 成功抓取 ${ids.size} 個模型")
            }.onFailure { e ->
                statusParts.add("自訂後端抓取失敗: ${e.message}")
            }
        }

        if (statusParts.isEmpty()) {
            statusParts.add("請先輸入 OpenAI 或 xAI API Key 再點擊抓取，目前顯示預設推薦模型清單。")
        }

        FetchedModelsResult(
            voiceModels = discoveredVoice.toList(),
            backendModels = discoveredBackend.toList(),
            statusMessage = statusParts.joinToString(" | ")
        )
    }

    private fun fetchFromEndpoint(baseUrl: String, apiKey: String): Result<List<String>> = runCatching {
        val url = "${baseUrl.removeSuffix("/")}/models"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "application/json")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: ${bodyStr.take(160)}")
            }
            val json = JSONObject(bodyStr)
            val dataArr = json.optJSONArray("data") ?: return@use emptyList()
            val list = mutableListOf<String>()
            for (i in 0 until dataArr.length()) {
                val item = dataArr.optJSONObject(i)
                val id = item?.optString("id", "") ?: ""
                if (id.isNotBlank()) {
                    list.add(id)
                }
            }
            list
        }
    }
}
