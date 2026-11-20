package com.example.livevoiceagent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.livevoiceagent.network.ConnectionState
import com.example.livevoiceagent.ui.MainScreen
import com.example.livevoiceagent.ui.MainViewModel
import kotlinx.coroutines.launch

private val AiVoiceDarkColorScheme = darkColorScheme(
    primary = Color(0xFF38BDF8),          // Electric Sky Cyan
    onPrimary = Color(0xFF061320),
    primaryContainer = Color(0xFF1D4ED8), // Deep Royal Blue
    onPrimaryContainer = Color(0xFFEFF6FF),
    secondary = Color(0xFFA855F7),        // Neon Violet
    onSecondary = Color(0xFF1E0936),
    secondaryContainer = Color(0xFF1E293B),
    onSecondaryContainer = Color(0xFFF1F5F9),
    tertiary = Color(0xFF10B981),         // Emerald Green (Translation / Calendar)
    onTertiary = Color(0xFF042F2E),
    tertiaryContainer = Color(0xFF132A3E),
    onTertiaryContainer = Color(0xFFE0F2FE),
    background = Color(0xFF080C14),       // Deep Space Obsidian
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF0F172A),          // Slate Midnight
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1E293B),   // Frosted Card Slate
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF334155),
    outlineVariant = Color(0xFF1E293B),
    error = Color(0xFFF43F5E),
    onError = Color(0xFFFFFFFF)
)

class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color(0xFF05070B).toArgb()
        window.navigationBarColor = Color(0xFF05070B).toArgb()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        // Google Maps is launched directly by LocationMapsToolManager (works in foreground AND background);
        // the Activity only shows a confirmation toast to avoid double-launching Maps.
        lifecycleScope.launch {
            mainViewModel.googleMapsLaunchEvent.collect { req ->
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                    Toast.makeText(
                        this@MainActivity,
                        "🧭 正在自動跳轉 Google Maps 導航：${req.destinationLabel}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        handleVoiceAssistantIntentIfNeeded(intent, isColdStart = savedInstanceState == null)

        setContent {
            val permissionsLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { result ->
                val micGranted = result[Manifest.permission.RECORD_AUDIO] == true ||
                        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

                if (micGranted) {
                    mainViewModel.toggleLiveCall()
                } else {
                    Toast.makeText(
                        this,
                        "需要麥克風權限 (RECORD_AUDIO) 才能進行即時語音對話或同步口譯",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            MaterialTheme(colorScheme = AiVoiceDarkColorScheme) {
                MainScreen(
                    viewModel = mainViewModel,
                    onRequestMicPermissionAndToggleCall = {
                        val needed = mutableListOf<String>()
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            needed.add(Manifest.permission.RECORD_AUDIO)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) {
                            needed.add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        if (mainViewModel.uiState.value.settings.calendarToolsEnabled) {
                            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                                needed.add(Manifest.permission.READ_CALENDAR)
                            }
                            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                                needed.add(Manifest.permission.WRITE_CALENDAR)
                            }
                        }
                        if (mainViewModel.uiState.value.settings.locationToolsEnabled) {
                            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                            ) {
                                needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
                                needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
                            }
                            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                                needed.add(Manifest.permission.READ_CONTACTS)
                            }
                        }

                        if (needed.isEmpty()) {
                            mainViewModel.toggleLiveCall()
                        } else {
                            permissionsLauncher.launch(needed.toTypedArray())
                        }
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleVoiceAssistantIntentIfNeeded(intent, isColdStart = false)
    }

    /**
     * Hands-free entry points that auto-start a Live Chat (coexists with "Hey Google"):
     * - ACTION_ASSIST / VOICE_COMMAND (set Luna as the default assistant app, or long-press home)
     * - App shortcut / deep link `livevoiceagent://talk` (Hey Google, "開啟 Luna 對話" routines, Samsung Bixby/back-tap shortcuts)
     * - Normal launcher open ("Hey Google, 開啟 Live Voice Agent") when "開啟 App 時自動開始對話" is enabled
     */
    private fun handleVoiceAssistantIntentIfNeeded(intent: Intent?, isColdStart: Boolean) {
        val action = intent?.action ?: return

        // Handle Android "Share -> Live Voice Brain" (image/* or text/plain)
        if (action == Intent.ACTION_SEND) {
            val mimeType = intent.type.orEmpty()
            if (mimeType.startsWith("image/")) {
                val imgUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                val caption = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                if (imgUri != null) {
                    Toast.makeText(this, "📲 已收到分享圖片，Catherine 正在為您解析！", Toast.LENGTH_SHORT).show()
                    lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            contentResolver.openInputStream(imgUri)?.use { input ->
                                android.graphics.BitmapFactory.decodeStream(input)
                            }?.let { bmp ->
                                mainViewModel.sendImageMessage(bmp, caption)
                            }
                        }
                    }
                }
                return
            } else if (mimeType == "text/plain") {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                if (sharedText.isNotBlank()) {
                    mainViewModel.handleSharedTextFromExternalApp(sharedText)
                    Toast.makeText(this, "📲 已收到分享內容，Catherine 正在整理重點！", Toast.LENGTH_SHORT).show()
                }
                return
            } else if (mimeType.startsWith("audio/")) {
                val audioUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (audioUri != null) {
                    mainViewModel.importAndTranscribeAudioUri(audioUri)
                    Toast.makeText(this, "🎙️ 已收到分享錄音檔，正在自動分段轉寫與摘要！", Toast.LENGTH_SHORT).show()
                }
                return
            }
        }

        val isAssistAction = action == Intent.ACTION_ASSIST || action == Intent.ACTION_VOICE_COMMAND
        val isTalkShortcut = action == ACTION_START_VOICE_CHAT ||
                (action == Intent.ACTION_VIEW && intent.data?.scheme == "livevoiceagent")
        val isLauncherOpen = action == Intent.ACTION_MAIN && isColdStart &&
                mainViewModel.uiState.value.settings.autoStartCallOnAppLaunch
        if (isAssistAction || isTalkShortcut || isLauncherOpen) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                mainViewModel.uiState.value.connectionState == ConnectionState.DISCONNECTED
            ) {
                mainViewModel.startLiveCall(isWakeWordSession = true)
            }
        }
    }

    companion object {
        const val ACTION_START_VOICE_CHAT = "com.example.livevoiceagent.START_VOICE_CHAT"
    }
}
