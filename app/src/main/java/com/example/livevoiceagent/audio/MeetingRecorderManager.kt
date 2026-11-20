package com.example.livevoiceagent.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.TimeUnit

data class AudioChunkSpec(
    val file: File,
    val startSeconds: Int,
    val endSeconds: Int,
    val isTemporary: Boolean
)

/**
 * Ultra-low-cost Meeting / Lecture Recorder & Long-Audio (4-5+ hour) Chunker:
 * - Records locally to compressed M4A (AAC 32kbps, ~14 MB/hour, $0 API cost while recording, works offline).
 * - Imports external audio files (M4A / MP4 / AAC / MP3 / WAV / OGG) of ANY length (even 5+ hours).
 * - Automatically slices long recordings into 15-minute chunks using native Android MediaExtractor+MediaMuxer
 *   (lossless, zero re-encoding) or WAV/MP3 boundary chunking, then transcribes with OpenAI `gpt-transcribe`.
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

    fun getAudioDurationSeconds(file: File): Int {
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                (durMs / 1000L).toInt().coerceAtLeast(0)
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrDefault(0)
    }

    /**
     * Splits any long audio file (including 4-5+ hour files) into safe <=15-minute / <=14MB chunks
     * and transcribes every chunk sequentially with `gpt-transcribe`, adding [HH:MM] section timestamps.
     */
    suspend fun transcribeLongAudioFile(
        file: File,
        apiKey: String,
        onProgress: (current: Int, total: Int, label: String) -> Unit = { _, _, _ -> }
    ): Result<Pair<String, Int>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanKey = apiKey.trim()
            if (cleanKey.isBlank()) throw IllegalArgumentException("請先設定 OpenAI API Key")
            if (!file.exists() || file.length() < 512L) throw IllegalArgumentException("錄音檔過短或無效")

            val durationSec = getAudioDurationSeconds(file)
            val chunks = buildSafeChunks(file, durationSec, chunkDurationSec = 900) // 15 min per chunk
            try {
                val combined = StringBuilder()
                chunks.forEachIndexed { idx, chunk ->
                    val startLabel = formatTimeLabel(chunk.startSeconds)
                    val endLabel = formatTimeLabel(chunk.endSeconds)
                    onProgress(idx + 1, chunks.size, "第 ${idx + 1} / ${chunks.size} 段 ($startLabel～$endLabel)")

                    val chunkText = transcribeSingleFile(chunk.file, cleanKey).getOrThrow()
                    if (chunkText.isNotBlank()) {
                        if (chunks.size > 1) {
                            combined.appendLine("【$startLabel ～ $endLabel】")
                        }
                        combined.appendLine(chunkText)
                        combined.appendLine()
                    }
                }
                val finalText = combined.toString().trim()
                if (finalText.isBlank()) throw IllegalStateException("未辨識出有效語音內容")
                finalText to durationSec
            } finally {
                chunks.filter { it.isTemporary }.forEach { runCatching { it.file.delete() } }
            }
        }
    }

    private fun buildSafeChunks(sourceFile: File, durationSec: Int, chunkDurationSec: Int): List<AudioChunkSpec> {
        val maxDirectBytes = 14L * 1024L * 1024L // 14 MB safety ceiling (OpenAI limit is 25 MB)
        val maxDirectDuration = 1080 // 18 minutes safety ceiling (OpenAI gpt-transcribe limit is ~23 min)

        if (sourceFile.length() <= maxDirectBytes && (durationSec in 1..maxDirectDuration || (durationSec == 0 && sourceFile.length() <= 6L * 1024L * 1024L))) {
            return listOf(
                AudioChunkSpec(
                    file = sourceFile,
                    startSeconds = 0,
                    endSeconds = durationSec.coerceAtLeast(1),
                    isTemporary = false
                )
            )
        }

        val ext = sourceFile.extension.lowercase(Locale.ROOT)
        // 1. Try lossless native MediaExtractor + MediaMuxer slicing for M4A / MP4 / AAC / 3GP
        if (ext in listOf("m4a", "mp4", "aac", "3gp", "3gpp")) {
            val muxed = sliceWithMediaMuxer(sourceFile, durationSec, chunkDurationSec)
            if (muxed.isNotEmpty()) return muxed
        }

        // 2. Clean WAV PCM slicing with valid 44-byte header per chunk
        if (ext == "wav") {
            val wavChunks = sliceWavFile(sourceFile, durationSec, chunkDurationSec)
            if (wavChunks.isNotEmpty()) return wavChunks
        }

        // 3. Fallback byte-slicing (<= 12 MB per chunk) for MP3 / OGG / Opus streams
        return sliceByByteSize(sourceFile, durationSec, maxChunkBytes = 12L * 1024L * 1024L)
    }

    @SuppressLint("WrongConstant")
    private fun sliceWithMediaMuxer(
        sourceFile: File,
        totalDurationSec: Int,
        chunkDurationSec: Int
    ): List<AudioChunkSpec> {
        val outList = mutableListOf<AudioChunkSpec>()
        val chunkDir = File(context.cacheDir, "audio_chunks").apply { mkdirs() }
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(sourceFile.absolutePath)
            var audioTrackIdx = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrackIdx = i
                    audioFormat = fmt
                    break
                }
            }
            if (audioTrackIdx < 0 || audioFormat == null) return emptyList()
            extractor.selectTrack(audioTrackIdx)

            val chunkUs = chunkDurationSec * 1_000_000L
            val maxBufSize = runCatching { audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }
                .getOrDefault(256 * 1024).coerceAtLeast(256 * 1024)
            val byteBuf = ByteBuffer.allocate(maxBufSize)
            val bufferInfo = MediaCodec.BufferInfo()

            var chunkIdx = 0
            var chunkStartUs = 0L
            var currentFile: File? = null
            var muxer: MediaMuxer? = null
            var muxerTrackIdx = -1
            var lastSampleUs = 0L

            fun openNextMuxer(startUs: Long) {
                val f = File(chunkDir, "chunk_${System.currentTimeMillis()}_$chunkIdx.m4a")
                val m = MediaMuxer(f.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                muxerTrackIdx = m.addTrack(audioFormat)
                m.start()
                currentFile = f
                muxer = m
                chunkStartUs = startUs
                chunkIdx++
            }

            fun closeCurrentMuxer(endUs: Long) {
                runCatching { muxer?.stop() }
                runCatching { muxer?.release() }
                muxer = null
                currentFile?.let { f ->
                    if (f.exists() && f.length() > 512L) {
                        outList.add(
                            AudioChunkSpec(
                                file = f,
                                startSeconds = (chunkStartUs / 1_000_000L).toInt(),
                                endSeconds = (endUs / 1_000_000L).toInt().coerceAtLeast((chunkStartUs / 1_000_000L).toInt() + 1),
                                isTemporary = true
                            )
                        )
                    }
                }
                currentFile = null
            }

            while (true) {
                val sampleSize = extractor.readSampleData(byteBuf, 0)
                if (sampleSize < 0) break
                val sampleTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                lastSampleUs = sampleTimeUs

                if (muxer == null) {
                    openNextMuxer(sampleTimeUs)
                } else if (sampleTimeUs - chunkStartUs >= chunkUs) {
                    closeCurrentMuxer(sampleTimeUs)
                    openNextMuxer(sampleTimeUs)
                }

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = (sampleTimeUs - chunkStartUs).coerceAtLeast(0L)
                bufferInfo.flags = extractor.sampleFlags
                muxer?.writeSampleData(muxerTrackIdx, byteBuf, bufferInfo)
                extractor.advance()
            }

            if (muxer != null) {
                val finalEndUs = maxOf(lastSampleUs, totalDurationSec * 1_000_000L)
                closeCurrentMuxer(finalEndUs)
            }
            outList
        } catch (_: Exception) {
            outList.forEach { runCatching { it.file.delete() } }
            emptyList()
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun sliceWavFile(sourceFile: File, durationSec: Int, chunkDurationSec: Int): List<AudioChunkSpec> {
        return runCatching {
            val outList = mutableListOf<AudioChunkSpec>()
            val chunkDir = File(context.cacheDir, "audio_chunks").apply { mkdirs() }
            FileInputStream(sourceFile).use { fis ->
                val header = ByteArray(44)
                if (fis.read(header) < 44) return emptyList()
                val byteRate = ByteBuffer.wrap(header, 28, 4).order(ByteOrder.LITTLE_ENDIAN).int.coerceAtLeast(16000)
                val blockAlign = ByteBuffer.wrap(header, 32, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt().coerceAtLeast(2)
                val rawChunkBytes = minOf(byteRate.toLong() * chunkDurationSec, 14L * 1024L * 1024L)
                val alignedChunkBytes = ((rawChunkBytes / blockAlign) * blockAlign).toInt().coerceAtLeast(blockAlign * 1024)

                val buf = ByteArray(64 * 1024)
                var idx = 0
                var totalBytesRead = 0L
                while (true) {
                    val outFile = File(chunkDir, "wav_chunk_${System.currentTimeMillis()}_$idx.wav")
                    var chunkWritten = 0
                    FileOutputStream(outFile).use { fos ->
                        fos.write(header) // placeholder header
                        while (chunkWritten < alignedChunkBytes) {
                            val toRead = minOf(buf.size, alignedChunkBytes - chunkWritten)
                            val r = fis.read(buf, 0, toRead)
                            if (r <= 0) break
                            fos.write(buf, 0, r)
                            chunkWritten += r
                        }
                    }
                    if (chunkWritten <= 0) {
                        runCatching { outFile.delete() }
                        break
                    }
                    // Patch WAV chunk size & data size
                    java.io.RandomAccessFile(outFile, "rw").use { raf ->
                        val hBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                        raf.seek(4)
                        hBuf.putInt(chunkWritten + 36)
                        raf.write(hBuf.array())
                        raf.seek(40)
                        hBuf.clear()
                        hBuf.putInt(chunkWritten)
                        raf.write(hBuf.array())
                    }
                    val startSec = (totalBytesRead / byteRate).toInt()
                    totalBytesRead += chunkWritten
                    val endSec = (totalBytesRead / byteRate).toInt().coerceAtLeast(startSec + 1)
                    outList.add(AudioChunkSpec(outFile, startSec, endSec, isTemporary = true))
                    idx++
                    if (chunkWritten < alignedChunkBytes) break
                }
            }
            outList
        }.getOrDefault(emptyList())
    }

    private fun sliceByByteSize(sourceFile: File, durationSec: Int, maxChunkBytes: Long): List<AudioChunkSpec> {
        val outList = mutableListOf<AudioChunkSpec>()
        val chunkDir = File(context.cacheDir, "audio_chunks").apply { mkdirs() }
        val ext = sourceFile.extension.ifBlank { "mp3" }
        val totalBytes = sourceFile.length().coerceAtLeast(1L)
        val totalChunks = ((totalBytes + maxChunkBytes - 1L) / maxChunkBytes).toInt().coerceAtLeast(1)

        FileInputStream(sourceFile).use { fis ->
            val buf = ByteArray(64 * 1024)
            for (idx in 0 until totalChunks) {
                val outFile = File(chunkDir, "part_${System.currentTimeMillis()}_$idx.$ext")
                var written = 0L
                FileOutputStream(outFile).use { fos ->
                    while (written < maxChunkBytes) {
                        val toRead = minOf(buf.size.toLong(), maxChunkBytes - written).toInt()
                        val r = fis.read(buf, 0, toRead)
                        if (r <= 0) break
                        fos.write(buf, 0, r)
                        written += r
                    }
                }
                if (written > 512L) {
                    val startSec = if (durationSec > 0) ((idx.toDouble() / totalChunks) * durationSec).toInt() else idx * 900
                    val endSec = if (durationSec > 0) (((idx + 1).toDouble() / totalChunks) * durationSec).toInt() else (idx + 1) * 900
                    outList.add(AudioChunkSpec(outFile, startSec, endSec, isTemporary = true))
                } else {
                    runCatching { outFile.delete() }
                }
            }
        }
        return outList
    }

    private fun transcribeSingleFile(file: File, apiKey: String): Result<String> = runCatching {
        val mime = when (file.extension.lowercase(Locale.ROOT)) {
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg", "opus" -> "audio/ogg"
            else -> "audio/mp4"
        }
        val models = listOf("gpt-transcribe", "gpt-4o-mini-transcribe", "whisper-1")
        var lastError = ""
        for (modelName in models) {
            val attempt = runCatching {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", file.name, file.asRequestBody(mime.toMediaType()))
                    .addFormDataPart("model", modelName)
                    .addFormDataPart("temperature", "0")
                    .build()

                val req = Request.Builder()
                    .url("https://api.openai.com/v1/audio/transcriptions")
                    .addHeader("Authorization", "Bearer $apiKey")
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
                lastError = "$modelName: ${err.message}"
            }
        }
        throw IllegalStateException("音檔轉寫失敗 ($lastError)")
    }

    private fun formatTimeLabel(totalSec: Int): String {
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }
}
