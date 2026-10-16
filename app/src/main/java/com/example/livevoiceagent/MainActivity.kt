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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.core.content.ContextCompat
import com.example.livevoiceagent.ui.MainScreen
import com.example.livevoiceagent.ui.MainViewModel

class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()

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
                        "需要麥克風權限 (RECORD_AUDIO) 才能進行即時語音對話",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            MaterialTheme(colorScheme = colorScheme) {
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
