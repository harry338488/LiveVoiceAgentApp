package com.example.livevoiceagent.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import com.example.livevoiceagent.MainActivity
import com.example.livevoiceagent.R

/**
 * Minimalist dark-obsidian Catherine Home Screen Widget (0% battery, 0 mic occupation, 100% Hey Google compatible).
 * Tapping "🎙️ 開講" immediately opens Catherine and starts hands-free Live Chat.
 * Tapping "🔴 錄音" immediately starts local $0 Meeting Recording.
 */
class CatherineWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val talkIntent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_START_VOICE_CHAT
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val talkPi = PendingIntent.getActivity(context, 7801, talkIntent, piFlags)

        val recordIntent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_START_MEETING_RECORD
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val recordPi = PendingIntent.getActivity(context, 7802, recordIntent, piFlags)

        for (widgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_catherine).apply {
                setOnClickPendingIntent(R.id.widget_orb_icon, talkPi)
                setOnClickPendingIntent(R.id.widget_talk_area, talkPi)
                setOnClickPendingIntent(R.id.widget_btn_talk, talkPi)
                setOnClickPendingIntent(R.id.widget_btn_record, recordPi)
            }
            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }
}
