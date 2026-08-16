package com.anas_mugally.videodownloader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.anas_mugally.videodownloader.ui.AllVideoDownloaderApp
import com.anas_mugally.videodownloader.ui.theme.DownloaderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val sharedUrl = intent.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        setContent { DownloaderTheme { AllVideoDownloaderApp(sharedUrl) } }
    }
}
