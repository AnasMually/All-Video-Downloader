package com.anas_mugally.videodownloader

import android.app.Application
import android.util.Log
import com.anas_mugally.videodownloader.data.AppRepository
import com.anas_mugally.videodownloader.data.YtDlpRuntime
import com.anas_mugally.videodownloader.download.DownloadNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VideoDownloaderApp : Application() {
    val repository by lazy { AppRepository(this) }
    val ytDlpRuntime by lazy { YtDlpRuntime(this) }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        DownloadNotifications.createChannels(this)
        applicationScope.launch {
            runCatching { ytDlpRuntime.ensureReady() }
                .onFailure { error -> Log.e(TAG, "yt-dlp initialization failed", error) }
        }
    }

    private companion object {
        const val TAG = "VideoDownloaderApp"
    }
}
