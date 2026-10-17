package com.example.livevoiceagent

import android.Manifest
import android.content.pm.PackageManager
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
import com.example.livevoiceagent.ui.MainScreen
import com.example.livevoiceagent.ui.MainViewModel

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

        window.statusBarColor = Color(0xFF080C14).toArgb()
        window.navigationBarColor = Color(0xFF0B1120).toArgb()

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
                        if (mainViewModel.uiState.value.settings.calendarToolsEnabled) {
                            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                                needed.add(Manifest.permission.READ_CALENDAR)
                            }
                            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                                needed.add(Manifest.permission.WRITE_CALENDAR)
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
}
