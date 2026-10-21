package com.example.livevoiceagent.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import android.util.Base64
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt

class FullDuplexAudioEngine(
    private val context: Context,
    private val sampleRate: Int = 24000,
    private val onPcmBase64Captured: (String) -> Unit,
    private val onMicLevelChanged: (Float) -> Unit = {}
) {
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null

    private var scope: CoroutineScope? = null
    private var playbackThread: Thread? = null
    private val playbackQueue = LinkedBlockingQueue<ByteArray>()
    private val flushRequested = AtomicBoolean(false)

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var isMuted: Boolean = false

    @Volatile
    private var speakerphoneEnabled: Boolean = true

    @Volatile
    private var simultaneousTranslationActive: Boolean = false

    /**
     * Exact wall-clock timestamp (ms) until which the hardware AudioTrack will physically
     * continue playing AI speech through the loudspeaker (computed via playbackHeadPosition).
     */
    @Volatile
    private var speakerActiveUntilWallMs: Long = 0L

    /**
     * Smoothed normalized RMS (0.0..1.0) of the far-end AI voice currently playing from the speaker.
     * Used by the residual echo gate to scale the barge-in threshold dynamically.
     */
    @Volatile
    private var farEndRmsEnvelope: Float = 0f

    /**
     * Timestamp until which user barge-in is active (ducks speaker output & keeps mic gate open).
     */
    @Volatile
    private var bargeInHoldoverUntilMs: Long = 0L

    private fun isHeadsetConnected(audioManager: AudioManager): Boolean {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }

    private fun configureCommunicationAudioRouting(
        audioManager: AudioManager,
        useSpeakerphone: Boolean,
        headsetActive: Boolean
    ) {
        // CRITICAL: Always enable Hardware VoIP AEC (MODE_IN_COMMUNICATION) whenever no external
        // headset is connected — including when Speakerphone is ON in gpt-live-1!
        // Previously, speakerphone in gpt-live-1 used MODE_NORMAL + VOICE_RECOGNITION + USAGE_MEDIA,
        // which bypassed Android's hardware AEC reference path and caused OpenAI's speaker voice
        // to be picked up by the microphone and recognized as the user's speech.
        val useHardwareVoipAec = !headsetActive
        audioManager.mode = if (useHardwareVoipAec) {
            AudioManager.MODE_IN_COMMUNICATION
        } else {
            AudioManager.MODE_NORMAL
        }

        val targetSpeaker = useSpeakerphone && !headsetActive
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                if (!useHardwareVoipAec) {
                    audioManager.clearCommunicationDevice()
                } else {
                    val desiredType = if (targetSpeaker) {
                        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    } else {
                        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    }
                    val targetDevice = audioManager.availableCommunicationDevices
                        .firstOrNull { it.type == desiredType }
                    if (targetDevice != null) {
                        audioManager.setCommunicationDevice(targetDevice)
                    }
                }
            }
        }

        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = targetSpeaker
    }

    @SuppressLint("MissingPermission")
    fun start(
        useSpeakerphone: Boolean = true,
        isSimultaneousTranslation: Boolean = false
    ) {
        if (isRunning) return
        isRunning = true
        speakerphoneEnabled = useSpeakerphone
        simultaneousTranslationActive = isSimultaneousTranslation
        speakerActiveUntilWallMs = 0L
        farEndRmsEnvelope = 0f
        bargeInHoldoverUntilMs = 0L
        flushRequested.set(false)

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val headsetActive = isHeadsetConnected(audioManager)
        val useHardwareVoipAec = !headsetActive

        configureCommunicationAudioRouting(audioManager, useSpeakerphone, headsetActive)

        val minRecBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(3840)

        val audioSource = if (useHardwareVoipAec) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }

        val recorder = AudioRecord(
            audioSource,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minRecBuf * 4
        )
        audioRecord = recorder

        val sessionId = recorder.audioSessionId
        runCatching {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            }
            if (AutomaticGainControl.isAvailable()) {
                // Disable aggressive upward AGC on speakerphone so residual room echo is not amplified
                gainControl = AutomaticGainControl.create(sessionId)?.apply { enabled = false }
            }
        }

        val minPlayBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4800)

        // Allocate a generous 2-second internal ring buffer (96,000 bytes at 24kHz 16-bit mono)
        val trackBufferBytes = maxOf(minPlayBuf * 4, sampleRate * 2 * 2)

        // IMPORTANT: Use PERFORMANCE_MODE_NONE instead of PERFORMANCE_MODE_LOW_LATENCY!
        // Streamed 24,000 Hz WebSocket audio on a 48,000 Hz hardware speaker causes FastMixer
        // resampling glitches and micro-underrun static/crackling when LOW_LATENCY is forced.
        // PERFORMANCE_MODE_NONE uses Android's high-precision sinc resampler and stable mixer path.
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(
                        if (useHardwareVoipAec) AudioAttributes.USAGE_VOICE_COMMUNICATION
                        else AudioAttributes.USAGE_MEDIA
                    )
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(trackBufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
            .build()

        audioTrack = track
        recorder.startRecording()
        track.play()

        val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = engineScope

        // Microphone Capture Loop (40ms chunks = 960 samples = 1920 bytes at 24kHz PCM16)
        engineScope.launch {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val readBuffer = ByteArray(1920)
            val silenceBuffer = ByteArray(1920)
            val silenceBase64 = Base64.encodeToString(silenceBuffer, Base64.NO_WRAP)
            var pendingByte: Byte? = null

            val preRollFrames = ArrayDeque<String>(3)
            var consecutiveBargeInFrames = 0

            while (isActive && isRunning) {
                val readCount = recorder.read(readBuffer, 0, readBuffer.size)
                if (readCount > 0) {
                    if (isMuted) {
                        onMicLevelChanged(0f)
                        consecutiveBargeInFrames = 0
                        preRollFrames.clear()
                        continue
                    }

                    val combined: ByteArray
                    if (pendingByte != null) {
                        combined = ByteArray(readCount + 1)
                        combined[0] = pendingByte!!
                        System.arraycopy(readBuffer, 0, combined, 1, readCount)
                        pendingByte = null
                    } else {
                        combined = readBuffer.copyOfRange(0, readCount)
                    }

                    val evenLength = combined.size - (combined.size % 2)
                    if (combined.size % 2 != 0) {
                        pendingByte = combined[combined.size - 1]
                    }

                    if (evenLength > 0) {
                        val alignedBytes = if (evenLength == combined.size) {
                            combined
                        } else {
                            combined.copyOfRange(0, evenLength)
                        }

                        val rawRms = calculateRawRms(alignedBytes)
                        val now = System.currentTimeMillis()
                        val headsetNow = isHeadsetConnected(audioManager)

                        // Smart Speakerphone Residual Echo Gate for conversational models (gpt-live-1 / Realtime):
                        // While the loudspeaker is physically playing AI speech (plus a 360ms room reverb tail),
                        // block low-level residual speaker echo by sending digital silence unless the user
                        // genuinely speaks into the mic to interrupt (barge-in).
                        val echoGateEnabled = !simultaneousTranslationActive && speakerphoneEnabled && !headsetNow
                        val inSpeakerEchoWindow = now <= (speakerActiveUntilWallMs + 360L)

                        if (echoGateEnabled && inSpeakerEchoWindow) {
                            val b64 = Base64.encodeToString(alignedBytes, Base64.NO_WRAP)

                            if (now < bargeInHoldoverUntilMs) {
                                // User is actively barging in: keep gate open and extend holdover if still speaking
                                if (rawRms >= 0.026f) {
                                    bargeInHoldoverUntilMs = now + 800L
                                }
                                val uiLevel = (rawRms * 3.5f).coerceIn(0f, 1f)
                                onMicLevelChanged(uiLevel)
                                onPcmBase64Captured(b64)
                            } else {
                                // Require near-field speech energy well above residual speaker echo
                                val farEnd = farEndRmsEnvelope
                                val bargeInThreshold = maxOf(0.056f, farEnd * 0.48f).coerceAtMost(0.18f)

                                if (rawRms >= bargeInThreshold) {
                                    if (preRollFrames.size >= 2) {
                                        preRollFrames.pollFirst()
                                    }
                                    preRollFrames.addLast(b64)
                                    consecutiveBargeInFrames += if (rawRms >= bargeInThreshold * 1.55f) 2 else 1

                                    if (consecutiveBargeInFrames >= 3) {
                                        // Genuine user barge-in confirmed! Flush pre-roll frames so first syllable is kept
                                        bargeInHoldoverUntilMs = now + 900L
                                        consecutiveBargeInFrames = 0
                                        while (preRollFrames.isNotEmpty()) {
                                            onPcmBase64Captured(preRollFrames.pollFirst()!!)
                                        }
                                        val uiLevel = (rawRms * 3.5f).coerceIn(0f, 1f)
                                        onMicLevelChanged(uiLevel)
                                    } else {
                                        // Hold back until confirmed so brief speaker spikes don't leak
                                        onMicLevelChanged(0f)
                                        onPcmBase64Captured(
                                            if (alignedBytes.size == silenceBuffer.size) silenceBase64
                                            else Base64.encodeToString(ByteArray(alignedBytes.size), Base64.NO_WRAP)
                                        )
                                    }
                                } else {
                                    // Pure residual speaker echo or room reflection -> send digital silence
                                    consecutiveBargeInFrames = 0
                                    preRollFrames.clear()
                                    onMicLevelChanged(0f)
                                    onPcmBase64Captured(
                                        if (alignedBytes.size == silenceBuffer.size) silenceBase64
                                        else Base64.encodeToString(ByteArray(alignedBytes.size), Base64.NO_WRAP)
                                    )
                                }
                            }
                        } else {
                            // AI speaker is silent (or user is on headphones / simultaneous translation):
                            // pass 100% of microphone audio untouched
                            consecutiveBargeInFrames = 0
                            preRollFrames.clear()
                            val uiLevel = (rawRms * 3.5f).coerceIn(0f, 1f)
                            onMicLevelChanged(uiLevel)
                            val b64 = Base64.encodeToString(alignedBytes, Base64.NO_WRAP)
                            onPcmBase64Captured(b64)
                        }
                    }
                }
            }
        }

        // Dedicated High-Priority Audio Playback Thread with Jitter Cushion,
        // Speakerphone High-Pass / Soft-Knee Limiter DSP, and Anti-Click Fade Envelopes
        val playThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            // 200ms pre-buffer (9600 bytes) for gpt-live-1 eliminates mid-sentence Wi-Fi/4G underrun static;
            // 60ms (2880 bytes) for simultaneous translation keeps latency minimal.
            val preBufferThresholdBytes = if (isSimultaneousTranslation) 2880 else 9600
            val minBytesForTimeoutRelease = if (isSimultaneousTranslation) 1920 else 4800
            val maxPreBufferWaitMs = if (isSimultaneousTranslation) 35L else 160L
            val reArmSilenceThresholdMs = if (isSimultaneousTranslation) 1800L else 600L

            val jitterBuffer = ByteArrayOutputStream(24000)
            var isPreBuffering = true
            var needsFadeIn = true
            var hasWrittenTailFade = false
            var firstChunkArrivalMs = 0L
            var lastWriteTimeMs = 0L
            var leftoverByte: Byte? = null
            var totalFramesWritten = 0L

            // DSP filter states for clean, static-free speakerphone output
            var dcFilterPrevX = 0f
            var dcFilterPrevY = 0f
            var lpPrevSample = 0f
            var lastOutputSample = 0f

            fun updateHardwarePlaybackWindow() {
                val headFrames = runCatching {
                    track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                }.getOrDefault(totalFramesWritten)
                val unplayedFrames = (totalFramesWritten - headFrames).coerceAtLeast(0L)
                val unplayedMs = (unplayedFrames * 1000L) / sampleRate
                val nowMs = System.currentTimeMillis()
                if (unplayedFrames > 0L) {
                    speakerActiveUntilWallMs = maxOf(speakerActiveUntilWallMs, nowMs + unplayedMs)
                } else {
                    farEndRmsEnvelope *= 0.7f
                }
            }

            fun writeConditionedPcm(rawBytes: ByteArray, applyFadeIn: Boolean) {
                if (rawBytes.isEmpty()) return
                val combined: ByteArray = if (leftoverByte != null) {
                    ByteArray(rawBytes.size + 1).also {
                        it[0] = leftoverByte!!
                        System.arraycopy(rawBytes, 0, it, 1, rawBytes.size)
                    }
                } else {
                    rawBytes
                }

                val evenLength = combined.size - (combined.size % 2)
                leftoverByte = if (combined.size % 2 != 0) combined[combined.size - 1] else null
                if (evenLength <= 0) return

                val sampleCount = evenLength / 2
                val outBytes = ByteArray(evenLength)
                val onSpeaker = speakerphoneEnabled && !isHeadsetConnected(audioManager)
                val userBargingIn = System.currentTimeMillis() < bargeInHoldoverUntilMs

                // Headroom gain prevents phone loudspeaker DRC / amplifier clipping distortion
                val targetGain = when {
                    userBargingIn && onSpeaker -> 0.35f
                    onSpeaker -> 0.84f
                    else -> 0.95f
                }

                val fadeSamples = minOf(144, sampleCount) // 6ms smooth de-click ramp at 24kHz
                val startSampleVal = lastOutputSample
                var sumSquares = 0.0

                for (i in 0 until sampleCount) {
                    val low = combined[i * 2].toInt() and 0xFF
                    val high = combined[i * 2 + 1].toInt()
                    val rawPcm = ((high shl 8) or low).toShort().toFloat()

                    // 1. 85Hz High-Pass Filter removes DC offset & sub-bass speaker cone rattle
                    val hp = 0.978f * (dcFilterPrevY + rawPcm - dcFilterPrevX)
                    dcFilterPrevX = rawPcm
                    dcFilterPrevY = hp

                    // 2. Gentle HF de-harshness filter on speakerphone tames quantization fizz
                    val filtered = if (onSpeaker) {
                        val s = 0.84f * hp + 0.16f * lpPrevSample
                        lpPrevSample = s
                        s
                    } else {
                        hp
                    }

                    // 3. Apply headroom gain & soft-knee saturation limiter (no hard clipping at ±32767)
                    var scaled = filtered * targetGain
                    val absVal = abs(scaled)
                    if (absVal > 24000f) {
                        val sign = if (scaled >= 0f) 1f else -1f
                        val excess = (absVal - 24000f) / 8768f
                        val compressed = 24000f + 6800f * (excess / (1f + excess))
                        scaled = sign * compressed
                    }

                    // 4. Smooth 6ms fade-in ramp when starting or recovering from underrun
                    if (applyFadeIn && i < fadeSamples) {
                        val alpha = (i + 1).toFloat() / fadeSamples.toFloat()
                        scaled = startSampleVal * (1f - alpha) + scaled * alpha
                    }

                    val clampedInt = scaled.toInt().coerceIn(-32760, 32760)
                    outBytes[i * 2] = (clampedInt and 0xFF).toByte()
                    outBytes[i * 2 + 1] = ((clampedInt shr 8) and 0xFF).toByte()

                    lastOutputSample = clampedInt.toFloat()
                    val norm = clampedInt / 32768.0
                    sumSquares += norm * norm
                }

                val chunkRms = sqrt(sumSquares / sampleCount).toFloat()
                farEndRmsEnvelope = maxOf(chunkRms, farEndRmsEnvelope * 0.82f + chunkRms * 0.18f)

                val writtenBytes = track.write(outBytes, 0, evenLength, AudioTrack.WRITE_BLOCKING)
                if (writtenBytes > 0) {
                    totalFramesWritten += (writtenBytes / 2).toLong()
                }
                updateHardwarePlaybackWindow()
            }

            fun writeSmoothTailToZero() {
                if (abs(lastOutputSample) < 80f) {
                    lastOutputSample = 0f
                    return
                }
                val tailSamples = 144 // 6ms smooth ramp to zero prevents underrun pop
                val tailBytes = ByteArray(tailSamples * 2)
                val startVal = lastOutputSample
                for (i in 0 until tailSamples) {
                    val remain = 1f - ((i + 1).toFloat() / tailSamples.toFloat())
                    // Cosine-like smooth decay
                    val v = (startVal * remain * remain).toInt().coerceIn(-32760, 32760)
                    tailBytes[i * 2] = (v and 0xFF).toByte()
                    tailBytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                lastOutputSample = 0f
                val writtenBytes = track.write(tailBytes, 0, tailBytes.size, AudioTrack.WRITE_BLOCKING)
                if (writtenBytes > 0) {
                    totalFramesWritten += (writtenBytes / 2).toLong()
                }
                updateHardwarePlaybackWindow()
            }

            while (isRunning && !Thread.currentThread().isInterrupted) {
                try {
                    if (flushRequested.getAndSet(false)) {
                        playbackQueue.clear()
                        jitterBuffer.reset()
                        leftoverByte = null
                        isPreBuffering = true
                        needsFadeIn = true
                        hasWrittenTailFade = false
                        lastOutputSample = 0f
                        farEndRmsEnvelope = 0f
                        speakerActiveUntilWallMs = 0L
                        runCatching {
                            track.pause()
                            track.flush()
                            totalFramesWritten = 0L
                            track.play()
                        }
                    }

                    val chunk = playbackQueue.poll(10, TimeUnit.MILLISECONDS)
                    val now = System.currentTimeMillis()
                    updateHardwarePlaybackWindow()

                    if (chunk != null && chunk.isNotEmpty()) {
                        if (isPreBuffering) {
                            if (jitterBuffer.size() == 0) {
                                firstChunkArrivalMs = now
                            }
                            jitterBuffer.write(chunk)
                            while (jitterBuffer.size() < preBufferThresholdBytes) {
                                val next = playbackQueue.poll() ?: break
                                jitterBuffer.write(next)
                            }
                            val waitedMs = now - firstChunkArrivalMs
                            if (jitterBuffer.size() >= preBufferThresholdBytes ||
                                (waitedMs >= maxPreBufferWaitMs && jitterBuffer.size() >= minBytesForTimeoutRelease) ||
                                waitedMs >= maxPreBufferWaitMs * 2
                            ) {
                                val burst = jitterBuffer.toByteArray()
                                jitterBuffer.reset()
                                writeConditionedPcm(burst, applyFadeIn = needsFadeIn)
                                isPreBuffering = false
                                needsFadeIn = false
                                hasWrittenTailFade = false
                                lastWriteTimeMs = System.currentTimeMillis()
                            }
                        } else {
                            jitterBuffer.write(chunk)
                            while (jitterBuffer.size() < 4800) {
                                val next = playbackQueue.poll() ?: break
                                jitterBuffer.write(next)
                            }
                            val merged = jitterBuffer.toByteArray()
                            jitterBuffer.reset()
                            writeConditionedPcm(merged, applyFadeIn = needsFadeIn)
                            needsFadeIn = false
                            hasWrittenTailFade = false
                            lastWriteTimeMs = System.currentTimeMillis()
                        }
                    } else {
                        // Queue was empty on this 10ms poll
                        val headFrames = runCatching {
                            track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                        }.getOrDefault(totalFramesWritten)
                        val unplayedFrames = (totalFramesWritten - headFrames).coerceAtLeast(0L)

                        if (isPreBuffering && jitterBuffer.size() > 0 &&
                            (now - firstChunkArrivalMs) >= maxPreBufferWaitMs
                        ) {
                            val burst = jitterBuffer.toByteArray()
                            jitterBuffer.reset()
                            writeConditionedPcm(burst, applyFadeIn = needsFadeIn)
                            isPreBuffering = false
                            needsFadeIn = false
                            hasWrittenTailFade = false
                            lastWriteTimeMs = System.currentTimeMillis()
                        } else if (!isPreBuffering) {
                            // If hardware buffer is about to run dry (< 15ms left) and no new chunk is queued,
                            // smoothly ramp the signal to zero to prevent an underrun pop/crackle, then re-arm pre-buffer!
                            if (unplayedFrames <= 360L && !hasWrittenTailFade) {
                                writeSmoothTailToZero()
                                hasWrittenTailFade = true
                                isPreBuffering = true
                                needsFadeIn = true
                            } else if ((now - lastWriteTimeMs) > reArmSilenceThresholdMs && unplayedFrames == 0L) {
                                isPreBuffering = true
                                needsFadeIn = true
                            }
                        }
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {
                }
            }
        }, "LiveVoiceAudioTrackThread")
        playbackThread = playThread
        playThread.start()
    }

    fun enqueueOutputAudioBase64(base64Pcm: String) {
        if (!isRunning || base64Pcm.isEmpty()) return
        runCatching {
            val bytes = Base64.decode(base64Pcm, Base64.DEFAULT)
            if (bytes.isNotEmpty()) {
                playbackQueue.offer(bytes)
            }
        }
    }

    fun clearPlaybackBuffer() {
        playbackQueue.clear()
        flushRequested.set(true)
    }

    fun setSpeakerphone(enabled: Boolean) {
        speakerphoneEnabled = enabled
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val headsetActive = isHeadsetConnected(audioManager)
        configureCommunicationAudioRouting(audioManager, enabled, headsetActive)
    }

    fun stop() {
        isRunning = false
        speakerActiveUntilWallMs = 0L
        farEndRmsEnvelope = 0f
        bargeInHoldoverUntilMs = 0L
        playbackQueue.clear()
        scope?.cancel()
        scope = null

        runCatching {
            playbackThread?.interrupt()
        }
        playbackThread = null

        runCatching {
            echoCanceler?.release()
            noiseSuppressor?.release()
            gainControl?.release()
        }
        echoCanceler = null
        noiseSuppressor = null
        gainControl = null

        runCatching {
            audioRecord?.stop()
            audioRecord?.release()
        }
        audioRecord = null

        runCatching {
            audioTrack?.stop()
            audioTrack?.release()
        }
        audioTrack = null

        runCatching {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { audioManager.clearCommunicationDevice() }
            }
            audioManager.mode = AudioManager.MODE_NORMAL
        }
        onMicLevelChanged(0f)
    }

    private fun calculateRawRms(pcm16Bytes: ByteArray): Float {
        if (pcm16Bytes.size < 2) return 0f
        var sumSquares = 0.0
        val sampleCount = pcm16Bytes.size / 2
        for (i in 0 until sampleCount) {
            val low = pcm16Bytes[i * 2].toInt() and 0xFF
            val high = pcm16Bytes[i * 2 + 1].toInt()
            val sample = ((high shl 8) or low).toShort().toInt()
            val normalized = sample / 32768.0
            sumSquares += normalized * normalized
        }
        return sqrt(sumSquares / sampleCount).toFloat()
    }
}
