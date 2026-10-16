package com.example.livevoiceagent.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import kotlinx.coroutines.*
import java.util.concurrent.LinkedBlockingQueue
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
    private val playbackQueue = LinkedBlockingQueue<ByteArray>()

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var isMuted: Boolean = false

    @SuppressLint("MissingPermission")
    fun start(useSpeakerphone: Boolean = true) {
        if (isRunning) return
        isRunning = true

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = useSpeakerphone

        val minRecBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4800)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minRecBuf * 2
        )
        audioRecord = recorder

        // Attach hardware AEC / NS / AGC for clean full-duplex barge-in
        val sessionId = recorder.audioSessionId
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
        }
        if (AutomaticGainControl.isAvailable()) {
            gainControl = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
        }

        val minPlayBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4800)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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
            .setBufferSizeInBytes(minPlayBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack = track
        recorder.startRecording()
        track.play()

        val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = engineScope

        // Capture loop (100ms chunks = 2400 samples = 4800 bytes at 24kHz PCM16)
        engineScope.launch {
            val readBuffer = ByteArray(4800)
            var pendingByte: Byte? = null

            while (isActive && isRunning) {
                val readCount = recorder.read(readBuffer, 0, readBuffer.size)
                if (readCount > 0) {
                    if (isMuted) {
                        onMicLevelChanged(0f)
                        continue
                    }

                    // Ensure 2-byte alignment for 16-bit PCM samples as required by OpenAI Live API
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
                        onMicLevelChanged(calculateRmsLevel(alignedBytes))
                        val b64 = Base64.encodeToString(alignedBytes, Base64.NO_WRAP)
                        onPcmBase64Captured(b64)
                    }
                }
            }
        }

        // Playback loop
        engineScope.launch {
            while (isActive && isRunning) {
                val chunk = playbackQueue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (chunk != null && chunk.isNotEmpty()) {
                    track.write(chunk, 0, chunk.size)
                }
            }
        }
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
        runCatching {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
        }
    }

    fun setSpeakerphone(enabled: Boolean) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = enabled
    }

    fun stop() {
        isRunning = false
        playbackQueue.clear()
        scope?.cancel()
        scope = null

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
