package com.anas_mugally.videodownloader

import android.app.Application
import com.anas_mugally.videodownloader.data.AppRepository
import com.anas_mugally.videodownloader.data.VideoFlowApi
import com.anas_mugally.videodownloader.download.DownloadNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VideoDownloaderApp : Application() {
    val repository by lazy { AppRepository(this) }
    val api by lazy { VideoFlowApi() }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        DownloadNotifications.createChannels(this)
        applicationScope.launch { api.refreshHealth() }
    }
}
