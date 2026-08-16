package com.anas_mugally.videodownloader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anas_mugally.videodownloader.ui.AllVideoDownloaderApp
import com.anas_mugally.videodownloader.ui.AppLaunchData
import com.anas_mugally.videodownloader.ui.MainViewModel
import com.anas_mugally.videodownloader.ui.theme.DownloaderTheme

class MainActivity : ComponentActivity() {
    private var launchData by mutableStateOf(AppLaunchData())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        launchData = intent.toLaunchData(1L)
        setContent {
            val mainViewModel: MainViewModel = viewModel()
            val settings by mainViewModel.settings.collectAsStateWithLifecycle()
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
        val sharedText = takeIf { action == Intent.ACTION_SEND }
            ?.getStringExtra(Intent.EXTRA_TEXT)
            .orEmpty()
        return AppLaunchData(
            sharedText = sharedText,
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
