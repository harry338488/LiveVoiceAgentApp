package com.example.livevoiceagent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.livevoiceagent.model.AppSettings
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private val SettingsBg = Color(0xFF05070B)
private val SettingsCardBg = Color(0xFF0F141F)
private val SettingsAccentCyan = Color(0xFF38BDF8)
private val SettingsAccentViolet = Color(0xFFA855F7)
private val SettingsAccentEmerald = Color(0xFF10B981)

/**
 * Clean, minimalist Apple/ChatGPT-style collapsible Settings Screen for Catherine (v2.1).
 */
@Composable
internal fun CatherineSettingsScreen(
    uiState: UiState,
    onUpdateSettings: (AppSettings) -> Unit,
    onFetchModels: () -> Unit,
    onOpenMemoryHub: () -> Unit,
    onOpenWakeWordDialog: () -> Unit
) {
    val s = uiState.settings
    val scrollState = rememberScrollState()

    var expandVoice by remember { mutableStateOf(true) }
    var expandLife by remember { mutableStateOf(false) }
    var expandModels by remember { mutableStateOf(false) }
    var expandAdvanced by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SettingsBg)
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ======================================================
        // 1. 語音喚醒與背景體驗
        // ======================================================
        SettingsCollapsibleCard(
            title = "🎙️ 語音喚醒與背景體驗",
            subtitle = if (uiState.isWakeWordStandbyActive) "待機中 · 喊「${s.wakeWordPhrase.ifBlank { "hey Catherine" }}」" else "管理喚醒詞、聲紋鎖、懸浮膠囊與權限",
            accent = SettingsAccentCyan,
            expanded = expandVoice,
            onToggleExpand = { expandVoice = !expandVoice }
        ) {
            Button(
                onClick = onOpenWakeWordDialog,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SettingsAccentCyan,
                    contentColor = Color(0xFF05070B)
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("開啟「hey Catherine」喚醒與聲紋設定", fontWeight = FontWeight.Bold)
            }

            SettingsSwitchRow(
                title = "背景懸浮語音膠囊",
                subtitle = "背景通話時顯示可拖曳的迷你動態島（字幕與一鍵掛斷）",
                checked = s.floatingVoicePillEnabled,
                onCheckedChange = { onUpdateSettings(s.copy(floatingVoicePillEnabled = it)) }
            )
            SettingsSwitchRow(
                title = "跳轉導航後自動結束通話",
                subtitle = "開啟 Google Maps 導航後自動掛斷，不與導航語音搶麥克風",
                checked = s.autoEndCallOnNavigation,
                onCheckedChange = { onUpdateSettings(s.copy(autoEndCallOnNavigation = it)) }
            )
            SettingsSwitchRow(
                title = "智慧情境待機（充電或連藍牙才監聽）",
                subtitle = "平時自動釋放麥克風讓 Hey Google 正常運作",
                checked = s.wakeWordSmartContextOnly,
                onCheckedChange = { onUpdateSettings(s.copy(wakeWordSmartContextOnly = it)) }
            )
        }

        // ======================================================
        // 2. 記憶、常用地點、停車與待辦
        // ======================================================
        SettingsCollapsibleCard(
            title = "🧠 記憶、常用地點與待辦",
            subtitle = "管理長期記憶、我家／公司地址、車位紀錄與待辦清單",
            accent = SettingsAccentViolet,
            expanded = expandLife,
            onToggleExpand = { expandLife = !expandLife }
        ) {
            SettingsSwitchRow(
                title = "跨對話長期記憶",
                subtitle = "說「記住我…」即時存入，對話滿 2 輪也會自動整理",
                checked = s.longTermMemoryEnabled,
                onCheckedChange = { onUpdateSettings(s.copy(longTermMemoryEnabled = it)) }
            )
            OutlinedButton(
                onClick = onOpenMemoryHub,
                border = BorderStroke(1.dp, SettingsAccentViolet.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("檢視或編輯長期記憶庫", color = Color(0xFFE9D5FF), fontWeight = FontWeight.SemiBold)
            }

            var aliasText by remember(s.savedPlaceAliases) { mutableStateOf(s.savedPlaceAliases) }
            OutlinedTextField(
                value = aliasText,
                onValueChange = {
                    aliasText = it
                    onUpdateSettings(s.copy(savedPlaceAliases = it))
                },
                label = { Text("🏠 常用地點（每行一筆，如：家=台北市...）") },
                placeholder = { Text("家=台北市北投區...\n公司=台北市信義區...") },
                minLines = 2,
                maxLines = 4,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            val parkedObj = remember(s.parkedCarInfoJson) {
                runCatching {
                    if (s.parkedCarInfoJson.isBlank()) null else JSONObject(s.parkedCarInfoJson)
                }.getOrNull()
            }
            if (parkedObj != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White.copy(alpha = 0.04f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "🅿️ 上次停車位置（${parkedObj.optString("saved_at", "")}）",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFFA7F3D0),
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${parkedObj.optString("address", "")} · 備註：${parkedObj.optString("note", "無")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFCBD5E1)
                        )
                    }
                }
            }

            val todoList = remember(s.todoItemsJson) {
                runCatching {
                    val out = mutableListOf<JSONObject>()
                    if (s.todoItemsJson.isNotBlank()) {
                        val arr = JSONArray(s.todoItemsJson)
                        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it) }
                    }
                    out
                }.getOrDefault(emptyList())
            }
            if (todoList.isNotEmpty()) {
                Text(
                    text = "📝 語音待辦清單（點擊打勾完成）",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFFFDE68A),
                    fontWeight = FontWeight.Bold
                )
                todoList.take(10).forEachIndexed { idx, item ->
                    val done = item.optBoolean("done", false)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val nextArr = JSONArray()
                                todoList.forEachIndexed { i, o ->
                                    val c = JSONObject(o.toString())
                                    if (i == idx) c.put("done", !done)
                                    nextArr.put(c)
                                }
                                onUpdateSettings(s.copy(todoItemsJson = nextArr.toString()))
                            }
                            .padding(vertical = 3.dp)
                    ) {
                        Text(if (done) "✅" else "⬜", modifier = Modifier.padding(end = 8.dp))
                        Text(
                            text = item.optString("text", ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (done) Color(0xFF64748B) else Color(0xFFF1F5F9),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // ======================================================
        // 3. 模型、音色與 API 金鑰
        // ======================================================
        SettingsCollapsibleCard(
            title = "🔑 模型、音色與 API 金鑰",
            subtitle = "${s.frontendVoiceModel} · ${s.backendBrainModel} · 音色 ${s.voiceName}",
            accent = SettingsAccentEmerald,
            expanded = expandModels,
            onToggleExpand = { expandModels = !expandModels }
        ) {
            MaskedKeyField(
                label = "OpenAI API Key",
                value = s.openAiApiKey,
                onValueChange = { onUpdateSettings(s.copy(openAiApiKey = it)) }
            )
            MaskedKeyField(
                label = "Google Maps API Key（精準最近分店與即時路況）",
                value = s.googleMapsApiKey,
                onValueChange = { onUpdateSettings(s.copy(googleMapsApiKey = it)) }
            )
            MaskedKeyField(
                label = "Linkup API Key（即時網路與天氣搜尋）",
                value = s.linkupApiKey,
                onValueChange = { onUpdateSettings(s.copy(linkupApiKey = it)) }
            )
            MaskedKeyField(
                label = "xAI API Key（選填，使用 Grok 模型時填寫）",
                value = s.xAiApiKey,
                onValueChange = { onUpdateSettings(s.copy(xAiApiKey = it)) }
            )

            OutlinedTextField(
                value = s.frontendVoiceModel,
                onValueChange = { onUpdateSettings(s.copy(frontendVoiceModel = it)) },
                label = { Text("前端即時語音模型") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = s.backendBrainModel,
                onValueChange = { onUpdateSettings(s.copy(backendBrainModel = it)) },
                label = { Text("後端深度推理模型") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = s.voiceName,
                onValueChange = { onUpdateSettings(s.copy(voiceName = it)) },
                label = { Text("Catherine 音色 (marin / alloy / shimmer / coral / sage)") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedButton(
                onClick = onFetchModels,
                enabled = !uiState.isFetchingModels,
                border = BorderStroke(1.dp, SettingsAccentEmerald.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (uiState.isFetchingModels) "正在查詢可用模型..." else "🔄 測試連線並查詢可用模型清單",
                    color = Color(0xFFA7F3D0)
                )
            }
            if (uiState.modelFetchStatus.isNotBlank()) {
                Text(uiState.modelFetchStatus, style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
            }
        }

        // ======================================================
        // 4. 花費儀表板與進階工具設定
        // ======================================================
        SettingsCollapsibleCard(
            title = "📊 使用量花費與進階設定",
            subtitle = "API 預估花費統計、工具開關與系統指令",
            accent = Color(0xFFF59E0B),
            expanded = expandAdvanced,
            onToggleExpand = { expandAdvanced = !expandAdvanced }
        ) {
            val totalMin = s.totalVoiceUsageSeconds / 60.0
            val totalUsd = totalMin * 0.06 + s.totalWakeWhisperCalls * 0.0001
            val totalTwd = totalUsd * 32.0
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.White.copy(alpha = 0.04f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "累計通話 ${String.format(Locale.US, "%.1f", totalMin)} 分鐘 · 喚醒驗證 ${s.totalWakeWhisperCalls} 次",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFCBD5E1)
                        )
                        Text(
                            text = "預估累計花費：約 \$${String.format(Locale.US, "%.3f", totalUsd)} 美元（約台幣 ${String.format(Locale.US, "%.1f", totalTwd)} 元）",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFA7F3D0)
                        )
                    }
                    TextButton(
                        onClick = { onUpdateSettings(s.copy(totalVoiceUsageSeconds = 0L, totalWakeWhisperCalls = 0)) }
                    ) {
                        Text("歸零", color = Color(0xFF94A3B8))
                    }
                }
            }

            SettingsSwitchRow(
                title = "GPS 定位、導航與系統工具",
                subtitle = "啟用導航、鬧鐘、電話、通知讀取、找車與早安簡報",
                checked = s.locationToolsEnabled,
                onCheckedChange = { onUpdateSettings(s.copy(locationToolsEnabled = it)) }
            )
            SettingsSwitchRow(
                title = "手機行事曆讀寫",
                subtitle = "查詢、新增、修改與刪除手機行程",
                checked = s.calendarToolsEnabled,
                onCheckedChange = { onUpdateSettings(s.copy(calendarToolsEnabled = it)) }
            )
            SettingsSwitchRow(
                title = "Linkup 即時網路搜尋",
                subtitle = "查詢即時新聞、天氣與網路資料（支援極速 fast / flash 模式）",
                checked = s.linkupSearchEnabled,
                onCheckedChange = { onUpdateSettings(s.copy(linkupSearchEnabled = it)) }
            )
            if (s.linkupSearchEnabled) {
                Text(
                    text = "⚡ Linkup 搜尋速度模式（語音建議 fast 或 flash）",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF94A3B8)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "flash" to "⚡flash",
                        "fast" to "🚀fast",
                        "standard" to "standard",
                        "deep" to "deep"
                    ).forEach { (depthKey, label) ->
                        val selected = s.linkupSearchDepth == depthKey
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (selected) SettingsAccentCyan.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.05f),
                            border = BorderStroke(
                                1.dp,
                                if (selected) SettingsAccentCyan else Color.White.copy(alpha = 0.12f)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onUpdateSettings(s.copy(linkupSearchDepth = depthKey)) }
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) Color.White else Color(0xFF94A3B8),
                                modifier = Modifier.padding(vertical = 7.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = {
                    onUpdateSettings(
                        s.copy(
                            voiceSystemInstructions = AppSettings.DEFAULT_FRONTEND_VOICE_PROMPT,
                            backendSystemInstructions = AppSettings.DEFAULT_BACKEND_BRAIN_PROMPT
                        )
                    )
                },
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("還原 Catherine 最新預設系統指令", color = Color(0xFFE2E8F0))
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsCollapsibleCard(
    title: String,
    subtitle: String,
    accent: Color,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = SettingsCardBg,
        border = BorderStroke(1.dp, if (expanded) accent.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.08f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() }
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8)
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = Color(0xFF94A3B8)
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content
                )
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = Color(0xFFF1F5F9)
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF94A3B8)
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun MaskedKeyField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = null,
                    tint = Color(0xFF94A3B8)
                )
            }
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    )
}
