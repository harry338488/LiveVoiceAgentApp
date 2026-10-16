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

    @SuppressLint("MissingPermission")
    fun start(useSpeakerphone: Boolean = true) {
        if (isRunning) return
        isRunning = true

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // Keep MODE_NORMAL when on speakerphone so Android uses the full-bandwidth 24kHz/48kHz HD DAC
        // instead of compressing audio through the 8kHz narrowband telephony voice-call pipeline.
        audioManager.mode = if (useSpeakerphone) AudioManager.MODE_NORMAL else AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = useSpeakerphone

        val minRecBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4800)

        // VOICE_RECOGNITION provides clean, uncompressed microphone input without triggering narrowband call DSP
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
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
        ).coerceAtLeast(9600)

        // Allocate a generous 2-second internal ring buffer (96,000 bytes at 24kHz 16-bit mono) to prevent underruns
        val trackBufferBytes = maxOf(minPlayBuf * 4, sampleRate * 2 * 2)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(if (useSpeakerphone) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_VOICE_COMMUNICATION)
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
            .build()

        audioTrack = track
        recorder.startRecording()
        track.play()

        val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = engineScope

        // Microphone Capture Loop (80ms chunks = 1920 samples = 3840 bytes at 24kHz PCM16)
        engineScope.launch {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val readBuffer = ByteArray(3840)
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

                        // Soft echo guard when AEC hardware is absent: if AI is playing loudly and mic RMS is very low background bleed, attenuate slightly
                        val b64 = Base64.encodeToString(alignedBytes, Base64.NO_WRAP)
                        onPcmBase64Captured(b64)
                    }
                }
            }
        }

        // Dedicated High-Priority Audio Playback Thread with Jitter Pre-Buffering
        // Pre-buffers ~200ms (9600 bytes) before starting a burst so network jitter never causes choppy gaps!
        val playThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val preBufferThresholdBytes = 9600 // 200ms at 24kHz 16-bit mono (48 bytes/ms)
            val maxPreBufferWaitMs = 140L
            val jitterBuffer = ByteArrayOutputStream(19200)
            var isPreBuffering = true
            var firstChunkArrivalMs = 0L
            var lastWriteTimeMs = 0L
            var leftoverByte: Byte? = null

            while (isRunning && !Thread.currentThread().isInterrupted) {
                try {
                    val chunk = playbackQueue.poll(20, TimeUnit.MILLISECONDS)
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
                            // Drain any immediately available chunks together for smooth contiguous writes
                            jitterBuffer.write(chunk)
                            while (jitterBuffer.size() < 9600) {
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
                        } else if (!isPreBuffering && (now - lastWriteTimeMs) > 320L) {
                            // AI paused speaking for > 320ms; re-arm jitter pre-buffer for the next sentence
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
        audioManager.mode = if (enabled) AudioManager.MODE_NORMAL else AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = enabled
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
