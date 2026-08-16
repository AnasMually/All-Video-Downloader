package com.anas_mugally.videodownloader

import android.graphics.Color
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anas_mugally.videodownloader.ui.AllVideoDownloaderApp
import com.anas_mugally.videodownloader.ui.AppLaunchData
import com.anas_mugally.videodownloader.ui.MainViewModel
import com.anas_mugally.videodownloader.ui.theme.DownloaderTheme
import com.anas_mugally.videodownloader.domain.ThemeMode

class MainActivity : ComponentActivity() {
    private var launchData by mutableStateOf(AppLaunchData())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        launchData = intent.toLaunchData(1L)
        setContent {
            val mainViewModel: MainViewModel = viewModel()
            val settings by mainViewModel.settings.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            SideEffect {
                val transparent = Color.TRANSPARENT
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(transparent, transparent) { dark },
                    navigationBarStyle = SystemBarStyle.auto(transparent, transparent) { dark },
                )
            }
            DownloaderTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
            ) {
                AllVideoDownloaderApp(launchData = launchData, viewModel = mainViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchData = intent.toLaunchData(launchData.sequence + 1L)
    }

    private fun Intent.toLaunchData(sequence: Long): AppLaunchData {
        return AppLaunchData(
            screen = getStringExtra(EXTRA_SCREEN),
            taskId = getStringExtra(EXTRA_TASK_ID),
            play = getBooleanExtra(EXTRA_PLAY, false),
            sequence = sequence,
        )
    }

    companion object {
        const val EXTRA_SCREEN = "open_screen"
        const val EXTRA_TASK_ID = "open_task_id"
        const val EXTRA_PLAY = "play_download"
        const val SCREEN_DOWNLOADS = "downloads"
    }
}
