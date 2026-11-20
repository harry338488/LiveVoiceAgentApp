package com.example.livevoiceagent.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Ultra-low-cost Meeting / Lecture Recorder:
 * - Records locally to compressed M4A (AAC 32kbps, ~14 MB/hour, $0 API cost while recording, works offline).
 * - On stop, transcribes with OpenAI's next-gen `gpt-transcribe` (~$0.003/min, ~NT$5.8/hour)
 *   with automatic fallback to `gpt-4o-mini-transcribe` / `whisper-1`.
 */
class MeetingRecorderManager(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private var mediaRecorder: MediaRecorder? = null
    private var currentOutputFile: File? = null
    @Volatile
    var isRecording: Boolean = false
        private set

    fun startRecording(): Result<File> = runCatching {
        stopRecordingSilently()
        val dir = File(context.cacheDir, "meeting_recordings").apply { mkdirs() }
        val file = File(dir, "meeting_${System.currentTimeMillis()}.m4a")
        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        mr.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(16000)
            setAudioEncodingBitRate(32000)
            setAudioChannels(1)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        mediaRecorder = mr
        currentOutputFile = file
        isRecording = true
        file
    }

    fun stopRecording(): File? {
        val out = currentOutputFile
        stopRecordingSilently()
        return out?.takeIf { it.exists() && it.length() > 512L }
    }

    fun stopRecordingSilently() {
        isRecording = false
        runCatching { mediaRecorder?.stop() }
        runCatching { mediaRecorder?.reset() }
        runCatching { mediaRecorder?.release() }
        mediaRecorder = null
    }

    suspend fun transcribeAudioFile(file: File, apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanKey = apiKey.trim()
            if (cleanKey.isBlank()) throw IllegalArgumentException("請先設定 OpenAI API Key")
            if (!file.exists() || file.length() < 512L) throw IllegalArgumentException("錄音檔過短或無效")

            val models = listOf("gpt-transcribe", "gpt-4o-mini-transcribe", "whisper-1")
            var lastError = ""
            for (modelName in models) {
                val attempt = runCatching {
                    val body = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("file", file.name, file.asRequestBody("audio/mp4".toMediaType()))
                        .addFormDataPart("model", modelName)
                        .addFormDataPart("temperature", "0")
                        .build()

                    val req = Request.Builder()
                        .url("https://api.openai.com/v1/audio/transcriptions")
                        .addHeader("Authorization", "Bearer $cleanKey")
                        .post(body)
                        .build()

                    httpClient.newCall(req).execute().use { resp ->
                        val raw = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            throw IllegalStateException("HTTP ${resp.code}: ${raw.take(180)}")
                        }
                        JSONObject(raw).optString("text", "").trim()
                    }
                }
                attempt.onSuccess { text ->
                    if (text.isNotBlank()) return@runCatching text
                }.onFailure { err ->
                    lastError = "${modelName}: ${err.message}"
                }
            }
            throw IllegalStateException("音檔轉寫失敗 ($lastError)")
        }
    }
}
