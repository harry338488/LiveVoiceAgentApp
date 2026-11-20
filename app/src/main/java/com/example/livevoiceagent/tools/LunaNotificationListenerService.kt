package com.example.livevoiceagent.tools

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.lang.ref.WeakReference
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
 * Captures both currently active status-bar notifications (via `activeNotifications` on connect & on demand)
 * and newly posted notifications (LINE, SMS, Gmail, Messenger, etc.), including MessagingStyle & InboxStyle lines.
 */
class LunaNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeServiceRef = WeakReference(this)
        syncFromActiveNotifications()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        activeServiceRef = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        activeServiceRef = WeakReference(this)
        val item = sbn ?: return
        parseStatusBarNotification(this, item)?.let { entry ->
            recordEntry(entry)
        }
    }

    private fun syncFromActiveNotifications() {
        runCatching {
            val active = activeNotifications ?: return
            active.sortedBy { it.postTime }.forEach { sbn ->
                parseStatusBarNotification(this, sbn)?.let { recordEntry(it) }
            }
        }
    }

    companion object {
        private val recentNotifications = CopyOnWriteArrayList<CapturedNotificationItem>()
        @Volatile
        private var activeServiceRef: WeakReference<LunaNotificationListenerService>? = null

        private fun recordEntry(entry: CapturedNotificationItem) {
            recentNotifications.removeAll {
                it.packageName == entry.packageName && it.title == entry.title && it.text == entry.text
            }
            recentNotifications.add(0, entry)
            recentNotifications.sortByDescending { it.timestampMs }
            while (recentNotifications.size > 35) {
                recentNotifications.removeAt(recentNotifications.lastIndex)
            }
        }

        private fun parseStatusBarNotification(
            context: Context,
            item: StatusBarNotification
        ): CapturedNotificationItem? {
            if (item.isOngoing) return null
            val pkg = item.packageName ?: return null
            if (pkg == context.packageName || pkg == "android" || pkg == "com.android.systemui") return null
            val notif = item.notification ?: return null
            if ((notif.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return null

            val extras = notif.extras ?: return null
            val title = (extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.trim().orEmpty()

            // Extract rich body: MessagingStyle (LINE/Messenger/WhatsApp) -> InboxStyle lines -> BigText -> Text
            val richText = buildString {
                val msgBundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                if (!msgBundles.isNullOrEmpty()) {
                    val lastMsgs = msgBundles.takeLast(3).mapNotNull { it as? Bundle }.mapNotNull { b ->
                        val sender = b.getCharSequence("sender")?.toString()?.trim().orEmpty()
                        val msg = b.getCharSequence("text")?.toString()?.trim().orEmpty()
                        when {
                            msg.isBlank() -> null
                            sender.isNotBlank() && sender != title -> "$sender: $msg"
                            else -> msg
                        }
                    }
                    if (lastMsgs.isNotEmpty()) append(lastMsgs.joinToString(" / "))
                }
                if (isEmpty()) {
                    val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                    if (!lines.isNullOrEmpty()) {
                        append(lines.takeLast(3).mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotBlank() } }.joinToString(" / "))
                    }
                }
                if (isEmpty()) {
                    val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                        ?: extras.getCharSequence(Notification.EXTRA_TEXT)
                        ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT))?.toString()?.trim().orEmpty()
                    append(body)
                }
            }.trim()

            if (title.isBlank() && richText.isBlank()) return null

            val appLabel = runCatching {
                val pm = context.applicationContext.packageManager
                val ai = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(ai).toString()
            }.getOrDefault(pkg.substringAfterLast('.'))

            return CapturedNotificationItem(
                packageName = pkg,
                appLabel = appLabel,
                title = title.ifBlank { appLabel },
                text = richText.ifBlank { "（無內文預覽）" },
                timestampMs = item.postTime.takeIf { it > 0L } ?: System.currentTimeMillis()
            )
        }

        fun isNotificationAccessEnabled(context: Context): Boolean {
            return runCatching {
                val enabledListeners = Settings.Secure.getString(
                    context.contentResolver,
                    "enabled_notification_listeners"
                ).orEmpty()
                enabledListeners.contains(context.packageName)
            }.getOrDefault(false)
        }

        fun formatRecentNotificationsForLlm(context: Context, appFilter: String = ""): String {
            if (!isNotificationAccessEnabled(context)) {
                return "尚未開啟「通知讀取權限 (Notification Access)」。請提醒使用者可在「語音喚醒設定」中點擊「🔔 一鍵開啟通知讀取權限」，即可讓 Catherine 幫您朗讀 LINE 與簡訊通知。"
            }

            // Pull live active notifications from the status bar right now!
            val svc = activeServiceRef?.get()
            if (svc != null) {
                svc.syncFromActiveNotifications()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                runCatching {
                    requestRebind(ComponentName(context, LunaNotificationListenerService::class.java))
                }
            }

            val filterNorm = appFilter.trim().lowercase(Locale.ROOT)
            val filtered = recentNotifications.filter { item ->
                if (filterNorm.isBlank()) true
                else item.appLabel.lowercase(Locale.ROOT).contains(filterNorm) ||
                        item.packageName.lowercase(Locale.ROOT).contains(filterNorm) ||
                        item.title.lowercase(Locale.ROOT).contains(filterNorm)
            }.take(12)

            if (filtered.isEmpty()) {
                return if (svc == null) {
                    "系統通知服務剛剛重新連線中（更新 App 後 Android 會重新綁定通知監聽），請稍候幾秒再試，或到系統通知讀取權限重新開關一次「Catherine 通知朗讀助手」。"
                } else if (filterNorm.isBlank()) {
                    "目前通知欄與近期紀錄中沒有未讀的新訊息或通知。"
                } else {
                    "目前沒有找到與「$appFilter」相關的最新通知。"
                }
            }

            val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            val nowMs = System.currentTimeMillis()
            return buildString {
                appendLine("📬 目前手機通知欄與最新收到的通知（共 ${filtered.size} 則）：")
                filtered.forEachIndexed { idx, n ->
                    val timeStr = fmt.format(Date(n.timestampMs))
                    val agoMin = ((nowMs - n.timestampMs) / 60_000L).coerceAtLeast(0L)
                    val agoStr = if (agoMin < 60) "${agoMin}分鐘前" else "${agoMin / 60}小時前"
                    appendLine("${idx + 1}. [$timeStr · $agoStr] 【${n.appLabel}】${n.title}：${n.text.take(180)}")
                }
            }.trim()
        }
    }
}
