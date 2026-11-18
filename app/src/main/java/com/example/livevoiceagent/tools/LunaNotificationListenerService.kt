package com.example.livevoiceagent.tools

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

data class CapturedNotificationItem(
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val timestampMs: Long
)

/**
 * Captures recent user notifications (LINE, SMS, Gmail, Messenger, etc.) in memory so Luna can
 * read out recent messages hands-free when the user asks "剛剛有誰傳訊息給我？" or "幫我唸最新通知".
 */
class LunaNotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val item = sbn ?: return
        if (item.isOngoing) return
        val pkg = item.packageName ?: return
        if (pkg == packageName || pkg == "android" || pkg == "com.android.systemui") return

        val extras = item.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()

        if (title.isBlank() && text.isBlank()) return

        val appLabel = runCatching {
            val pm = applicationContext.packageManager
            val ai = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(ai).toString()
        }.getOrDefault(pkg.substringAfterLast('.'))

        val entry = CapturedNotificationItem(
            packageName = pkg,
            appLabel = appLabel,
            title = title,
            text = text,
            timestampMs = item.postTime.takeIf { it > 0L } ?: System.currentTimeMillis()
        )

        // Deduplicate identical consecutive notifications
        recentNotifications.removeAll {
            it.packageName == entry.packageName && it.title == entry.title && it.text == entry.text
        }
        recentNotifications.add(0, entry)
        while (recentNotifications.size > 25) {
            recentNotifications.removeAt(recentNotifications.lastIndex)
        }
    }

    companion object {
        private val recentNotifications = CopyOnWriteArrayList<CapturedNotificationItem>()

        fun isNotificationAccessEnabled(context: Context): Boolean {
            return runCatching {
                val enabledListeners = Settings.Secure.getString(
                    context.contentResolver,
                    "enabled_notification_listeners"
                ).orEmpty()
                enabledListeners.contains(context.packageName)
            }.getOrDefault(false)
        }

        fun getRecentNotificationsSummary(limit: Int = 10): List<CapturedNotificationItem> {
            return recentNotifications.take(limit.coerceIn(1, 25))
        }

        fun formatRecentNotificationsForLlm(context: Context, appFilter: String = ""): String {
            if (!isNotificationAccessEnabled(context)) {
                return "尚未開啟「通知讀取權限 (Notification Access)」。請提醒使用者可在「語音喚醒與聲紋鎖」設定視窗中點擊「🔔 一鍵授權通知讀取」，即可讓 Luna 幫您朗讀 LINE 與簡訊通知。"
            }
            val filterNorm = appFilter.trim().lowercase(Locale.ROOT)
            val filtered = recentNotifications.filter { item ->
                if (filterNorm.isBlank()) true
                else item.appLabel.lowercase(Locale.ROOT).contains(filterNorm) ||
                        item.packageName.lowercase(Locale.ROOT).contains(filterNorm) ||
                        item.title.lowercase(Locale.ROOT).contains(filterNorm)
            }.take(10)

            if (filtered.isEmpty()) {
                return if (filterNorm.isBlank()) {
                    "目前暫無收到新的訊息或通知紀錄。"
                } else {
                    "目前沒有找到與「$appFilter」相關的最新通知。"
                }
            }

            val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            return buildString {
                appendLine("📬 最新收到的手機通知（共 ${filtered.size} 則）：")
                filtered.forEachIndexed { idx, n ->
                    val timeStr = fmt.format(Date(n.timestampMs))
                    appendLine("${idx + 1}. [$timeStr] 【${n.appLabel}】${n.title}：${n.text}")
                }
            }.trim()
        }
    }
}
