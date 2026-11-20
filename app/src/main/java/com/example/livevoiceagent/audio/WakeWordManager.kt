package com.example.livevoiceagent.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import com.example.livevoiceagent.model.AppSettings
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.*

data class VoiceprintProfile(
    val sampleCount: Int,
    val meanPitchHz: Float,
    val pitchStdHz: Float,
    val meanZcr: Float,
    val bandEnergies: FloatArray,
    val temporalEnvelope: FloatArray = FloatArray(0)
) {
    fun toJsonString(): String = JSONObject().apply {
        put("sampleCount", sampleCount)
        put("meanPitchHz", meanPitchHz.toDouble())
        put("pitchStdHz", pitchStdHz.toDouble())
        put("meanZcr", meanZcr.toDouble())
        val arr = JSONArray()
        bandEnergies.forEach { arr.put(it.toDouble()) }
        put("bandEnergies", arr)
        if (temporalEnvelope.isNotEmpty()) {
            val envArr = JSONArray()
            temporalEnvelope.forEach { envArr.put(it.toDouble()) }
            put("temporalEnvelope", envArr)
        }
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
                val envArr = obj.optJSONArray("temporalEnvelope")
                val env = if (envArr != null && envArr.length() > 0) {
                    FloatArray(envArr.length()) { idx -> envArr.optDouble(idx, 0.0).toFloat() }
                } else {
                    FloatArray(0)
                }
                VoiceprintProfile(
                    sampleCount = count,
                    meanPitchHz = obj.optDouble("meanPitchHz", 160.0).toFloat(),
                    pitchStdHz = obj.optDouble("pitchStdHz", 35.0).toFloat(),
                    meanZcr = obj.optDouble("meanZcr", 0.08).toFloat(),
                    bandEnergies = bands,
                    temporalEnvelope = env
                )
            }.getOrNull()
        }
    }
}

/**
 * Continuous Single-AudioRecord Wake Word & Acoustic Voiceprint Manager:
 * - Holds ONE steady AudioRecord stream while in standby (eliminating the Android SpeechRecognizer
 *   start/stop loop that caused the system microphone indicator to repeatedly flash "occupied -> cancelled").
 * - Uses a 0.55s pre-roll ring buffer + local RMS VAD energy gate ($0 API cost during silence).
 * - Verifies acoustic voiceprint directly on the captured wake utterance PCM buffer.
 * - Transcribes short candidate wake utterances via OpenAI `gpt-4o-mini-transcribe` (with bilingual prompt
 *   bias for `"hey Luna 我需要你"`) and cleanly releases the microphone BEFORE starting the Live Call.
 */
class WakeWordManager(
    private val context: Context,
    private val onWakeWordDetected: (matchedTranscript: String, similarityScore: Int) -> Unit,
    private val onStandbyStatusChanged: (isListening: Boolean, statusText: String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .build()

    private var standbyJob: Job? = null
    private var externalAudioMonitorJob: Job? = null

    @Volatile
    private var isStandbyEnabled: Boolean = false
    @Volatile
    private var isPausedForActiveCall: Boolean = false
    @Volatile
    private var isEnrollingVoiceprint: Boolean = false
    @Volatile
    private var isYieldingForExternalAudio: Boolean = false
    @Volatile
    private var activeWakeAudioSessionId: Int = -1
    @Volatile
    private var isRecordingCallbackRegistered: Boolean = false

    @Volatile
    private var openAiApiKey: String = ""
    @Volatile
    private var targetWakePhrase: String = "hey Catherine"
    @Volatile
    private var voiceprintEnabled: Boolean = true
    @Volatile
    private var enrolledProfile: VoiceprintProfile? = null
    @Volatile
    private var smartContextOnly: Boolean = false
    @Volatile
    private var offlineDtwEnabled: Boolean = false
    @Volatile
    var onWhisperWakeCallTracked: (() -> Unit)? = null

    private var lastTriggerWallMs: Long = 0L

    /**
     * System callback that fires immediately whenever another app (LINE voice message/call,
     * Camera video recording, Gboard voice typing, Voice Recorder, etc.) starts or stops recording.
     */
    private val audioRecordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            if (!isStandbyEnabled || isPausedForActiveCall || isEnrollingVoiceprint) return
            val mySessionId = activeWakeAudioSessionId
            val otherAppRecording = configs.any { cfg ->
                mySessionId <= 0 || cfg.clientAudioSessionId != mySessionId
            }
            val silencedBySystem = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mySessionId > 0) {
                configs.any { cfg -> cfg.clientAudioSessionId == mySessionId && cfg.isClientSilenced }
            } else {
                false
            }
            if ((otherAppRecording || silencedBySystem || isExternalCallOrCommunicationModeActive()) &&
                standbyJob?.isActive == true
            ) {
                yieldMicrophoneForExternalApp("📞 偵測到電話、LINE 通話或其他程式錄音，已自動禮讓麥克風")
            }
        }
    }

    private fun registerExternalAudioListenersIfNeeded() {
        if (!isRecordingCallbackRegistered) {
            runCatching {
                audioManager.registerAudioRecordingCallback(audioRecordingCallback, mainHandler)
                isRecordingCallbackRegistered = true
            }
        }
        if (externalAudioMonitorJob?.isActive != true) {
            externalAudioMonitorJob = scope.launch {
                var clearChecks = 0
                while (isActive && isStandbyEnabled) {
                    delay(900L)
                    if (!isStandbyEnabled || isPausedForActiveCall || isEnrollingVoiceprint) {
                        clearChecks = 0
                        continue
                    }
                    val callOrVoipBusy = isExternalCallOrCommunicationModeActive()
                    val otherRecBusy = hasOtherAppActiveRecording()
                    val contextBlocked = smartContextOnly && !isSmartContextSatisfied()

                    if (callOrVoipBusy || otherRecBusy) {
                        clearChecks = 0
                        if (standbyJob?.isActive == true) {
                            yieldMicrophoneForExternalApp("📞 偵測到電話或通訊軟體(LINE等)通話中，已自動釋放麥克風禮讓")
                        }
                    } else if (contextBlocked) {
                        clearChecks = 0
                        if (standbyJob?.isActive == true) {
                            yieldMicrophoneForExternalApp("🔋 智慧情境待機：未充電且未連藍牙耳機/車機，已釋放麥克風讓 Hey Google 正常運作")
                        }
                    } else if (isYieldingForExternalAudio || standbyJob?.isActive != true) {
                        clearChecks++
                        if (clearChecks >= 2) {
                            clearChecks = 0
                            isYieldingForExternalAudio = false
                            startContinuousStandbyJobIfNeeded()
                        }
                    }
                }
            }
        }
    }

    private fun unregisterExternalAudioListeners() {
        externalAudioMonitorJob?.cancel()
        externalAudioMonitorJob = null
        if (isRecordingCallbackRegistered) {
            runCatching {
                audioManager.unregisterAudioRecordingCallback(audioRecordingCallback)
            }
            isRecordingCallbackRegistered = false
        }
    }

    private fun isExternalCallOrCommunicationModeActive(): Boolean {
        return runCatching {
            val mode = audioManager.mode
            mode == AudioManager.MODE_RINGTONE ||
                    mode == AudioManager.MODE_IN_CALL ||
                    mode == AudioManager.MODE_IN_COMMUNICATION ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mode == AudioManager.MODE_CALL_SCREENING)
        }.getOrDefault(false)
    }

    private fun hasOtherAppActiveRecording(): Boolean {
        return runCatching {
            val mySessionId = activeWakeAudioSessionId
            val configs = audioManager.activeRecordingConfigurations
            configs.any { cfg ->
                mySessionId <= 0 || cfg.clientAudioSessionId != mySessionId
            }
        }.getOrDefault(false)
    }

    private fun yieldMicrophoneForExternalApp(statusReason: String) {
        isYieldingForExternalAudio = true
        stopContinuousStandbyJob()
        notifyStatusOnMain(false, statusReason)
    }

    private fun isSmartContextSatisfied(): Boolean {
        val isCharging = runCatching {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            bm.isCharging
        }.getOrDefault(false)
        if (isCharging) return true

        val hasBluetoothAudio = runCatching {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            devices.any { d ->
                d.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        d.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && (
                                d.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET ||
                                        d.type == android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER
                                ))
            }
        }.getOrDefault(false)
        return hasBluetoothAudio
    }

    fun startStandby(settings: AppSettings) {
        openAiApiKey = settings.openAiApiKey.trim()
        smartContextOnly = settings.wakeWordSmartContextOnly
        offlineDtwEnabled = settings.wakeWordOfflineDtwEnabled
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
        stopContinuousStandbyJob()
        if (!isStandbyEnabled) {
            unregisterExternalAudioListeners()
            stopForegroundKeepAliveService()
        }
        notifyStatusOnMain(false, "語音喚醒待機已暫停")
    }

    fun configure(
        enabled: Boolean,
        wakePhrase: String,
        useVoiceprint: Boolean,
        voiceprintDataJson: String,
        isCallCurrentlyActive: Boolean
    ) {
        targetWakePhrase = wakePhrase.trim().ifBlank { "hey Catherine" }
        voiceprintEnabled = useVoiceprint
        enrolledProfile = VoiceprintProfile.fromJsonString(voiceprintDataJson)
        isPausedForActiveCall = isCallCurrentlyActive

        if (enabled) {
            isStandbyEnabled = true
            startForegroundKeepAliveService()
            registerExternalAudioListenersIfNeeded()
            if (isPausedForActiveCall || isEnrollingVoiceprint) {
                stopContinuousStandbyJob()
            } else {
                startContinuousStandbyJobIfNeeded()
            }
        } else {
            isStandbyEnabled = false
            isYieldingForExternalAudio = false
            unregisterExternalAudioListeners()
            stopContinuousStandbyJob()
            stopForegroundKeepAliveService()
            notifyStatusOnMain(false, "語音喚醒待機已關閉")
        }
    }

    fun setCallActiveState(isActive: Boolean) {
        isPausedForActiveCall = isActive
        if (isActive) {
            stopContinuousStandbyJob()
        } else if (isStandbyEnabled && !isEnrollingVoiceprint) {
            mainHandler.postDelayed({
                if (isStandbyEnabled && !isPausedForActiveCall && !isEnrollingVoiceprint) {
                    startContinuousStandbyJobIfNeeded()
                }
            }, 900L)
        }
    }

    private fun notifyStatusOnMain(isListening: Boolean, statusText: String) {
        mainHandler.post {
            onStandbyStatusChanged(isListening, statusText)
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

    private fun stopContinuousStandbyJob() {
        val job = standbyJob
        standbyJob = null
        job?.cancel()
    }

    @SuppressLint("MissingPermission")
    private fun startContinuousStandbyJobIfNeeded() {
        if (!isStandbyEnabled || isPausedForActiveCall || isEnrollingVoiceprint) return
        if (standbyJob?.isActive == true) {
            notifyStatusOnMain(true, "🎧 穩定待機中：請說「$targetWakePhrase」（靜音時 0 費用）")
            return
        }
        if (!hasRecordPermission()) {
            notifyStatusOnMain(false, "缺少麥克風權限，無法啟動喚醒詞待機")
            return
        }

        standbyJob = scope.launch {
            // Brief settle delay so any prior call AudioRecord is 100% released
            delay(250L)
            if (!isActive || !isStandbyEnabled || isPausedForActiveCall || isEnrollingVoiceprint) return@launch

            if (isExternalCallOrCommunicationModeActive() || hasOtherAppActiveRecording()) {
                isYieldingForExternalAudio = true
                notifyStatusOnMain(false, "📞 偵測到電話或通訊軟體(LINE等)使用麥克風中，已自動禮讓")
                return@launch
            }
            if (smartContextOnly && !isSmartContextSatisfied()) {
                isYieldingForExternalAudio = true
                notifyStatusOnMain(false, "🔋 智慧情境待機：未充電且未連藍牙耳機/車機，已釋放麥克風讓 Hey Google 運作")
                return@launch
            }

            val sampleRate = 16000
            val frameSize = 512 // 32ms per frame at 16kHz
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(frameSize * 8)

            val recorder = createWakeAudioRecord(sampleRate, minBuf * 2)
            if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                isYieldingForExternalAudio = true
                notifyStatusOnMain(false, "📞 麥克風正由其他程式使用中，將自動等待並恢復待機")
                return@launch
            }

            activeWakeAudioSessionId = recorder.audioSessionId
            try {
                recorder.startRecording()
                isYieldingForExternalAudio = false
                notifyStatusOnMain(true, "🎧 穩定待機中：請說「$targetWakePhrase」（電話/LINE 通話會自動禮讓）")

                // Pre-roll circular buffer (~576ms = 18 frames) so the first word "hey" is never clipped
                val preRollCapacity = 18
                val preRollFrames = ArrayDeque<ShortArray>(preRollCapacity)

                // Max wake utterance capture: 4.2 seconds (132 frames of 32ms)
                val maxSpeechFrames = 132
                val speechFrames = ArrayList<ShortArray>(maxSpeechFrames + preRollCapacity)

                var isCollectingSpeech = false
                var consecutiveActiveFrames = 0
                var consecutiveSilentFrames = 0
                var voicedFramesInUtterance = 0
                var maxRmsInUtterance = 0f
                var sumRmsInUtterance = 0f
                var ambientNoiseRms = 0.008f
                var modeCheckCounter = 0

                val frameBuf = ShortArray(frameSize)

                while (isActive && isStandbyEnabled && !isPausedForActiveCall && !isEnrollingVoiceprint) {
                    // Check every ~320ms (10 frames) if a phone call or LINE/VoIP call started
                    modeCheckCounter++
                    if (modeCheckCounter >= 10) {
                        modeCheckCounter = 0
                        if (isExternalCallOrCommunicationModeActive()) {
                            isYieldingForExternalAudio = true
                            notifyStatusOnMain(false, "📞 偵測到電話或 LINE 語音通話，已自動釋放麥克風禮讓")
                            break
                        }
                    }

                    val read = recorder.read(frameBuf, 0, frameSize)
                    if (read <= 0) {
                        delay(15L)
                        continue
                    }

                    // Calculate frame RMS energy (0.0 .. 1.0)
                    var sumSq = 0.0
                    for (i in 0 until read) {
                        val norm = frameBuf[i].toDouble() / 32768.0
                        sumSq += norm * norm
                    }
                    val frameRms = sqrt(sumSq / read).toFloat()
                    val frameCopy = frameBuf.copyOf(read)

                    // Balanced near-to-mid field speech threshold (calibrated for short 3-4 syllable "hey Catherine" at desk distance)
                    val onsetThreshold = (ambientNoiseRms * 2.5f).coerceIn(0.018f, 0.052f)
                    val sustainThreshold = (ambientNoiseRms * 1.8f).coerceIn(0.012f, 0.036f)

                    if (!isCollectingSpeech) {
                        if (frameRms < onsetThreshold) {
                            ambientNoiseRms = (ambientNoiseRms * 0.96f + frameRms * 0.04f).coerceIn(0.003f, 0.022f)
                            consecutiveActiveFrames = 0
                        } else {
                            consecutiveActiveFrames++
                        }

                        if (preRollFrames.size >= preRollCapacity) {
                            preRollFrames.removeFirst()
                        }
                        preRollFrames.addLast(frameCopy)

                        // Trigger utterance capture after 2 consecutive voiced frames (~64ms)
                        if (consecutiveActiveFrames >= 2) {
                            isCollectingSpeech = true
                            consecutiveSilentFrames = 0
                            voicedFramesInUtterance = consecutiveActiveFrames
                            maxRmsInUtterance = frameRms
                            sumRmsInUtterance = frameRms
                            speechFrames.clear()
                            for (pf in preRollFrames) {
                                speechFrames.add(pf)
                            }
                            preRollFrames.clear()
                        }
                    } else {
                        speechFrames.add(frameCopy)
                        if (frameRms > maxRmsInUtterance) {
                            maxRmsInUtterance = frameRms
                        }
                        sumRmsInUtterance += frameRms

                        if (frameRms >= sustainThreshold) {
                            voicedFramesInUtterance++
                            consecutiveSilentFrames = 0
                        } else {
                            consecutiveSilentFrames++
                        }

                        // End of utterance when speaker pauses for ~512ms (16 frames) or hits 4.2s cap
                        val endedByPause = consecutiveSilentFrames >= 16
                        val endedByMaxLen = speechFrames.size >= (maxSpeechFrames + preRollCapacity)

                        if (endedByPause || endedByMaxLen) {
                            isCollectingSpeech = false
                            consecutiveActiveFrames = 0
                            val capturedVoicedCount = voicedFramesInUtterance
                            val totalFrameCount = speechFrames.size
                            val peakRms = maxRmsInUtterance
                            val avgRms = (sumRmsInUtterance / totalFrameCount.coerceAtLeast(1)).coerceAtLeast(0.001f)
                            val modulationRatio = peakRms / avgRms
                            voicedFramesInUtterance = 0
                            maxRmsInUtterance = 0f
                            sumRmsInUtterance = 0f

                            // Calibrated wake-phrase gate for short phrases like "hey Catherine" (~0.65s-1.2s):
                            // 1) At least 6 voiced frames (~0.19s active vowels) & 20 total frames (~0.64s incl. pre-roll)
                            // 2) Peak RMS >= 0.024f (works reliably at desk distance 0.5m-1m while ignoring room hum)
                            // 3) Syllable modulation ratio >= 1.32f (still rejects flat steady fan/AC hum)
                            if (capturedVoicedCount >= 6 &&
                                totalFrameCount >= 20 &&
                                peakRms >= 0.024f &&
                                modulationRatio >= 1.32f
                            ) {
                                val totalSamples = speechFrames.sumOf { it.size }
                                val fullPcm = ShortArray(totalSamples)
                                var offset = 0
                                for (chunk in speechFrames) {
                                    System.arraycopy(chunk, 0, fullPcm, offset, chunk.size)
                                    offset += chunk.size
                                }
                                speechFrames.clear()

                                val triggered = evaluateAndVerifyWakeUtterance(
                                    pcmSamples = fullPcm,
                                    sampleRate = sampleRate,
                                    activeRecorderToReleaseOnTrigger = recorder
                                )
                                if (triggered) {
                                    // Wake word matched! Recorder was already stopped & released for clean call handoff.
                                    return@launch
                                }
                            } else {
                                speechFrames.clear()
                            }
                        }
                    }
                }
            } catch (_: CancellationException) {
                // Normal standby cancellation
            } catch (e: Exception) {
                notifyStatusOnMain(false, "⚠️ 語音待機重連中：${e.message?.take(30)}")
            } finally {
                activeWakeAudioSessionId = -1
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createWakeAudioRecord(sampleRate: Int, bufferSizeInBytes: Int): AudioRecord? {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )
        for (src in sources) {
            val rec = runCatching {
                AudioRecord(
                    src,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSizeInBytes
                )
            }.getOrNull()
            if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                return rec
            }
            runCatching { rec?.release() }
        }
        return null
    }

    /**
     * Evaluates a captured utterance for:
     * 1) Acoustic Voiceprint match (directly on the spoken PCM buffer!)
     * 2) Bilingual wake phrase transcription ("hey Luna 我需要你")
     * Returns true if wake word was triggered and Live Call is starting.
     */
    private suspend fun evaluateAndVerifyWakeUtterance(
        pcmSamples: ShortArray,
        sampleRate: Int,
        activeRecorderToReleaseOnTrigger: AudioRecord
    ): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastTriggerWallMs < 3500L) return false

        // Step 1: Extract acoustic voiceprint & verify true human vocal-cord harmonicity
        val candidateProfile = extractAcousticVoiceprint(
            samples = pcmSamples,
            length = pcmSamples.size,
            sampleRate = sampleRate,
            minVoicedFrames = 5
        ) ?: return false

        var similarityScore = 95
        val profile = enrolledProfile
        if (voiceprintEnabled && profile != null) {
            similarityScore = computeVoiceprintSimilarityScore(profile, candidateProfile)
            if (similarityScore < 65) {
                notifyStatusOnMain(
                    true,
                    "🛡️ 已略過非本人聲紋語音（吻合度 $similarityScore% < 65%）· 持續待機中"
                )
                return false
            }

            // ⚡ 100% On-Device Offline Multi-Band DTW + Voiceprint Instant Wake-Up (opt-in, requires >=2 samples)
            val dtwSim = computeDtwEnvelopeSimilarity(profile.temporalEnvelope, candidateProfile.temporalEnvelope)
            val durationSec = pcmSamples.size.toFloat() / sampleRate.coerceAtLeast(1)
            if (offlineDtwEnabled &&
                profile.sampleCount >= 2 &&
                profile.temporalEnvelope.size == 48 &&
                candidateProfile.temporalEnvelope.size == 48 &&
                similarityScore >= 84 &&
                dtwSim >= 0.84f &&
                durationSec in 0.65f..3.15f
            ) {
                lastTriggerWallMs = System.currentTimeMillis()
                isPausedForActiveCall = true
                runCatching { activeRecorderToReleaseOnTrigger.stop() }
                runCatching { activeRecorderToReleaseOnTrigger.release() }
                delay(180L)
                withContext(Dispatchers.Main) {
                    playWakeChimeAndVibrate()
                    onWakeWordDetected("$targetWakePhrase (⚡離線DTW直通)", similarityScore)
                }
                return true
            }
        }

        val apiKey = openAiApiKey.trim()
        if (apiKey.isBlank()) {
            notifyStatusOnMain(false, "⚠️ 請先在設定填入 OpenAI API Key 以辨識喚醒詞")
            return false
        }

        notifyStatusOnMain(true, "🎙️ 偵測到人聲，正在辨識是否為「$targetWakePhrase」...")
        onWhisperWakeCallTracked?.invoke()

        // Step 2: Fast bilingual transcription WITHOUT wake-phrase prompt (eliminates Whisper prompt hallucination on noise!)
        val transcript = transcribeWakeCandidateWithOpenAi(
            pcmSamples = pcmSamples,
            sampleRate = sampleRate,
            apiKey = apiKey
        ).trim()

        if (transcript.isBlank() || isKnownWhisperHallucination(transcript)) {
            notifyStatusOnMain(true, "🎧 穩定待機中：請說「$targetWakePhrase」")
            return false
        }

        if (!matchesWakePhrase(transcript, targetWakePhrase)) {
            val preview = transcript.replace("\n", " ").take(20)
            notifyStatusOnMain(
                true,
                "🎧 待機中（聽到：「$preview」未吻合「$targetWakePhrase」）"
            )
            return false
        }

        // Step 3: Matched! Immediately release the standby AudioRecord BEFORE starting Live Call
        lastTriggerWallMs = System.currentTimeMillis()
        isPausedForActiveCall = true
        runCatching { activeRecorderToReleaseOnTrigger.stop() }
        runCatching { activeRecorderToReleaseOnTrigger.release() }

        // Give Android AudioFlinger 180ms to mark the mic free before LiveSessionManager opens its AudioRecord
        delay(180L)

        withContext(Dispatchers.Main) {
            playWakeChimeAndVibrate()
            onWakeWordDetected(transcript, similarityScore)
        }
        return true
    }

    /**
     * IMPORTANT: Never pass the target wake phrase in `prompt`, because Whisper / gpt-4o-mini-transcribe
     * will hallucinate the exact `prompt` text whenever given quiet audio or ambient noise!
     */
    private fun transcribeWakeCandidateWithOpenAi(
        pcmSamples: ShortArray,
        sampleRate: Int,
        apiKey: String
    ): String {
        val wavBytes = encodePcmToWavBytes(pcmSamples, sampleRate)

        val modelsToTry = listOf("gpt-transcribe", "gpt-4o-mini-transcribe", "whisper-1")
        for (modelName in modelsToTry) {
            val text = runCatching {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file",
                        "wake.wav",
                        wavBytes.toRequestBody("audio/wav".toMediaType())
                    )
                    .addFormDataPart("model", modelName)
                    .addFormDataPart("temperature", "0")
                    .build()

                val request = Request.Builder()
                    .url("https://api.openai.com/v1/audio/transcriptions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(body)
                    .build()

                httpClient.newCall(request).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@use ""
                    JSONObject(raw).optString("text", "").trim()
                }
            }.getOrDefault("")

            if (text.isNotBlank()) {
                return text
            }
        }
        return ""
    }

    private fun isKnownWhisperHallucination(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT).trim()
        val hallucinations = listOf(
            "請不吝點讚", "訂閱", "轉發", "打賞", "明鏡與點點", "字幕由", "amara.org",
            "thank you for watching", "thanks for watching", "subtitles by", "you", "."
        )
        return hallucinations.any { lower == it || (lower.length > 6 && lower.contains(it)) }
    }

    private fun encodePcmToWavBytes(pcmSamples: ShortArray, sampleRate: Int): ByteArray {
        val byteDataSize = pcmSamples.size * 2
        val totalDataLen = byteDataSize + 36
        val byteRate = sampleRate * 2
        val out = ByteArrayOutputStream(byteDataSize + 44)

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(totalDataLen)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // PCM chunk size
        header.putShort(1) // AudioFormat = 1 (PCM)
        header.putShort(1) // Mono = 1 channel
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(2) // BlockAlign = 2 bytes
        header.putShort(16) // BitsPerSample = 16
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(byteDataSize)
        out.write(header.array())

        val pcmBuf = ByteBuffer.allocate(byteDataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcmSamples) {
            pcmBuf.putShort(s)
        }
        out.write(pcmBuf.array())
        return out.toByteArray()
    }

    /**
     * Records a 3.0-second voiceprint sample of the user saying the wake phrase (e.g., "hey Luna 我需要你")
     * using the exact same AudioSource and acoustic feature extractor as standby verification.
     */
    @SuppressLint("MissingPermission")
    suspend fun enrollVoiceprintSample(existingDataJson: String): Result<VoiceprintProfile> = withContext(Dispatchers.IO) {
        if (!hasRecordPermission()) {
            return@withContext Result.failure(IllegalStateException("請先授予麥克風權限"))
        }
        isEnrollingVoiceprint = true
        stopContinuousStandbyJob()
        delay(300L)

        try {
            val sampleRate = 16000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(3200)

            val recorder = createWakeAudioRecord(sampleRate, minBuf * 2)
                ?: return@withContext Result.failure(IllegalStateException("無法啟動麥克風錄製聲紋"))

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
                activeWakeAudioSessionId = -1
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
                val mergedEnv = if (extracted.temporalEnvelope.isNotEmpty() && prior.temporalEnvelope.size == extracted.temporalEnvelope.size) {
                    FloatArray(extracted.temporalEnvelope.size) { i ->
                        (prior.temporalEnvelope[i] * wPrior + extracted.temporalEnvelope[i] * wNew) / totalW
                    }
                } else {
                    extracted.temporalEnvelope
                }
                VoiceprintProfile(
                    sampleCount = (prior.sampleCount + 1).coerceAtMost(5),
                    meanPitchHz = (prior.meanPitchHz * wPrior + extracted.meanPitchHz * wNew) / totalW,
                    pitchStdHz = (prior.pitchStdHz * wPrior + extracted.pitchStdHz * wNew) / totalW,
                    meanZcr = (prior.meanZcr * wPrior + extracted.meanZcr * wNew) / totalW,
                    bandEnergies = mergedBands,
                    temporalEnvelope = mergedEnv
                )
            }

            enrolledProfile = merged
            Result.success(merged)
        } finally {
            isEnrollingVoiceprint = false
            if (isStandbyEnabled && !isPausedForActiveCall) {
                startContinuousStandbyJobIfNeeded()
            }
        }
    }

    private fun extractAcousticVoiceprint(
        samples: ShortArray,
        length: Int,
        sampleRate: Int,
        minVoicedFrames: Int = 8
    ): VoiceprintProfile? {
        val frameSize = 512 // 32ms at 16kHz
        if (length < frameSize * minVoicedFrames) return null

        val pitches = mutableListOf<Float>()
        val zcrs = mutableListOf<Float>()
        val bandAccum = FloatArray(8)
        var periodicVoicedFrames = 0

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
            val zeroLagEnergy = sumSq / frameSize
            val rms = sqrt(zeroLagEnergy)

            // Require clear human speech frame energy (rms > 0.014, desk-distance friendly)
            if (rms > 0.014) {
                // Estimate Fundamental Pitch F0 via Normalized Autocorrelation (85 Hz .. 350 Hz)
                val minLag = sampleRate / 350
                val maxLag = sampleRate / 85
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

                // Normalized harmonicity ratio: periodic human vocal cords have >= 0.30; random noise is < 0.20
                val harmonicity = maxCorr / zeroLagEnergy.coerceAtLeast(1e-7)
                if (harmonicity >= 0.30) {
                    periodicVoicedFrames++
                    pitches.add(sampleRate.toFloat() / bestLag)
                    zcrs.add(zeroCrossings.toFloat() / frameSize)

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
            }
            frameStart += frameSize / 2
        }

        // If the audio does not contain enough periodic human vocal-cord frames, reject as noise!
        if (periodicVoicedFrames < minVoicedFrames || pitches.size < minVoicedFrames) return null

        val meanPitch = pitches.average().toFloat()
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

        // Trim leading & trailing silence before extracting a 12-segment x 4-band spectral trajectory (48 features)
        val trimStep = 256
        var activeStart = 0
        var activeEnd = length
        while (activeStart + trimStep < length) {
            var sq = 0.0
            for (i in activeStart until activeStart + trimStep) {
                val v = samples[i].toDouble() / 32768.0
                sq += v * v
            }
            if (sqrt(sq / trimStep) > 0.022) break
            activeStart += trimStep
        }
        while (activeEnd - trimStep > activeStart) {
            var sq = 0.0
            for (i in (activeEnd - trimStep) until activeEnd) {
                val v = samples[i].toDouble() / 32768.0
                sq += v * v
            }
            if (sqrt(sq / trimStep) > 0.022) break
            activeEnd -= trimStep
        }
        if (activeEnd - activeStart < sampleRate / 3) {
            activeStart = 0
            activeEnd = length
        }

        val bins = 12
        val dtwFreqs = floatArrayOf(380f, 920f, 1850f, 3200f)
        val temporalEnv = FloatArray(bins * dtwFreqs.size)
        val activeLen = (activeEnd - activeStart).coerceAtLeast(bins)
        val segLen = (activeLen / bins).coerceAtLeast(1)
        for (b in 0 until bins) {
            val sIdx = activeStart + b * segLen
            val eIdx = minOf(activeEnd, sIdx + segLen)
            val bandVals = FloatArray(dtwFreqs.size)
            var segNormSq = 0f
            for (fIdx in dtwFreqs.indices) {
                val omega = (2.0 * PI * dtwFreqs[fIdx]) / sampleRate
                val coeff = 2.0 * cos(omega)
                var q1 = 0.0
                var q2 = 0.0
                var i = sIdx
                while (i < eIdx) {
                    val x = samples[i].toDouble() / 32768.0
                    val q0 = coeff * q1 - q2 + x
                    q2 = q1
                    q1 = q0
                    i += 2
                }
                val p = sqrt((q1 * q1 + q2 * q2 - q1 * q2 * coeff).coerceAtLeast(1e-9)).toFloat()
                bandVals[fIdx] = p
                segNormSq += p * p
            }
            val segNorm = sqrt(segNormSq.coerceAtLeast(1e-6f))
            for (fIdx in dtwFreqs.indices) {
                temporalEnv[b * dtwFreqs.size + fIdx] = bandVals[fIdx] / segNorm
            }
        }

        return VoiceprintProfile(
            sampleCount = 1,
            meanPitchHz = meanPitch,
            pitchStdHz = pitchStd,
            meanZcr = meanZcr,
            bandEnergies = normalizedBands,
            temporalEnvelope = temporalEnv
        )
    }

    private fun computeDtwEnvelopeSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != 48 || b.size != 48) return 0f
        val n = 12
        val dims = 4
        val dp = Array(n + 1) { FloatArray(n + 1) { 999f } }
        dp[0][0] = 0f
        for (i in 1..n) {
            for (j in 1..n) {
                if (abs(i - j) <= 2) {
                    var distSq = 0f
                    for (d in 0 until dims) {
                        val diff = a[(i - 1) * dims + d] - b[(j - 1) * dims + d]
                        distSq += diff * diff
                    }
                    val cost = sqrt(distSq)
                    val prev = minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
                    dp[i][j] = cost + prev
                }
            }
        }
        val avgDist = dp[n][n] / n.toFloat()
        return (1f - avgDist * 1.15f).coerceIn(0f, 1f)
    }

    private fun computeVoiceprintSimilarityScore(
        enrolled: VoiceprintProfile,
        candidate: VoiceprintProfile
    ): Int {
        val len = minOf(enrolled.bandEnergies.size, candidate.bandEnergies.size)
        if (len < 4) return 0

        // 1. Zero-Mean Log-Spectral Shape Correlation (prevents positive-vector 78% floor on flat noise!)
        val logA = FloatArray(len) { i -> ln(enrolled.bandEnergies[i].coerceAtLeast(1e-4f)) }
        val logB = FloatArray(len) { i -> ln(candidate.bandEnergies[i].coerceAtLeast(1e-4f)) }
        val meanA = logA.average().toFloat()
        val meanB = logB.average().toFloat()

        var dot = 0f
        var magA = 0f
        var magB = 0f
        for (i in 0 until len) {
            val da = logA[i] - meanA
            val db = logB[i] - meanB
            dot += da * db
            magA += da * da
            magB += db * db
        }
        val rawCorr = if (magA > 1e-5f && magB > 1e-5f) {
            (dot / (sqrt(magA) * sqrt(magB))).coerceIn(-1f, 1f)
        } else {
            0f
        }
        val spectralShapeSim = ((rawCorr + 1f) * 0.5f).coerceIn(0f, 1f)

        // 2. Strict Pitch F0 proximity score (no artificial 0.45 floor)
        val pitchDiffRatio = abs(enrolled.meanPitchHz - candidate.meanPitchHz) / enrolled.meanPitchHz.coerceAtLeast(85f)
        val pitchSim = (1f - pitchDiffRatio * 1.25f).coerceIn(0f, 1f)

        val combined = (spectralShapeSim * 0.68f + pitchSim * 0.32f) * 100f
        return combined.roundToInt().coerceIn(0, 100)
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
         * Normalizes mixed English/Chinese speech transcripts so `"hey Luna 我需要你"` (or custom wake phrases)
         * are reliably recognized across English/Traditional/Simplified transcriptions and homophones.
         */
        fun matchesWakePhrase(spokenRaw: String, configuredPhraseRaw: String): Boolean {
            val spokenNorm = normalizeForWakeComparison(spokenRaw)
            val targetNorm = normalizeForWakeComparison(configuredPhraseRaw)
            if (spokenNorm.isBlank() || targetNorm.isBlank()) return false

            if (spokenNorm.contains(targetNorm)) return true

            if (targetNorm.contains("catherine") && spokenNorm.contains("catherine")) {
                return true
            }

            val targetHasLuna = targetNorm.contains("luna")
            val targetHasNeedYou = targetNorm.contains("需要你")
            if (targetHasLuna && targetHasNeedYou) {
                val spokenHasLuna = spokenNorm.contains("luna")
                val spokenHasNeedYou = spokenNorm.contains("需要你") || spokenNorm.contains("需要")
                if (spokenHasLuna && spokenHasNeedYou) return true
            }
            return false
        }

        private fun normalizeForWakeComparison(input: String): String {
            var s = input.lowercase(Locale.ROOT)
                .replace(Regex("[\\s,，.。!！?？、~～\"'「」『』（）()\\-_]"), "")

            // Map common English/Chinese STT homophones of "Catherine" -> "catherine"
            val catherineHomophones = listOf(
                "katherine", "kathryn", "cathryn", "catharine", "katheryn", "katarina", "catherin",
                "凱瑟琳", "凱薩琳", "凱斯琳", "凱西琳", "凱特琳", "開瑟琳", "凱瑟林", "凱薩林", "凱斯林"
            )
            for (h in catherineHomophones) {
                s = s.replace(h, "catherine")
            }

            // Simplified / variant Chinese normalization for "我需要你"
            s = s.replace("需要妳", "需要你")
                .replace("須要你", "需要你")
                .replace("須要妳", "需要你")
                .replace("需要泥", "需要你")
                .replace("想要你", "需要你")

            // Map common Chinese/English STT homophones of "Hey Luna" -> "heyluna"
            val heyLunaHomophones = listOf(
                "嘿露娜", "黑露娜", "嘿路娜", "黑路娜", "嘿嚕娜", "黑嚕娜",
                "嘿盧娜", "黑盧娜", "嘿如娜", "嘿羅娜", "黑羅娜", "嗨露娜", "嗨路娜",
                "嘿露那", "黑露那", "嘿路那", "黑路那", "heylona", "heiluna", "haluna", "hiluna"
            )
            for (h in heyLunaHomophones) {
                s = s.replace(h, "heyluna")
            }

            val lunaShortHomophones = listOf(
                "露娜", "路娜", "嚕娜", "盧娜", "如娜", "羅娜", "洛娜",
                "露那", "路那", "嚕那", "盧那", "如那", "羅那",
                "lona", "luma", "runa", "rula"
            )
            for (h in lunaShortHomophones) {
                s = s.replace(h, "luna")
            }

            val heyHomophones = listOf("嘿", "黑", "嗨", "欸", "喂", "hi", "hello", "hei")
            for (h in heyHomophones) {
                if (s.startsWith(h)) {
                    s = "hey" + s.removePrefix(h)
                }
            }
            return s
        }
    }
}
