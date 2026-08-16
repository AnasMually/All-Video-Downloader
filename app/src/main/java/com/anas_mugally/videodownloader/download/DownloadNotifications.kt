package com.anas_mugally.videodownloader.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.anas_mugally.videodownloader.MainActivity
import com.anas_mugally.videodownloader.R
import com.anas_mugally.videodownloader.domain.DownloadStatus
import com.anas_mugally.videodownloader.domain.DownloadTask

object DownloadNotifications {
    const val FOREGROUND_NOTIFICATION_ID = 4_200
    private const val DOWNLOAD_CHANNEL = "active_downloads"
    private const val RESULT_CHANNEL = "download_results"
    private const val GROUP_KEY = "all_video_downloader_results"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    DOWNLOAD_CHANNEL,
                    context.getString(R.string.notification_channel_downloads),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = context.getString(R.string.notification_channel_downloads_description) },
                NotificationChannel(
                    RESULT_CHANNEL,
                    context.getString(R.string.notification_channel_results),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = context.getString(R.string.notification_channel_results_description) },
            ),
        )
    }

    fun preparing(context: Context): Notification = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL)
        .setSmallIcon(R.drawable.ic_download)
        .setContentTitle(context.getString(R.string.app_name))
        .setContentText(context.getString(R.string.preparing_download_engine))
        .setProgress(0, 0, true)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(openAppIntent(context, null, false))
        .build()

    fun active(context: Context, task: DownloadTask): Notification {
        val waiting = task.status == DownloadStatus.WAITING_FOR_WIFI
        val text = if (waiting) {
            context.getString(R.string.waiting_for_wifi)
        } else {
            context.getString(R.string.download_progress_percent, task.progress)
        }
        return NotificationCompat.Builder(context, DOWNLOAD_CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(task.title)
            .setContentText(text)
            .setProgress(100, task.progress.coerceIn(0, 100), waiting || task.progress <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent(context, task.id, false))
            .addAction(
                R.drawable.ic_pause,
                context.getString(R.string.pause),
                serviceIntent(context, DownloadService.ACTION_PAUSE, task.id, 1),
            )
            .addAction(
                R.drawable.ic_close,
                context.getString(R.string.cancel),
                serviceIntent(context, DownloadService.ACTION_CANCEL, task.id, 2),
            )
            .build()
    }

    fun completed(context: Context, task: DownloadTask): Notification {
        return NotificationCompat.Builder(context, RESULT_CHANNEL)
            .setSmallIcon(R.drawable.ic_download_done)
            .setContentTitle(context.getString(R.string.download_completed))
            .setContentText(task.title)
            .setAutoCancel(true)
            .setGroup(GROUP_KEY)
            .setContentIntent(openAppIntent(context, task.id, true))
            .addAction(
                R.drawable.ic_play,
                context.getString(R.string.play),
                openAppIntent(context, task.id, true),
            )
            .build()
    }

    fun failed(context: Context, task: DownloadTask): Notification {
        return NotificationCompat.Builder(context, RESULT_CHANNEL)
            .setSmallIcon(R.drawable.ic_error)
            .setContentTitle(context.getString(R.string.download_failed))
            .setContentText(task.title)
            .setAutoCancel(true)
            .setGroup(GROUP_KEY)
            .setContentIntent(openAppIntent(context, task.id, false))
            .addAction(
                R.drawable.ic_retry,
                context.getString(R.string.retry),
                serviceIntent(context, DownloadService.ACTION_RETRY, task.id, 3),
            )
            .build()
    }

    fun resultNotificationId(taskId: String): Int = 10_000 + (taskId.hashCode() and 0x0FFF_FFFF)

    private fun openAppIntent(context: Context, taskId: String?, play: Boolean): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_DOWNLOADS)
            taskId?.let { putExtra(MainActivity.EXTRA_TASK_ID, it) }
            putExtra(MainActivity.EXTRA_PLAY, play)
        }
        return PendingIntent.getActivity(
            context,
            (taskId?.hashCode() ?: 0) + if (play) 31 else 17,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun serviceIntent(
        context: Context,
        action: String,
        taskId: String,
        requestOffset: Int,
    ): PendingIntent {
        val intent = Intent(context, DownloadService::class.java).apply {
            this.action = action
            putExtra(DownloadService.EXTRA_TASK_ID, taskId)
        }
        return PendingIntent.getForegroundService(
            context,
            taskId.hashCode() + requestOffset,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
