package com.example.livevoiceagent.tools

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class CalendarToolManager(private val context: Context) {

    fun hasCalendarPermission(): Boolean {
        val read = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        val write = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
        return read && write
    }

    /**
     * Executes any calendar tool call (`list_calendar_events`, `create_calendar_event`, `update_calendar_event`, `delete_calendar_event`)
     * and returns a structured JSON string for the Backend LLM.
     */
    fun executeCalendarTool(toolName: String, argsJson: JSONObject): String {
        if (!hasCalendarPermission()) {
            return JSONObject().apply {
                put("status", "error")
                put("message", "尚未取得 Android 行事曆讀寫權限 (READ_CALENDAR / WRITE_CALENDAR)。請提醒使用者在手機權限彈窗中允許行事曆權限。")
            }.toString()
        }

        return runCatching {
            when (toolName) {
                "list_calendar_events" -> listEvents(argsJson)
                "create_calendar_event" -> createEvent(argsJson)
                "update_calendar_event" -> updateEvent(argsJson)
                "delete_calendar_event" -> deleteEvent(argsJson)
                else -> JSONObject().put("status", "error").put("message", "未知的行事曆工具: $toolName").toString()
            }
        }.getOrElse { err ->
            JSONObject().apply {
                put("status", "error")
                put("message", "行事曆操作發生例外: ${err.message}")
            }.toString()
        }
    }

    private fun listEvents(args: JSONObject): String {
        val now = System.currentTimeMillis()
        val startMs = parseDateTimeMs(args.optString("start_time", "")) ?: now
        val endMs = parseDateTimeMs(args.optString("end_time", "")) ?: (startMs + 7L * 24 * 3600 * 1000)
        val keyword = args.optString("keyword", "").trim()

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DESCRIPTION
        )

        val selection = StringBuilder("(${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?) AND ${CalendarContract.Events.DELETED} != 1")
        val selectionArgs = mutableListOf(startMs.toString(), endMs.toString())

        if (keyword.isNotEmpty()) {
            selection.append(" AND (${CalendarContract.Events.TITLE} LIKE ? OR ${CalendarContract.Events.DESCRIPTION} LIKE ?)")
            selectionArgs.add("%$keyword%")
            selectionArgs.add("%$keyword%")
        }

        val eventsArr = JSONArray()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            selection.toString(),
            selectionArgs.toTypedArray(),
            "${CalendarContract.Events.DTSTART} ASC"
        )?.use { cursor ->
            var count = 0
            while (cursor.moveToNext() && count < 30) {
                val id = cursor.getLong(0)
                val title = cursor.getString(1) ?: "(無標題)"
                val dtStart = cursor.getLong(2)
                val dtEnd = cursor.getLong(3)
                val loc = cursor.getString(4) ?: ""
                val desc = cursor.getString(5) ?: ""

                eventsArr.put(JSONObject().apply {
                    put("event_id", id)
                    put("title", title)
                    put("start_time", sdf.format(Date(dtStart)))
                    put("end_time", if (dtEnd > 0) sdf.format(Date(dtEnd)) else "")
                    if (loc.isNotBlank()) put("location", loc)
                    if (desc.isNotBlank()) put("description", desc)
                })
                count++
            }
        }

        return JSONObject().apply {
            put("status", "success")
            put("count", eventsArr.length())
            put("events", eventsArr)
        }.toString()
    }

    private fun createEvent(args: JSONObject): String {
        val calendarId = findWritableCalendarId()
            ?: return JSONObject().put("status", "error").put("message", "找不到手機上可寫入的行事曆帳號").toString()

        val title = args.optString("title", "").trim().ifBlank { "AI 語音預約事項" }
        val startMs = parseDateTimeMs(args.optString("start_time", ""))
            ?: return JSONObject().put("status", "error").put("message", "start_time 格式無法解析，請使用 yyyy-MM-dd HH:mm 格式").toString()

        val endMs = parseDateTimeMs(args.optString("end_time", "")) ?: (startMs + 60 * 60 * 1000L)
        val location = args.optString("location", "")
        val description = args.optString("description", "")

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMs)
            put(CalendarContract.Events.DTEND, endMs)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            if (location.isNotBlank()) put(CalendarContract.Events.EVENT_LOCATION, location)
            if (description.isNotBlank()) put(CalendarContract.Events.DESCRIPTION, description)
        }

        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        val eventId = uri?.lastPathSegment?.toLongOrNull()

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return JSONObject().apply {
            put("status", "success")
            put("message", "已成功寫入行事曆！")
            put("event_id", eventId ?: -1L)
            put("title", title)
            put("start_time", sdf.format(Date(startMs)))
            put("end_time", sdf.format(Date(endMs)))
            put("location", location)
        }.toString()
    }

    private fun updateEvent(args: JSONObject): String {
        var eventId = args.optLong("event_id", -1L)
        val searchTitle = args.optString("search_title", "").trim()

        if (eventId <= 0L && searchTitle.isNotEmpty()) {
            eventId = findEventIdByTitle(searchTitle) ?: -1L
        }

        if (eventId <= 0L) {
            return JSONObject().apply {
                put("status", "error")
                put("message", "找不到指定的行事曆事件 (請先呼叫 list_calendar_events 確認 event_id 或提供正確的 search_title)")
            }.toString()
        }

        val values = ContentValues()
        if (args.has("new_title") && args.optString("new_title").isNotBlank()) {
            values.put(CalendarContract.Events.TITLE, args.optString("new_title").trim())
        }
        if (args.has("new_start_time") && args.optString("new_start_time").isNotBlank()) {
            parseDateTimeMs(args.optString("new_start_time"))?.let { newStart ->
                values.put(CalendarContract.Events.DTSTART, newStart)
                // If new_end_time is not explicitly set, default to 1 hour after newStart
                if (!args.has("new_end_time") || args.optString("new_end_time").isBlank()) {
                    values.put(CalendarContract.Events.DTEND, newStart + 3600_000L)
                }
            }
        }
        if (args.has("new_end_time") && args.optString("new_end_time").isNotBlank()) {
            parseDateTimeMs(args.optString("new_end_time"))?.let { newEnd ->
                values.put(CalendarContract.Events.DTEND, newEnd)
            }
        }
        if (args.has("new_location")) {
            values.put(CalendarContract.Events.EVENT_LOCATION, args.optString("new_location"))
        }
        if (args.has("new_description")) {
            values.put(CalendarContract.Events.DESCRIPTION, args.optString("new_description"))
        }

        if (values.size() == 0) {
            return JSONObject().put("status", "error").put("message", "未提供任何要修改的欄位").toString()
        }

        val updateUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val rows = context.contentResolver.update(updateUri, values, null, null)

        return JSONObject().apply {
            put("status", if (rows > 0) "success" else "error")
            put("updated_rows", rows)
            put("event_id", eventId)
            put("message", if (rows > 0) "已成功更新行事曆行程 (ID: $eventId)" else "找不到該行程或更新失敗")
        }.toString()
    }

    private fun deleteEvent(args: JSONObject): String {
        var eventId = args.optLong("event_id", -1L)
        val searchTitle = args.optString("search_title", "").trim()

        if (eventId <= 0L && searchTitle.isNotEmpty()) {
            eventId = findEventIdByTitle(searchTitle) ?: -1L
        }

        if (eventId <= 0L) {
            return JSONObject().put("status", "error").put("message", "找不到要刪除的行事曆行程").toString()
        }

        val deleteUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val rows = context.contentResolver.delete(deleteUri, null, null)

        return JSONObject().apply {
            put("status", if (rows > 0) "success" else "error")
            put("deleted_rows", rows)
            put("event_id", eventId)
            put("message", if (rows > 0) "已成功刪除行事曆行程 (ID: $eventId)" else "刪除失敗")
        }.toString()
    }

    private fun findWritableCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )
        var fallbackId: Long? = null

        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.VISIBLE} = 1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
            null,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val isPrimary = cursor.getInt(1) == 1
                if (isPrimary) return id
                if (fallbackId == null) fallbackId = id
            }
        }
        return fallbackId
    }

    private fun findEventIdByTitle(titleKeyword: String): Long? {
        val projection = arrayOf(CalendarContract.Events._ID)
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            "${CalendarContract.Events.TITLE} LIKE ? AND ${CalendarContract.Events.DELETED} != 1",
            arrayOf("%$titleKeyword%"),
            "${CalendarContract.Events.DTSTART} DESC"
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(0)
            }
        }
        return null
    }

    private fun parseDateTimeMs(raw: String): Long? {
        val clean = raw.trim().replace("T", " ")
        if (clean.isEmpty()) return null

        val formats = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy/MM/dd HH:mm",
            "yyyy-MM-dd"
        )
        for (pattern in formats) {
            runCatching {
                val sdf = SimpleDateFormat(pattern, Locale.getDefault())
                val parsed = sdf.parse(clean)
                if (parsed != null) return parsed.time
            }
        }
        return null
    }

    companion object {
        val CALENDAR_TOOL_NAMES = setOf(
            "list_calendar_events",
            "create_calendar_event",
            "update_calendar_event",
            "delete_calendar_event"
        )

        /**
         * Returns current real-time date, time, day of week, and timezone string to inject into LLM prompts.
         */
        fun buildCurrentTimeContextString(): String {
            val now = Date()
            val tz = TimeZone.getDefault()
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss (EEEE)", Locale.TAIWAN)
            return "【系統即時基本資訊】目前當地日期與時間：${sdf.format(now)}，時區：${tz.id} (${tz.displayName})"
        }

        /**
         * Builds the 4 Calendar tool schemas for OpenAI Responses Delegation (`/v1/live/sessions`).
         */
        fun buildResponsesCalendarToolSchemas(): List<JSONObject> {
            val listTool = JSONObject().apply {
                put("type", "function")
                put("name", "list_calendar_events")
                put("description", "查詢手機行事曆中的行程。在修改、刪除或確認是否有行程衝突前可先呼叫此工具。")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("start_time", JSONObject().put("type", "string").put("description", "查詢起始時間 (格式: yyyy-MM-dd HH:mm，留空預設為現在)"))
                        put("end_time", JSONObject().put("type", "string").put("description", "查詢結束時間 (格式: yyyy-MM-dd HH:mm，留空預設為未來7天)"))
                        put("keyword", JSONObject().put("type", "string").put("description", "選填，篩選標題或內容關鍵字"))
                    })
                })
            }

            val createTool = JSONObject().apply {
                put("type", "function")
                put("name", "create_calendar_event")
                put("description", "在手機行事曆新增一個行程或提醒事項。")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("title", JSONObject().put("type", "string").put("description", "行程標題"))
                        put("start_time", JSONObject().put("type", "string").put("description", "開始時間 (格式: yyyy-MM-dd HH:mm)"))
                        put("end_time", JSONObject().put("type", "string").put("description", "結束時間 (格式: yyyy-MM-dd HH:mm，選填，預設1小時)"))
                        put("location", JSONObject().put("type", "string").put("description", "地點 (選填)"))
                        put("description", JSONObject().put("type", "string").put("description", "備註或詳細說明 (選填)"))
                    })
                    put("required", JSONArray().put("title").put("start_time"))
                })
            }

            val updateTool = JSONObject().apply {
                put("type", "function")
                put("name", "update_calendar_event")
                put("description", "修改手機行事曆中現有的行程（可透過 event_id 或 search_title 找到行程並更新時間、標題、地點或備註）。")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("event_id", JSONObject().put("type", "integer").put("description", "要修改的行程 ID (若已知)"))
                        put("search_title", JSONObject().put("type", "string").put("description", "若未知 event_id，可傳入原行程標題關鍵字進行匹配"))
                        put("new_title", JSONObject().put("type", "string").put("description", "更新後的標題 (選填)"))
                        put("new_start_time", JSONObject().put("type", "string").put("description", "更新後的開始時間 yyyy-MM-dd HH:mm (選填)"))
                        put("new_end_time", JSONObject().put("type", "string").put("description", "更新後的結束時間 yyyy-MM-dd HH:mm (選填)"))
                        put("new_location", JSONObject().put("type", "string").put("description", "更新後的地點 (選填)"))
                        put("new_description", JSONObject().put("type", "string").put("description", "更新後的備註說明 (選填)"))
                    })
                })
            }

            val deleteTool = JSONObject().apply {
                put("type", "function")
                put("name", "delete_calendar_event")
                put("description", "刪除手機行事曆中的指定行程。")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("event_id", JSONObject().put("type", "integer").put("description", "要刪除的行程 ID"))
                        put("search_title", JSONObject().put("type", "string").put("description", "若未知 event_id，可傳入要刪除的行程標題關鍵字"))
                    })
                })
            }

            return listOf(listTool, createTool, updateTool, deleteTool)
        }

        /**
         * Wraps the Responses tool schemas into standard `/v1/chat/completions` format.
         */
        fun buildChatCompletionsCalendarToolSchemas(): List<JSONObject> {
            return buildResponsesCalendarToolSchemas().map { schema ->
                JSONObject().apply {
                    put("type", "function")
                    put("function", JSONObject().apply {
                        put("name", schema.getString("name"))
                        put("description", schema.getString("description"))
                        put("parameters", schema.getJSONObject("parameters"))
                    })
                }
            }
        }
    }
}
