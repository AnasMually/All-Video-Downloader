package com.anas_mugally.videodownloader.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.anas_mugally.videodownloader.R
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString(KEY_URL) ?: return@withContext Result.failure()
        val format = inputData.getString(KEY_FORMAT) ?: "bestvideo+bestaudio/best"
        val audioOnly = inputData.getBoolean(KEY_AUDIO, false)
        createChannel(); setForeground(progressInfo(0, "بدء التنزيل…"))
        val cacheDir = File(applicationContext.cacheDir, "downloads").apply { mkdirs() }
        val template = File(cacheDir, "%(title).180B-%(id)s.%(ext)s").absolutePath
        val request = YoutubeDLRequest(url).apply {
            addOption("-f", if (audioOnly) "bestaudio/best" else "$format+bestaudio/$format/best")
            addOption("-o", template)
            addOption("--no-playlist")
            if (audioOnly) { addOption("-x"); addOption("--audio-format", "mp3") }
        }
        return@withContext try {
            val before = cacheDir.listFiles()?.toSet().orEmpty()
            YoutubeDL.getInstance().execute(request) { progress, _, line ->
                setProgressAsync(androidx.work.workDataOf("progress" to progress.toInt()))
                setForegroundAsync(progressInfo(progress.toInt(), line.take(60)))
            }
            val file = cacheDir.listFiles()?.filterNot { it in before }?.maxByOrNull { it.lastModified() }
                ?: error("لم يتم العثور على الملف الناتج")
            saveToMediaStore(file, audioOnly)
            manager.notify(id.hashCode(), notification("اكتمل التنزيل", 100, false).build())
            file.delete(); Result.success()
        } catch (error: Exception) {
            manager.notify(id.hashCode(), notification("فشل التنزيل: ${error.message.orEmpty().take(80)}", 0, false).build())
            Result.failure()
        }
    }

    private fun saveToMediaStore(file: File, audio: Boolean) {
        val collection = if (audio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (audio) "audio/mpeg" else "video/${file.extension.ifBlank { "mp4" }}")
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (audio) "Music/All Video Downloader" else "Movies/All Video Downloader")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = applicationContext.contentResolver
        val uri = resolver.insert(collection, values) ?: error("تعذر إنشاء ملف الوسائط")
        try {
            resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
            values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0); resolver.update(uri, values, null, null)
        } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "التنزيلات", NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(text: String, progress: Int, ongoing: Boolean) = NotificationCompat.Builder(applicationContext, CHANNEL)
        .setSmallIcon(R.drawable.ic_download).setContentTitle("All Video Downloader").setContentText(text)
        .setProgress(100, progress.coerceIn(0, 100), progress <= 0).setOngoing(ongoing).setOnlyAlertOnce(ongoing).setAutoCancel(!ongoing)
    private fun progressInfo(progress: Int, text: String) = ForegroundInfo(id.hashCode(), notification(text, progress, true).build())

    companion object { const val KEY_URL="url"; const val KEY_FORMAT="format"; const val KEY_AUDIO="audio"; private const val CHANNEL="downloads" }
}
