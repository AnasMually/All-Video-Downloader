package com.anas_mugally.videodownloader

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anas_mugally.videodownloader.ui.MainViewModel
import com.anas_mugally.videodownloader.ui.SharedLinkSheet
import com.anas_mugally.videodownloader.ui.theme.DownloaderTheme

/** Compact share target that keeps the source app visible behind a Material sheet. */
class ShareReceiverActivity : ComponentActivity() {
    private var sharedText by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(true)
        sharedText = intent.sharedText()
        setContent {
            val mainViewModel: MainViewModel = viewModel()
            val settings by mainViewModel.settings.collectAsStateWithLifecycle()
            DownloaderTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
            ) {
                SharedLinkSheet(
                    sharedText = sharedText,
                    viewModel = mainViewModel,
                    onDismiss = ::closeShareTask,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
        window.setGravity(Gravity.BOTTOM)
    }

    private fun closeShareTask() {
        if (isTaskRoot) finishAndRemoveTask() else finish()
    }

    private fun Intent.sharedText(): String = takeIf { action == Intent.ACTION_SEND }
        ?.getCharSequenceExtra(Intent.EXTRA_TEXT)
        ?.toString()
        .orEmpty()
}
