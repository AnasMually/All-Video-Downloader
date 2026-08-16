package com.anas_mugally.videodownloader

import android.app.Application
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException

class VideoDownloaderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try { YoutubeDL.getInstance().init(this) }
        catch (error: YoutubeDLException) { Log.e("VideoDownloaderApp", "yt-dlp initialization failed", error) }
    }
}
