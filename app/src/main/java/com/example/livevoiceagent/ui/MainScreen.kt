package com.example.livevoiceagent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.livevoiceagent.model.*
import com.example.livevoiceagent.network.ConnectionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onRequestMicPermissionAndToggleCall: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(uiState.errorToast) {
        uiState.errorToast?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            viewModel.clearErrorToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = uiState.currentSession.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${uiState.settings.frontendVoiceModel} ➔ ${uiState.settings.backendBrainModel}" +
                                    (if (uiState.settings.linkupSearchEnabled) " + Linkup" else "") +
                                    (if (uiState.settings.calendarToolsEnabled) " + 行事曆" else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                actions = {
                    if (selectedTab == 0) {
                        IconButton(
                            onClick = {
                                val md = viewModel.exportSessionMarkdown(uiState.currentSession)
                                copyToClipboard(context, "Conversation Markdown", md)
                                Toast.makeText(context, "已複製當前對話完整 Markdown 筆記", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "複製完整對話筆記")
                        }
                        IconButton(onClick = { viewModel.startNewConversationSession() }) {
                            Icon(Icons.Default.AddComment, contentDescription = "開啟新對話")
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.GraphicEq, contentDescription = null) },
                    label = { Text("即時語音對話") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (uiState.savedSessions.isNotEmpty()) {
                                    Badge { Text("${uiState.savedSessions.size}") }
                                }
                            }
                        ) {
                            Icon(Icons.Default.History, contentDescription = null)
                        }
                    },
                    label = { Text("對話紀錄留存") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("模型與 API 設定") }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> LiveConversationTab(
                    uiState = uiState,
                    onToggleCall = onRequestMicPermissionAndToggleCall,
                    onToggleMute = { viewModel.toggleMute() },
                    onToggleSpeaker = { viewModel.toggleSpeakerphone() },
                    onSendTypedMessage = { viewModel.sendTypedMessage(it) }
                )

                1 -> HistorySessionsTab(
                    uiState = uiState,
                    onSearchQueryChange = { viewModel.updateHistorySearchQuery(it) },
                    onLoadSession = { session ->
                        viewModel.loadSavedSession(session)
                        selectedTab = 0
                    },
                    onExportSession = { session ->
                        val md = viewModel.exportSessionMarkdown(session)
                        copyToClipboard(context, session.title, md)
                        Toast.makeText(context, "已複製「${session.title}」完整筆記", Toast.LENGTH_SHORT).show()
                    },
                    onDeleteSession = { viewModel.deleteSavedSession(it) }
                )

                2 -> SettingsAndModelFetchTab(
                    uiState = uiState,
                    onUpdateSettings = { viewModel.updateSettings(it) },
                    onFetchModels = { viewModel.fetchModelsFromApis() }
                )
            }
        }
    }
}

@Composable
private fun LiveConversationTab(
    uiState: UiState,
    onToggleCall: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onSendTypedMessage: (String) -> Unit
) {
    val listState = rememberLazyListState()
    val context = LocalContext.current
    var typedInput by remember { mutableStateOf("") }

    val messages = uiState.currentSession.messages

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Status & Audio Level Banner
        StatusHeaderCard(uiState = uiState)

        // Transcript & Backend Notes List
        if (messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.RecordVoiceOver,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "全雙工 Speech-to-Speech + 後端大腦",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "• 前端語音 (${uiState.settings.frontendVoiceModel}) 負責毫秒級自然對話與隨時打斷\n" +
                                "• 後端任務 (${uiState.settings.backendBrainModel}) 負責深度推理與任務處理\n" +
                                "• 網路搜尋 (Linkup API) 與所有對話內容會即時留存在此畫面，不怕 AI 一次講太多記不住！",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    ChatMessageItem(
                        message = msg,
                        onCopyText = { text ->
                            copyToClipboard(context, "Message", text)
                            Toast.makeText(context, "已複製內容", Toast.LENGTH_SHORT).show()
                        },
                        onOpenUrl = { url ->
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        }
                    )
                }
            }
        }

        // Bottom Input + Live Call Control Bar
        Surface(
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Text input row (supports sending exact text/keywords mid-call or offline)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = typedInput,
                        onValueChange = { typedInput = it },
                        placeholder = { Text("通話中也可輸入文字補充關鍵字或離線提問...") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = {
                            if (typedInput.isNotBlank()) {
                                onSendTypedMessage(typedInput)
                                typedInput = ""
                            }
                        },
                        enabled = typedInput.isNotBlank()
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "發送文字")
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Voice Call Controls Row
                val isConnected = uiState.connectionState != ConnectionState.DISCONNECTED
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Mute toggle
                    OutlinedButton(
                        onClick = onToggleMute,
                        enabled = isConnected
                    ) {
                        Icon(
                            imageVector = if (uiState.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = null,
                            tint = if (uiState.isMuted) MaterialTheme.colorScheme.error else LocalContentColor.current
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (uiState.isMuted) "已靜音" else "麥克風")
                    }

                    // Primary Start / Stop Call Button
                    val callBtnColor by animateColorAsState(
                        targetValue = if (isConnected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        label = "callBtnColor"
                    )
                    Button(
                        onClick = onToggleCall,
                        colors = ButtonDefaults.buttonColors(containerColor = callBtnColor),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
                        shape = CircleShape
                    ) {
                        Icon(
                            imageVector = if (isConnected) Icons.Default.CallEnd else Icons.Default.Call,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isConnected) "結束通話" else "開始語音對話",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }

                    // Speakerphone toggle
                    OutlinedButton(onClick = onToggleSpeaker) {
                        Icon(
                            imageVector = if (uiState.isSpeakerphoneOn) Icons.Default.VolumeUp else Icons.Default.PhoneInTalk,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (uiState.isSpeakerphoneOn) "擴音" else "聽筒")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusHeaderCard(uiState: UiState) {
    val dotColor = when (uiState.connectionState) {
        ConnectionState.DISCONNECTED -> Color.Gray
        ConnectionState.CONNECTING, ConnectionState.CLOSING -> Color(0xFFFFA000)
        ConnectionState.LIVE -> Color(0xFF00C853)
        ConnectionState.DELEGATING -> Color(0xFF7C4DFF)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = uiState.statusBanner,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (uiState.voiceUsageSeconds > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${uiState.voiceUsageSeconds}s",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            if (uiState.connectionState == ConnectionState.LIVE || uiState.connectionState == ConnectionState.DELEGATING) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { uiState.micLevel.coerceIn(0.03f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                )
            }
        }
    }
}

@Composable
private fun ChatMessageItem(
    message: ChatMessage,
    onCopyText: (String) -> Unit,
    onOpenUrl: (String) -> Unit
) {
    val timeStr = remember(message.timestampMs) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(message.timestampMs))
    }

    val isUser = message.role == MessageRole.USER
    val containerColor = when (message.role) {
        MessageRole.USER -> MaterialTheme.colorScheme.primaryContainer
        MessageRole.ASSISTANT_VOICE -> MaterialTheme.colorScheme.secondaryContainer
        MessageRole.BACKEND_BRAIN -> MaterialTheme.colorScheme.tertiaryContainer
        MessageRole.WEB_SEARCH -> MaterialTheme.colorScheme.surfaceVariant
        MessageRole.SYSTEM -> MaterialTheme.colorScheme.errorContainer
    }

    val roleLabel = when (message.role) {
        MessageRole.USER -> "🗣️ 你"
        MessageRole.ASSISTANT_VOICE -> "🎧 語音回覆 (${message.modelTag})"
        MessageRole.BACKEND_BRAIN -> "🧠 後端模型重點詳情 (${message.modelTag})"
        MessageRole.WEB_SEARCH -> "🌐 Linkup 網路搜尋與來源"
        MessageRole.SYSTEM -> "⚙️ 系統"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier.widthIn(min = 120.dp, max = 340.dp),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            colors = CardDefaults.cardColors(containerColor = containerColor)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = roleLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = timeStr,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "複製",
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { onCopyText(message.text) }
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium
                )

                if (message.sources.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "參考網頁來源 (點擊開啟):",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                    message.sources.take(5).forEachIndexed { idx, src ->
                        Text(
                            text = "${idx + 1}. ${src.title} (${src.url})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clickable {
                                    if (src.url.isNotBlank()) onOpenUrl(src.url)
                                }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistorySessionsTab(
    uiState: UiState,
    onSearchQueryChange: (String) -> Unit,
    onLoadSession: (ConversationSession) -> Unit,
    onExportSession: (ConversationSession) -> Unit,
    onDeleteSession: (String) -> Unit
) {
    val query = uiState.historySearchQuery.trim().lowercase()
    val filteredSessions = remember(uiState.savedSessions, query) {
        if (query.isEmpty()) {
            uiState.savedSessions
        } else {
            uiState.savedSessions.filter { s ->
                s.title.lowercase().contains(query) ||
                        s.messages.any { m -> m.text.lowercase().contains(query) }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        OutlinedTextField(
            value = uiState.historySearchQuery,
            onValueChange = onSearchQueryChange,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = { Text("搜尋歷史對話內容、後端筆記或 Linkup 搜尋紀錄...") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (filteredSessions.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "尚無符合的對話紀錄。\n開始語音通話後，所有逐字稿與後端回覆都會自動保存在這裡。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filteredSessions, key = { it.id }) { session ->
                    val sdf = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()) }
                    var expanded by remember { mutableStateOf(false) }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = !expanded }
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = session.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${sdf.format(Date(session.updatedAt))} • ${session.frontendModel} ➔ ${session.backendModel} • ${session.messages.size} 則訊息",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                IconButton(onClick = { onExportSession(session) }) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "複製 Markdown")
                                }
                                IconButton(onClick = { onDeleteSession(session.id) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "刪除",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }

                            val lastPreview = session.messages.lastOrNull()?.text.orEmpty()
                            if (lastPreview.isNotBlank() && !expanded) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = lastPreview,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (expanded) {
                                Spacer(modifier = Modifier.height(8.dp))
                                HorizontalDivider()
                                Spacer(modifier = Modifier.height(8.dp))
                                session.messages.forEach { m ->
                                    val prefix = when (m.role) {
                                        MessageRole.USER -> "🗣️ 你: "
                                        MessageRole.ASSISTANT_VOICE -> "🎧 語音: "
                                        MessageRole.BACKEND_BRAIN -> "🧠 後端詳情: "
                                        MessageRole.WEB_SEARCH -> "🌐 Linkup: "
                                        MessageRole.SYSTEM -> "⚙️ 系統: "
                                    }
                                    Text(
                                        text = prefix + m.text,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(vertical = 3.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                horizontalArrangement = Arrangement.End,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                TextButton(onClick = { expanded = !expanded }) {
                                    Text(if (expanded) "收合內容" else "展開完整紀錄")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(onClick = { onLoadSession(session) }) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("載入並接續對話")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsAndModelFetchTab(
    uiState: UiState,
    onUpdateSettings: (AppSettings) -> Unit,
    onFetchModels: () -> Unit
) {
    val s = uiState.settings
    val scrollState = rememberScrollState()
    var showKeys by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. API Keys Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "🔑 1. API Keys 設定",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { showKeys = !showKeys }) {
                        Text(if (showKeys) "隱藏金鑰" else "顯示金鑰")
                    }
                }

                val visualTrans = if (showKeys) VisualTransformation.None else PasswordVisualTransformation()

                OutlinedTextField(
                    value = s.openAiApiKey,
                    onValueChange = { onUpdateSettings(s.copy(openAiApiKey = it)) },
                    label = { Text("OpenAI API Key (支援 gpt-live-1 & gpt-6-luna)") },
                    placeholder = { Text("sk-...") },
                    singleLine = true,
                    visualTransformation = visualTrans,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = s.xAiApiKey,
                    onValueChange = { onUpdateSettings(s.copy(xAiApiKey = it)) },
                    label = { Text("xAI API Key (選填，支援 Grok Voice & Grok 後端)") },
                    placeholder = { Text("xai-...") },
                    singleLine = true,
                    visualTransformation = visualTrans,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = s.linkupApiKey,
                    onValueChange = { onUpdateSettings(s.copy(linkupApiKey = it)) },
                    label = { Text("Linkup Web Search API Key (api.linkup.so)") },
                    placeholder = { Text("輸入你的 Linkup API Key...") },
                    singleLine = true,
                    visualTransformation = visualTrans,
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = onFetchModels,
                    enabled = !uiState.isFetchingModels,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (uiState.isFetchingModels) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("從 API 自動 Fetch 適用模型清單 (/v1/models)")
                }

                if (uiState.modelFetchStatus.isNotBlank()) {
                    Text(
                        text = uiState.modelFetchStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // 2. Frontend Voice Model & Backend Brain Model Selection
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "🧠 2. 前端語音模型 ＆ 後端大腦模型選擇",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                // Protocol selector
                DropdownSelectorField(
                    label = "語音連線協定 (Live Protocol)",
                    selectedValue = s.protocol.label,
                    options = LiveProtocol.entries.map { it.label },
                    onOptionSelected = { label ->
                        val picked = LiveProtocol.entries.firstOrNull { it.label == label } ?: LiveProtocol.OPENAI_LIVE
                        onUpdateSettings(s.copy(protocol = picked))
                    }
                )

                // Frontend Live Voice Model (Dropdown + Editable)
                EditableModelDropdownField(
                    label = "前端語音模型 (Speech-to-Speech Live Model)",
                    value = s.frontendVoiceModel,
                    options = s.fetchedVoiceModels,
                    onValueChange = { modelId ->
                        val autoProtocol = when {
                            modelId.lowercase().startsWith("grok") -> LiveProtocol.XAI_REALTIME
                            modelId.lowercase().startsWith("gpt-live") -> LiveProtocol.OPENAI_LIVE
                            else -> s.protocol
                        }
                        onUpdateSettings(s.copy(frontendVoiceModel = modelId, protocol = autoProtocol))
                    }
                )

                // Backend Brain Model (Dropdown + Editable)
                EditableModelDropdownField(
                    label = "後端任務推理模型 (Backend Brain LLM)",
                    value = s.backendBrainModel,
                    options = s.fetchedBackendModels,
                    onValueChange = { modelId ->
                        onUpdateSettings(s.copy(backendBrainModel = modelId))
                    }
                )

                // Delegation mode selector
                DropdownSelectorField(
                    label = "後端委派架構 (Delegation Mode)",
                    selectedValue = s.delegationMode.label,
                    options = DelegationMode.entries.map { it.label },
                    onOptionSelected = { label ->
                        val picked = DelegationMode.entries.firstOrNull { it.label == label } ?: DelegationMode.RESPONSES
                        onUpdateSettings(s.copy(delegationMode = picked))
                    }
                )

                // Voice selector
                val currentVoiceLabel = AppSettings.AVAILABLE_VOICES.firstOrNull { it.first == s.voiceName }?.second
                    ?: s.voiceName
                DropdownSelectorField(
                    label = "語音聲線 (Voice)",
                    selectedValue = currentVoiceLabel,
                    options = AppSettings.AVAILABLE_VOICES.map { "${it.first} — ${it.second}" },
                    onOptionSelected = { item ->
                        val voiceId = item.substringBefore(" — ").trim()
                        onUpdateSettings(s.copy(voiceName = voiceId))
                    }
                )
            }
        }

        // 3. Linkup Web Search & Android Calendar Tools Configuration
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "🌐 3. 網路搜尋 (Linkup) ＆ 手機行事曆工具",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("啟用 Linkup Web Search 工具", fontWeight = FontWeight.Medium)
                        Text(
                            "後端模型可自動呼叫 api.linkup.so/v1/search 並將來源網址存入對話紀錄",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = s.linkupSearchEnabled,
                        onCheckedChange = { onUpdateSettings(s.copy(linkupSearchEnabled = it)) }
                    )
                }

                DropdownSelectorField(
                    label = "Linkup 搜尋深度 (Search Depth)",
                    selectedValue = if (s.linkupSearchDepth == "deep") "deep (深度多步研究)" else "standard (標準快速搜尋)",
                    options = listOf("standard (標準快速搜尋)", "deep (深度多步研究)"),
                    onOptionSelected = { sel ->
                        val depth = if (sel.startsWith("deep")) "deep" else "standard"
                        onUpdateSettings(s.copy(linkupSearchDepth = depth))
                    }
                )

                HorizontalDivider()

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("📅 啟用手機行事曆讀寫與修改工具", fontWeight = FontWeight.Medium)
                        Text(
                            "允許後端模型查詢、新增、修改與刪除 Android 手機行事曆行程",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = s.calendarToolsEnabled,
                        onCheckedChange = { onUpdateSettings(s.copy(calendarToolsEnabled = it)) }
                    )
                }

                HorizontalDivider()

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("同時啟用 OpenAI 內建 web_search", fontWeight = FontWeight.Medium)
                        Text(
                            "僅適用於 OpenAI Responses 委派模式",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = s.openAiNativeSearchEnabled,
                        onCheckedChange = { onUpdateSettings(s.copy(openAiNativeSearchEnabled = it)) }
                    )
                }
            }
        }

        // 4. Custom Prompts & Custom Backend Endpoint
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "📝 4. 系統提示詞 (Prompts) ＆ 進階端點",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            onUpdateSettings(
                                s.copy(
                                    voiceSystemInstructions = AppSettings.DEFAULT_FRONTEND_VOICE_PROMPT,
                                    backendSystemInstructions = AppSettings.DEFAULT_BACKEND_BRAIN_PROMPT
                                )
                            )
                        }
                    ) {
                        Text("還原預設 Prompt")
                    }
                }

                OutlinedTextField(
                    value = s.voiceSystemInstructions,
                    onValueChange = { onUpdateSettings(s.copy(voiceSystemInstructions = it)) },
                    label = { Text("前端語音模型提示詞 (Voice Persona)") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = s.backendSystemInstructions,
                    onValueChange = { onUpdateSettings(s.copy(backendSystemInstructions = it)) },
                    label = { Text("後端大腦模型提示詞 (Backend Brain Instructions)") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = s.customBackendBaseUrl,
                    onValueChange = { onUpdateSettings(s.copy(customBackendBaseUrl = it)) },
                    label = { Text("Client 模式自訂後端 Base URL (預設 OpenAI)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditableModelDropdownField(
    label: String,
    value: String,
    options: List<String>,
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onValueChange(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownSelectorField(
    label: String,
    selectedValue: String,
    options: List<String>,
    onOptionSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selectedValue,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onOptionSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
