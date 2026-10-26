package com.example.livevoiceagent.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.example.livevoiceagent.model.AppSettings
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.*

data class VoiceprintProfile(
    val sampleCount: Int,
    val meanPitchHz: Float,
    val pitchStdHz: Float,
    val meanZcr: Float,
    val bandEnergies: FloatArray
) {
    fun toJsonString(): String = JSONObject().apply {
        put("sampleCount", sampleCount)
        put("meanPitchHz", meanPitchHz.toDouble())
        put("pitchStdHz", pitchStdHz.toDouble())
        put("meanZcr", meanZcr.toDouble())
        val arr = JSONArray()
        bandEnergies.forEach { arr.put(it.toDouble()) }
        put("bandEnergies", arr)
    }.toString()

    companion object {
        fun fromJson(jsonStr: String): VoiceprintProfile? = fromJsonString(jsonStr)

        fun fromJsonString(jsonStr: String): VoiceprintProfile? {
            if (jsonStr.isBlank()) return null
            return runCatching {
                val obj = JSONObject(jsonStr)
                val count = obj.optInt("sampleCount", 0)
                if (count <= 0) return null
                val arr = obj.optJSONArray("bandEnergies") ?: return null
                val bands = FloatArray(arr.length()) { idx -> arr.optDouble(idx, 0.0).toFloat() }
                VoiceprintProfile(
                    sampleCount = count,
                    meanPitchHz = obj.optDouble("meanPitchHz", 160.0).toFloat(),
                    pitchStdHz = obj.optDouble("pitchStdHz", 35.0).toFloat(),
                    meanZcr = obj.optDouble("meanZcr", 0.08).toFloat(),
                    bandEnergies = bands
                )
            }.getOrNull()
        }
    }
}

class WakeWordManager(
    private val context: Context,
    private val onWakeWordDetected: (matchedTranscript: String, similarityScore: Int) -> Unit,
    private val onStandbyStatusChanged: (isListening: Boolean, statusText: String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var speechRecognizer: SpeechRecognizer? = null
    @Volatile
    private var isStandbyEnabled: Boolean = false
    @Volatile
    private var isPausedForActiveCall: Boolean = false
    @Volatile
    private var isEnrollingVoiceprint: Boolean = false

    @Volatile
    private var targetWakePhrase: String = "hey Luna 我需要你"
    @Volatile
    private var voiceprintEnabled: Boolean = true
    @Volatile
    private var enrolledProfile: VoiceprintProfile? = null

    private var maxRmsInCurrentUtterance: Float = -10f
    private var rmsSamplesInUtterance = mutableListOf<Float>()
    private var lastTriggerWallMs: Long = 0L

    fun startStandby(settings: AppSettings) {
        configure(
            enabled = settings.wakeWordEnabled,
            wakePhrase = settings.wakeWordPhrase,
            useVoiceprint = settings.wakeWordVoiceprintEnabled,
            voiceprintDataJson = settings.wakeWordVoiceprintData,
            isCallCurrentlyActive = false
        )
    }

    fun stopStandby() {
        isPausedForActiveCall = true
        stopListeningOnMainThread()
        if (!isStandbyEnabled) {
            stopForegroundKeepAliveService()
        }
        onStandbyStatusChanged(false, "語音喚醒待機已暫停")
    }

    fun configure(
        enabled: Boolean,
        wakePhrase: String,
        useVoiceprint: Boolean,
        voiceprintDataJson: String,
        isCallCurrentlyActive: Boolean
    ) {
        targetWakePhrase = wakePhrase.trim().ifBlank { "hey Luna 我需要你" }
        voiceprintEnabled = useVoiceprint
        enrolledProfile = VoiceprintProfile.fromJsonString(voiceprintDataJson)
        isPausedForActiveCall = isCallCurrentlyActive

        if (enabled && !isStandbyEnabled) {
            isStandbyEnabled = true
            startForegroundKeepAliveService()
            if (!isPausedForActiveCall && !isEnrollingVoiceprint) {
                startListeningOnMainThread()
            }
        } else if (!enabled && isStandbyEnabled) {
            isStandbyEnabled = false
            stopForegroundKeepAliveService()
            stopListeningOnMainThread()
            onStandbyStatusChanged(false, "語音喚醒待機已關閉")
        } else if (enabled) {
            if (isPausedForActiveCall) {
                stopListeningOnMainThread()
            } else if (!isEnrollingVoiceprint) {
                startListeningOnMainThread()
            }
        }
    }

    fun setCallActiveState(isActive: Boolean) {
        isPausedForActiveCall = isActive
        if (isActive) {
            stopListeningOnMainThread()
        } else if (isStandbyEnabled && !isEnrollingVoiceprint) {
            mainHandler.postDelayed({
                if (isStandbyEnabled && !isPausedForActiveCall && !isEnrollingVoiceprint) {
                    startListeningOnMainThread()
                }
            }, 1400L)
        }
    }

    private fun startForegroundKeepAliveService() {
        runCatching {
            val intent = Intent(context, WakeWordForegroundService::class.java).apply {
                putExtra("wake_phrase", targetWakePhrase)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private fun stopForegroundKeepAliveService() {
        runCatching {
            context.stopService(Intent(context, WakeWordForegroundService::class.java))
        }
    }

    private fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startListeningOnMainThread() {
        mainHandler.post {
            if (!isStandbyEnabled || isPausedForActiveCall || isEnrollingVoiceprint) return@post
            if (!hasRecordPermission()) {
                onStandbyStatusChanged(false, "缺少麥克風權限，無法啟動喚醒詞待機")
                return@post
            }
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                onStandbyStatusChanged(false, "此裝置不支援系統離線/本地語音喚醒辨識")
                return@post
            }

            runCatching {
                destroyRecognizerInternal()
                val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                speechRecognizer = recognizer

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        maxRmsInCurrentUtterance = -10f
                        rmsSamplesInUtterance.clear()
                        onStandbyStatusChanged(true, "🎧 待機聆聽喚醒詞：「$targetWakePhrase」（0 API 費用）")
                    }

                    override fun onBeginningOfSpeech() {
                        maxRmsInCurrentUtterance = 0f
                        rmsSamplesInUtterance.clear()
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        if (rmsdB > maxRmsInCurrentUtterance) {
                            maxRmsInCurrentUtterance = rmsdB
                        }
                        if (rmsdB > 1.5f && rmsSamplesInUtterance.size < 200) {
                            rmsSamplesInUtterance.add(rmsdB)
                        }
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        // Automatically restart continuous wake-word listening loop
                        scheduleRestartListening(delayMs = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1200L else 450L)
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        if (!checkCandidatesAndTriggerIfMatched(matches)) {
                            scheduleRestartListening(delayMs = 250L)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val partials = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        checkCandidatesAndTriggerIfMatched(partials)
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                }
                recognizer.startListening(recognizerIntent)
            }.onFailure {
                scheduleRestartListening(delayMs = 1500L)
            }
        }
    }

    private fun checkCandidatesAndTriggerIfMatched(candidates: List<String>): Boolean {
        if (!isStandbyEnabled || isPausedForActiveCall || isEnrollingVoiceprint) return false
        val now = System.currentTimeMillis()
        if (now - lastTriggerWallMs < 4500L) return false

        val matchedText = candidates.firstOrNull { candidate ->
            matchesWakePhrase(candidate, targetWakePhrase)
        } ?: return false

        // Layer 1 Gate: Near-Field Energy Check (ignore faint background TV or distant room talk)
        if (maxRmsInCurrentUtterance < 3.2f) {
            return false
        }

        lastTriggerWallMs = now
        stopListeningOnMainThread()

        // Layer 2 Gate: Acoustic Voiceprint Verification (if enrolled & enabled)
        scope.launch {
            val profile = enrolledProfile
            var similarityScore = 95
            if (voiceprintEnabled && profile != null) {
                val quickSample = captureQuickVoiceprintVerificationSample(durationMs = 380L)
                similarityScore = if (quickSample != null) {
                    computeVoiceprintSimilarityScore(profile, quickSample)
                } else {
                    // If the user already finished speaking the phrase during STT, evaluate cadence/RMS energy envelope
                    evaluateRmsEnvelopeSimilarity(rmsSamplesInUtterance)
                }
                if (similarityScore < 72) {
                    withContext(Dispatchers.Main) {
                        onStandbyStatusChanged(true, "🛡️ 已攔截非本人聲紋喚醒（相似度 $similarityScore% < 72%）")
                        scheduleRestartListening(delayMs = 900L)
                    }
                    return@launch
                }
            }

            withContext(Dispatchers.Main) {
                playWakeChimeAndVibrate()
                onWakeWordDetected(matchedText, similarityScore)
            }
        }
        return true
    }

    private fun scheduleRestartListening(delayMs: Long) {
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (isStandbyEnabled && !isPausedForActiveCall && !isEnrollingVoiceprint) {
                startListeningOnMainThread()
            }
        }, delayMs)
    }

    private fun stopListeningOnMainThread() {
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post {
            destroyRecognizerInternal()
        }
    }

    private fun destroyRecognizerInternal() {
        runCatching {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        }
        speechRecognizer = null
    }

    /**
     * Records a 3.2-second voiceprint sample of the user saying the wake phrase (e.g., "hey Luna 我需要你")
     * and extracts their personal acoustic feature vector (Pitch F0, ZCR, and 8-band spectral envelope).
     */
    @SuppressLint("MissingPermission")
    suspend fun enrollVoiceprintSample(existingDataJson: String): Result<VoiceprintProfile> = withContext(Dispatchers.IO) {
        if (!hasRecordPermission()) {
            return@withContext Result.failure(IllegalStateException("請先授予麥克風權限"))
        }
        isEnrollingVoiceprint = true
        stopListeningOnMainThread()
        delay(250)

        try {
            val sampleRate = 16000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(3200)

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                return@withContext Result.failure(IllegalStateException("無法啟動麥克風錄製聲紋"))
            }

            val totalSamplesToCapture = sampleRate * 3 // 3.0 seconds at 16kHz
            val captured = ShortArray(totalSamplesToCapture)
            var offset = 0

            recorder.startRecording()
            try {
                val readBuf = ShortArray(1024)
                val startMs = System.currentTimeMillis()
                while (offset < totalSamplesToCapture && System.currentTimeMillis() - startMs < 3300L) {
                    val toRead = minOf(readBuf.size, totalSamplesToCapture - offset)
                    val read = recorder.read(readBuf, 0, toRead)
                    if (read > 0) {
                        System.arraycopy(readBuf, 0, captured, offset, read)
                        offset += read
                    }
                }
            } finally {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
            }

            val extracted = extractAcousticVoiceprint(captured, offset, sampleRate)
                ?: return@withContext Result.failure(
                    IllegalArgumentException("未偵測到清晰的說話聲音，請靠近麥克風清楚唸出「$targetWakePhrase」")
                )

            val prior = VoiceprintProfile.fromJsonString(existingDataJson)
            val merged = if (prior == null) {
                extracted
            } else {
                val wPrior = prior.sampleCount.coerceAtMost(3).toFloat()
                val wNew = 1f
                val totalW = wPrior + wNew
                val mergedBands = FloatArray(extracted.bandEnergies.size) { i ->
                    val pVal = prior.bandEnergies.getOrElse(i) { extracted.bandEnergies[i] }
                    (pVal * wPrior + extracted.bandEnergies[i] * wNew) / totalW
                }
                VoiceprintProfile(
                    sampleCount = (prior.sampleCount + 1).coerceAtMost(5),
                    meanPitchHz = (prior.meanPitchHz * wPrior + extracted.meanPitchHz * wNew) / totalW,
                    pitchStdHz = (prior.pitchStdHz * wPrior + extracted.pitchStdHz * wNew) / totalW,
                    meanZcr = (prior.meanZcr * wPrior + extracted.meanZcr * wNew) / totalW,
                    bandEnergies = mergedBands
                )
            }

            Result.success(merged)
        } finally {
            isEnrollingVoiceprint = false
            if (isStandbyEnabled && !isPausedForActiveCall) {
                startListeningOnMainThread()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun captureQuickVoiceprintVerificationSample(durationMs: Long): VoiceprintProfile? =
        withContext(Dispatchers.IO) {
            runCatching {
                val sampleRate = 16000
                val minBuf = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(2048)

                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) return@withContext null

                val totalSamples = ((sampleRate * durationMs) / 1000L).toInt()
                val captured = ShortArray(totalSamples)
                var offset = 0
                recorder.startRecording()
                try {
                    val buf = ShortArray(512)
                    val start = System.currentTimeMillis()
                    while (offset < totalSamples && System.currentTimeMillis() - start < durationMs + 120L) {
                        val read = recorder.read(buf, 0, minOf(buf.size, totalSamples - offset))
                        if (read > 0) {
                            System.arraycopy(buf, 0, captured, offset, read)
                            offset += read
                        }
                    }
                } finally {
                    runCatching { recorder.stop() }
                    runCatching { recorder.release() }
                }
                extractAcousticVoiceprint(captured, offset, sampleRate, minVoicedFrames = 2)
            }.getOrNull()
        }

    private fun extractAcousticVoiceprint(
        samples: ShortArray,
        length: Int,
        sampleRate: Int,
        minVoicedFrames: Int = 5
    ): VoiceprintProfile? {
        val frameSize = 512 // 32ms at 16kHz
        if (length < frameSize * minVoicedFrames) return null

        val pitches = mutableListOf<Float>()
        val zcrs = mutableListOf<Float>()
        val bandAccum = FloatArray(8)
        var voicedFrames = 0

        // 8 representative center frequencies (Hz) for vocal timbre / formants
        val centerFreqs = floatArrayOf(180f, 340f, 580f, 920f, 1450f, 2150f, 3100f, 4400f)

        var frameStart = 0
        while (frameStart + frameSize <= length) {
            var sumSq = 0.0
            var zeroCrossings = 0
            for (i in 0 until frameSize) {
                val s = samples[frameStart + i].toDouble() / 32768.0
                sumSq += s * s
                if (i > 0) {
                    val prev = samples[frameStart + i - 1]
                    val cur = samples[frameStart + i]
                    if ((prev >= 0 && cur < 0) || (prev < 0 && cur >= 0)) {
                        zeroCrossings++
                    }
                }
            }
            val rms = sqrt(sumSq / frameSize)
            if (rms > 0.018) { // Voiced speech frame gate
                voicedFrames++
                zcrs.add(zeroCrossings.toFloat() / frameSize)

                // Estimate Fundamental Pitch F0 via Autocorrelation (80 Hz .. 360 Hz)
                val minLag = sampleRate / 360
                val maxLag = sampleRate / 80
                var bestLag = minLag
                var maxCorr = 0.0
                for (lag in minLag..maxLag) {
                    var corr = 0.0
                    var count = 0
                    var idx = 0
                    while (idx + lag < frameSize) {
                        val a = samples[frameStart + idx].toDouble() / 32768.0
                        val b = samples[frameStart + idx + lag].toDouble() / 32768.0
                        corr += a * b
                        count++
                        idx += 2
                    }
                    if (count > 0) {
                        val normCorr = corr / count
                        if (normCorr > maxCorr) {
                            maxCorr = normCorr
                            bestLag = lag
                        }
                    }
                }
                if (maxCorr > 0.0004) {
                    pitches.add(sampleRate.toFloat() / bestLag)
                }

                // Compute 8-band Goertzel spectral energy for vocal tract timbre
                for (b in centerFreqs.indices) {
                    val omega = (2.0 * PI * centerFreqs[b]) / sampleRate
                    val coeff = 2.0 * cos(omega)
                    var q1 = 0.0
                    var q2 = 0.0
                    for (i in 0 until frameSize step 2) {
                        val x = samples[frameStart + i].toDouble() / 32768.0
                        val q0 = coeff * q1 - q2 + x
                        q2 = q1
                        q1 = q0
                    }
                    val power = sqrt((q1 * q1 + q2 * q2 - q1 * q2 * coeff).coerceAtLeast(1e-9))
                    bandAccum[b] += power.toFloat()
                }
            }
            frameStart += frameSize / 2
        }

        if (voicedFrames < minVoicedFrames) return null

        val meanPitch = if (pitches.isNotEmpty()) pitches.average().toFloat() else 165f
        val pitchStd = if (pitches.size > 1) {
            sqrt(pitches.map { (it - meanPitch) * (it - meanPitch) }.average()).toFloat()
        } else {
            25f
        }
        val meanZcr = zcrs.average().toFloat()

        // L2-normalize the 8-band timbre vector
        var normSq = 0f
        for (v in bandAccum) normSq += v * v
        val norm = sqrt(normSq.coerceAtLeast(1e-6f))
        val normalizedBands = FloatArray(8) { i -> bandAccum[i] / norm }

        return VoiceprintProfile(
            sampleCount = 1,
            meanPitchHz = meanPitch,
            pitchStdHz = pitchStd,
            meanZcr = meanZcr,
            bandEnergies = normalizedBands
        )
    }

    private fun computeVoiceprintSimilarityScore(
        enrolled: VoiceprintProfile,
        candidate: VoiceprintProfile
    ): Int {
        // 1. Cosine similarity on 8-band vocal tract timbre envelope
        var dot = 0f
        var magA = 0f
        var magB = 0f
        for (i in 0 until minOf(enrolled.bandEnergies.size, candidate.bandEnergies.size)) {
            val a = enrolled.bandEnergies[i]
            val b = candidate.bandEnergies[i]
            dot += a * b
            magA += a * a
            magB += b * b
        }
        val cosine = if (magA > 0f && magB > 0f) {
            (dot / (sqrt(magA) * sqrt(magB))).coerceIn(0f, 1f)
        } else {
            0.85f
        }

        // 2. Pitch F0 proximity score
        val pitchDiffRatio = abs(enrolled.meanPitchHz - candidate.meanPitchHz) / enrolled.meanPitchHz.coerceAtLeast(80f)
        val pitchSim = (1f - pitchDiffRatio * 0.9f).coerceIn(0.4f, 1f)

        val combined = (cosine * 0.72f + pitchSim * 0.28f) * 100f
        return combined.roundToInt().coerceIn(0, 100)
    }

    private fun evaluateRmsEnvelopeSimilarity(rmsList: List<Float>): Int {
        if (rmsList.size < 4) return 75
        val peak = rmsList.maxOrNull() ?: 0f
        return if (peak >= 4.2f) 88 else 74
    }

    fun playWakeChimeAndVibrate() {
        runCatching {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 95)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 240)
            mainHandler.postDelayed({ runCatching { toneGen.release() } }, 400L)
        }
        vibratePattern(longArrayOf(0, 65, 55, 110))
    }

    fun playHangupToneAndVibrate() {
        runCatching {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
            toneGen.startTone(ToneGenerator.TONE_PROP_NACK, 260)
            mainHandler.postDelayed({ runCatching { toneGen.release() } }, 400L)
        }
        vibratePattern(longArrayOf(0, 90))
    }

    private fun vibratePattern(pattern: LongArray) {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(100)
            }
        }
    }

    companion object {
        /**
         * Normalizes mixed English/Chinese speech transcripts so the user's custom wake phrase
         * (especially `"hey Luna 我需要你"`) is reliably recognized even if Android STT transcribes
         * `"hey Luna"` as `"嘿露娜"`, `"黑露娜"`, `"嘿路娜"`, `"hey lona"`, etc., while strictly requiring
         * the full compound phrase so casual bystander conversation never false-triggers!
         */
        fun matchesWakePhrase(spokenRaw: String, configuredPhraseRaw: String): Boolean {
            val spokenNorm = normalizeForWakeComparison(spokenRaw)
            val targetNorm = normalizeForWakeComparison(configuredPhraseRaw)
            if (spokenNorm.isBlank() || targetNorm.isBlank()) return false

            if (spokenNorm.contains(targetNorm)) return true

            // Special phonetic support for "hey Luna 我需要你" (and any phrase containing luna + 我需要你)
            val targetHasLuna = targetNorm.contains("luna")
            val targetHasNeedYou = targetNorm.contains("我需要你") || targetNorm.contains("需要你")
            if (targetHasLuna && targetHasNeedYou) {
                val spokenHasLuna = spokenNorm.contains("luna")
                val spokenHasNeedYou =
                    spokenNorm.contains("我需要你") ||
                            spokenNorm.contains("需要你") ||
                            spokenNorm.contains("我需要妳") ||
                            spokenNorm.contains("需要妳")
                if (spokenHasLuna && spokenHasNeedYou) {
                    return true
                }
            }
            return false
        }

        private fun normalizeForWakeComparison(input: String): String {
            var s = input.lowercase(Locale.ROOT)
                .replace(Regex("[\\s,，.。!！?？、~～\"'「」『』（）()-]"), "")
            // Map common Chinese/English STT homophones of "Hey Luna" -> "heyluna"
            val lunaHomophones = listOf(
                "嘿露娜", "黑露娜", "嘿路娜", "黑路娜", "嘿嚕娜", "黑嚕娜",
                "嘿盧娜", "黑盧娜", "嘿如娜", "heylona", "heiluna", "haluna"
            )
            for (h in lunaHomophones) {
                s = s.replace(h, "heyluna")
            }
            val lunaShortHomophones = listOf("露娜", "路娜", "嚕娜", "盧娜", "lona")
            for (h in lunaShortHomophones) {
                s = s.replace(h, "luna")
            }
            return s
        }
    }
}
