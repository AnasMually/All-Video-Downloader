package com.anas_mugally.videodownloader.download

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import com.anas_mugally.videodownloader.R
import com.anas_mugally.videodownloader.domain.DownloadTask

object DownloadProgressText {
    fun primary(context: Context, task: DownloadTask): String {
        if (task.progress >= 90 && (task.speedBytesPerSecond ?: 0L) <= 0L) {
            return context.getString(R.string.processing_media, task.progress)
        }
        val downloaded = task.downloadedBytes
        val total = task.totalBytes
        return if (downloaded != null && total != null && total > 0L) {
            context.getString(
                R.string.download_progress_amount,
                task.progress,
                Formatter.formatShortFileSize(context, downloaded),
                Formatter.formatShortFileSize(context, total),
            )
        } else {
            context.getString(R.string.download_progress_percent, task.progress)
        }
    }

    fun secondary(context: Context, task: DownloadTask): String? {
        if (task.progress >= 90 && (task.speedBytesPerSecond ?: 0L) <= 0L) return null
        val speed = task.speedBytesPerSecond?.takeIf { it > 0L }
            ?.let { Formatter.formatShortFileSize(context, it) }
        val eta = task.etaSeconds?.takeIf { it >= 0L }
            ?.let { DateUtils.formatElapsedTime(it) }
        return when {
            speed != null && eta != null -> context.getString(R.string.download_speed_eta, speed, eta)
            speed != null -> context.getString(R.string.download_speed, speed)
            eta != null -> context.getString(R.string.download_eta, eta)
            else -> null
        }
    }
}
