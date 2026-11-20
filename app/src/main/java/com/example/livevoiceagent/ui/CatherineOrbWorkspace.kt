package com.example.livevoiceagent.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.livevoiceagent.model.AppMode
import com.example.livevoiceagent.model.AppSettings
import com.example.livevoiceagent.model.MessageRole
import com.example.livevoiceagent.network.ConnectionState
import kotlin.math.cos
import kotlin.math.sin

private val CatherineObsidian = Color(0xFF05070B)
private val CatherineGlass = Color(0xFF121722)
private val CatherineCyan = Color(0xFF38BDF8)
private val CatherineIndigo = Color(0xFF818CF8)
private val CatherineViolet = Color(0xFFC084FC)
private val CatherineEmerald = Color(0xFF10B981)
private val CatherineAmber = Color(0xFFF59E0B)
private val CatherineCrimson = Color(0xFFE11D48)

/**
 * Flagship Minimalist Deep-Black Orb Workspace for Catherine (v2.0):
 * - Default View: Pure dark canvas with an audio-reactive glowing Catherine Orb, clean status typography,
 *   live 2-line captions, and a sleek 3-button circular dock.
 * - Swipe Up / Tap Handle: Smoothly transitions to the clean full conversation transcript with rich cards
 *   (Map Previews, Search Sources, Thinking Drawer, Audio Replay, Vision Photos) & minimal text input.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CatherineOrbWorkspace(
    uiState: UiState,
    currentMode: AppMode,
    onOpenModeConfigSheet: () -> Unit,
    onOpenNotebookDialog: () -> Unit,
    onOpenWakeWordDialog: () -> Unit,
    onOpenMapPreview: (MapPreviewData) -> Unit,
    onSelectTargetLanguage: (String) -> Unit,
    onTriggerTutorQuickAction: (String) -> Unit,
    onToggleCall: () -> Unit,
    onToggleMeetingRecording: () -> Unit,
    onRetryFailedMeeting: () -> Unit,
    onImportAudioUri: (Uri) -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onSendTypedMessage: (String) -> Unit,
    onSendImageMessage: (Bitmap, String) -> Unit,
    onPlayMessageVoiceClip: (String, Float) -> Unit,
    onSaveMessageToNotebook: (com.example.livevoiceagent.model.ChatMessage) -> Unit
) {
    val context = LocalContext.current
    val isConnected = uiState.connectionState != ConnectionState.DISCONNECTED
    val isDelegating = uiState.connectionState == ConnectionState.DELEGATING
    val isConnecting = uiState.connectionState == ConnectionState.CONNECTING
    val isTutorMode = currentMode == AppMode.TUTOR
    val isTranslateMode = currentMode == AppMode.TRANSLATE

    var showTranscriptView by remember { mutableStateOf(false) }
    var typedInput by remember { mutableStateOf("") }
    var showImagePickerSheet by remember { mutableStateOf(false) }
    var showMeetingSheet by remember { mutableStateOf(false) }

    val messages = uiState.currentSession.messages
    val listState = rememberLazyListState()
    val savedNotebookSentences = remember(uiState.tutorNotebook) {
        uiState.tutorNotebook.map { it.aiSentence.trim() }.toSet()
    }

    // Camera & Gallery launchers
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            onSendImageMessage(bitmap, "")
            showTranscriptView = true
        }
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }?.let { bmp ->
                    onSendImageMessage(bmp, "")
                    showTranscriptView = true
                }
            }
        }
    }
    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            onImportAudioUri(uri)
        }
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length, showTranscriptView) {
        if (showTranscriptView && messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CatherineObsidian)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = showTranscriptView,
                transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(180)) },
                label = "catherine_stage_switch",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { inTranscript ->
                if (!inTranscript) {
                    // ==========================================
                    // STAGE 1: MINIMALIST GLOWING ORB STAGE
                    // ==========================================
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectVerticalDragGestures { _, dragAmount ->
                                    if (dragAmount < -32f) {
                                        showTranscriptView = true
                                    }
                                }
                            }
                            .padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Spacer(modifier = Modifier.height(12.dp))

                        // Central Hero Orb + Minimal Status
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.weight(1f)
                        ) {
                            CatherineGlowingOrb(
                                connectionState = uiState.connectionState,
                                appMode = currentMode,
                                micLevel = if (uiState.isMuted) 0f else uiState.micLevel,
                                isWakeStandby = uiState.isWakeWordStandbyActive,
                                onClick = onToggleCall
                            )

                            Spacer(modifier = Modifier.height(28.dp))

                            val heroStatus = when {
                                uiState.isTranscribingMeeting -> uiState.meetingProgressMessage.ifBlank { "📝 gpt-transcribe 正在轉寫與摘要會議…" }
                                uiState.isMeetingRecording -> {
                                    val m = uiState.meetingRecordingSeconds / 60
                                    val sec = uiState.meetingRecordingSeconds % 60
                                    "🔴 會議錄音中 ${String.format(java.util.Locale.US, "%02d:%02d", m, sec)}（純本機零流量）"
                                }
                                isConnecting -> "正在連線至 Catherine…"
                                isDelegating -> "正在思考與查詢…"
                                isConnected && uiState.isMuted -> "已靜音 · 點擊左下取消靜音"
                                isConnected && isTranslateMode -> "同步口譯中 · 請直接說話"
                                isConnected && isTutorMode -> "外語陪練中 · 請開口說"
                                isConnected -> "我在聽…"
                                uiState.isWakeWordStandbyActive -> "待機中 · 喊「${uiState.settings.wakeWordPhrase.ifBlank { "hey Catherine" }}」"
                                else -> "輕觸光球開始對話"
                            }

                            Text(
                                text = heroStatus,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFE2E8F0),
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            // Live 2-Line Minimal Subtitle Caption
                            val latestMsg = messages.lastOrNull {
                                it.role == MessageRole.ASSISTANT_VOICE || it.role == MessageRole.USER
                            }
                            val captionText = when {
                                uiState.isAnalyzingImage -> "📷 正在解析您傳送的圖片…"
                                latestMsg != null && latestMsg.text.isNotBlank() -> {
                                    val prefix = if (latestMsg.role == MessageRole.USER) "你：" else ""
                                    prefix + latestMsg.text.replace("\n", " ").trim()
                                }
                                isConnected -> "直接說話即可，說「掰掰」會自動掛斷"
                                else -> "支援語音導航、鬧鐘、找車、行事曆與即時搜尋"
                            }

                            Text(
                                text = captionText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF94A3B8),
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                lineHeight = 22.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 44.dp)
                                    .clickable { if (messages.isNotEmpty()) showTranscriptView = true }
                                    .padding(horizontal = 8.dp)
                            )
                        }

                        // Contextual Mode Micro-Bar (Only visible in Translate or Tutor Mode)
                        if (isTranslateMode) {
                            val primary = uiState.settings.translationTargetLanguage
                            val secondary = uiState.settings.translationSecondaryLanguage
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = CatherineGlass,
                                border = BorderStroke(1.dp, CatherineEmerald.copy(alpha = 0.35f)),
                                modifier = Modifier
                                    .padding(bottom = 12.dp)
                                    .clickable { onSelectTargetLanguage(if (primary == "zh") secondary else "zh") }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "🌐 輸出：${AppSettings.languageLabel(primary).substringBefore(" (")}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color(0xFFA7F3D0),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "⇄ 切換",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CatherineEmerald
                                    )
                                }
                            }
                        } else if (isTutorMode) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                            ) {
                                MinimalActionChip("🐢 慢速", CatherineCyan) { onTriggerTutorQuickAction("repeat_slowly") }
                                MinimalActionChip("💡 提示", CatherineViolet) { onTriggerTutorQuickAction("give_hint") }
                                MinimalActionChip("🎧 抽考", CatherineAmber) { onTriggerTutorQuickAction("audio_flashcard_quiz") }
                                MinimalActionChip("📒 筆記", CatherineEmerald) { onOpenNotebookDialog() }
                            }
                        }

                        // If a prior meeting transcription failed due to network drop, show one-tap Retry chip!
                        if (!uiState.failedMeetingAudioPath.isNullOrBlank() && !uiState.isTranscribingMeeting) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = CatherineAmber.copy(alpha = 0.2f),
                                border = BorderStroke(1.dp, CatherineAmber),
                                modifier = Modifier
                                    .padding(bottom = 8.dp)
                                    .clickable { onRetryFailedMeeting() }
                            ) {
                                Text(
                                    text = "🔄 重試轉寫剛才已保存的錄音檔",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color(0xFFFDE68A),
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp)
                                )
                            }
                        }

                        // Sleek Action Row above dock: In-call Live Camera OR Meeting Recorder + Swipe-Up Transcript Handle
                        Row(
                            modifier = Modifier.padding(bottom = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isConnected) {
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = CatherineCyan.copy(alpha = 0.16f),
                                    border = BorderStroke(0.5.dp, CatherineCyan.copy(alpha = 0.55f)),
                                    modifier = Modifier.clickable { cameraLauncher.launch(null) }
                                ) {
                                    Text(
                                        text = "👁️ 給 Catherine 看畫面",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color(0xFFBAE6FD),
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                                    )
                                }
                            } else {
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = if (uiState.isMeetingRecording) CatherineCrimson.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.06f),
                                    border = BorderStroke(
                                        0.5.dp,
                                        if (uiState.isMeetingRecording) CatherineCrimson else Color.White.copy(alpha = 0.14f)
                                    ),
                                    modifier = Modifier.clickable(enabled = !uiState.isTranscribingMeeting) {
                                        if (uiState.isMeetingRecording) {
                                            onToggleMeetingRecording()
                                        } else {
                                            showMeetingSheet = true
                                        }
                                    }
                                ) {
                                    Text(
                                        text = when {
                                            uiState.isTranscribingMeeting -> "⏳ 轉寫中…"
                                            uiState.isMeetingRecording -> "⏹️ 結束會議錄音"
                                            else -> "🎙️ 會議錄音"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (uiState.isMeetingRecording) Color(0xFFFDA4AF) else Color(0xFFCBD5E1),
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                                    )
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(50),
                                color = Color.White.copy(alpha = 0.06f),
                                border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.12f)),
                                modifier = Modifier.clickable { showTranscriptView = true }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowUp,
                                        contentDescription = "查看對話紀錄",
                                        tint = Color(0xFF94A3B8),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = if (messages.isEmpty()) "對話紀錄" else "紀錄 (${messages.size})",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color(0xFFCBD5E1)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // ==========================================
                    // STAGE 2: CLEAN TRANSCRIPT & RICH CARDS VIEW
                    // ==========================================
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Subtle "Back to Orb" Pill
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp, bottom = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = Color.White.copy(alpha = 0.07f),
                                border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.14f)),
                                modifier = Modifier.clickable { showTranscriptView = false }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = "回到光球",
                                        tint = CatherineCyan,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "回到 Catherine 光球",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFFE2E8F0),
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }

                        if (messages.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "尚無對話內容\n點擊下方麥克風開口說話，或直接輸入文字",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFF64748B),
                                    textAlign = TextAlign.Center,
                                    lineHeight = 24.sp
                                )
                            }
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(messages, key = { it.id }) { msg ->
                                    ChatMessageItem(
                                        message = msg,
                                        isSavedInNotebook = savedNotebookSentences.contains(msg.text.trim()),
                                        onCopyText = { text ->
                                            copyToClipboard(context, "Message", text)
                                            Toast.makeText(context, "已複製內容", Toast.LENGTH_SHORT).show()
                                        },
                                        onOpenUrl = { url ->
                                            runCatching {
                                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                                    if (url.contains("google.com/maps") ||
                                                        url.contains("maps.app.goo.gl") ||
                                                        url.startsWith("google.navigation:")
                                                    ) {
                                                        setPackage("com.google.android.apps.maps")
                                                    }
                                                }
                                                try {
                                                    context.startActivity(intent)
                                                } catch (_: Exception) {
                                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                                }
                                            }
                                        },
                                        onOpenMapPreview = onOpenMapPreview,
                                        onPlayVoiceClip = onPlayMessageVoiceClip,
                                        onSaveToNotebook = { onSaveMessageToNotebook(msg) }
                                    )
                                }
                            }
                        }

                        // Silent Commute / Library Quick-Command Chips (no mic needed!)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                "☀️ 早安簡報" to "早安，請幫我播報今天的早安出門簡報",
                                "📝 看待辦" to "幫我列出目前未完成的待辦清單",
                                "📬 看通知" to "幫我看看剛剛有哪些最新手機通知",
                                "🅿️ 找車" to "我的車停在哪裡？"
                            ).forEach { (chipLabel, cmdText) ->
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = Color.White.copy(alpha = 0.06f),
                                    border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.12f)),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { onSendTypedMessage(cmdText) }
                                ) {
                                    Text(
                                        text = chipLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFFCBD5E1),
                                        textAlign = TextAlign.Center,
                                        maxLines = 1,
                                        modifier = Modifier.padding(vertical = 5.dp)
                                    )
                                }
                            }
                        }

                        // Minimal Frosted Text Input Row in Transcript View
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = typedInput,
                                onValueChange = { typedInput = it },
                                placeholder = { Text("輸入訊息給 Catherine…", color = Color(0xFF64748B), style = MaterialTheme.typography.bodyMedium) },
                                singleLine = true,
                                shape = RoundedCornerShape(24.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = CatherineGlass,
                                    unfocusedContainerColor = CatherineGlass,
                                    focusedBorderColor = CatherineCyan.copy(alpha = 0.6f),
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            Surface(
                                shape = CircleShape,
                                color = if (typedInput.isNotBlank()) CatherineCyan else CatherineGlass,
                                modifier = Modifier
                                    .size(46.dp)
                                    .clickable(enabled = typedInput.isNotBlank()) {
                                        val msg = typedInput.trim()
                                        if (msg.isNotBlank()) {
                                            onSendTypedMessage(msg)
                                            typedInput = ""
                                        }
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowUpward,
                                        contentDescription = "傳送",
                                        tint = if (typedInput.isNotBlank()) Color(0xFF05070B) else Color(0xFF64748B),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ==========================================
            // MINIMALIST 3-BUTTON CIRCULAR BOTTOM DOCK
            // ==========================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 36.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Button: Mute (when Live) / Camera-Photo (when Idle)
                Surface(
                    shape = CircleShape,
                    color = if (isConnected && uiState.isMuted) CatherineCrimson.copy(alpha = 0.25f) else CatherineGlass,
                    border = BorderStroke(
                        1.dp,
                        if (isConnected && uiState.isMuted) CatherineCrimson.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.12f)
                    ),
                    modifier = Modifier
                        .size(54.dp)
                        .clickable {
                            if (isConnected) onToggleMute() else showImagePickerSheet = true
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = when {
                                isConnected && uiState.isMuted -> Icons.Default.MicOff
                                isConnected -> Icons.Default.Mic
                                else -> Icons.Default.PhotoCamera
                            },
                            contentDescription = if (isConnected) "靜音切換" else "拍照或選圖",
                            tint = if (isConnected && uiState.isMuted) Color(0xFFFDA4AF) else Color(0xFFE2E8F0),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // Center Hero Button: Start Talk / Hang Up
                Surface(
                    shape = CircleShape,
                    color = if (isConnected) CatherineCrimson else Color.White,
                    modifier = Modifier
                        .size(76.dp)
                        .shadow(
                            elevation = if (isConnected) 18.dp else 14.dp,
                            shape = CircleShape,
                            ambientColor = if (isConnected) CatherineCrimson else CatherineCyan,
                            spotColor = if (isConnected) CatherineCrimson else CatherineCyan
                        )
                        .clickable { onToggleCall() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isConnected) Icons.Default.Close else Icons.Default.Mic,
                            contentDescription = if (isConnected) "結束通話" else "開始與 Catherine 對話",
                            tint = if (isConnected) Color.White else CatherineObsidian,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                // Right Button: Speakerphone (when Live) / Mode & Tools Sheet (when Idle)
                Surface(
                    shape = CircleShape,
                    color = CatherineGlass,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                    modifier = Modifier
                        .size(54.dp)
                        .clickable {
                            if (isConnected) onToggleSpeaker() else onOpenModeConfigSheet()
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = when {
                                isConnected && uiState.isSpeakerphoneOn -> Icons.Default.VolumeUp
                                isConnected -> Icons.Default.PhoneInTalk
                                else -> Icons.Default.AutoAwesome
                            },
                            contentDescription = if (isConnected) "擴音切換" else "模式與工具",
                            tint = Color(0xFFE2E8F0),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }

    if (showImagePickerSheet) {
        ModalBottomSheet(
            onDismissRequest = { showImagePickerSheet = false },
            containerColor = Color(0xFF0F141F),
            contentColor = Color.White
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "傳送圖片給 Catherine",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Button(
                    onClick = {
                        showImagePickerSheet = false
                        cameraLauncher.launch(null)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CatherineCyan, contentColor = CatherineObsidian),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("立即拍照詢問", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = {
                        showImagePickerSheet = false
                        galleryLauncher.launch("image/*")
                    },
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("從相簿選擇圖片", color = Color.White)
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showMeetingSheet) {
        ModalBottomSheet(
            onDismissRequest = { showMeetingSheet = false },
            containerColor = Color(0xFF0F141F),
            contentColor = Color.White
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "🎙️ 會議／課程錄音與超長音檔轉寫",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "採用最新 gpt-transcribe（1 小時約台幣 5.8 元），超過 15 分鐘自動本機無損分段，支援 4～5 小時超長錄音並自動整理重點摘要！",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8)
                )
                Button(
                    onClick = {
                        showMeetingSheet = false
                        onToggleMeetingRecording()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CatherineCrimson, contentColor = Color.White),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.FiberManualRecord, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("開始即時錄音（純本機零流量）", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = {
                        showMeetingSheet = false
                        audioPickerLauncher.launch("audio/*")
                    },
                    border = BorderStroke(1.dp, CatherineCyan.copy(alpha = 0.55f)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.AudioFile, contentDescription = null, tint = CatherineCyan)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("📂 匯入既有錄音檔（MP3 / M4A / WAV）", color = Color(0xFFBAE6FD), fontWeight = FontWeight.SemiBold)
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun MinimalActionChip(label: String, accent: Color, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = accent.copy(alpha = 0.14f),
        border = BorderStroke(0.5.dp, accent.copy(alpha = 0.45f)),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFFF1F5F9),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

/**
 * Audio-reactive luminous Catherine Glowing Orb (ChatGPT Voice / Gemini Live aesthetic).
 */
@Composable
private fun CatherineGlowingOrb(
    connectionState: ConnectionState,
    appMode: AppMode,
    micLevel: Float,
    isWakeStandby: Boolean,
    onClick: () -> Unit
) {
    val isLive = connectionState == ConnectionState.LIVE
    val isDelegating = connectionState == ConnectionState.DELEGATING
    val isConnecting = connectionState == ConnectionState.CONNECTING

    val infinite = rememberInfiniteTransition(label = "orb_breathe")
    val breathe by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isLive || isDelegating) 1800 else 3400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathe_val"
    )
    val orbitAngle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isDelegating) 2400 else 6500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbit_angle"
    )

    val smoothLevel by animateFloatAsState(
        targetValue = micLevel.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 320f),
        label = "smooth_mic"
    )

    val primaryGlow = when {
        isDelegating -> CatherineViolet
        appMode == AppMode.TRANSLATE && isLive -> CatherineEmerald
        appMode == AppMode.TUTOR && isLive -> CatherineAmber
        isLive -> CatherineCyan
        isConnecting -> CatherineIndigo
        isWakeStandby -> Color(0xFF0EA5E9)
        else -> Color(0xFF3B82F6)
    }

    val secondaryGlow = when {
        isDelegating -> CatherineAmber
        appMode == AppMode.TRANSLATE -> Color(0xFF34D399)
        appMode == AppMode.TUTOR -> CatherineViolet
        else -> CatherineViolet
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(260.dp)
            .clip(CircleShape)
            .clickable { onClick() }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = size.minDimension * 0.28f
            val reactiveRadius = baseRadius * (1f + breathe * 0.06f + smoothLevel * 0.28f)

            // 1. Deep Ambient Outer Aura
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryGlow.copy(alpha = if (isLive || isDelegating) 0.36f else 0.20f),
                        secondaryGlow.copy(alpha = if (isLive) 0.14f else 0.06f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = size.minDimension * 0.50f
                ),
                radius = size.minDimension * 0.50f,
                center = center
            )

            // 2. Reactive Outer Soundwave Ring (visible when live or delegating)
            if (isLive || isDelegating || isConnecting) {
                val ringRadius = reactiveRadius * (1.22f + breathe * 0.12f)
                drawCircle(
                    color = primaryGlow.copy(alpha = (0.28f * (1f - breathe * 0.5f) + smoothLevel * 0.25f).coerceIn(0f, 0.55f)),
                    radius = ringRadius,
                    center = center,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f)
                )
            }

            // 3. Swirling Inner Nebula Highlight
            val rad = Math.toRadians(orbitAngle.toDouble())
            val swirlOffset = Offset(
                x = center.x + (cos(rad) * reactiveRadius * 0.28f).toFloat(),
                y = center.y + (sin(rad) * reactiveRadius * 0.28f).toFloat()
            )

            // 4. Core Luminous Sphere
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.92f),
                        primaryGlow.copy(alpha = 0.90f),
                        secondaryGlow.copy(alpha = 0.78f),
                        Color(0xFF0F172A).copy(alpha = 0.95f)
                    ),
                    center = swirlOffset,
                    radius = reactiveRadius * 1.15f
                ),
                radius = reactiveRadius,
                center = center
            )

            // 5. Crisp Inner Rim Sheen
            drawCircle(
                color = Color.White.copy(alpha = 0.28f),
                radius = reactiveRadius,
                center = center,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6f)
            )
        }
    }
}
