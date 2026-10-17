package com.example.livevoiceagent.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.util.Base64
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
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

    private var scope: CoroutineScope? = null
    private var playbackThread: Thread? = null
    private val playbackQueue = LinkedBlockingQueue<ByteArray>()

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var isMuted: Boolean = false

    @Volatile
    private var isAiCurrentlyPlaying: Boolean = false

    @Volatile
    private var simultaneousTranslationActive: Boolean = false

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

    @SuppressLint("MissingPermission")
    fun start(
        useSpeakerphone: Boolean = true,
        isSimultaneousTranslation: Boolean = false
    ) {
        if (isRunning) return
        isRunning = true
        simultaneousTranslationActive = isSimultaneousTranslation

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val headsetActive = isHeadsetConnected(audioManager)

        // CRITICAL FOR SIMULTANEOUS INTERPRETATION (gpt-realtime-translate):
        // When translating simultaneously on speakerphone (without headphones), if the phone's loudspeaker
        // plays the translated target language into the room without Hardware AEC reference, the microphone
        // records the phone's own speaker output! When gpt-realtime-translate hears its own target-language
        // output or overlapping voices, it pauses/stalls translation until the speaker stops playing.
        // Using MODE_IN_COMMUNICATION + VOICE_COMMUNICATION when not on headphones binds Android's hardware DSP
        // Acoustic Echo Canceler (AEC) to subtract the AudioTrack playback from the microphone input in real time.
        val useHardwareVoipAec = !headsetActive && (isSimultaneousTranslation || !useSpeakerphone)

        audioManager.mode = if (useHardwareVoipAec) {
            AudioManager.MODE_IN_COMMUNICATION
        } else {
            AudioManager.MODE_NORMAL
        }
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = useSpeakerphone && !headsetActive

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
        }

        val minPlayBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4800)

        // Allocate a generous 2-second internal ring buffer (96,000 bytes at 24kHz 16-bit mono) to prevent underruns
        val trackBufferBytes = maxOf(minPlayBuf * 4, sampleRate * 2 * 2)

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
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        audioTrack = track
        recorder.startRecording()
        track.play()

        val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = engineScope

        // Microphone Capture Loop (40ms ultra-low-latency chunks = 960 samples = 1920 bytes at 24kHz PCM16)
        engineScope.launch {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val readBuffer = ByteArray(1920)
            var pendingByte: Byte? = null

            while (isActive && isRunning) {
                val readCount = recorder.read(readBuffer, 0, readBuffer.size)
                if (readCount > 0) {
                    if (isMuted) {
                        onMicLevelChanged(0f)
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
                        val alignedBytes = if (evenLength == combined.size) combined else combined.copyOfRange(0, evenLength)
                        val rms = calculateRmsLevel(alignedBytes)
                        onMicLevelChanged(rms)

                        val b64 = Base64.encodeToString(alignedBytes, Base64.NO_WRAP)
                        onPcmBase64Captured(b64)
                    }
                }
            }
        }

        // Dedicated High-Priority Audio Playback Thread
        // Note: gpt-realtime-translate emits 200ms PCM16 chunks at 1x real-time pace as the user speaks.
        // We must NOT stall chunks in a secondary buffer or re-arm pre-buffering on brief 300ms speech pauses!
        val playThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val preBufferThresholdBytes = if (isSimultaneousTranslation) 1920 else 4800 // 40ms for translation, 100ms for agent
            val maxPreBufferWaitMs = if (isSimultaneousTranslation) 20L else 80L
            val reArmSilenceThresholdMs = if (isSimultaneousTranslation) 2200L else 800L
            val jitterBuffer = ByteArrayOutputStream(19200)
            var isPreBuffering = true
            var firstChunkArrivalMs = 0L
            var lastWriteTimeMs = 0L
            var leftoverByte: Byte? = null

            while (isRunning && !Thread.currentThread().isInterrupted) {
                try {
                    val chunk = playbackQueue.poll(10, TimeUnit.MILLISECONDS)
                    val now = System.currentTimeMillis()

                    if (chunk != null && chunk.isNotEmpty()) {
                        if (isPreBuffering) {
                            if (jitterBuffer.size() == 0) {
                                firstChunkArrivalMs = now
                            }
                            jitterBuffer.write(chunk)
                            val waitedMs = now - firstChunkArrivalMs
                            if (jitterBuffer.size() >= preBufferThresholdBytes || waitedMs >= maxPreBufferWaitMs) {
                                val burst = jitterBuffer.toByteArray()
                                jitterBuffer.reset()
                                leftoverByte = writeAlignedPcm(track, burst, leftoverByte)
                                isPreBuffering = false
                                isAiCurrentlyPlaying = true
                                lastWriteTimeMs = System.currentTimeMillis()
                            }
                        } else {
                            // Write immediately to AudioTrack's native ring buffer without holding chunks back
                            jitterBuffer.write(chunk)
                            while (jitterBuffer.size() < 4800) {
                                val next = playbackQueue.poll() ?: break
                                jitterBuffer.write(next)
                            }
                            val merged = jitterBuffer.toByteArray()
                            jitterBuffer.reset()
                            leftoverByte = writeAlignedPcm(track, merged, leftoverByte)
                            isAiCurrentlyPlaying = true
                            lastWriteTimeMs = System.currentTimeMillis()
                        }
                    } else {
                        // Queue was empty on this poll
                        if (isPreBuffering && jitterBuffer.size() > 0 && (now - firstChunkArrivalMs) >= maxPreBufferWaitMs) {
                            val burst = jitterBuffer.toByteArray()
                            jitterBuffer.reset()
                            leftoverByte = writeAlignedPcm(track, burst, leftoverByte)
                            isPreBuffering = false
                            isAiCurrentlyPlaying = true
                            lastWriteTimeMs = System.currentTimeMillis()
                        } else if (!isPreBuffering && (now - lastWriteTimeMs) > reArmSilenceThresholdMs) {
                            isPreBuffering = true
                            isAiCurrentlyPlaying = false
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

    private fun writeAlignedPcm(track: AudioTrack, rawBytes: ByteArray, previousLeftover: Byte?): Byte? {
        if (rawBytes.isEmpty()) return previousLeftover
        val combined: ByteArray = if (previousLeftover != null) {
            ByteArray(rawBytes.size + 1).also {
                it[0] = previousLeftover
                System.arraycopy(rawBytes, 0, it, 1, rawBytes.size)
            }
        } else {
            rawBytes
        }

        val evenLength = combined.size - (combined.size % 2)
        val newLeftover = if (combined.size % 2 != 0) combined[combined.size - 1] else null
        if (evenLength > 0) {
            track.write(combined, 0, evenLength, AudioTrack.WRITE_BLOCKING)
        }
        return newLeftover
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
        isAiCurrentlyPlaying = false
        runCatching {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
        }
    }

    fun setSpeakerphone(enabled: Boolean) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val headsetActive = isHeadsetConnected(audioManager)
        val useHardwareVoipAec = !headsetActive && (simultaneousTranslationActive || !enabled)
        audioManager.mode = if (useHardwareVoipAec) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = enabled && !headsetActive
    }

    fun stop() {
        isRunning = false
        isAiCurrentlyPlaying = false
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
        }
        echoCanceler = null
        noiseSuppressor = null

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
            audioManager.mode = AudioManager.MODE_NORMAL
        }
        onMicLevelChanged(0f)
    }

    private fun calculateRmsLevel(pcm16Bytes: ByteArray): Float {
        if (pcm16Bytes.size < 2) return 0f
        var sumSquares = 0.0
        val sampleCount = pcm16Bytes.size / 2
        for (i in 0 until sampleCount) {
            val low = pcm16Bytes[i * 2].toInt() and 0xFF
            val high = pcm16Bytes[i * 2 + 1].toInt()
            val sample = (high shl 8) or low
            val normalized = sample / 32768.0
            sumSquares += normalized * normalized
        }
        val rms = sqrt(sumSquares / sampleCount).toFloat()
        return (rms * 3.5f).coerceIn(0f, 1f)
    }
}
