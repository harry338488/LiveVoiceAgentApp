package com.example.livevoiceagent.network

import com.example.livevoiceagent.model.SearchSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    suspend fun search(
        apiKey: String,
        query: String,
        depth: String = "standard"
    ): Result<LinkupSearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanKey = apiKey.trim()
            if (cleanKey.isBlank()) {
                throw IllegalArgumentException("尚未設定 Linkup API Key，請先至「設定」頁面填入。")
            }

            val payload = JSONObject().apply {
                put("q", query)
                put("depth", if (depth == "deep") "deep" else "standard")
                put("outputType", "sourcedAnswer")
                put("includeInlineCitations", true)
            }

            val request = Request.Builder()
                .url("https://api.linkup.so/v1/search")
                .addHeader("Authorization", "Bearer $cleanKey")
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

                val sourcesArr = json.optJSONArray("sources") ?: json.optJSONArray("results")
                if (sourcesArr != null) {
                    for (i in 0 until sourcesArr.length()) {
                        val sObj = sourcesArr.optJSONObject(i) ?: continue
                        val name = sObj.optString("name", sObj.optString("title", "來源 ${i + 1}"))
                        val url = sObj.optString("url", "")
                        val snippet = sObj.optString("snippet", sObj.optString("content", ""))
                        if (url.isNotBlank() || snippet.isNotBlank()) {
                            sourcesList.add(SearchSource(title = name, url = url, snippet = snippet))
                        }
                    }
                }

                val llmBuilder = StringBuilder()
                if (answer.isNotBlank()) {
                    llmBuilder.appendLine("【Linkup 搜尋綜合解答】:")
                    llmBuilder.appendLine(answer)
                }
                if (sourcesList.isNotEmpty()) {
                    llmBuilder.appendLine("\n【參考來源】:")
                    sourcesList.take(6).forEachIndexed { idx, src ->
                        llmBuilder.appendLine("${idx + 1}. ${src.title} (${src.url})")
                        if (src.snippet.isNotBlank()) {
                            llmBuilder.appendLine("   摘要: ${src.snippet.take(260)}")
                        }
                    }
                }

                LinkupSearchResult(
                    query = query,
                    answer = answer.ifBlank {
                        sourcesList.joinToString("\n\n") { "• ${it.title}: ${it.snippet.take(200)}" }
                    },
                    sources = sourcesList,
                    rawFormattedForLlm = llmBuilder.toString().ifBlank { "查無相關結果。" }
                )
            }
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
                "Use Linkup API to search the live web for current events, facts, documentation, news, or real-time information."
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
                        put("enum", org.json.JSONArray().put("standard").put("deep"))
                        put("description", "Search depth: 'standard' for fast lookup, 'deep' for multi-step research.")
                    })
                })
                put("required", org.json.JSONArray().put("query"))
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
