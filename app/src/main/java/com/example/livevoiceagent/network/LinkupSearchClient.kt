package com.example.livevoiceagent.network

import com.example.livevoiceagent.model.SearchSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class LinkupSearchResult(
    val query: String,
    val answer: String,
    val sources: List<SearchSource>,
    val rawFormattedForLlm: String
)

class LinkupSearchClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun search(
        apiKey: String,
        query: String,
        depth: String = "fast"
    ): Result<LinkupSearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanKey = apiKey.trim()
            if (cleanKey.isBlank()) {
                throw IllegalArgumentException("尚未設定 Linkup API Key，請先至「設定」頁面填入。")
            }

            val normalizedDepth = when (depth.trim().lowercase()) {
                "flash" -> "flash"
                "fast" -> "fast"
                "deep" -> "deep"
                else -> "standard"
            }

            // Use searchResults for ultra-low-latency live voice modes (flash/fast) to avoid double-LLM synthesis lag,
            // with graceful fallback to standard + sourcedAnswer if an alpha depth is rejected.
            val useDirectSnippets = normalizedDepth == "flash" || normalizedDepth == "fast"
            val firstAttempt = executeLinkupRequest(
                apiKey = cleanKey,
                query = query,
                depth = normalizedDepth,
                outputType = if (useDirectSnippets) "searchResults" else "sourcedAnswer"
            )

            firstAttempt.getOrElse { firstErr ->
                if (normalizedDepth == "flash" || normalizedDepth == "fast") {
                    executeLinkupRequest(
                        apiKey = cleanKey,
                        query = query,
                        depth = "standard",
                        outputType = "sourcedAnswer"
                    ).getOrThrow()
                } else {
                    throw firstErr
                }
            }
        }
    }

    private fun executeLinkupRequest(
        apiKey: String,
        query: String,
        depth: String,
        outputType: String
    ): Result<LinkupSearchResult> = runCatching {
        val payload = JSONObject().apply {
            put("q", query)
            put("depth", depth)
            put("outputType", outputType)
            if (outputType == "sourcedAnswer") {
                put("includeInlineCitations", true)
            }
        }

        val request = Request.Builder()
            .url("https://api.linkup.so/v1/search")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw IllegalStateException("Linkup API 錯誤 (HTTP ${response.code}): ${bodyStr.take(200)}")
            }

            val json = JSONObject(bodyStr)
            val answer = json.optString("answer", "")
            val sourcesList = mutableListOf<SearchSource>()

            val sourcesArr = json.optJSONArray("results") ?: json.optJSONArray("sources")
            if (sourcesArr != null) {
                for (i in 0 until sourcesArr.length()) {
                    val sObj = sourcesArr.optJSONObject(i) ?: continue
                    val name = sObj.optString("name", sObj.optString("title", "來源 ${i + 1}"))
                    val url = sObj.optString("url", "")
                    val snippet = sObj.optString("content", sObj.optString("snippet", "")).trim()
                    if (url.isNotBlank() || snippet.isNotBlank()) {
                        sourcesList.add(SearchSource(title = name, url = url, snippet = snippet))
                    }
                }
            }

            val llmBuilder = StringBuilder()
            if (answer.isNotBlank()) {
                llmBuilder.appendLine("【Linkup 搜尋綜合解答 ($depth)】:")
                llmBuilder.appendLine(answer)
            }
            if (sourcesList.isNotEmpty()) {
                llmBuilder.appendLine("\n【Linkup 即時網頁摘要 ($depth)】:")
                sourcesList.take(6).forEachIndexed { idx, src ->
                    llmBuilder.appendLine("${idx + 1}. ${src.title} (${src.url})")
                    if (src.snippet.isNotBlank()) {
                        llmBuilder.appendLine("   摘要: ${src.snippet.take(360)}")
                    }
                }
            }

            LinkupSearchResult(
                query = query,
                answer = answer.ifBlank {
                    sourcesList.take(4).joinToString("\n\n") { "• ${it.title}: ${it.snippet.take(200)}" }
                },
                sources = sourcesList,
                rawFormattedForLlm = llmBuilder.toString().ifBlank { "查無相關結果。" }
            )
        }
    }

    companion object {
        /**
         * Tool definition for OpenAI Responses Delegation (`/v1/live/sessions` -> `delegation.responses.tools`)
         */
        fun buildResponsesToolSchema(): JSONObject = JSONObject().apply {
            put("type", "function")
            put("name", "linkup_web_search")
            put(
                "description",
                "Use Linkup API to search the live web for current events, facts, weather, prices, documentation, news, or real-time information. Prefer depth='fast' (~1s) for live voice conversation."
            )
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("query", JSONObject().apply {
                        put("type", "string")
                        put("description", "Natural language search query or specific keywords to search on Linkup.")
                    })
                    put("depth", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().put("flash").put("fast").put("standard").put("deep"))
                        put("description", "Search depth: 'flash' (<200ms ultra-fast), 'fast' (~1s recommended default for live voice), 'standard' (1-3s), 'deep' (multi-step research).")
                    })
                })
                put("required", JSONArray().put("query"))
                put("additionalProperties", false)
            })
        }

        /**
         * Tool definition for standard Chat Completions (`/v1/chat/completions`)
         */
        fun buildChatCompletionsToolSchema(): JSONObject = JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "linkup_web_search")
                put(
                    "description",
                    "Use Linkup API to search the web for up-to-date facts, news, or detailed information."
                )
                put("parameters", buildResponsesToolSchema().getJSONObject("parameters"))
            })
        }
    }
}
