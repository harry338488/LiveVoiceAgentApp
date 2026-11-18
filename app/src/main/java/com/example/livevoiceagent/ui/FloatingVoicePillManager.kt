package com.example.livevoiceagent.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.example.livevoiceagent.MainActivity
import com.example.livevoiceagent.network.ConnectionState
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Dynamic-Island style draggable floating voice pill shown over other apps during an active Live Call.
 * Lets the user see real-time voice status, live subtitles, mute/unmute, open the app, or hang up with one tap.
 */
class FloatingVoicePillManager(
    private val context: Context,
    private val onToggleMute: () -> Unit,
    private val onHangupCall: () -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var rootContainer: LinearLayout? = null
    private var statusTitleView: TextView? = null
    private var subtitleView: TextView? = null
    private var muteBtnView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var isShowing = false
    private var lastState: ConnectionState = ConnectionState.DISCONNECTED
    private var lastMuted: Boolean = false
    private var lastSubtitle: String = "請直接開口說話..."

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).roundToInt()
    }

    private fun canDrawOverlay(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun updatePillState(
        enabled: Boolean,
        connectionState: ConnectionState,
        isMuted: Boolean,
        micLevel: Float,
        latestTranscriptSnippet: String? = null
    ) {
        mainHandler.post {
            lastState = connectionState
            lastMuted = isMuted
            if (!latestTranscriptSnippet.isNullOrBlank()) {
                lastSubtitle = latestTranscriptSnippet.replace("\n", " ").trim().takeLast(38)
            }

            if (!enabled || connectionState == ConnectionState.DISCONNECTED || !canDrawOverlay()) {
                hideInternal()
                return@post
            }

            if (!isShowing) {
                showInternal()
            }
            refreshContent(connectionState, isMuted, micLevel, lastSubtitle)
        }
    }

    fun hide() {
        mainHandler.post { hideInternal() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showInternal() {
        if (isShowing || !canDrawOverlay()) return
        runCatching {
            val pillBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(28).toFloat()
                setColor(Color.parseColor("#EB0F172A"))
                setStroke(dp(1), Color.parseColor("#8038BDF8"))
            }

            val container = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = pillBg
                setPadding(dp(14), dp(8), dp(10), dp(8))
                elevation = dp(10).toFloat()
            }

            val textColumn = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(8)
                }
            }

            val titleTv = TextView(context).apply {
                setTextColor(Color.parseColor("#38BDF8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                text = "🎙️ Luna 通話中"
            }

            val subTv = TextView(context).apply {
                setTextColor(Color.parseColor("#E2E8F0"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.START
                text = lastSubtitle
            }

            textColumn.addView(titleTv)
            textColumn.addView(subTv)

            fun makeChipButton(label: String, bgColor: String, textColor: String): TextView {
                return TextView(context).apply {
                    text = label
                    setTextColor(Color.parseColor(textColor))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setPadding(dp(9), dp(5), dp(9), dp(5))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(16).toFloat()
                        setColor(Color.parseColor(bgColor))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = dp(4)
                    }
                }
            }

            val muteBtn = makeChipButton("🔇", "#334155", "#F8FAFC").apply {
                setOnClickListener { onToggleMute() }
            }

            val openAppBtn = makeChipButton("📲", "#1E3A8A", "#BAE6FD").apply {
                setOnClickListener {
                    runCatching {
                        val intent = Intent(context, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        }
                        context.startActivity(intent)
                    }
                }
            }

            val hangupBtn = makeChipButton("📴 掛斷", "#9F1239", "#FFE4E6").apply {
                setOnClickListener { onHangupCall() }
            }

            container.addView(textColumn)
            container.addView(muteBtn)
            container.addView(openAppBtn)
            container.addView(hangupBtn)

            val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val screenWidth = context.resources.displayMetrics.widthPixels
            val pillWidth = (screenWidth * 0.90f).roundToInt().coerceAtMost(dp(370))

            val params = WindowManager.LayoutParams(
                pillWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                x = 0
                y = dp(42)
            }

            // Enable smooth drag to reposition anywhere on screen
            var initialX = 0
            var initialY = 0
            var touchX = 0f
            var touchY = 0f
            var moved = false

            textColumn.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        moved = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - touchX).roundToInt()
                        val dy = (event.rawY - touchY).roundToInt()
                        if (abs(dx) > dp(4) || abs(dy) > dp(4)) {
                            moved = true
                            params.x = initialX + dx
                            params.y = (initialY + dy).coerceAtLeast(dp(16))
                            runCatching { windowManager.updateViewLayout(container, params) }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            // Single tap on text area opens MainActivity
                            runCatching {
                                val intent = Intent(context, MainActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                                }
                                context.startActivity(intent)
                            }
                        }
                        true
                    }
                    else -> false
                }
            }

            windowManager.addView(container, params)
            rootContainer = container
            statusTitleView = titleTv
            subtitleView = subTv
            muteBtnView = muteBtn
            layoutParams = params
            isShowing = true
        }
    }

    private fun refreshContent(
        state: ConnectionState,
        isMuted: Boolean,
        micLevel: Float,
        subtitle: String
    ) {
        val titleTv = statusTitleView ?: return
        val subTv = subtitleView ?: return
        val muteTv = muteBtnView ?: return

        val bars = when {
            isMuted -> "🔇 已靜音"
            micLevel > 0.25f -> "🟢 ▂▄▆█"
            micLevel > 0.10f -> "🟢 ▂▄▆"
            micLevel > 0.03f -> "🟢 ▂▄"
            else -> "🟢 ▂"
        }

        val stateLabel = when (state) {
            ConnectionState.CONNECTING -> "⏳ Luna 連線中..."
            ConnectionState.DELEGATING -> "🧠 Luna 後端查詢中 · $bars"
            ConnectionState.LIVE -> "🎙️ Luna 背景通話中 · $bars"
            ConnectionState.CLOSING -> "📴 正在結束通話..."
            ConnectionState.DISCONNECTED -> "已結束"
        }

        titleTv.text = stateLabel
        subTv.text = subtitle.ifBlank { "請直接說出指令，或說「掰掰」自動掛斷" }
        muteTv.text = if (isMuted) "🎙️取消靜音" else "🔇"
    }

    private fun hideInternal() {
        if (!isShowing) return
        runCatching {
            rootContainer?.let { windowManager.removeView(it) }
        }
        rootContainer = null
        statusTitleView = null
        subtitleView = null
        muteBtnView = null
        isShowing = false
    }
}
