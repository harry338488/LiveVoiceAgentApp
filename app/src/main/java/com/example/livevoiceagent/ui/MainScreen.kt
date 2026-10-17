package com.example.livevoiceagent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.livevoiceagent.model.*
import com.example.livevoiceagent.network.ConnectionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val DeepSpaceBgBrush = Brush.verticalGradient(
    colors = listOf(
        Color(0xFF070B14),
        Color(0xFF0F172A),
        Color(0xFF090D18)
    )
)

private val UserBubbleGradient = Brush.linearGradient(
    colors = listOf(
        Color(0xFF2563EB),
        Color(0xFF1D4ED8)
    )
)

private val AssistantCardBg = Color(0xFF151F32).copy(alpha = 0.88f)
private val GlassSurfaceBg = Color(0xFF1E293B).copy(alpha = 0.65f)
private val CyanAccent = Color(0xFF38BDF8)
private val VioletAccent = Color(0xFFA855F7)
private val EmeraldAccent = Color(0xFF10B981)
private val RoseDanger = Color(0xFFF43F5E)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onRequestMicPermissionAndToggleCall: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }

    val isTranslationMode = uiState.settings.protocol == LiveProtocol.OPENAI_TRANSLATE ||
            uiState.settings.frontendVoiceModel.lowercase().contains("translate")

    LaunchedEffect(uiState.errorToast) {
        uiState.errorToast?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            viewModel.clearErrorToast()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Surface(
                color = Color(0xFF090E1A).copy(alpha = 0.92f),
                tonalElevation = 0.dp
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Brand Glowing Badge
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        if (isTranslationMode) {
                                            listOf(EmeraldAccent, CyanAccent)
                                        } else {
                                            listOf(CyanAccent, VioletAccent)
                                        }
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isTranslationMode) Icons.Default.Translate else Icons.Default.GraphicEq,
                                contentDescription = null,
                                tint = Color(0xFF06101E),
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = uiState.currentSession.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF8FAFC),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            val subtitleText = if (isTranslationMode) {
                                "gpt-realtime-translate • 70+ 語言自動偵測 ➔ ${
                                    AppSettings.languageLabel(uiState.settings.translationTargetLanguage)
                                }"
                            } else {
                                "${uiState.settings.frontendVoiceModel} ⇄ ${uiState.settings.backendBrainModel}" +
                                        (if (uiState.settings.linkupSearchEnabled) " · Linkup" else "") +
                                        (if (uiState.settings.calendarToolsEnabled) " · 行事曆" else "")
                            }
                            Text(
                                text = subtitleText,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isTranslationMode) EmeraldAccent else CyanAccent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (selectedTab == 0) {
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
                            IconButton(onClick = { viewModel.startNewConversationSession() }) {
                                Icon(
                                    Icons.Default.AddComment,
                                    contentDescription = "開啟新對話",
                                    tint = CyanAccent
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.07f))
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF0B1120).copy(alpha = 0.96f),
                tonalElevation = 0.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = {
                        Icon(
                            if (isTranslationMode) Icons.Default.Translate else Icons.Default.GraphicEq,
                            contentDescription = null
                        )
                    },
                    label = { Text(if (isTranslationMode) "即時同步口譯" else "即時語音對話") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = if (isTranslationMode) EmeraldAccent else CyanAccent,
                        selectedTextColor = if (isTranslationMode) EmeraldAccent else CyanAccent,
                        indicatorColor = (if (isTranslationMode) EmeraldAccent else CyanAccent).copy(alpha = 0.16f)
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (uiState.savedSessions.isNotEmpty()) {
                                    Badge(containerColor = VioletAccent) {
                                        Text("${uiState.savedSessions.size}", color = Color.White)
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.History, contentDescription = null)
                        }
                    },
                    label = { Text("對話紀錄留存") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyanAccent,
                        selectedTextColor = CyanAccent,
                        indicatorColor = CyanAccent.copy(alpha = 0.16f)
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Tune, contentDescription = null) },
                    label = { Text("模型與 API 設定") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyanAccent,
                        selectedTextColor = CyanAccent,
                        indicatorColor = CyanAccent.copy(alpha = 0.16f)
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DeepSpaceBgBrush)
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> LiveConversationTab(
                    uiState = uiState,
                    isTranslationMode = isTranslationMode,
                    onSelectTranslationMode = { viewModel.setTranslationMode(it) },
                    onSelectTargetLanguage = { viewModel.setTranslationTargetLanguage(it) },
                    onSwapTranslationLanguages = { viewModel.swapTranslationLanguages() },
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
    isTranslationMode: Boolean,
    onSelectTranslationMode: (Boolean) -> Unit,
    onSelectTargetLanguage: (String) -> Unit,
    onSwapTranslationLanguages: () -> Unit,
    onToggleCall: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onSendTypedMessage: (String) -> Unit
) {
    val listState = rememberLazyListState()
    val context = LocalContext.current
    var typedInput by remember { mutableStateOf("") }
    var showKeyboardBar by remember { mutableStateOf(false) }

    val messages = uiState.currentSession.messages

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 1. Top Segmented Mode Switcher: [🎙️ 全雙工 AI 語音助理] vs [🌐 即時同步口譯]
        ModeSegmentedSwitcher(
            isTranslationMode = isTranslationMode,
            onSelectTranslationMode = onSelectTranslationMode
        )

        // 2. If in Translation Mode, show the sleek Language Bar & A ⇄ B Quick Swap
        AnimatedVisibility(
            visible = isTranslationMode,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            TranslationLanguageControlBar(
                targetLang = uiState.settings.translationTargetLanguage,
                secondaryLang = uiState.settings.translationSecondaryLanguage,
                onSelectTargetLanguage = onSelectTargetLanguage,
                onSwapLanguages = onSwapTranslationLanguages
            )
        }

        // 3. Compact Status & Live Waveform Pill (always visible when chatting)
        if (messages.isNotEmpty()) {
            CompactVoiceOrbStatusCard(
                uiState = uiState,
                isTranslationMode = isTranslationMode
            )
        }

        // 4. Main Centerpiece Area: Animated 3D Voice Orb (when empty) OR Glassmorphic Chat Stream
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
                    isTranslationMode = isTranslationMode,
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

        // 5. Floating Glassmorphic Bottom Control Dock
        FloatingBottomControlDock(
            uiState = uiState,
            isTranslationMode = isTranslationMode,
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
            }
        )
    }
}

@Composable
private fun ModeSegmentedSwitcher(
    isTranslationMode: Boolean,
    onSelectTranslationMode: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        shape = RoundedCornerShape(50),
        color = Color(0xFF131C2E).copy(alpha = 0.9f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Pill 1: AI Voice Assistant
            val assistantSelected = !isTranslationMode
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (assistantSelected) {
                            Brush.horizontalGradient(
                                listOf(CyanAccent.copy(alpha = 0.25f), VioletAccent.copy(alpha = 0.25f))
                            )
                        } else {
                            Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                        }
                    )
                    .border(
                        width = if (assistantSelected) 1.dp else 0.dp,
                        color = if (assistantSelected) CyanAccent.copy(alpha = 0.6f) else Color.Transparent,
                        shape = RoundedCornerShape(50)
                    )
                    .clickable { if (isTranslationMode) onSelectTranslationMode(false) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = if (assistantSelected) CyanAccent else Color(0xFF94A3B8),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "全雙工 AI 語音助理",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (assistantSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (assistantSelected) Color.White else Color(0xFF94A3B8)
                    )
                }
            }

            // Pill 2: Live Translation (`gpt-realtime-translate`)
            val translateSelected = isTranslationMode
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (translateSelected) {
                            Brush.horizontalGradient(
                                listOf(EmeraldAccent.copy(alpha = 0.28f), CyanAccent.copy(alpha = 0.22f))
                            )
                        } else {
                            Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                        }
                    )
                    .border(
                        width = if (translateSelected) 1.dp else 0.dp,
                        color = if (translateSelected) EmeraldAccent.copy(alpha = 0.65f) else Color.Transparent,
                        shape = RoundedCornerShape(50)
                    )
                    .clickable { if (!isTranslationMode) onSelectTranslationMode(true) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Translate,
                        contentDescription = null,
                        tint = if (translateSelected) EmeraldAccent else Color(0xFF94A3B8),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "即時同步口譯",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (translateSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (translateSelected) Color.White else Color(0xFF94A3B8)
                    )
                }
            }
        }
    }
}

@Composable
private fun TranslationLanguageControlBar(
    targetLang: String,
    secondaryLang: String,
    onSelectTargetLanguage: (String) -> Unit,
    onSwapLanguages: () -> Unit
) {
    val chipScroll = rememberScrollState()
    val targetLabel = AppSettings.languageLabel(targetLang).substringBefore(" (")
    val secondaryLabel = AppSettings.languageLabel(secondaryLang).substringBefore(" (")

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF0F222D).copy(alpha = 0.85f),
        border = BorderStroke(1.dp, EmeraldAccent.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "🌐 目標口譯輸出語言：$targetLabel",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = EmeraldAccent
                    )
                    Text(
                        text = "自動偵測 70+ 國輸入語言 • 保留原說話者音色語氣",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8)
                    )
                }

                // A ⇄ B Quick Swap Pill for Two-Way Face-to-Face Interpretation
                Surface(
                    shape = RoundedCornerShape(50),
                    color = EmeraldAccent.copy(alpha = 0.18f),
                    border = BorderStroke(1.dp, EmeraldAccent.copy(alpha = 0.55f)),
                    modifier = Modifier.clickable { onSwapLanguages() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = "雙向語言切換",
                            tint = EmeraldAccent,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "$targetLabel ⇄ $secondaryLabel",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Horizontal Scrollable 13 Target Languages
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(chipScroll),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppSettings.AVAILABLE_TRANSLATION_LANGUAGES.forEach { (code, fullLabel) ->
                    val isSelected = code == targetLang
                    val shortLabel = fullLabel.substringBefore(" (")
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (isSelected) EmeraldAccent else Color(0xFF1E293B).copy(alpha = 0.75f),
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) EmeraldAccent else Color.White.copy(alpha = 0.1f)
                        ),
                        modifier = Modifier.clickable { onSelectTargetLanguage(code) }
                    ) {
                        Text(
                            text = shortLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) Color(0xFF042F2E) else Color(0xFFE2E8F0),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimatedVoiceOrbHero(
    uiState: UiState,
    isTranslationMode: Boolean,
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

    val isConnected = uiState.connectionState != ConnectionState.DISCONNECTED
    val isDelegating = uiState.connectionState == ConnectionState.DELEGATING
    val dynamicLevel = uiState.micLevel.coerceIn(0f, 1f)

    val primaryOrbColor = when {
        isDelegating -> VioletAccent
        isTranslationMode && isConnected -> EmeraldAccent
        isConnected -> CyanAccent
        isTranslationMode -> EmeraldAccent.copy(alpha = 0.78f)
        else -> CyanAccent.copy(alpha = 0.78f)
    }

    val secondaryOrbColor = when {
        isDelegating -> Color(0xFFEC4899)
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
            text = if (isTranslationMode) {
                "🌐 即時雙向同步口譯 (gpt-realtime-translate)"
            } else {
                "🎙️ 全雙工多語言語音助理 + 後端推理大腦"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        val featureSummary = if (isTranslationMode) {
            "• 支援中、英、日、韓、歐美與東南亞等 70+ 種語言自動聽音辨識\n" +
                    "• 邊聽邊翻無需等待回合，自動模仿說話者音色語氣輸出目標語言\n" +
                    "• 點擊上方「⇄」按鈕可在通話中即時切換雙向口譯目標語言！"
        } else {
            "• 支援中、英、日文自然混講（自動保留日文漢字與假名、中文轉繁體）\n" +
                    "• 前台語音 (${uiState.settings.frontendVoiceModel}) 毫秒級回應，查資料時仍可繼續聊天\n" +
                    "• 後端大腦 (${uiState.settings.backendBrainModel}) 思考過程、Linkup 搜尋與行事曆收折於氣泡抽屜中"
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
    isTranslationMode: Boolean
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
        ConnectionState.LIVE -> if (isTranslationMode) EmeraldAccent else CyanAccent
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
    isTranslationMode: Boolean,
    typedInput: String,
    onTypedInputChange: (String) -> Unit,
    showKeyboardBar: Boolean,
    onToggleKeyboardBar: () -> Unit,
    onToggleCall: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onSendTypedMessage: () -> Unit
) {
    val isConnected = uiState.connectionState != ConnectionState.DISCONNECTED

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
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left side: Keyboard Toggle (in AI Mode) + Mute Button
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isTranslationMode) {
                        Surface(
                            shape = CircleShape,
                            color = if (showKeyboardBar) CyanAccent.copy(alpha = 0.22f) else Color(0xFF1E293B),
                            border = BorderStroke(
                                1.dp,
                                if (showKeyboardBar) CyanAccent else Color.White.copy(alpha = 0.08f)
                            ),
                            modifier = Modifier
                                .size(44.dp)
                                .clickable { onToggleKeyboardBar() }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Keyboard,
                                    contentDescription = "文字輸入",
                                    tint = if (showKeyboardBar) CyanAccent else Color(0xFFCBD5E1),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    // Mute Circular Button
                    Surface(
                        shape = CircleShape,
                        color = if (uiState.isMuted) RoseDanger.copy(alpha = 0.22f) else Color(0xFF1E293B),
                        border = BorderStroke(
                            1.dp,
                            if (uiState.isMuted) RoseDanger else Color.White.copy(alpha = 0.08f)
                        ),
                        modifier = Modifier
                            .size(44.dp)
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
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // Centerpiece: Glowing Call / Interpret Pill Button
                val mainBtnBrush = when {
                    isConnected -> Brush.horizontalGradient(listOf(RoseDanger, Color(0xFFE11D48)))
                    isTranslationMode -> Brush.horizontalGradient(listOf(EmeraldAccent, CyanAccent))
                    else -> Brush.horizontalGradient(listOf(CyanAccent, Color(0xFF6366F1)))
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(mainBtnBrush)
                        .clickable { onToggleCall() }
                        .padding(horizontal = 24.dp, vertical = 13.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = when {
                                isConnected -> Icons.Default.CallEnd
                                isTranslationMode -> Icons.Default.Translate
                                else -> Icons.Default.GraphicEq
                            },
                            contentDescription = null,
                            tint = if (isConnected) Color.White else Color(0xFF04101E),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when {
                                isConnected && isTranslationMode -> "停止同步口譯"
                                isConnected -> "結束語音通話"
                                isTranslationMode -> "開始同步口譯"
                                else -> "開始全雙工對話"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = if (isConnected) Color.White else Color(0xFF04101E)
                        )
                    }
                }

                // Right side: Speakerphone / Earpiece Toggle
                Surface(
                    shape = CircleShape,
                    color = if (uiState.isSpeakerphoneOn) CyanAccent.copy(alpha = 0.18f) else Color(0xFF1E293B),
                    border = BorderStroke(
                        1.dp,
                        if (uiState.isSpeakerphoneOn) CyanAccent.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.08f)
                    ),
                    modifier = Modifier
                        .size(44.dp)
                        .clickable { onToggleSpeaker() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (uiState.isSpeakerphoneOn) Icons.Default.VolumeUp else Icons.Default.PhoneInTalk,
                            contentDescription = if (uiState.isSpeakerphoneOn) "擴音" else "聽筒",
                            tint = if (uiState.isSpeakerphoneOn) CyanAccent else Color(0xFFCBD5E1),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
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
    val isTranslationBubble = message.modelTag.contains("口譯")
    val isLegacyCollapsibleNote = message.role == MessageRole.BACKEND_BRAIN || message.role == MessageRole.WEB_SEARCH
    var isThinkingExpanded by remember(message.id) { mutableStateOf(false) }

    val roleLabel = when {
        isUser -> if (message.modelTag.contains("原音")) "🎙️ 原音辨識" else "🗣️ 你"
        isTranslationBubble -> message.modelTag
        message.role == MessageRole.ASSISTANT_VOICE -> "🎧 ${message.modelTag.ifBlank { "AI 語音" }}"
        message.role == MessageRole.BACKEND_BRAIN -> "🧠 後端模型重點筆記 (${message.modelTag})"
        message.role == MessageRole.WEB_SEARCH -> "🌐 Linkup 網路搜尋 (${message.sources.size} 筆)"
        else -> "⚙️ 系統紀錄"
    }

    val roleAccentColor = when {
        isUser -> Color(0xFF93C5FD)
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

                // Embedded Perplexity / DeepSeek R1 style "Thinking Mode" collapsible drawer
                if (message.hasThinkingDetails) {
                    Spacer(modifier = Modifier.height(8.dp))

                    val summaryBadges = buildList {
                        if (message.backendThinkingText.isNotBlank()) {
                            add("🧠 ${message.backendModelTag.ifBlank { "後端推理" }}")
                        }
                        if (message.searchQueriesAndSummaries.isNotEmpty() || message.sources.isNotEmpty()) {
                            val count = maxOf(message.searchQueriesAndSummaries.size, 1)
                            add("🌐 Linkup ($count)")
                        }
                        if (message.calendarActionLogs.isNotEmpty()) {
                            add("📅 行事曆 (${message.calendarActionLogs.size})")
                        }
                    }.joinToString(" · ")

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF090E1A).copy(alpha = 0.72f),
                        border = BorderStroke(1.dp, VioletAccent.copy(alpha = 0.35f)),
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
                                    imageVector = Icons.Default.Psychology,
                                    contentDescription = null,
                                    tint = VioletAccent,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = summaryBadges.ifBlank { "思考過程與背景重點" },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFE9D5FF),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isThinkingExpanded) "收合 ▲" else "展開詳情 ▼",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = VioletAccent
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

                                    // 4. Backend Brain Detailed Notes
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
                        onUpdateSettings(s.copy(protocol = picked, frontendVoiceModel = autoModel))
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
                        onUpdateSettings(s.copy(frontendVoiceModel = modelId, protocol = autoProtocol))
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
                    text = "🌐 3. 網路搜尋 (Linkup) ＆ 手機行事曆工具",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

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

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    clipboard.setPrimaryClip(clip)
}
