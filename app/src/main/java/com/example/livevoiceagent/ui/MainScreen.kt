package com.example.livevoiceagent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.livevoiceagent.audio.VoiceprintProfile
import com.example.livevoiceagent.audio.WakeWordManager
import com.example.livevoiceagent.model.*
import com.example.livevoiceagent.network.ConnectionState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val DeepSpaceBgBrush = Brush.verticalGradient(
    colors = listOf(
        Color(0xFF090D16),
        Color(0xFF0F172A),
        Color(0xFF090D16)
    )
)

private val UserBubbleGradient = Brush.linearGradient(
    colors = listOf(
        Color(0xFF1E40AF),
        Color(0xFF1D4ED8)
    )
)

private val AssistantCardBg = Color(0xFF161F30).copy(alpha = 0.94f)
private val GlassSurfaceBg = Color(0xFF1E293B).copy(alpha = 0.72f)
private val CyanAccent = Color(0xFF38BDF8)
private val VioletAccent = Color(0xFFA855F7)
private val EmeraldAccent = Color(0xFF10B981)
private val AmberTutorAccent = Color(0xFFF59E0B)
private val RoseDanger = Color(0xFFF43F5E)

private data class MapPreviewData(
    val lat: Double,
    val lon: Double,
    val userLat: Double?,
    val userLon: Double?,
    val name: String,
    val address: String,
    val meta: String,
    val navUrl: String
)

private fun parseMapPreviewSnippet(snippet: String, fallbackTitle: String, navUrl: String): MapPreviewData? {
    if (!snippet.startsWith("MAP_PREVIEW|")) return null
    val map = mutableMapOf<String, String>()
    snippet.removePrefix("MAP_PREVIEW|").split("|").forEach { part ->
        val idx = part.indexOf('=')
        if (idx > 0) {
            map[part.substring(0, idx)] = part.substring(idx + 1)
        }
    }
    val lat = map["lat"]?.toDoubleOrNull() ?: return null
    val lon = map["lon"]?.toDoubleOrNull() ?: return null
    val uLat = map["uLat"]?.toDoubleOrNull()
    val uLon = map["uLon"]?.toDoubleOrNull()
    val name = map["name"].orEmpty().ifBlank { fallbackTitle }
    val addr = map["addr"].orEmpty()
    val meta = map["meta"].orEmpty()
    return MapPreviewData(
        lat = lat,
        lon = lon,
        userLat = uLat,
        userLon = uLon,
        name = name,
        address = addr,
        meta = meta,
        navUrl = navUrl
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onRequestMicPermissionAndToggleCall: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    // RikkaHub-style single workspace: "CHAT" is the primary full-screen workspace, "SETTINGS" is opened from Drawer
    var showFullSettingsScreen by remember { mutableStateOf(false) }
    var showModeConfigBottomSheet by remember { mutableStateOf(false) }
    var showNotebookDialog by remember { mutableStateOf(false) }
    var showMemoryDialog by remember { mutableStateOf(false) }
    var showWakeWordDialog by remember { mutableStateOf(false) }
    var activeMapPreviewData by remember { mutableStateOf<MapPreviewData?>(null) }

    val currentMode = uiState.settings.appMode
    val isTranslationMode = currentMode == AppMode.TRANSLATE
    val isTutorMode = currentMode == AppMode.TUTOR
    val isConnected = uiState.connectionState != ConnectionState.DISCONNECTED
    val hasActiveMemory = uiState.settings.longTermMemoryEnabled && uiState.settings.userMemoryProfile.isNotBlank()

    LaunchedEffect(uiState.errorToast) {
        uiState.errorToast?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            viewModel.clearErrorToast()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            RikkaStyleNavigationDrawerSheet(
                uiState = uiState,
                currentMode = currentMode,
                hasActiveMemory = hasActiveMemory,
                showFullSettingsScreen = showFullSettingsScreen,
                onStartNewChat = {
                    viewModel.startNewConversationSession()
                    showFullSettingsScreen = false
                    scope.launch { drawerState.close() }
                },
                onSelectAppMode = { mode ->
                    viewModel.setAppMode(mode)
                    showFullSettingsScreen = false
                    scope.launch { drawerState.close() }
                },
                onOpenNotebook = {
                    scope.launch { drawerState.close() }
                    showNotebookDialog = true
                },
                onOpenMemoryHub = {
                    scope.launch { drawerState.close() }
                    showMemoryDialog = true
                },
                onOpenSettingsScreen = {
                    showFullSettingsScreen = true
                    scope.launch { drawerState.close() }
                },
                onSearchQueryChange = { viewModel.updateHistorySearchQuery(it) },
                onLoadSession = { session ->
                    viewModel.loadSavedSession(session)
                    showFullSettingsScreen = false
                    scope.launch { drawerState.close() }
                },
                onExportSession = { session ->
                    val md = viewModel.exportSessionMarkdown(session)
                    copyToClipboard(context, session.title, md)
                    Toast.makeText(context, "已複製「${session.title}」完整 Markdown 筆記", Toast.LENGTH_SHORT).show()
                },
                onDeleteSession = { id ->
                    viewModel.deleteSavedSession(id)
                }
            )
        }
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                Surface(
                    color = Color(0xFF0B111E).copy(alpha = 0.95f),
                    tonalElevation = 0.dp
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Left: Drawer Menu Button OR Back from Settings
                            IconButton(
                                onClick = {
                                    if (showFullSettingsScreen) {
                                        showFullSettingsScreen = false
                                    } else {
                                        scope.launch { drawerState.open() }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = if (showFullSettingsScreen) Icons.Default.ArrowBack else Icons.Default.Menu,
                                    contentDescription = if (showFullSettingsScreen) "返回對話" else "開啟側邊選單",
                                    tint = Color(0xFFE2E8F0)
                                )
                            }

                            Spacer(modifier = Modifier.width(4.dp))

                            // Center: RikkaHub-style Clean Title & Live Status Subtitle
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable(enabled = !showFullSettingsScreen) {
                                        showModeConfigBottomSheet = true
                                    }
                            ) {
                                Text(
                                    text = if (showFullSettingsScreen) {
                                        "⚙️ 模型、API 金鑰與工具設定"
                                    } else {
                                        uiState.currentSession.title
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFF8FAFC),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(1.dp))
                                val subtitleText = when {
                                    showFullSettingsScreen -> "管理 OpenAI / xAI / Linkup 金鑰、定位導航與系統 Prompt"
                                    isConnected -> uiState.statusBanner
                                    isTutorMode -> {
                                        val langShort = AppSettings.tutorLanguageLabel(uiState.settings.tutorTargetLanguage).substringBefore(" (")
                                        "🎓 外語陪練 · $langShort (${uiState.settings.tutorLevel.shortLabel})"
                                    }
                                    isTranslationMode -> {
                                        "🌐 同步口譯 · 自動偵測 ➔ ${AppSettings.languageLabel(uiState.settings.translationTargetLanguage).substringBefore(" (")}"
                                    }
                                    else -> {
                                        "🎙️ ${uiState.settings.frontendVoiceModel} ⇄ ${uiState.settings.backendBrainModel}"
                                    }
                                }
                                Text(
                                    text = subtitleText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when {
                                        isConnected && uiState.connectionState == ConnectionState.DELEGATING -> VioletAccent
                                        isTutorMode -> AmberTutorAccent
                                        isTranslationMode -> EmeraldAccent
                                        else -> CyanAccent
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // Call Timer Pill if active
                            if (uiState.voiceUsageSeconds > 0 && !showFullSettingsScreen) {
                                val mins = uiState.voiceUsageSeconds / 60
                                val secs = uiState.voiceUsageSeconds % 60
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = EmeraldAccent.copy(alpha = 0.16f),
                                    border = BorderStroke(0.5.dp, EmeraldAccent.copy(alpha = 0.5f)),
                                    modifier = Modifier.padding(end = 4.dp)
                                ) {
                                    Text(
                                        text = String.format(Locale.US, "%02d:%02d", mins, secs),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = EmeraldAccent,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            if (!showFullSettingsScreen) {
                                // Mode Quick Config BottomSheet Trigger
                                IconButton(onClick = { showModeConfigBottomSheet = true }) {
                                    Icon(
                                        Icons.Default.Tune,
                                        contentDescription = "當前模式與工具快速設定",
                                        tint = when {
                                            isTutorMode -> AmberTutorAccent
                                            isTranslationMode -> EmeraldAccent
                                            else -> CyanAccent
                                        }
                                    )
                                }

                                // Copy Markdown Button
                                IconButton(
                                    onClick = {
                                        val md = viewModel.exportSessionMarkdown(uiState.currentSession)
                                        copyToClipboard(context, "Conversation Markdown", md)
                                        Toast.makeText(context, "已複製當前對話完整 Markdown 筆記", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = "複製完整對話筆記",
                                        tint = Color(0xFFCBD5E1)
                                    )
                                }

                                // New Chat Button
                                IconButton(onClick = { viewModel.startNewConversationSession() }) {
                                    Icon(
                                        Icons.Default.AddComment,
                                        contentDescription = "開啟新對話",
                                        tint = CyanAccent
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DeepSpaceBgBrush)
                    .padding(innerPadding)
            ) {
                if (showFullSettingsScreen) {
                    SettingsAndModelFetchTab(
                        uiState = uiState,
                        onUpdateSettings = { viewModel.updateSettings(it) },
                        onFetchModels = { viewModel.fetchModelsFromApis() },
                        onOpenMemoryHub = { showMemoryDialog = true },
                        onOpenWakeWordDialog = { showWakeWordDialog = true }
                    )
                } else {
                    LiveConversationTab(
                        uiState = uiState,
                        currentMode = currentMode,
                        onOpenModeConfigSheet = { showModeConfigBottomSheet = true },
                        onOpenNotebookDialog = { showNotebookDialog = true },
                        onOpenWakeWordDialog = { showWakeWordDialog = true },
                        onOpenMapPreview = { preview -> activeMapPreviewData = preview },
                        onSelectTargetLanguage = { viewModel.setTranslationTargetLanguage(it) },
                        onSelectSecondaryLanguage = { viewModel.setTranslationSecondaryLanguage(it) },
                        onUpdateSettings = { viewModel.updateSettings(it) },
                        onTriggerTutorQuickAction = { viewModel.triggerTutorQuickAction(it) },
                        onToggleCall = onRequestMicPermissionAndToggleCall,
                        onToggleMute = { viewModel.toggleMute() },
                        onToggleSpeaker = { viewModel.toggleSpeakerphone() },
                        onSendTypedMessage = { viewModel.sendTypedMessage(it) },
                        onSendImageMessage = { bmp, prompt -> viewModel.sendImageMessage(bmp, prompt) },
                        onPlayMessageVoiceClip = { path, speed -> viewModel.playMessageVoiceClip(path, speed) },
                        onSaveMessageToNotebook = { msg -> viewModel.saveMessageToTutorNotebook(msg) }
                    )
                }
            }
        }
    }

    // Contextual Mode & Tool Configuration ModalBottomSheet (RikkaHub style!)
    if (showModeConfigBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showModeConfigBottomSheet = false },
            containerColor = Color(0xFF0F172A),
            contentColor = Color(0xFFF8FAFC),
            tonalElevation = 0.dp
        ) {
            ModeConfigBottomSheetContent(
                uiState = uiState,
                currentMode = currentMode,
                onSelectAppMode = { viewModel.setAppMode(it) },
                onUpdateSettings = { viewModel.updateSettings(it) },
                onUpdateTutorConfig = { newSettings, restartGreeting ->
                    viewModel.updateTutorConfig(newSettings, restartGreeting)
                },
                onSelectTargetLanguage = { viewModel.setTranslationTargetLanguage(it) },
                onSelectSecondaryLanguage = { viewModel.setTranslationSecondaryLanguage(it) },
                onOpenNotebook = {
                    showModeConfigBottomSheet = false
                    showNotebookDialog = true
                },
                onOpenWakeWordDialog = {
                    showModeConfigBottomSheet = false
                    showWakeWordDialog = true
                },
                onOpenFullSettings = {
                    showModeConfigBottomSheet = false
                    showFullSettingsScreen = true
                },
                onDismiss = { showModeConfigBottomSheet = false }
            )
        }
    }

    if (showWakeWordDialog) {
        WakeWordAndVoiceprintDialog(
            uiState = uiState,
            onDismiss = { showWakeWordDialog = false },
            onUpdateSettings = { viewModel.updateSettings(it) },
            onEnrollVoiceprint = { viewModel.enrollUserVoiceprint() },
            onClearVoiceprint = { viewModel.clearUserVoiceprint() }
        )
    }

    activeMapPreviewData?.let { mapPreview ->
        InAppMapPreviewDialog(
            previewData = mapPreview,
            onDismiss = { activeMapPreviewData = null },
            onLaunchGoogleMapsNavigation = { navUrl ->
                activeMapPreviewData = null
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(navUrl)).apply {
                        setPackage("com.google.android.apps.maps")
                    }
                    try {
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(navUrl)))
                    }
                }
            }
        )
    }

    if (showNotebookDialog) {
        TutorNotebookDialog(
            settings = uiState.settings,
            entries = uiState.tutorNotebook,
            onDismiss = { showNotebookDialog = false },
            onToggleInjectWeaknesses = { enabled ->
                viewModel.updateTutorConfig(uiState.settings.copy(tutorInjectNotebookWeaknesses = enabled), false)
            },
            onDeleteEntry = { id -> viewModel.deleteTutorNotebookEntry(id) },
            onExportAnki = {
                val tsv = viewModel.exportNotebookToAnki()
                if (tsv.isBlank()) {
                    Toast.makeText(context, "複習本目前是空的", Toast.LENGTH_SHORT).show()
                } else {
                    copyToClipboard(context, "Anki Flashcards TSV", tsv)
                    Toast.makeText(
                        context,
                        "已複製 ${uiState.tutorNotebook.size} 筆 Anki 字卡 (TSV 格式) 到剪貼簿！",
                        Toast.LENGTH_LONG
                    ).show()
                }
            },
            onCopyItem = { text ->
                copyToClipboard(context, "Notebook Item", text)
                Toast.makeText(context, "已複製句子與解析", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showMemoryDialog) {
        LongTermMemoryDialog(
            settings = uiState.settings,
            isExtracting = uiState.isExtractingMemory,
            onDismiss = { showMemoryDialog = false },
            onExtractFromCurrentSession = { viewModel.extractMemoryFromCurrentConversation() },
            onSave = { enabled, newProfile ->
                viewModel.updateSettings(
                    uiState.settings.copy(
                        longTermMemoryEnabled = enabled,
                        userMemoryProfile = newProfile
                    )
                )
                showMemoryDialog = false
            }
        )
    }
}

@Composable
private fun RikkaStyleNavigationDrawerSheet(
    uiState: UiState,
    currentMode: AppMode,
    hasActiveMemory: Boolean,
    showFullSettingsScreen: Boolean,
    onStartNewChat: () -> Unit,
    onSelectAppMode: (AppMode) -> Unit,
    onOpenNotebook: () -> Unit,
    onOpenMemoryHub: () -> Unit,
    onOpenSettingsScreen: () -> Unit,
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
                        s.messages.any { m ->
                            m.text.lowercase().contains(query) ||
                                    m.backendThinkingText.lowercase().contains(query)
                        }
            }
        }
    }
    val sdf = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }

    ModalDrawerSheet(
        drawerContainerColor = Color(0xFF0B111E),
        drawerContentColor = Color(0xFFF8FAFC),
        modifier = Modifier.width(315.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            // Drawer Brand Header + New Chat Button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(CyanAccent, VioletAccent))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = Color(0xFF06101E),
                        modifier = Modifier.size(19.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "LiveVoiceAgent",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                    Text(
                        text = "雙腦全雙工語音 · 定位導航 · 外語家教",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = onStartNewChat,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyanAccent,
                    contentColor = Color(0xFF06101E)
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.AddComment, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("開啟新對話 (New Chat)", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Section 1: 3-Mode Switcher
            Text(
                text = "工作模式",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF94A3B8),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )

            DrawerModeItem(
                title = "🎙️ 全雙工語音助理",
                subtitle = "GPS 定位 · Google Maps 導航 · Linkup · 行事曆",
                selected = !showFullSettingsScreen && currentMode == AppMode.ASSISTANT,
                accentColor = CyanAccent,
                onClick = { onSelectAppMode(AppMode.ASSISTANT) }
            )
            DrawerModeItem(
                title = "🌐 即時雙向同步口譯",
                subtitle = "70+ 語言自動聽辨 · 雙語按鍵極速切換",
                selected = !showFullSettingsScreen && currentMode == AppMode.TRANSLATE,
                accentColor = EmeraldAccent,
                onClick = { onSelectAppMode(AppMode.TRANSLATE) }
            )
            DrawerModeItem(
                title = "🎓 AI 外語口說陪練家教",
                subtitle = "中日/中英混講救星 · 假名標音 · 0.75x 跟讀",
                selected = !showFullSettingsScreen && currentMode == AppMode.TUTOR,
                accentColor = AmberTutorAccent,
                onClick = { onSelectAppMode(AppMode.TUTOR) }
            )

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.07f))
            Spacer(modifier = Modifier.height(8.dp))

            // Section 2: Quick Hubs (Notebook, Memory, Model & API Settings)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF161F30),
                    border = BorderStroke(0.5.dp, AmberTutorAccent.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onOpenNotebook() }
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "📒 複習本 (${uiState.tutorNotebook.size})",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFDE68A),
                            maxLines = 1
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF161F30),
                    border = BorderStroke(
                        0.5.dp,
                        if (hasActiveMemory) VioletAccent else Color.White.copy(alpha = 0.15f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onOpenMemoryHub() }
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (hasActiveMemory) "🧠 記憶庫 (開)" else "🧠 長期記憶",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (hasActiveMemory) Color(0xFFE9D5FF) else Color(0xFFCBD5E1),
                            maxLines = 1
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (showFullSettingsScreen) CyanAccent.copy(alpha = 0.2f) else Color(0xFF161F30),
                    border = BorderStroke(
                        0.5.dp,
                        if (showFullSettingsScreen) CyanAccent else Color.White.copy(alpha = 0.15f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onOpenSettingsScreen() }
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "⚙️ API 設定",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (showFullSettingsScreen) CyanAccent else Color(0xFFCBD5E1),
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.07f))
            Spacer(modifier = Modifier.height(8.dp))

            // Section 3: RikkaHub-style Searchable Conversation History List
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 6.dp)
            ) {
                Text(
                    text = "歷史對話紀錄 (${uiState.savedSessions.size})",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF94A3B8),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            OutlinedTextField(
                value = uiState.historySearchQuery,
                onValueChange = onSearchQueryChange,
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = CyanAccent,
                        modifier = Modifier.size(17.dp)
                    )
                },
                placeholder = {
                    Text(
                        "搜尋對話或筆記...",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF64748B)
                    )
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = Color.White),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (filteredSessions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "尚無對話紀錄",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF64748B)
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    items(filteredSessions, key = { it.id }) { session ->
                        val isCurrent = session.id == uiState.currentSession.id && !showFullSettingsScreen
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isCurrent) Color(0xFF1E293B) else Color(0xFF131B2B),
                            border = BorderStroke(
                                0.5.dp,
                                if (isCurrent) CyanAccent.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.06f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onLoadSession(session) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = session.title,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isCurrent) Color.White else Color(0xFFE2E8F0),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${sdf.format(Date(session.updatedAt))} · ${session.messages.size} 則",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF94A3B8)
                                    )
                                }
                                IconButton(
                                    onClick = { onExportSession(session) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = "複製",
                                        tint = Color(0xFF94A3B8),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { onDeleteSession(session.id) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "刪除",
                                        tint = RoseDanger.copy(alpha = 0.8f),
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerModeItem(
    title: String,
    subtitle: String,
    selected: Boolean,
    accentColor: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) accentColor.copy(alpha = 0.16f) else Color.Transparent,
        border = BorderStroke(
            width = if (selected) 1.dp else 0.dp,
            color = if (selected) accentColor.copy(alpha = 0.55f) else Color.Transparent
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) Color.White else Color(0xFFCBD5E1)
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) accentColor else Color(0xFF64748B),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LiveConversationTab(
    uiState: UiState,
    currentMode: AppMode,
    onOpenModeConfigSheet: () -> Unit,
    onOpenNotebookDialog: () -> Unit,
    onOpenWakeWordDialog: () -> Unit,
    onOpenMapPreview: (MapPreviewData) -> Unit,
    onSelectTargetLanguage: (String) -> Unit,
    onSelectSecondaryLanguage: (String) -> Unit,
    onUpdateSettings: (AppSettings) -> Unit,
    onTriggerTutorQuickAction: (String) -> Unit,
    onToggleCall: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onSendTypedMessage: (String) -> Unit,
    onSendImageMessage: (Bitmap, String) -> Unit,
    onPlayMessageVoiceClip: (String, Float) -> Unit,
    onSaveMessageToNotebook: (ChatMessage) -> Unit
) {
    val listState = rememberLazyListState()
    val context = LocalContext.current
    var typedInput by remember { mutableStateOf("") }
    var showKeyboardBar by remember { mutableStateOf(false) }

    val messages = uiState.currentSession.messages
    val savedNotebookSentences = remember(uiState.tutorNotebook) {
        uiState.tutorNotebook.mapTo(hashSetOf()) { it.aiSentence.trim() }
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length, messages.lastOrNull()?.backendThinkingText?.length) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 1. RikkaHub-Style Slim Contextual Mode Bar (Replaces the bulky 4-row stacked panels!)
        SlimContextualModeHeaderBar(
            uiState = uiState,
            currentMode = currentMode,
            onOpenModeConfigSheet = onOpenModeConfigSheet,
            onOpenNotebookDialog = onOpenNotebookDialog,
            onOpenWakeWordDialog = onOpenWakeWordDialog,
            onSelectTargetLanguage = onSelectTargetLanguage,
            onSelectSecondaryLanguage = onSelectSecondaryLanguage,
            onUpdateSettings = onUpdateSettings,
            onTriggerTutorQuickAction = onTriggerTutorQuickAction
        )

        // 2. Main Centerpiece Area: Animated 3D Voice Orb (when empty) OR Clean Chat Stream (80%+ screen space!)
        if (messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                AnimatedVoiceOrbHero(
                    uiState = uiState,
                    currentMode = currentMode,
                    onTapOrb = onToggleCall
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

        // 3. Single Clean Bottom Control Dock
        FloatingBottomControlDock(
            uiState = uiState,
            currentMode = currentMode,
            typedInput = typedInput,
            onTypedInputChange = { typedInput = it },
            showKeyboardBar = showKeyboardBar,
            onToggleKeyboardBar = { showKeyboardBar = !showKeyboardBar },
            onToggleCall = onToggleCall,
            onToggleMute = onToggleMute,
            onToggleSpeaker = onToggleSpeaker,
            onSendTypedMessage = {
                if (typedInput.isNotBlank()) {
                    onSendTypedMessage(typedInput)
                    typedInput = ""
                }
            },
            onSendImageMessage = onSendImageMessage
        )
    }
}

@Composable
private fun SlimContextualModeHeaderBar(
    uiState: UiState,
    currentMode: AppMode,
    onOpenModeConfigSheet: () -> Unit,
    onOpenNotebookDialog: () -> Unit,
    onOpenWakeWordDialog: () -> Unit,
    onSelectTargetLanguage: (String) -> Unit,
    onSelectSecondaryLanguage: (String) -> Unit,
    onUpdateSettings: (AppSettings) -> Unit,
    onTriggerTutorQuickAction: (String) -> Unit
) {
    val settings = uiState.settings
    val isConnected = uiState.connectionState != ConnectionState.DISCONNECTED

    when (currentMode) {
        AppMode.ASSISTANT -> {
            val chipScroll = rememberScrollState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(chipScroll)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Model & Mode Config Pill
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color(0xFF161F30),
                    border = BorderStroke(0.5.dp, CyanAccent.copy(alpha = 0.45f)),
                    modifier = Modifier.clickable { onOpenModeConfigSheet() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🎙️ ${settings.frontendVoiceModel} ⇄ ${settings.backendBrainModel} ▾",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE2E8F0)
                        )
                    }
                }

                // Hands-Free Wake Word + Voiceprint Quick Config Chip ("hey Luna 我需要你")
                val hasVoiceprint = settings.wakeWordVoiceprintData.isNotBlank()
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (settings.wakeWordEnabled) AmberTutorAccent.copy(alpha = 0.20f) else Color(0xFF161F30),
                    border = BorderStroke(
                        0.5.dp,
                        if (settings.wakeWordEnabled) AmberTutorAccent.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.14f)
                    ),
                    modifier = Modifier.clickable { onOpenWakeWordDialog() }
                ) {
                    Text(
                        text = when {
                            settings.wakeWordEnabled && hasVoiceprint ->
                                "🎧 聲紋喚醒: «${settings.wakeWordPhrase}»"
                            settings.wakeWordEnabled ->
                                "🎧 喚醒待機: «${settings.wakeWordPhrase}»"
                            else ->
                                "🎧 免碰喚醒: 關 (點擊設定)"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (settings.wakeWordEnabled) Color(0xFFFDE68A) else Color(0xFF94A3B8),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }

                // GPS & Google Maps Tool Quick Toggle Chip
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (settings.locationToolsEnabled) EmeraldAccent.copy(alpha = 0.18f) else Color(0xFF161F30),
                    border = BorderStroke(
                        0.5.dp,
                        if (settings.locationToolsEnabled) EmeraldAccent.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.12f)
                    ),
                    modifier = Modifier.clickable {
                        onUpdateSettings(settings.copy(locationToolsEnabled = !settings.locationToolsEnabled))
                    }
                ) {
                    Text(
                        text = if (settings.locationToolsEnabled) "📍 定位＆導航: 開" else "📍 定位＆導航: 關",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (settings.locationToolsEnabled) EmeraldAccent else Color(0xFF94A3B8),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }

                // Linkup Web Search Quick Toggle Chip
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (settings.linkupSearchEnabled) CyanAccent.copy(alpha = 0.16f) else Color(0xFF161F30),
                    border = BorderStroke(
                        0.5.dp,
                        if (settings.linkupSearchEnabled) CyanAccent.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.12f)
                    ),
                    modifier = Modifier.clickable {
                        onUpdateSettings(settings.copy(linkupSearchEnabled = !settings.linkupSearchEnabled))
                    }
                ) {
                    Text(
                        text = if (settings.linkupSearchEnabled) "🌐 Linkup 搜尋: 開" else "🌐 Linkup: 關",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (settings.linkupSearchEnabled) CyanAccent else Color(0xFF94A3B8),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }

                // Calendar Quick Toggle Chip
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (settings.calendarToolsEnabled) VioletAccent.copy(alpha = 0.18f) else Color(0xFF161F30),
                    border = BorderStroke(
                        0.5.dp,
                        if (settings.calendarToolsEnabled) VioletAccent.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.12f)
                    ),
                    modifier = Modifier.clickable {
                        onUpdateSettings(settings.copy(calendarToolsEnabled = !settings.calendarToolsEnabled))
                    }
                ) {
                    Text(
                        text = if (settings.calendarToolsEnabled) "📅 行事曆: 開" else "📅 行事曆: 關",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (settings.calendarToolsEnabled) Color(0xFFE9D5FF) else Color(0xFF94A3B8),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }

        AppMode.TRANSLATE -> {
            // Compact 1-row Fixed Dual-Language Quick Toggle (Slot A ⇄ Slot B) + [⚙️ 13國/詞庫 ▾]
            val targetLang = settings.translationTargetLanguage
            val secondaryLang = settings.translationSecondaryLanguage
            var slotALang by remember { mutableStateOf(targetLang.ifBlank { "zh" }) }
            var slotBLang by remember {
                mutableStateOf(
                    if (secondaryLang.isNotBlank() && secondaryLang != slotALang) secondaryLang
                    else if (slotALang == "zh") "ja" else "zh"
                )
            }
            LaunchedEffect(targetLang) {
                if (targetLang != slotALang && targetLang != slotBLang) {
                    slotBLang = targetLang
                    onSelectSecondaryLanguage(targetLang)
                }
            }

            val isSlotAActive = targetLang == slotALang
            val isSlotBActive = targetLang == slotBLang
            val slotALabel = AppSettings.languageLabel(slotALang).substringBefore(" (")
            val slotBLabel = AppSettings.languageLabel(slotBLang).substringBefore(" (")

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Slot A Fixed Button
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSlotAActive) EmeraldAccent.copy(alpha = 0.22f) else Color(0xFF161F30),
                    border = BorderStroke(
                        width = if (isSlotAActive) 1.dp else 0.5.dp,
                        color = if (isSlotAActive) EmeraldAccent else Color.White.copy(alpha = 0.12f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            onSelectTargetLanguage(slotALang)
                            onSelectSecondaryLanguage(slotBLang)
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = if (isSlotAActive) "🔊 翻成 $slotALabel" else "輸出 1: $slotALabel",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSlotAActive) FontWeight.ExtraBold else FontWeight.Medium,
                            color = if (isSlotAActive) Color.White else Color(0xFF94A3B8),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Slot B Fixed Button
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSlotBActive) EmeraldAccent.copy(alpha = 0.22f) else Color(0xFF161F30),
                    border = BorderStroke(
                        width = if (isSlotBActive) 1.dp else 0.5.dp,
                        color = if (isSlotBActive) EmeraldAccent else Color.White.copy(alpha = 0.12f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            onSelectTargetLanguage(slotBLang)
                            onSelectSecondaryLanguage(slotALang)
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = if (isSlotBActive) "🔊 翻成 $slotBLabel" else "輸出 2: $slotBLabel",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSlotBActive) FontWeight.ExtraBold else FontWeight.Medium,
                            color = if (isSlotBActive) Color.White else Color(0xFF94A3B8),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Open 13-Language & Glossary BottomSheet
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF161F30),
                    border = BorderStroke(0.5.dp, CyanAccent.copy(alpha = 0.45f)),
                    modifier = Modifier.clickable { onOpenModeConfigSheet() }
                ) {
                    Text(
                        text = "⚙️ 13國/詞庫 ▾",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = CyanAccent,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }
        }

        AppMode.TUTOR -> {
            val langShort = AppSettings.tutorLanguageLabel(settings.tutorTargetLanguage).substringBefore(" (")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Row 1: Compact Summary Pill (opens BottomSheet) + Notebook Button + Auto-Coach Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = AmberTutorAccent.copy(alpha = 0.16f),
                        border = BorderStroke(0.5.dp, AmberTutorAccent.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onOpenModeConfigSheet() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "$langShort · ${settings.tutorLevel.shortLabel} · ${settings.tutorScenario.shortLabel} ▾",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFDE68A),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color(0xFF161F30),
                        border = BorderStroke(0.5.dp, VioletAccent.copy(alpha = 0.5f)),
                        modifier = Modifier.clickable { onOpenNotebookDialog() }
                    ) {
                        Text(
                            text = "📒 複習本 (${uiState.tutorNotebook.size})",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE9D5FF),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }

                // Row 2 (Only when in active call): 1-Tap Live Tutor Helper Actions
                if (isConnected) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = AmberTutorAccent.copy(alpha = 0.16f),
                            border = BorderStroke(0.5.dp, AmberTutorAccent.copy(alpha = 0.45f)),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onTriggerTutorQuickAction("start_scenario") }
                        ) {
                            Text(
                                text = "🎬 AI 先開口",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFDE68A),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 5.dp)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(50),
                            color = CyanAccent.copy(alpha = 0.16f),
                            border = BorderStroke(0.5.dp, CyanAccent.copy(alpha = 0.45f)),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onTriggerTutorQuickAction("repeat_slowly") }
                        ) {
                            Text(
                                text = "🐢 慢速再說一次",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFBAE6FD),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 5.dp)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(50),
                            color = VioletAccent.copy(alpha = 0.18f),
                            border = BorderStroke(0.5.dp, VioletAccent.copy(alpha = 0.45f)),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onTriggerTutorQuickAction("give_hint") }
                        ) {
                            Text(
                                text = "💡 提示我怎麼回",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE9D5FF),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 5.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeConfigBottomSheetContent(
    uiState: UiState,
    currentMode: AppMode,
    onSelectAppMode: (AppMode) -> Unit,
    onUpdateSettings: (AppSettings) -> Unit,
    onUpdateTutorConfig: (AppSettings, Boolean) -> Unit,
    onSelectTargetLanguage: (String) -> Unit,
    onSelectSecondaryLanguage: (String) -> Unit,
    onOpenNotebook: () -> Unit,
    onOpenWakeWordDialog: () -> Unit,
    onOpenFullSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val settings = uiState.settings
    var showGlossaryDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Top Mode Segmented Switcher inside the BottomSheet
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                Triple(AppMode.ASSISTANT, "🎙️ 語音助理", CyanAccent),
                Triple(AppMode.TRANSLATE, "🌐 同步口譯", EmeraldAccent),
                Triple(AppMode.TUTOR, "🎓 外語陪練", AmberTutorAccent)
            ).forEach { (mode, label, accent) ->
                val selected = currentMode == mode
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) accent.copy(alpha = 0.22f) else Color(0xFF161F30),
                    border = BorderStroke(1.dp, if (selected) accent else Color.White.copy(alpha = 0.08f)),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelectAppMode(mode) }
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) Color.White else Color(0xFF94A3B8),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 9.dp)
                    )
                }
            }
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

        when (currentMode) {
            AppMode.ASSISTANT -> {
                Text(
                    text = "🎙️ 語音助理工具與模型快速設定",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                // GPS + Google Maps + Linkup Nearby Switch
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF161F30),
                    border = BorderStroke(0.5.dp, EmeraldAccent.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "📍 手機 GPS 定位 ＋ 周邊搜尋 ＋ Google Maps 導航",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "支援詢問「我在哪裡」、「找附近拉麵店」或「幫我開 Google Maps 導航去某地」",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        Switch(
                            checked = settings.locationToolsEnabled,
                            onCheckedChange = { onUpdateSettings(settings.copy(locationToolsEnabled = it)) }
                        )
                    }
                }

                // Hands-Free Wake Word ("hey Luna 我需要你") & Auto-Hangup Card
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF161F30),
                    border = BorderStroke(0.5.dp, AmberTutorAccent.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenWakeWordDialog() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "🎧 免碰手機語音喚醒 («${settings.wakeWordPhrase}») ＋ 聲紋辨識鎖",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFDE68A)
                            )
                            Text(
                                text = "待機 $0 API 費用 · 支援本人聲紋鎖、導航自動掛斷、說「掰掰」免手觸掛斷與四道防誤觸保護",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        Text(
                            text = "設定 ➔",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = AmberTutorAccent
                        )
                    }
                }

                // Linkup Web Search Switch
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF161F30),
                    border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.1f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "🌐 Linkup 即時網路搜尋",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "查詢最新新聞、營業時間、店家評價與即時資訊",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        Switch(
                            checked = settings.linkupSearchEnabled,
                            onCheckedChange = { onUpdateSettings(settings.copy(linkupSearchEnabled = it)) }
                        )
                    }
                }

                // Android Calendar Switch
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF161F30),
                    border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.1f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "📅 Android 手機行事曆讀寫",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "查詢行程、新增活動並可搭配 Google Maps 導航至下一個行程地點",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        Switch(
                            checked = settings.calendarToolsEnabled,
                            onCheckedChange = { onUpdateSettings(settings.copy(calendarToolsEnabled = it)) }
                        )
                    }
                }

                OutlinedButton(
                    onClick = onOpenFullSettings,
                    border = BorderStroke(1.dp, CyanAccent.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("開啟完整模型與 API 金鑰設定", color = CyanAccent, fontWeight = FontWeight.Bold)
                }
            }

            AppMode.TRANSLATE -> {
                Text(
                    text = "🌐 選擇同步口譯輸出語言 (13 國語言)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "說話端自動偵測 70+ 種語言；點選下方語言即可切換手機播出的目標語言：",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF94A3B8)
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppSettings.AVAILABLE_TRANSLATION_LANGUAGES.chunked(2).forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowItems.forEach { (code, fullLabel) ->
                                val selected = settings.translationTargetLanguage == code
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (selected) EmeraldAccent.copy(alpha = 0.22f) else Color(0xFF161F30),
                                    border = BorderStroke(
                                        1.dp,
                                        if (selected) EmeraldAccent else Color.White.copy(alpha = 0.08f)
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { onSelectTargetLanguage(code) }
                                ) {
                                    Text(
                                        text = fullLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (selected) Color.White else Color(0xFFCBD5E1),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp)
                                    )
                                }
                            }
                            if (rowItems.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                Button(
                    onClick = { showGlossaryDialog = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VioletAccent,
                        contentColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("編輯專屬詞彙庫 ＆ 口譯領域 Prompt", fontWeight = FontWeight.Bold)
                }
            }

            AppMode.TUTOR -> {
                Text(
                    text = "🎓 外語口說陪練設定",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                // 1. Target Language Grid
                Text(
                    text = "1. 選擇練習語言：",
                    style = MaterialTheme.typography.labelMedium,
                    color = AmberTutorAccent,
                    fontWeight = FontWeight.Bold
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppSettings.AVAILABLE_TUTOR_LANGUAGES.chunked(2).forEach { rowLangs ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowLangs.forEach { (code, label) ->
                                val selected = settings.tutorTargetLanguage == code
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (selected) AmberTutorAccent.copy(alpha = 0.22f) else Color(0xFF161F30),
                                    border = BorderStroke(
                                        1.dp,
                                        if (selected) AmberTutorAccent else Color.White.copy(alpha = 0.08f)
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            onUpdateTutorConfig(settings.copy(tutorTargetLanguage = code), true)
                                        }
                                ) {
                                    Text(
                                        text = label.substringBefore(" ("),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (selected) Color.White else Color(0xFFCBD5E1),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // 2. Proficiency Level
                Text(
                    text = "2. 選擇程度等級：",
                    style = MaterialTheme.typography.labelMedium,
                    color = VioletAccent,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TutorLevel.entries.forEach { level ->
                        val selected = settings.tutorLevel == level
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) VioletAccent.copy(alpha = 0.25f) else Color(0xFF161F30),
                            border = BorderStroke(
                                1.dp,
                                if (selected) VioletAccent else Color.White.copy(alpha = 0.08f)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    onUpdateTutorConfig(settings.copy(tutorLevel = level), false)
                                }
                        ) {
                            Text(
                                text = level.shortLabel,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) Color.White else Color(0xFFCBD5E1),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                // 3. Roleplay Scenario
                Text(
                    text = "3. 選擇實戰對話情境：",
                    style = MaterialTheme.typography.labelMedium,
                    color = CyanAccent,
                    fontWeight = FontWeight.Bold
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TutorScenario.entries.chunked(2).forEach { rowScenarios ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowScenarios.forEach { scenario ->
                                val selected = settings.tutorScenario == scenario
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (selected) CyanAccent.copy(alpha = 0.22f) else Color(0xFF161F30),
                                    border = BorderStroke(
                                        1.dp,
                                        if (selected) CyanAccent else Color.White.copy(alpha = 0.08f)
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            onUpdateTutorConfig(settings.copy(tutorScenario = scenario), true)
                                        }
                                ) {
                                    Text(
                                        text = scenario.shortLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (selected) Color.White else Color(0xFFCBD5E1),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                                    )
                                }
                            }
                            if (rowScenarios.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                if (settings.tutorScenario == TutorScenario.CUSTOM) {
                    OutlinedTextField(
                        value = settings.tutorCustomScenarioPrompt,
                        onValueChange = {
                            onUpdateTutorConfig(settings.copy(tutorCustomScenarioPrompt = it), false)
                        },
                        label = { Text("✨ 自訂角色與情境描述") },
                        placeholder = { Text("例如：你是日本藥妝店員，我要詢問止痛藥與退稅...") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 4. Auto-Coach Notes Toggle
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF161F30),
                    border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.1f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "📖 每回合自動附上漢字假名標音、中譯與口說糾錯",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        Switch(
                            checked = settings.tutorAutoCoachEnabled,
                            onCheckedChange = {
                                onUpdateTutorConfig(settings.copy(tutorAutoCoachEnabled = it), false)
                            }
                        )
                    }
                }
            }
        }
    }

    if (showGlossaryDialog) {
        CustomGlossaryAndPromptDialog(
            settings = settings,
            onDismiss = { showGlossaryDialog = false },
            onSave = { newGlossary, newContextPrompt, usePromptInterpreter ->
                onUpdateSettings(
                    settings.copy(
                        customGlossaryTerms = newGlossary,
                        translationContextPrompt = newContextPrompt,
                        usePromptAwareInterpreter = usePromptInterpreter
                    )
                )
                showGlossaryDialog = false
            }
        )
    }
}

@Composable
private fun TutorNotebookDialog(
    settings: AppSettings,
    entries: List<TutorNotebookEntry>,
    onDismiss: () -> Unit,
    onToggleInjectWeaknesses: (Boolean) -> Unit,
    onDeleteEntry: (String) -> Unit,
    onExportAnki: () -> Unit,
    onCopyItem: (String) -> Unit
) {
    val sdf = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF111827),
        titleContentColor = Color.White,
        textContentColor = Color(0xFFE2E8F0),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.School,
                    contentDescription = null,
                    tint = AmberTutorAccent,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "📒 外語單字＆錯題複習本 (${entries.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (settings.tutorInjectNotebookWeaknesses) AmberTutorAccent.copy(alpha = 0.16f) else Color(0xFF1E293B),
                    border = BorderStroke(
                        1.dp,
                        if (settings.tutorInjectNotebookWeaknesses) AmberTutorAccent.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.1f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleInjectWeaknesses(!settings.tutorInjectNotebookWeaknesses) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "🎯 通話時自動將複習本弱點出題考我",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "開啟後，AI 家教會在對話中自然引導你複習下方收錄過的句型與單字",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFCBD5E1)
                            )
                        }
                        Switch(
                            checked = settings.tutorInjectNotebookWeaknesses,
                            onCheckedChange = onToggleInjectWeaknesses
                        )
                    }
                }

                if (entries.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "尚未收錄任何句子。\n在外語陪練對話中，點擊 AI 氣泡解析卡上的「⭐ 收錄複習本」即可一鍵加入！",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8),
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        items(entries, key = { it.id }) { item ->
                            val langShort = AppSettings.tutorLanguageLabel(item.languageCode).substringBefore(" (")
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF1A2337),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "$langShort · ${sdf.format(Date(item.createdAt))}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = AmberTutorAccent,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(
                                            onClick = {
                                                onCopyItem("${item.aiSentence}\n\n${item.coachNotes}")
                                            },
                                            modifier = Modifier.size(26.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.ContentCopy,
                                                contentDescription = "複製",
                                                tint = Color(0xFFCBD5E1),
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = { onDeleteEntry(item.id) },
                                            modifier = Modifier.size(26.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "刪除",
                                                tint = RoseDanger,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = item.aiSentence,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    if (item.userSentence.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "🗣️ 你的原句：${item.userSentence}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF93C5FD)
                                        )
                                    }
                                    if (item.coachNotes.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = item.coachNotes,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFFCBD5E1),
                                            lineHeight = 18.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (entries.isNotEmpty()) {
                Button(
                    onClick = onExportAnki,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AmberTutorAccent,
                        contentColor = Color(0xFF06101E)
                    )
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("匯出 Anki 字卡 (TSV)", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("關閉", color = Color(0xFF94A3B8))
            }
        }
    )
}

@Composable
private fun CustomGlossaryAndPromptDialog(
    settings: AppSettings,
    onDismiss: () -> Unit,
    onSave: (String, String, Boolean) -> Unit
) {
    var glossaryText by remember { mutableStateOf(settings.customGlossaryTerms) }
    var contextPromptText by remember { mutableStateOf(settings.translationContextPrompt) }
    var usePromptInterpreter by remember { mutableStateOf(settings.usePromptAwareInterpreter) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF111827),
        titleContentColor = Color.White,
        textContentColor = Color(0xFFE2E8F0),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = CyanAccent,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "口譯與語音專屬詞彙庫 ＆ Prompt",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF1E293B).copy(alpha = 0.7f),
                    border = BorderStroke(1.dp, CyanAccent.copy(alpha = 0.3f))
                ) {
                    Text(
                        text = "💡 說明：OpenAI 官方為防止語音注入攻擊，限制 `gpt-realtime-translate` 端點不可接收 System Prompt，但本 App 支援：\n" +
                                "1️⃣ 【即時字幕詞彙自動校正】：支援 `誤聽詞=正確詞` 在所有模式下即時替換字幕。\n" +
                                "2️⃣ 【Prompt 智慧詞庫口譯模式】：開啟下方開關後，將改由 `gpt-live-1` 擔任口譯官，連同語音發音都能 100% 讀懂你的專有名詞與領域背景！",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFCBD5E1),
                        modifier = Modifier.padding(10.dp)
                    )
                }

                OutlinedTextField(
                    value = glossaryText,
                    onValueChange = { glossaryText = it },
                    label = { Text("📚 常用專有名詞 / 諧音校正表") },
                    placeholder = {
                        Text("範例：\nTSMC 台積電, CoWoS, 初音未來\n雷姆=蕾姆\n科沃斯=CoWoS\n林克阿普=Linkup")
                    },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = contextPromptText,
                    onValueChange = { contextPromptText = it },
                    label = { Text("🎯 口譯領域背景與風格提示詞 (Prompt)") },
                    placeholder = {
                        Text("範例：這是一場日本動漫／半導體技術交流，遇到日文角色名與英文技術縮寫請保留原意...")
                    },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (usePromptInterpreter) VioletAccent.copy(alpha = 0.18f) else Color(0xFF162032),
                    border = BorderStroke(
                        1.dp,
                        if (usePromptInterpreter) VioletAccent else Color.White.copy(alpha = 0.1f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { usePromptInterpreter = !usePromptInterpreter }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "啟用 Prompt 智慧詞庫口譯官 (gpt-live-1)",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = if (usePromptInterpreter) {
                                    "已開啟：語音輸出本身也會嚴格遵循上方 Prompt 與專有名詞庫（適合專有名詞較多的場合）"
                                } else {
                                    "關閉中（預設）：使用 gpt-realtime-translate 極速邊講邊翻，詞彙表用於即時字幕修正"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        Switch(
                            checked = usePromptInterpreter,
                            onCheckedChange = { usePromptInterpreter = it }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(glossaryText.trim(), contextPromptText.trim(), usePromptInterpreter)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = EmeraldAccent,
                    contentColor = Color(0xFF042F2E)
                )
            ) {
                Text("儲存套用", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = Color(0xFF94A3B8))
            }
        }
    )
}

@Composable
private fun AnimatedVoiceOrbHero(
    uiState: UiState,
    currentMode: AppMode,
    onTapOrb: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orbTransition")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbPhase"
    )
    val breathe by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orbBreathe"
    )

    val isTranslationMode = currentMode == AppMode.TRANSLATE
    val isTutorMode = currentMode == AppMode.TUTOR
    val isConnected = uiState.connectionState != ConnectionState.DISCONNECTED
    val isDelegating = uiState.connectionState == ConnectionState.DELEGATING
    val dynamicLevel = uiState.micLevel.coerceIn(0f, 1f)

    val primaryOrbColor = when {
        isDelegating -> VioletAccent
        isTutorMode && isConnected -> AmberTutorAccent
        isTranslationMode && isConnected -> EmeraldAccent
        isConnected -> CyanAccent
        isTutorMode -> AmberTutorAccent.copy(alpha = 0.82f)
        isTranslationMode -> EmeraldAccent.copy(alpha = 0.78f)
        else -> CyanAccent.copy(alpha = 0.78f)
    }

    val secondaryOrbColor = when {
        isDelegating -> Color(0xFFEC4899)
        isTutorMode -> VioletAccent
        isTranslationMode -> CyanAccent
        else -> VioletAccent
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Animated 3D-style Fluid Voice Orb
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(220.dp)
                .clip(CircleShape)
                .clickable { onTapOrb() }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val baseRadius = size.minDimension * 0.28f
                val activeBoost = if (isConnected) (1f + dynamicLevel * 0.45f) else breathe
                val r = baseRadius * activeBoost

                // Outer soft ambient glow
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            primaryOrbColor.copy(alpha = if (isConnected) 0.36f else 0.20f),
                            secondaryOrbColor.copy(alpha = 0.08f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = size.minDimension * 0.49f
                    ),
                    radius = size.minDimension * 0.49f,
                    center = center
                )

                // Orbiting fluid wave rings
                for (ring in 0..2) {
                    val ringRadius = r * (1.15f + ring * 0.16f) + sin(phase + ring * 1.2f) * 6.dp.toPx()
                    drawCircle(
                        brush = Brush.sweepGradient(
                            colors = listOf(
                                primaryOrbColor.copy(alpha = 0.55f - ring * 0.12f),
                                secondaryOrbColor.copy(alpha = 0.35f - ring * 0.08f),
                                primaryOrbColor.copy(alpha = 0.55f - ring * 0.12f)
                            ),
                            center = center
                        ),
                        radius = ringRadius,
                        center = Offset(
                            center.x + cos(phase + ring) * 4.dp.toPx(),
                            center.y + sin(phase - ring) * 4.dp.toPx()
                        ),
                        style = Stroke(width = (2.5f - ring * 0.5f).dp.toPx())
                    )
                }

                // Core luminous sphere
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.92f),
                            primaryOrbColor,
                            secondaryOrbColor.copy(alpha = 0.85f),
                            Color(0xFF0B1120)
                        ),
                        center = Offset(center.x - r * 0.22f, center.y - r * 0.22f),
                        radius = r * 1.15f
                    ),
                    radius = r,
                    center = center
                )
            }

            Icon(
                imageVector = when {
                    isTutorMode -> Icons.Default.School
                    isConnected && isTranslationMode -> Icons.Default.Translate
                    isConnected -> Icons.Default.GraphicEq
                    isTranslationMode -> Icons.Default.Translate
                    else -> Icons.Default.Mic
                },
                contentDescription = null,
                tint = Color(0xFF06101E),
                modifier = Modifier.size(38.dp)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Status Pill
        Surface(
            shape = RoundedCornerShape(50),
            color = GlassSurfaceBg,
            border = BorderStroke(1.dp, primaryOrbColor.copy(alpha = 0.4f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(primaryOrbColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = uiState.statusBanner,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFFE2E8F0),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = when {
                isTutorMode -> "🎓 雙腦 AI 外語口說陪練家教 (${AppSettings.tutorLanguageLabel(uiState.settings.tutorTargetLanguage)})"
                isTranslationMode -> "🌐 即時雙向同步口譯 (gpt-realtime-translate)"
                else -> "🎙️ 全雙工多語言語音助理 + 後端推理大腦"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        val featureSummary = when {
            isTutorMode ->
                "• 開始通話後 AI 會主動開口打招呼！不會說的單字可直接用中文混著講\n" +
                        "• 前台 (${uiState.settings.frontendVoiceModel}) 負責自然外語對答與角色扮演，絕不打斷對話節奏\n" +
                        "• 後端 (${uiState.settings.backendBrainModel}) 自動在氣泡附上「漢字平假名標音、中譯、文法微調與下一句跟讀提示」"
            isTranslationMode ->
                "• 支援中、英、日、韓、歐美與東南亞等 70+ 種語言自動聽音辨識\n" +
                        "• 邊聽邊翻無需等待回合，自動模仿說話者音色語氣輸出目標語言\n" +
                        "• 點擊上方固定雙語按鈕可在通話中一鍵切換手機播出的目標語言！"
            else ->
                "• 支援中、英、日文自然混講（自動保留日文漢字與假名、中文轉繁體）\n" +
                        "• 內建手機 GPS 定位＋Linkup 搜周邊＋一鍵喚醒手機 Google Maps 導航\n" +
                        "• 前台語音 (${uiState.settings.frontendVoiceModel}) 毫秒級回應，後端大腦 (${uiState.settings.backendBrainModel}) 推理與行事曆收折於抽屜"
        }

        Text(
            text = featureSummary,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF94A3B8),
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
    }
}

@Composable
private fun CompactVoiceOrbStatusCard(
    uiState: UiState,
    currentMode: AppMode
) {
    val infiniteTransition = rememberInfiniteTransition(label = "compactWave")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )

    val isConnected = uiState.connectionState != ConnectionState.DISCONNECTED
    val accentColor = when (uiState.connectionState) {
        ConnectionState.DISCONNECTED -> Color(0xFF64748B)
        ConnectionState.CONNECTING, ConnectionState.CLOSING -> Color(0xFFF59E0B)
        ConnectionState.DELEGATING -> VioletAccent
        ConnectionState.LIVE -> when (currentMode) {
            AppMode.TUTOR -> AmberTutorAccent
            AppMode.TRANSLATE -> EmeraldAccent
            AppMode.ASSISTANT -> CyanAccent
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF131D31).copy(alpha = 0.85f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Mini Reactive Soundwave Bars
            Canvas(
                modifier = Modifier
                    .width(28.dp)
                    .height(20.dp)
            ) {
                val barCount = 4
                val spacing = size.width / (barCount + 1)
                val level = if (isConnected) uiState.micLevel.coerceIn(0.12f, 1f) else 0.15f
                for (i in 0 until barCount) {
                    val x = spacing * (i + 1)
                    val animFactor = if (isConnected) {
                        0.35f + 0.65f * ((sin(wavePhase + i * 1.1f) + 1f) / 2f) * (0.45f + level * 0.55f)
                    } else {
                        0.25f
                    }
                    val barH = (size.height * animFactor).coerceAtLeast(4.dp.toPx())
                    val topY = (size.height - barH) / 2f
                    drawLine(
                        color = accentColor,
                        start = Offset(x, topY),
                        end = Offset(x, topY + barH),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Text(
                text = uiState.statusBanner,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = Color(0xFFE2E8F0),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (uiState.voiceUsageSeconds > 0) {
                Spacer(modifier = Modifier.width(8.dp))
                val mins = uiState.voiceUsageSeconds / 60
                val secs = uiState.voiceUsageSeconds % 60
                Surface(
                    shape = RoundedCornerShape(50),
                    color = accentColor.copy(alpha = 0.16f)
                ) {
                    Text(
                        text = String.format(Locale.US, "%02d:%02d", mins, secs),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun FloatingBottomControlDock(
    uiState: UiState,
    currentMode: AppMode,
    typedInput: String,
    onTypedInputChange: (String) -> Unit,
    showKeyboardBar: Boolean,
    onToggleKeyboardBar: () -> Unit,
    onToggleCall: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onSendTypedMessage: () -> Unit,
    onSendImageMessage: (Bitmap, String) -> Unit
) {
    val isConnected = uiState.connectionState != ConnectionState.DISCONNECTED
    val isTranslationMode = currentMode == AppMode.TRANSLATE
    val isTutorMode = currentMode == AppMode.TUTOR
    var showVisionDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        // Collapsible sleek keyboard input pill bar
        AnimatedVisibility(
            visible = showKeyboardBar && !isTranslationMode,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = Color(0xFF151F32).copy(alpha = 0.95f),
                border = BorderStroke(1.dp, CyanAccent.copy(alpha = 0.35f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    TextField(
                        value = typedInput,
                        onValueChange = onTypedInputChange,
                        placeholder = {
                            Text(
                                "輸入文字補充關鍵字或離線提問...",
                                color = Color(0xFF64748B),
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    FilledIconButton(
                        onClick = onSendTypedMessage,
                        enabled = typedInput.isNotBlank(),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = CyanAccent,
                            contentColor = Color(0xFF06101E)
                        ),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            Icons.Default.ArrowUpward,
                            contentDescription = "發送文字",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // Main Floating Glass Pill Dock
        Surface(
            shape = RoundedCornerShape(36.dp),
            color = Color(0xFF11192B).copy(alpha = 0.94f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.09f)),
            shadowElevation = 12.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left side: Camera/Photo Vision Button + Keyboard Toggle
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 📷 Multimodal Vision (Camera / Gallery) Button — active in all 3 modes
                    Surface(
                        shape = CircleShape,
                        color = if (uiState.isAnalyzingImage) AmberTutorAccent.copy(alpha = 0.25f) else Color(0xFF1E293B),
                        border = BorderStroke(
                            1.dp,
                            if (uiState.isAnalyzingImage) AmberTutorAccent else VioletAccent.copy(alpha = 0.45f)
                        ),
                        modifier = Modifier
                            .size(40.dp)
                            .clickable { showVisionDialog = true }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (uiState.isAnalyzingImage) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = AmberTutorAccent
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.PhotoCamera,
                                    contentDescription = "拍照或上傳圖片解析",
                                    tint = Color(0xFFE9D5FF),
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }
                    }

                    if (!isTranslationMode) {
                        Surface(
                            shape = CircleShape,
                            color = if (showKeyboardBar) CyanAccent.copy(alpha = 0.22f) else Color(0xFF1E293B),
                            border = BorderStroke(
                                1.dp,
                                if (showKeyboardBar) CyanAccent else Color.White.copy(alpha = 0.08f)
                            ),
                            modifier = Modifier
                                .size(40.dp)
                                .clickable { onToggleKeyboardBar() }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Keyboard,
                                    contentDescription = "文字輸入",
                                    tint = if (showKeyboardBar) CyanAccent else Color(0xFFCBD5E1),
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }
                    }
                }

                // Centerpiece: Glowing Call / Interpret / Tutor Pill Button
                val mainBtnBrush = when {
                    isConnected -> Brush.horizontalGradient(listOf(RoseDanger, Color(0xFFE11D48)))
                    isTutorMode -> Brush.horizontalGradient(listOf(AmberTutorAccent, VioletAccent))
                    isTranslationMode -> Brush.horizontalGradient(listOf(EmeraldAccent, CyanAccent))
                    else -> Brush.horizontalGradient(listOf(CyanAccent, Color(0xFF6366F1)))
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .background(mainBtnBrush)
                        .clickable { onToggleCall() }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = when {
                                isConnected -> Icons.Default.CallEnd
                                isTutorMode -> Icons.Default.School
                                isTranslationMode -> Icons.Default.Translate
                                else -> Icons.Default.GraphicEq
                            },
                            contentDescription = null,
                            tint = if (isConnected) Color.White else Color(0xFF04101E),
                            modifier = Modifier.size(19.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when {
                                isConnected && isTutorMode -> "結束外語陪練"
                                isConnected && isTranslationMode -> "停止同步口譯"
                                isConnected -> "結束語音通話"
                                isTutorMode -> "開始外語陪練"
                                isTranslationMode -> "開始同步口譯"
                                else -> "開始全雙工對話"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (isConnected) Color.White else Color(0xFF04101E)
                        )
                    }
                }

                // Right side: Mute Button + Speakerphone / Earpiece Toggle
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Mute Circular Button
                    Surface(
                        shape = CircleShape,
                        color = if (uiState.isMuted) RoseDanger.copy(alpha = 0.22f) else Color(0xFF1E293B),
                        border = BorderStroke(
                            1.dp,
                            if (uiState.isMuted) RoseDanger else Color.White.copy(alpha = 0.08f)
                        ),
                        modifier = Modifier
                            .size(40.dp)
                            .clickable(enabled = isConnected) { onToggleMute() }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (uiState.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                contentDescription = "靜音切換",
                                tint = when {
                                    !isConnected -> Color(0xFF475569)
                                    uiState.isMuted -> RoseDanger
                                    else -> Color(0xFFCBD5E1)
                                },
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }

                    // Speakerphone Circular Button
                    Surface(
                        shape = CircleShape,
                        color = if (uiState.isSpeakerphoneOn) CyanAccent.copy(alpha = 0.18f) else Color(0xFF1E293B),
                        border = BorderStroke(
                            1.dp,
                            if (uiState.isSpeakerphoneOn) CyanAccent.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.08f)
                        ),
                        modifier = Modifier
                            .size(40.dp)
                            .clickable { onToggleSpeaker() }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (uiState.isSpeakerphoneOn) Icons.Default.VolumeUp else Icons.Default.PhoneInTalk,
                                contentDescription = if (uiState.isSpeakerphoneOn) "擴音" else "聽筒",
                                tint = if (uiState.isSpeakerphoneOn) CyanAccent else Color(0xFFCBD5E1),
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showVisionDialog) {
        VisionInputDialog(
            currentMode = currentMode,
            settings = uiState.settings,
            onDismiss = { showVisionDialog = false },
            onSendImage = { bmp, prompt ->
                showVisionDialog = false
                onSendImageMessage(bmp, prompt)
            }
        )
    }
}

@Composable
private fun VisionInputDialog(
    currentMode: AppMode,
    settings: AppSettings,
    onDismiss: () -> Unit,
    onSendImage: (Bitmap, String) -> Unit
) {
    val context = LocalContext.current
    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val defaultPrompt = remember(currentMode, settings.tutorTargetLanguage, settings.translationTargetLanguage) {
        when (currentMode) {
            AppMode.TUTOR -> {
                val lang = AppSettings.tutorLanguageLabel(settings.tutorTargetLanguage).substringBefore(" (")
                "請幫我辨識這張照片中的外文內容（附上假名標音與中文解釋），並用「$lang」扮演情境角色與我根據這張圖進行口說互動！"
            }
            AppMode.TRANSLATE -> {
                val outLang = AppSettings.languageLabel(settings.translationTargetLanguage).substringBefore(" (")
                "請完整辨識並將這張圖片上的所有文字翻譯成「$outLang」，同時將重點唸給我聽。"
            }
            AppMode.ASSISTANT -> {
                "請幫我詳細解析這張照片的內容；如果圖中包含活動日期或時間，請詢問我或直接幫我加入手機行事曆。"
            }
        }
    }
    var customPrompt by remember(defaultPrompt) { mutableStateOf(defaultPrompt) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bmp: Bitmap? ->
        if (bmp != null) {
            selectedBitmap = bmp
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            }.getOrNull()?.let { bmp ->
                selectedBitmap = bmp
            } ?: Toast.makeText(context, "無法讀取所選圖片", Toast.LENGTH_SHORT).show()
        }
    }

    val quickPrompts = remember(currentMode) {
        when (currentMode) {
            AppMode.TUTOR -> listOf(
                "🍱 看菜單用外語練習點餐",
                "📖 幫我標註圖上日文漢字假名與文法",
                "🗣️ 根據照片場景考我 3 個外語問題"
            )
            AppMode.TRANSLATE -> listOf(
                "🌐 完整翻譯圖片文字並語音朗讀",
                "🍽️ 翻譯這份菜單並解釋特色餐點",
                "⚠️ 翻譯路標/告示牌的注意事項"
            )
            AppMode.ASSISTANT -> listOf(
                "🔍 解析照片內容並連網查相關資訊",
                "📅 辨識海報/傳單上的活動時間並加入行事曆",
                "🧾 整理收據/文件重點表格"
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF111827),
        titleContentColor = Color.White,
        textContentColor = Color(0xFFE2E8F0),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.PhotoCamera,
                    contentDescription = null,
                    tint = CyanAccent,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "📷 多模態視覺輸入 (拍照 / 相簿選圖)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "拍下日本菜單、路標、講義或活動海報，後端大腦將立即進行視覺解析，若在通話中更會直接由 AI 語音與你互動！",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { cameraLauncher.launch(null) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyanAccent,
                            contentColor = Color(0xFF06101E)
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("拍照", fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { galleryLauncher.launch("image/*") },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = VioletAccent,
                            contentColor = Color.White
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("相簿選圖", fontWeight = FontWeight.Bold)
                    }
                }

                selectedBitmap?.let { bmp ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF090E1A),
                        border = BorderStroke(1.dp, CyanAccent.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(170.dp)
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "已選擇的圖片預覽",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // Quick prompt chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    quickPrompts.forEach { preset ->
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color(0xFF1E293B),
                            border = BorderStroke(1.dp, CyanAccent.copy(alpha = 0.35f)),
                            modifier = Modifier.clickable { customPrompt = preset }
                        ) {
                            Text(
                                text = preset,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFBAE6FD),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = customPrompt,
                    onValueChange = { customPrompt = it },
                    label = { Text("想讓 AI 針對這張圖做什麼？") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val bmp = selectedBitmap
                    if (bmp != null) {
                        onSendImage(bmp, customPrompt.trim())
                    }
                },
                enabled = selectedBitmap != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = EmeraldAccent,
                    contentColor = Color(0xFF042F2E)
                )
            ) {
                Text("🚀 傳送圖片並解析", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = Color(0xFF94A3B8))
            }
        }
    )
}

@Composable
private fun ChatMessageItem(
    message: ChatMessage,
    isSavedInNotebook: Boolean,
    onCopyText: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenMapPreview: (MapPreviewData) -> Unit,
    onPlayVoiceClip: (String, Float) -> Unit,
    onSaveToNotebook: () -> Unit
) {
    val timeStr = remember(message.timestampMs) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(message.timestampMs))
    }

    val isUser = message.role == MessageRole.USER
    val isTranslationBubble = message.modelTag.contains("口譯")
    val isTutorBubble = message.modelTag.contains("家教") || message.backendModelTag.contains("家教")
    val isLegacyCollapsibleNote = message.role == MessageRole.BACKEND_BRAIN || message.role == MessageRole.WEB_SEARCH
    // Auto-expand the Tutor Coach card so the user immediately sees Furigana readings, translation & next reply hints!
    var isThinkingExpanded by remember(message.id, isTutorBubble) { mutableStateOf(isTutorBubble) }

    val attachedImageBitmap = remember(message.imageLocalPath) {
        val path = message.imageLocalPath
        if (!path.isNullOrBlank()) {
            runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
        } else {
            null
        }
    }

    val roleLabel = when {
        isUser -> when {
            message.modelTag.contains("視覺") -> "📷 你的拍照/圖片提問"
            message.modelTag.contains("原音") -> "🎙️ 原音辨識"
            message.modelTag.contains("口說") -> "🗣️ 你的口說練習"
            else -> "🗣️ 你"
        }
        isTranslationBubble -> message.modelTag
        isTutorBubble && message.role == MessageRole.ASSISTANT_VOICE -> message.modelTag
        message.role == MessageRole.ASSISTANT_VOICE -> "🎧 ${message.modelTag.ifBlank { "AI 語音" }}"
        message.role == MessageRole.BACKEND_BRAIN -> "🧠 後端模型重點筆記 (${message.modelTag})"
        message.role == MessageRole.WEB_SEARCH -> "🌐 Linkup 網路搜尋 (${message.sources.size} 筆)"
        else -> "⚙️ 系統紀錄"
    }

    val roleAccentColor = when {
        isUser -> Color(0xFF93C5FD)
        isTutorBubble -> AmberTutorAccent
        isTranslationBubble -> EmeraldAccent
        message.role == MessageRole.ASSISTANT_VOICE -> CyanAccent
        else -> VioletAccent
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        val bubbleShape = RoundedCornerShape(
            topStart = 20.dp,
            topEnd = 20.dp,
            bottomStart = if (isUser) 20.dp else 6.dp,
            bottomEnd = if (isUser) 6.dp else 20.dp
        )

        Box(
            modifier = Modifier
                .widthIn(min = 130.dp, max = if (isUser) 325.dp else 365.dp)
                .clip(bubbleShape)
                .background(
                    if (isUser) {
                        UserBubbleGradient
                    } else {
                        Brush.verticalGradient(
                            listOf(
                                AssistantCardBg,
                                Color(0xFF0F172A).copy(alpha = 0.92f)
                            )
                        )
                    }
                )
                .border(
                    width = 1.dp,
                    brush = if (isUser) {
                        Brush.linearGradient(
                            listOf(Color(0xFF60A5FA).copy(alpha = 0.45f), Color.Transparent)
                        )
                    } else if (isTutorBubble) {
                        Brush.linearGradient(
                            listOf(AmberTutorAccent.copy(alpha = 0.50f), VioletAccent.copy(alpha = 0.25f))
                        )
                    } else if (isTranslationBubble) {
                        Brush.linearGradient(
                            listOf(EmeraldAccent.copy(alpha = 0.45f), CyanAccent.copy(alpha = 0.15f))
                        )
                    } else {
                        Brush.linearGradient(
                            listOf(CyanAccent.copy(alpha = 0.30f), VioletAccent.copy(alpha = 0.18f))
                        )
                    },
                    shape = bubbleShape
                )
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Column {
                // Header row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = roleLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = roleAccentColor,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = timeStr,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.50f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "複製",
                        tint = Color.White.copy(alpha = 0.65f),
                        modifier = Modifier
                            .size(15.dp)
                            .clickable {
                                val fullCopy = buildString {
                                    if (message.text.isNotBlank()) appendLine(message.text)
                                    if (message.backendThinkingText.isNotBlank()) {
                                        appendLine("\n--- 後端重點詳情 (${message.backendModelTag}) ---")
                                        appendLine(message.backendThinkingText)
                                    }
                                }.trim()
                                onCopyText(fullCopy)
                            }
                    )
                }

                // Attached Image Thumbnail (if present)
                if (attachedImageBitmap != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF090E1A),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 190.dp)
                    ) {
                        Image(
                            bitmap = attachedImageBitmap.asImageBitmap(),
                            contentDescription = "上傳圖片",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // Embedded Perplexity / DeepSeek R1 style "Thinking Mode" or "Language Tutor Coach" collapsible drawer
                if (message.hasThinkingDetails) {
                    Spacer(modifier = Modifier.height(8.dp))

                    val summaryBadges = buildList {
                        if (message.backendThinkingText.isNotBlank()) {
                            if (isTutorBubble) {
                                add("📖 假名標音・中譯・口說糾錯・下一句提示")
                            } else {
                                add("🧠 ${message.backendModelTag.ifBlank { "後端推理" }}")
                            }
                        }
                        if (message.searchQueriesAndSummaries.isNotEmpty() || message.sources.isNotEmpty()) {
                            val count = maxOf(message.searchQueriesAndSummaries.size, 1)
                            add("🌐 Linkup ($count)")
                        }
                        if (message.calendarActionLogs.isNotEmpty()) {
                            add("📅 行事曆 (${message.calendarActionLogs.size})")
                        }
                    }.joinToString(" · ")

                    val drawerAccent = if (isTutorBubble) AmberTutorAccent else VioletAccent

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF090E1A).copy(alpha = 0.78f),
                        border = BorderStroke(1.dp, drawerAccent.copy(alpha = 0.42f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isThinkingExpanded = !isThinkingExpanded }
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = if (isTutorBubble) Icons.Default.School else Icons.Default.Psychology,
                                    contentDescription = null,
                                    tint = drawerAccent,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = summaryBadges.ifBlank { "思考過程與背景重點" },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isTutorBubble) Color(0xFFFDE68A) else Color(0xFFE9D5FF),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isThinkingExpanded) "收合 ▲" else "展開詳情 ▼",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = drawerAccent
                                )
                            }

                            AnimatedVisibility(visible = isThinkingExpanded) {
                                Column {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                                    // 1. Linkup Search Queries & Summaries
                                    if (message.searchQueriesAndSummaries.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "🌐 Linkup 網路檢索摘要",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = CyanAccent
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        message.searchQueriesAndSummaries.forEach { searchItem ->
                                            Text(
                                                text = searchItem,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFFCBD5E1),
                                                modifier = Modifier.padding(bottom = 6.dp)
                                            )
                                        }
                                    }

                                    // 2. Web Sources
                                    if (message.sources.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "🔗 參考來源連結 (點擊開啟):",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFE2E8F0)
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        message.sources.take(6).forEachIndexed { idx, src ->
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = Color(0xFF1E293B).copy(alpha = 0.7f),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 2.dp)
                                                    .clickable {
                                                        if (src.url.isNotBlank()) onOpenUrl(src.url)
                                                    }
                                            ) {
                                                Text(
                                                    text = "${idx + 1}. ${src.title} (${src.url})",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = CyanAccent,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                    }

                                    // 3. Calendar Actions
                                    if (message.calendarActionLogs.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "📅 行事曆執行紀錄",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = EmeraldAccent
                                        )
                                        message.calendarActionLogs.forEach { calLog ->
                                            Text(
                                                text = calLog,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFFCBD5E1),
                                                modifier = Modifier.padding(vertical = 3.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                    }

                                    // 4. Backend Brain Detailed Notes + 1-Tap Save to Tutor Notebook
                                    if (message.backendThinkingText.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = "🧠 後端重點筆記 (${message.backendModelTag.ifBlank { "Backend LLM" }})",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = VioletAccent,
                                                modifier = Modifier.weight(1f)
                                            )
                                            if (isTutorBubble) {
                                                Surface(
                                                    shape = RoundedCornerShape(50),
                                                    color = if (isSavedInNotebook) EmeraldAccent.copy(alpha = 0.22f) else AmberTutorAccent.copy(alpha = 0.22f),
                                                    border = BorderStroke(
                                                        1.dp,
                                                        if (isSavedInNotebook) EmeraldAccent else AmberTutorAccent
                                                    ),
                                                    modifier = Modifier.clickable { onSaveToNotebook() }
                                                ) {
                                                    Text(
                                                        text = if (isSavedInNotebook) "✅ 已收錄複習本" else "⭐ 收錄複習本",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (isSavedInNotebook) EmeraldAccent else Color(0xFFFDE68A),
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(8.dp))
                                            }
                                            Text(
                                                text = "複製筆記",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = CyanAccent,
                                                modifier = Modifier.clickable {
                                                    onCopyText(message.backendThinkingText)
                                                }
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = message.backendThinkingText.trim(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFFE2E8F0),
                                            lineHeight = 19.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Main Unbroken Spoken / Translated Transcript
                if (message.text.isNotBlank()) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFF8FAFC),
                        lineHeight = 22.sp,
                        maxLines = if (isLegacyCollapsibleNote && !isThinkingExpanded) 3 else Int.MAX_VALUE,
                        overflow = TextOverflow.Ellipsis
                    )
                } else if (message.hasThinkingDetails) {
                    Text(
                        text = "（後端大腦處理中，語音對話保持順暢連線...）",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                }

                // Prominent Google Maps Navigation / In-App Map Preview Action Cards (Always visible right on the bubble!)
                val mapSources = remember(message.sources) {
                    message.sources.filter { src ->
                        src.url.contains("google.com/maps") ||
                                src.url.contains("maps.app.goo.gl") ||
                                src.snippet.startsWith("MAP_PREVIEW|") ||
                                src.title.startsWith("🗺️") ||
                                src.title.startsWith("📍")
                    }
                }
                if (mapSources.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        mapSources.take(4).forEach { mapSrc ->
                            val previewData = remember(mapSrc) {
                                parseMapPreviewSnippet(mapSrc.snippet, mapSrc.title, mapSrc.url)
                            }
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF0E1B2E).copy(alpha = 0.92f),
                                border = BorderStroke(1.dp, EmeraldAccent.copy(alpha = 0.65f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (previewData != null) {
                                            onOpenMapPreview(previewData)
                                        } else if (mapSrc.url.isNotBlank()) {
                                            onOpenUrl(mapSrc.url)
                                        }
                                    }
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Place,
                                            contentDescription = "地點與導航",
                                            tint = EmeraldAccent,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = mapSrc.title.ifBlank { "🗺️ 開啟 Google Maps 導航" },
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFD1FAE5),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }

                                    if (previewData != null && previewData.address.isNotBlank()) {
                                        Text(
                                            text = previewData.address,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF94A3B8),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 2.dp, start = 24.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        if (previewData != null) {
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = CyanAccent.copy(alpha = 0.18f),
                                                border = BorderStroke(0.5.dp, CyanAccent.copy(alpha = 0.6f)),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clickable { onOpenMapPreview(previewData) }
                                            ) {
                                                Text(
                                                    text = "🗺️ App 內看地圖",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFFBAE6FD),
                                                    textAlign = TextAlign.Center,
                                                    modifier = Modifier.padding(vertical = 6.dp)
                                                )
                                            }
                                        }

                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = EmeraldAccent.copy(alpha = 0.22f),
                                            border = BorderStroke(0.5.dp, EmeraldAccent.copy(alpha = 0.7f)),
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable {
                                                    if (mapSrc.url.isNotBlank()) {
                                                        onOpenUrl(mapSrc.url)
                                                    }
                                                }
                                        ) {
                                            Text(
                                                text = "🚀 直接導航 ➔",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = EmeraldAccent,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.padding(vertical = 6.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 5. Audio Replay & 0.75x Slow Shadowing Bar (for AI voice turns with saved PCM audio) + Save to Notebook shortcut
                val clipPath = message.audioClipPath
                if (message.role == MessageRole.ASSISTANT_VOICE && (!clipPath.isNullOrBlank() || isTutorBubble)) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!clipPath.isNullOrBlank()) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = CyanAccent.copy(alpha = 0.16f),
                                border = BorderStroke(1.dp, CyanAccent.copy(alpha = 0.45f)),
                                modifier = Modifier.clickable { onPlayVoiceClip(clipPath, 1.0f) }
                            ) {
                                Text(
                                    text = "🔊 原音重播",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFBAE6FD),
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(50),
                                color = VioletAccent.copy(alpha = 0.18f),
                                border = BorderStroke(1.dp, VioletAccent.copy(alpha = 0.45f)),
                                modifier = Modifier.clickable { onPlayVoiceClip(clipPath, 0.75f) }
                            ) {
                                Text(
                                    text = "🐢 0.75x 跟讀",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFE9D5FF),
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                                )
                            }
                        }

                        if (isTutorBubble && message.text.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (isSavedInNotebook) EmeraldAccent.copy(alpha = 0.18f) else AmberTutorAccent.copy(alpha = 0.18f),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSavedInNotebook) EmeraldAccent.copy(alpha = 0.5f) else AmberTutorAccent.copy(alpha = 0.5f)
                                ),
                                modifier = Modifier.clickable { onSaveToNotebook() }
                            ) {
                                Text(
                                    text = if (isSavedInNotebook) "✅ 已收錄" else "⭐ 收錄複習",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSavedInNotebook) EmeraldAccent else Color(0xFFFDE68A),
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                                )
                            }
                        }
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
                        s.messages.any { m ->
                            m.text.lowercase().contains(query) ||
                                    m.backendThinkingText.lowercase().contains(query)
                        }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp)
    ) {
        OutlinedTextField(
            value = uiState.historySearchQuery,
            onValueChange = onSearchQueryChange,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = CyanAccent) },
            placeholder = { Text("搜尋歷史對話、口譯紀錄、後端筆記或 Linkup 來源...") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (filteredSessions.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "尚無符合的對話紀錄。\n開始語音對話或同步口譯後，所有逐字稿與後端筆記都會自動保存在這裡。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF94A3B8),
                    textAlign = TextAlign.Center
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

                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = AssistantCardBg,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
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
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "${sdf.format(Date(session.updatedAt))} • ${session.frontendModel} ➔ ${session.backendModel} • ${session.messages.size} 則訊息",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CyanAccent
                                    )
                                }
                                IconButton(onClick = { onExportSession(session) }) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = "複製 Markdown",
                                        tint = Color(0xFFCBD5E1)
                                    )
                                }
                                IconButton(onClick = { onDeleteSession(session.id) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "刪除",
                                        tint = RoseDanger
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
                                    color = Color(0xFF94A3B8)
                                )
                            }

                            if (expanded) {
                                Spacer(modifier = Modifier.height(8.dp))
                                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                                Spacer(modifier = Modifier.height(8.dp))
                                session.messages.forEach { m ->
                                    val prefix = when (m.role) {
                                        MessageRole.USER -> "🗣️ 你: "
                                        MessageRole.ASSISTANT_VOICE -> "🎧 語音/口譯: "
                                        MessageRole.BACKEND_BRAIN -> "🧠 後端詳情: "
                                        MessageRole.WEB_SEARCH -> "🌐 Linkup: "
                                        MessageRole.SYSTEM -> "⚙️ 系統: "
                                    }
                                    Text(
                                        text = prefix + m.text,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFFE2E8F0),
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
                                Button(
                                    onClick = { onLoadSession(session) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = CyanAccent,
                                        contentColor = Color(0xFF06101E)
                                    )
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("載入並接續對話", fontWeight = FontWeight.Bold)
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
    onFetchModels: () -> Unit,
    onOpenMemoryHub: () -> Unit,
    onOpenWakeWordDialog: () -> Unit
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
        // 0. Cross-Session Long-Term Memory Hub Card
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = AssistantCardBg,
            border = BorderStroke(1.dp, VioletAccent.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Psychology,
                        contentDescription = null,
                        tint = VioletAccent,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "🧠 跨對話長期記憶庫 (Long-Term Memory)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "讓 AI 跨對話記住你的背景、語言程度、旅遊計畫與個人偏好",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                    Switch(
                        checked = s.longTermMemoryEnabled,
                        onCheckedChange = { onUpdateSettings(s.copy(longTermMemoryEnabled = it)) }
                    )
                }

                if (s.userMemoryProfile.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF090E1A).copy(alpha = 0.7f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = s.userMemoryProfile,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFE2E8F0),
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                Button(
                    onClick = onOpenMemoryHub,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VioletAccent,
                        contentColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("開啟長期記憶庫管理 / 從對話自動萃取記憶", fontWeight = FontWeight.Bold)
                }
            }
        }

        // 1. API Keys Card
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = AssistantCardBg,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
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
                        color = Color.White,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { showKeys = !showKeys }) {
                        Text(if (showKeys) "隱藏金鑰" else "顯示金鑰", color = CyanAccent)
                    }
                }

                val visualTrans = if (showKeys) VisualTransformation.None else PasswordVisualTransformation()

                OutlinedTextField(
                    value = s.openAiApiKey,
                    onValueChange = { onUpdateSettings(s.copy(openAiApiKey = it)) },
                    label = { Text("OpenAI API Key (支援 gpt-live-1 / gpt-realtime-translate / gpt-6-luna)") },
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

                OutlinedTextField(
                    value = s.googleMapsApiKey,
                    onValueChange = { onUpdateSettings(s.copy(googleMapsApiKey = it)) },
                    label = { Text("Google Maps Platform API Key (選填，啟用 Places API 精準星等/營業狀態/座標)") },
                    placeholder = { Text("AIza... (每月享免費額度，未填則自動使用手機 GPS + Linkup 搜尋)") },
                    singleLine = true,
                    visualTransformation = visualTrans,
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = onFetchModels,
                    enabled = !uiState.isFetchingModels,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyanAccent,
                        contentColor = Color(0xFF06101E)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (uiState.isFetchingModels) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF06101E)
                        )
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("從 API 自動 Fetch 適用模型清單 (/v1/models)", fontWeight = FontWeight.Bold)
                }

                if (uiState.modelFetchStatus.isNotBlank()) {
                    Text(
                        text = uiState.modelFetchStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = CyanAccent
                    )
                }
            }
        }

        // 2. Frontend Voice Model, Translation Language & Backend Brain Model Selection
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = AssistantCardBg,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "🧠 2. 語音模型、同步口譯語言 ＆ 後端大腦設定",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                // Protocol selector
                DropdownSelectorField(
                    label = "語音連線協定 (Live Protocol)",
                    selectedValue = s.protocol.label,
                    options = LiveProtocol.entries.map { it.label },
                    onOptionSelected = { label ->
                        val picked = LiveProtocol.entries.firstOrNull { it.label == label } ?: LiveProtocol.OPENAI_LIVE
                        val autoModel = if (picked == LiveProtocol.OPENAI_TRANSLATE) {
                            "gpt-realtime-translate"
                        } else if (s.frontendVoiceModel.contains("translate")) {
                            "gpt-live-1"
                        } else {
                            s.frontendVoiceModel
                        }
                        val autoAppMode = when {
                            picked == LiveProtocol.OPENAI_TRANSLATE -> AppMode.TRANSLATE
                            s.appMode == AppMode.TRANSLATE -> AppMode.ASSISTANT
                            else -> s.appMode
                        }
                        onUpdateSettings(s.copy(protocol = picked, frontendVoiceModel = autoModel, appMode = autoAppMode))
                    }
                )

                // Frontend Live Voice Model (Dropdown + Editable)
                EditableModelDropdownField(
                    label = "前端語音/口譯模型 (Speech-to-Speech Model)",
                    value = s.frontendVoiceModel,
                    options = s.fetchedVoiceModels,
                    onValueChange = { modelId ->
                        val autoProtocol = when {
                            modelId.lowercase().contains("translate") -> LiveProtocol.OPENAI_TRANSLATE
                            modelId.lowercase().startsWith("grok") -> LiveProtocol.XAI_REALTIME
                            modelId.lowercase().startsWith("gpt-live") -> LiveProtocol.OPENAI_LIVE
                            else -> s.protocol
                        }
                        val autoAppMode = when {
                            autoProtocol == LiveProtocol.OPENAI_TRANSLATE -> AppMode.TRANSLATE
                            s.appMode == AppMode.TRANSLATE -> AppMode.ASSISTANT
                            else -> s.appMode
                        }
                        onUpdateSettings(s.copy(frontendVoiceModel = modelId, protocol = autoProtocol, appMode = autoAppMode))
                    }
                )

                // Translation Primary & Secondary Language Selectors
                DropdownSelectorField(
                    label = "🌐 同步口譯主要輸出語言 (Target Language A)",
                    selectedValue = AppSettings.languageLabel(s.translationTargetLanguage),
                    options = AppSettings.AVAILABLE_TRANSLATION_LANGUAGES.map { "${it.first} — ${it.second}" },
                    onOptionSelected = { item ->
                        val code = item.substringBefore(" — ").trim()
                        onUpdateSettings(s.copy(translationTargetLanguage = code))
                    }
                )

                DropdownSelectorField(
                    label = "⇄ 雙向口譯快速切換第二語言 (Language B)",
                    selectedValue = AppSettings.languageLabel(s.translationSecondaryLanguage),
                    options = AppSettings.AVAILABLE_TRANSLATION_LANGUAGES.map { "${it.first} — ${it.second}" },
                    onOptionSelected = { item ->
                        val code = item.substringBefore(" — ").trim()
                        onUpdateSettings(s.copy(translationSecondaryLanguage = code))
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
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = AssistantCardBg,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "🌐 3. 手機 GPS 定位、Google Maps 導航、Linkup 搜尋 ＆ 行事曆",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("📍 啟用手機 GPS 定位、周邊搜尋與 Google Maps 導航", fontWeight = FontWeight.Medium, color = Color.White)
                        Text(
                            "免付費 API Key！自動讀取手機定位，結合 Linkup 搜尋附近餐廳/景點，並可直接喚醒手機 Google Maps App 導航",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                    Switch(
                        checked = s.locationToolsEnabled,
                        onCheckedChange = { onUpdateSettings(s.copy(locationToolsEnabled = it)) }
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("🧭 啟動 Google Maps 導航後自動結束語音通話", fontWeight = FontWeight.Medium, color = Color.White)
                        Text(
                            "開啟後，當 AI 自動為你跳轉 Google Maps 開始導航時，會在講完確認語後（約 2.8 秒）自動掛斷 Live Chat，避免與導航語音搶麥克風或浪費額度",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                    Switch(
                        checked = s.autoEndCallOnNavigation,
                        onCheckedChange = { onUpdateSettings(s.copy(autoEndCallOnNavigation = it)) }
                    )
                }

                Button(
                    onClick = onOpenWakeWordDialog,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AmberTutorAccent,
                        contentColor = Color(0xFF06101E)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "🎧 設定免碰語音喚醒 («${s.wakeWordPhrase}») ＆ 本人聲紋辨識鎖",
                        fontWeight = FontWeight.Bold
                    )
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("啟用 Linkup Web Search 工具", fontWeight = FontWeight.Medium, color = Color.White)
                        Text(
                            "後端模型可自動呼叫 api.linkup.so/v1/search 並將來源網址存入思考抽屜",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8)
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

                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("📅 啟用手機行事曆讀寫與修改工具", fontWeight = FontWeight.Medium, color = Color.White)
                        Text(
                            "允許後端模型查詢、新增、修改與刪除 Android 手機行事曆行程",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                    Switch(
                        checked = s.calendarToolsEnabled,
                        onCheckedChange = { onUpdateSettings(s.copy(calendarToolsEnabled = it)) }
                    )
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("同時啟用 OpenAI 內建 web_search", fontWeight = FontWeight.Medium, color = Color.White)
                        Text(
                            "僅適用於 OpenAI Responses 委派模式",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8)
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
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = AssistantCardBg,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
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
                        color = Color.White,
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
                        Text("還原預設 Prompt", color = CyanAccent)
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

@Composable
private fun LongTermMemoryDialog(
    settings: AppSettings,
    isExtracting: Boolean,
    onDismiss: () -> Unit,
    onExtractFromCurrentSession: () -> Unit,
    onSave: (Boolean, String) -> Unit
) {
    var enabled by remember(settings.longTermMemoryEnabled) {
        mutableStateOf(settings.longTermMemoryEnabled)
    }
    var memoryText by remember(settings.userMemoryProfile) {
        mutableStateOf(settings.userMemoryProfile)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF111827),
        titleContentColor = Color.White,
        textContentColor = Color(0xFFE2E8F0),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Psychology,
                    contentDescription = null,
                    tint = VioletAccent,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "🧠 跨對話長期記憶庫",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (enabled) VioletAccent.copy(alpha = 0.18f) else Color(0xFF1E293B),
                    border = BorderStroke(
                        1.dp,
                        if (enabled) VioletAccent else Color.White.copy(alpha = 0.1f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { enabled = !enabled }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "啟用跨對話個人記憶注入",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "開啟後，無論開啟新對話、語音助理或外語家教，AI 都會記得下方你的個人背景與偏好",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFCBD5E1)
                            )
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = { enabled = it }
                        )
                    }
                }

                Button(
                    onClick = onExtractFromCurrentSession,
                    enabled = !isExtracting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyanAccent,
                        contentColor = Color(0xFF06101E)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isExtracting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF06101E)
                        )
                    } else {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isExtracting) "正在從當前對話萃取記憶..." else "✨ 讓 AI 從當前對話自動萃取並更新記憶",
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedTextField(
                    value = memoryText,
                    onValueChange = { memoryText = it },
                    label = { Text("📝 長期記憶內容 (可隨時手動增刪)") },
                    placeholder = {
                        Text(
                            "範例：\n• 目前正在準備 JLPT N3 日文檢定，對平假名助詞較不熟\n• 預計 11 月底去東京與河口湖旅遊\n• 喜歡動漫與科技新聞，回答請保持精煉直接"
                        )
                    },
                    minLines = 5,
                    maxLines = 9,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(enabled, memoryText.trim()) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = EmeraldAccent,
                    contentColor = Color(0xFF042F2E)
                )
            ) {
                Text("儲存記憶庫", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = Color(0xFF94A3B8))
            }
        }
    )
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    clipboard.setPrimaryClip(clip)
}

@Composable
private fun WakeWordAndVoiceprintDialog(
    uiState: UiState,
    onDismiss: () -> Unit,
    onUpdateSettings: (AppSettings) -> Unit,
    onEnrollVoiceprint: () -> Unit,
    onClearVoiceprint: () -> Unit
) {
    val context = LocalContext.current
    val settings = uiState.settings
    var wakePhraseText by remember(settings.wakeWordPhrase) {
        mutableStateOf(settings.wakeWordPhrase.ifBlank { "hey Luna 我需要你" })
    }
    var canDrawOverlays by remember {
        mutableStateOf(
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                android.provider.Settings.canDrawOverlays(context)
            } else true
        )
    }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000L)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                canDrawOverlays = android.provider.Settings.canDrawOverlays(context)
            }
        }
    }
    val enrolledProfile = remember(settings.wakeWordVoiceprintData) {
        VoiceprintProfile.fromJson(settings.wakeWordVoiceprintData)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF111827),
        titleContentColor = Color.White,
        textContentColor = Color(0xFFE2E8F0),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.RecordVoiceOver,
                    contentDescription = null,
                    tint = AmberTutorAccent,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "🎧 免碰語音喚醒 ＆ 本人聲紋鎖",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. Standby Enable Switch ($0 OpenAI cost while waiting)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (settings.wakeWordEnabled) AmberTutorAccent.copy(alpha = 0.18f) else Color(0xFF1E293B),
                    border = BorderStroke(
                        1.dp,
                        if (settings.wakeWordEnabled) AmberTutorAccent else Color.White.copy(alpha = 0.1f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onUpdateSettings(
                                settings.copy(
                                    wakeWordEnabled = !settings.wakeWordEnabled,
                                    wakeWordPhrase = wakePhraseText.trim().ifBlank { "hey Luna 我需要你" }
                                )
                            )
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "啟用免碰手機語音喚醒待機（待機 $0 API 費用）",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = uiState.wakeWordStandbyStatus,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (uiState.isWakeWordStandbyActive) Color(0xFFFDE68A) else Color(0xFF94A3B8)
                            )
                        }
                        Switch(
                            checked = settings.wakeWordEnabled,
                            onCheckedChange = { enabled ->
                                onUpdateSettings(
                                    settings.copy(
                                        wakeWordEnabled = enabled,
                                        wakeWordPhrase = wakePhraseText.trim().ifBlank { "hey Luna 我需要你" }
                                    )
                                )
                            }
                        )
                    }
                }

                // 1b. Background Auto-Navigation & Wake-Up Overlay Permission Card
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (canDrawOverlays) Color(0xFF064E3B).copy(alpha = 0.25f) else Color(0xFF78350F).copy(alpha = 0.35f),
                    border = BorderStroke(
                        1.dp,
                        if (canDrawOverlays) EmeraldAccent.copy(alpha = 0.55f) else Color(0xFFF59E0B).copy(alpha = 0.7f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (canDrawOverlays) {
                                "✅ 已授權「顯示在其他應用程式上層」（背景自動跳轉 Google Maps 已啟用）"
                            } else {
                                "⚠️ 建議開啟「顯示在其他應用程式上層」權限（未開啟 App 時背景直接跳轉導航必備）"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (canDrawOverlays) Color(0xFFA7F3D0) else Color(0xFFFDE68A)
                        )
                        Text(
                            text = "Android 10～14+ 系統限制背景服務直接跳轉其他 App。開啟此權限後，即使您未打開本 App，直接喊「hey Luna 我需要你」要求導航時，也能 100% 零點擊自動拉起 Google Maps 開始導航。",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFCBD5E1)
                        )
                        if (!canDrawOverlays) {
                            Button(
                                onClick = {
                                    runCatching {
                                        val intent = android.content.Intent(
                                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            android.net.Uri.parse("package:${context.packageName}")
                                        ).apply {
                                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(intent)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFF59E0B),
                                    contentColor = Color(0xFF0F172A)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("🔓 一鍵前往開啟「顯示在其他應用程式上層」", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // 2. Wake Phrase Input (Default: "hey Luna 我需要你")
                OutlinedTextField(
                    value = wakePhraseText,
                    onValueChange = {
                        wakePhraseText = it
                        onUpdateSettings(settings.copy(wakeWordPhrase = it.trim().ifBlank { "hey Luna 我需要你" }))
                    },
                    label = { Text("🗣️ 專屬喚醒詞 (預設：hey Luna 我需要你)") },
                    placeholder = { Text("hey Luna 我需要你") },
                    singleLine = true,
                    trailingIcon = {
                        if (wakePhraseText != "hey Luna 我需要你") {
                            TextButton(
                                onClick = {
                                    wakePhraseText = "hey Luna 我需要你"
                                    onUpdateSettings(settings.copy(wakeWordPhrase = "hey Luna 我需要你"))
                                }
                            ) {
                                Text("預設", color = CyanAccent, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // 3. On-Device Acoustic Voiceprint Lock Card
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF161F30),
                    border = BorderStroke(
                        1.dp,
                        if (enrolledProfile != null && settings.wakeWordVoiceprintEnabled) EmeraldAccent.copy(alpha = 0.6f)
                        else VioletAccent.copy(alpha = 0.45f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "🔒 本地端本人聲紋辨識鎖 (Voiceprint Lock)",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = if (enrolledProfile != null) {
                                        "✅ 已錄製 ${enrolledProfile.sampleCount} 次本人聲紋樣本（基頻約 ${enrolledProfile.meanPitchHz.toInt()} Hz）— 僅本人聲線說出喚醒詞才會開啟"
                                    } else {
                                        "尚未錄製本人聲紋樣本（建議錄製 2～3 次以獲得最佳防旁人誤觸效果）"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (enrolledProfile != null) Color(0xFFA7F3D0) else Color(0xFFCBD5E1)
                                )
                            }
                            Switch(
                                checked = settings.wakeWordVoiceprintEnabled,
                                onCheckedChange = {
                                    onUpdateSettings(settings.copy(wakeWordVoiceprintEnabled = it))
                                }
                            )
                        }

                        if (uiState.voiceprintStatusMessage.isNotBlank()) {
                            Text(
                                text = uiState.voiceprintStatusMessage,
                                style = MaterialTheme.typography.labelSmall,
                                color = AmberTutorAccent,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onEnrollVoiceprint,
                                enabled = !uiState.isEnrollingVoiceprint,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CyanAccent,
                                    contentColor = Color(0xFF06101E)
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                if (uiState.isEnrollingVoiceprint) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(15.dp),
                                        strokeWidth = 2.dp,
                                        color = Color(0xFF06101E)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("請說「${wakePhraseText}」...", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                } else {
                                    Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (enrolledProfile == null) "錄製本人聲紋 (3秒)" else "追加取樣強化聲紋",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            }

                            if (enrolledProfile != null) {
                                OutlinedButton(
                                    onClick = onClearVoiceprint,
                                    border = BorderStroke(1.dp, RoseDanger.copy(alpha = 0.6f))
                                ) {
                                    Text("重設", color = RoseDanger, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // 4. Hands-Free Auto-Hangup & 4-Layer Safety Net Card
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF161F30),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "🛡️ 免碰手機自動掛斷 ＆ 四道防誤觸保護網",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = EmeraldAccent
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "🧭 跳轉 Google Maps 導航後自動掛斷通話",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "導航開啟後約 2.8 秒自動結束 Live Chat，不與導航語音搶麥克風",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                            Switch(
                                checked = settings.autoEndCallOnNavigation,
                                onCheckedChange = {
                                    onUpdateSettings(settings.copy(autoEndCallOnNavigation = it))
                                }
                            )
                        }

                        Text(
                            text = "• 語音道別掛斷：通話中說「掰掰 / 再見 / 先這樣 / 結束通話」自動掛斷\n" +
                                    "• 第 1 道（${settings.wakeWordFirstUtteranceTimeoutSec} 秒首句驗證窗）：喚醒後若無有效指令立即掛斷\n" +
                                    "• 第 2 道（AI 旁人閒聊辨識）：誤聽到旁人聊天半截話時 AI 主動秒掛斷\n" +
                                    "• 第 3 道（${settings.wakeWordIdleTimeoutSec} 秒無聲閒置掛斷）：對話停頓超過 ${settings.wakeWordIdleTimeoutSec} 秒自動掛斷\n" +
                                    "• 第 4 道（${settings.wakeWordMaxDurationSec} 秒單通安全上限）：滿 ${settings.wakeWordMaxDurationSec - 15} 秒主動語音詢問是否繼續",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFCBD5E1),
                            lineHeight = 17.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = EmeraldAccent,
                    contentColor = Color(0xFF042F2E)
                )
            ) {
                Text("完成並套用", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun InAppMapPreviewDialog(
    previewData: MapPreviewData,
    onDismiss: () -> Unit,
    onLaunchGoogleMapsNavigation: (String) -> Unit
) {
    val htmlContent = remember(previewData) {
        val lat = previewData.lat
        val lon = previewData.lon
        val uLat = previewData.userLat
        val uLon = previewData.userLon
        val safeName = previewData.name.replace("'", "\\'").replace("\"", "&quot;")
        val safeMeta = previewData.meta.replace("'", "\\'").replace("\"", "&quot;")
        val safeAddr = previewData.address.replace("'", "\\'").replace("\"", "&quot;")

        """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
            <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
            <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
            <style>
                html, body, #map { margin: 0; padding: 0; width: 100%; height: 100%; background: #0f172a; font-family: sans-serif; }
                .leaflet-popup-content-wrapper { background: #1e293b; color: #f8fafc; border-radius: 10px; border: 1px solid #38bdf8; }
                .leaflet-popup-tip { background: #1e293b; }
            </style>
        </head>
        <body>
            <div id="map"></div>
            <script>
                var destLat = $lat;
                var destLon = $lon;
                var map = L.map('map').setView([destLat, destLon], 16);
                L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
                    maxZoom: 19,
                    attribution: '© OpenStreetMap'
                }).addTo(map);

                var destMarker = L.marker([destLat, destLon]).addTo(map);
                destMarker.bindPopup("<b>📍 $safeName</b><br/><span style='color:#34d399;font-size:12px;'>$safeMeta</span><br/><span style='color:#cbd5e1;font-size:11px;'>$safeAddr</span>").openPopup();

                ${
                    if (uLat != null && uLon != null) {
                        """
                        var uLat = $uLat;
                        var uLon = $uLon;
                        var userCircle = L.circleMarker([uLat, uLon], {
                            radius: 8,
                            fillColor: '#38bdf8',
                            color: '#ffffff',
                            weight: 2,
                            opacity: 1,
                            fillOpacity: 0.9
                        }).addTo(map).bindPopup("<b>🕺 你的目前位置</b>");

                        var routeLine = L.polyline([[uLat, uLon], [destLat, destLon]], {
                            color: '#10b981',
                            weight: 4,
                            dashArray: '8, 8'
                        }).addTo(map);

                        map.fitBounds(routeLine.getBounds(), { padding: [42, 42], maxZoom: 17 });
                        """
                    } else ""
                }
            </script>
        </body>
        </html>
        """.trimIndent()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0F172A),
        titleContentColor = Color.White,
        textContentColor = Color(0xFFE2E8F0),
        title = {
            Column {
                Text(
                    text = "🗺️ ${previewData.name}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                if (previewData.meta.isNotBlank()) {
                    Text(
                        text = previewData.meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = EmeraldAccent,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF090E1A),
                    border = BorderStroke(1.dp, CyanAccent.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                webViewClient = WebViewClient()
                                loadDataWithBaseURL(
                                    "https://openstreetmap.org",
                                    htmlContent,
                                    "text/html",
                                    "UTF-8",
                                    null
                                )
                            }
                        },
                        update = { webView ->
                            webView.loadDataWithBaseURL(
                                "https://openstreetmap.org",
                                htmlContent,
                                "text/html",
                                "UTF-8",
                                null
                            )
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                if (previewData.address.isNotBlank()) {
                    Text(
                        text = "📍 地址：${previewData.address}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFCBD5E1)
                    )
                }
                Text(
                    text = "💡 你目前仍在 App 內，語音對話不會中斷！若確定要前往，可點擊下方按鈕直接跳轉 Google Maps 開始語音導航。",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF94A3B8)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onLaunchGoogleMapsNavigation(previewData.navUrl) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = EmeraldAccent,
                    contentColor = Color(0xFF042F2E)
                )
            ) {
                Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("🚀 跳轉 Google Maps 導航", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("關閉預覽（繼續聊天）", color = CyanAccent)
            }
        }
    )
}
