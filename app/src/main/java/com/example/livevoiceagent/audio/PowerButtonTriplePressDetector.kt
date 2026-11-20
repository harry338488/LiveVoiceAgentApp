package com.example.livevoiceagent.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock

/**
 * Detects 3 rapid power-button presses within 2.5 seconds via `ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF`
 * transitions, allowing instant zero-mic hardware wake-up of Catherine without touching the screen!
 */
class PowerButtonTriplePressDetector(
    private val context: Context,
    private val onTriplePressDetected: () -> Unit
) {
    private val pressTimestamps = ArrayDeque<Long>(4)
    private var lastTriggerMs = 0L
    private var isRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action != Intent.ACTION_SCREEN_ON && action != Intent.ACTION_SCREEN_OFF) return

            val now = SystemClock.elapsedRealtime()
            if (now - lastTriggerMs < 4000L) return

            // Ignore sub-120ms duplicate broadcasts
            val prev = pressTimestamps.lastOrNull()
            if (prev != null && now - prev < 120L) return

            pressTimestamps.addLast(now)
            while (pressTimestamps.size > 3) {
                pressTimestamps.removeFirst()
            }

            if (pressTimestamps.size == 3) {
                val span = pressTimestamps.last() - pressTimestamps.first()
                if (span <= 2500L) {
                    pressTimestamps.clear()
                    lastTriggerMs = now
                    onTriplePressDetected()
                }
            }
        }
    }

    fun start() {
        if (isRegistered) return
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            context.registerReceiver(receiver, filter)
            isRegistered = true
        }
    }

    fun stop() {
        if (!isRegistered) return
        runCatching { context.unregisterReceiver(receiver) }
        isRegistered = false
        pressTimestamps.clear()
    }
}
