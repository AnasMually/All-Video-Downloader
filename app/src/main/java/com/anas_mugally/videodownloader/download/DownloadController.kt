package com.anas_mugally.videodownloader.download

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import java.io.File

object DownloadController {
    fun enqueue(context: Context, taskId: String) = send(context, DownloadService.ACTION_ENQUEUE, taskId)

    fun pause(context: Context, taskId: String) = send(context, DownloadService.ACTION_PAUSE, taskId)

    fun resume(context: Context, taskId: String) = send(context, DownloadService.ACTION_RESUME, taskId)

    fun retry(context: Context, taskId: String) = send(context, DownloadService.ACTION_RETRY, taskId)

    fun cancel(context: Context, taskId: String) = send(context, DownloadService.ACTION_CANCEL, taskId)

    fun taskDirectory(context: Context, taskId: String): File {
        return File(File(context.cacheDir, "downloads"), taskId)
    }

    fun cleanTaskFiles(context: Context, taskId: String) {
        taskDirectory(context, taskId).deleteRecursively()
    }

    private fun send(context: Context, action: String, taskId: String) {
        val intent = Intent(context, DownloadService::class.java).apply {
            this.action = action
            putExtra(DownloadService.EXTRA_TASK_ID, taskId)
        }
        ContextCompat.startForegroundService(context, intent)
    }
}
