package com.example.livevoiceagent.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.livevoiceagent.MainActivity

class WakeWordForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Partial CPU wake lock (screen stays OFF) so the on-device wake-word loop keeps running
     * after the screen sleeps / phone enters Doze in the pocket or on the desk.
     */
    private var cpuWakeLock: android.os.PowerManager.WakeLock? = null

    private fun acquireCpuWakeLock() {
        if (cpuWakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            cpuWakeLock = pm.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "LiveVoiceAgent:WakeWordStandby"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    override fun onDestroy() {
        runCatching { if (cpuWakeLock?.isHeld == true) cpuWakeLock?.release() }
        cpuWakeLock = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        acquireCpuWakeLock()
        val mode = intent?.getStringExtra("service_mode") ?: "wake_word"
        val wakePhrase = intent?.getStringExtra("wake_phrase")?.takeIf { it.isNotBlank() } ?: "hey Catherine"
        val notification = buildNotification(mode, wakePhrase)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
        return START_STICKY
    }

    private fun buildNotification(mode: String, wakePhrase: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Catherine 語音與錄音服務",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "維持背景語音喚醒或長時段會議錄音不斷線"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, openAppIntent, pendingFlags)

        val isMeeting = mode == "meeting_recording"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(
                if (isMeeting) "🔴 Catherine 會議錄音中（防中斷保護）"
                else "🎧 Catherine 語音待命中：「$wakePhrase」"
            )
            .setContentText(
                if (isMeeting) "純本機安全錄音（0 API 費用）· 鎖屏或切換 App 皆不中斷"
                else "本地端免費聽音 · 說出喚醒詞即可免動手對話"
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "wake_word_standby_channel"
        private const val NOTIFICATION_ID = 8801
    }
}
