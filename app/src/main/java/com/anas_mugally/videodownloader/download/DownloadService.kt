package com.anas_mugally.videodownloader.download

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.annotation.RequiresApi
import com.anas_mugally.videodownloader.VideoDownloaderApp
import com.anas_mugally.videodownloader.data.AppRepository
import com.anas_mugally.videodownloader.data.MediaStream
import com.anas_mugally.videodownloader.data.ResolvedDownload
import com.anas_mugally.videodownloader.data.VideoFlowApi
import com.anas_mugally.videodownloader.domain.AppSettings
import com.anas_mugally.videodownloader.domain.DownloadFormatTools
import com.anas_mugally.videodownloader.domain.DownloadKind
import com.anas_mugally.videodownloader.domain.DownloadStatus
import com.anas_mugally.videodownloader.domain.DownloadTask
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DownloadService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestedStops = ConcurrentHashMap<String, DownloadStatus>()
    private lateinit var repository: AppRepository
    private lateinit var api: VideoFlowApi
    private lateinit var notificationManager: NotificationManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var downloadWakeLock: PowerManager.WakeLock
    private lateinit var mediaProcessor: OnDeviceMediaProcessor
    private var queueJob: Job? = null
    private var currentTaskId: String? = null
    @Volatile private var currentConnection: HttpURLConnection? = null
    private var foregroundStarted = false
    private var recoveredInterruptedTasks = false

    override fun onCreate() {
        super.onCreate()
        val app = application as VideoDownloaderApp
        repository = app.repository
        api = app.api
        notificationManager = getSystemService(NotificationManager::class.java)
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        mediaProcessor = OnDeviceMediaProcessor(this)
        downloadWakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:active-download",
        ).apply { setReferenceCounted(false) }
        DownloadNotifications.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        val taskId = intent?.getStringExtra(EXTRA_TASK_ID)
        when (intent?.action) {
            ACTION_PAUSE -> taskId?.let(::pauseTask)
            ACTION_CANCEL -> taskId?.let(::cancelTask)
            ACTION_RESUME, ACTION_RETRY -> taskId?.let(::resumeTask)
            ACTION_ENQUEUE, null -> ensureQueueProcessing()
            else -> ensureQueueProcessing()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        currentConnection?.disconnect()
        mediaProcessor.cancelActive()
        if (downloadWakeLock.isHeld) downloadWakeLock.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        val activeId = currentTaskId
        if (activeId != null) {
            requestedStops[activeId] = DownloadStatus.FAILED
            currentConnection?.disconnect()
            mediaProcessor.cancelActive()
            serviceScope.launch {
                repository.updateTask(activeId) { task ->
                    task.copy(status = DownloadStatus.FAILED, error = "Android foreground-service timeout")
                }
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private fun pauseTask(taskId: String) {
        requestedStops[taskId] = DownloadStatus.PAUSED
        if (currentTaskId == taskId) {
            currentConnection?.disconnect()
            mediaProcessor.cancelActive()
        }
        serviceScope.launch {
            repository.updateTask(taskId) { task -> task.copy(status = DownloadStatus.PAUSED, error = null) }
            stopIfQueueIsIdle()
        }
    }

    private fun cancelTask(taskId: String) {
        requestedStops[taskId] = DownloadStatus.CANCELLED
        val wasCurrent = currentTaskId == taskId
        if (wasCurrent) {
            currentConnection?.disconnect()
            mediaProcessor.cancelActive()
        }
        serviceScope.launch {
            repository.updateTask(taskId) { task -> task.copy(status = DownloadStatus.CANCELLED, error = null) }
            if (!wasCurrent) DownloadController.cleanTaskFiles(this@DownloadService, taskId)
            stopIfQueueIsIdle()
        }
    }

    private fun resumeTask(taskId: String) {
        requestedStops.remove(taskId)
        serviceScope.launch {
            repository.updateTask(taskId) { task -> task.copy(status = DownloadStatus.QUEUED, error = null) }
            ensureQueueProcessing()
        }
    }

    @Synchronized
    private fun ensureQueueProcessing() {
        if (queueJob?.isActive == true) return
        val newJob = serviceScope.launch {
            if (!recoveredInterruptedTasks) {
                repository.recoverInterruptedTasks()
                recoveredInterruptedTasks = true
            }
            processQueue()
        }
        queueJob = newJob
        newJob.invokeOnCompletion {
            if (serviceScope.isActive) {
                serviceScope.launch {
                    val hasRunnableTask = repository.tasks.first().any { task ->
                        task.status == DownloadStatus.QUEUED ||
                            task.status == DownloadStatus.WAITING_FOR_WIFI ||
                            task.status == DownloadStatus.DOWNLOADING
                    }
                    if (hasRunnableTask) ensureQueueProcessing()
                }
            }
        }
    }

    private suspend fun processQueue() {
        try {
            while (serviceScope.isActive) {
                val next = repository.tasks.first()
                    .filter { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.WAITING_FOR_WIFI }
                    .minByOrNull(DownloadTask::createdAt)
                    ?: break
                val settings = repository.settings.first()
                if (!awaitSuitableNetwork(next, settings)) continue
                val latestTask = repository.task(next.id) ?: continue
                if (latestTask.status != DownloadStatus.QUEUED) continue
                runDownload(latestTask, repository.settings.first())
            }
        } finally {
            currentTaskId = null
            stopIfQueueIsIdle()
        }
    }

    private suspend fun awaitSuitableNetwork(task: DownloadTask, settings: AppSettings): Boolean {
        var currentSettings = settings
        var latest = repository.task(task.id) ?: return false
        if (latest.status != DownloadStatus.QUEUED && latest.status != DownloadStatus.WAITING_FOR_WIFI) return false
        while (!hasSuitableNetwork(currentSettings.wifiOnly)) {
            latest = repository.task(task.id) ?: return false
            if (latest.status != DownloadStatus.QUEUED && latest.status != DownloadStatus.WAITING_FOR_WIFI) return false
            repository.updateTask(task.id) { current -> current.copy(status = DownloadStatus.WAITING_FOR_WIFI) }
            repository.task(task.id)?.let(::showActiveNotification)
            delay(NETWORK_POLL_INTERVAL_MS)
            currentSettings = repository.settings.first()
        }
        latest = repository.task(task.id) ?: return false
        if (latest.status != DownloadStatus.QUEUED && latest.status != DownloadStatus.WAITING_FOR_WIFI) return false
        repository.updateTask(task.id) { current ->
            if (current.status == DownloadStatus.QUEUED || current.status == DownloadStatus.WAITING_FOR_WIFI) {
                current.copy(status = DownloadStatus.QUEUED)
            } else current
        }
        return true
    }

    private fun hasSuitableNetwork(wifiOnly: Boolean): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        val online = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return online && (!wifiOnly || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
    }

    private suspend fun runDownload(taskSnapshot: DownloadTask, settings: AppSettings) {
        val taskId = taskSnapshot.id
        currentTaskId = taskId
        requestedStops.remove(taskId)
        repository.updateTask(taskId) { task ->
            task.copy(
                status = DownloadStatus.DOWNLOADING,
                progress = 0,
                downloadedBytes = null,
                totalBytes = null,
                speedBytesPerSecond = null,
                etaSeconds = null,
                error = null,
            )
        }
        repository.task(taskId)?.let(::showActiveNotification)

        val taskDirectory = DownloadController.taskDirectory(this, taskId).apply { mkdirs() }
        if (!downloadWakeLock.isHeld) downloadWakeLock.acquire(DOWNLOAD_WAKE_LOCK_TIMEOUT_MS)
        try {
            val output = createFinalMediaWithRecovery(taskSnapshot, taskDirectory)
            throwIfStopRequested(taskId)
            val saved = MediaStoreWriter.save(
                context = this,
                source = output,
                audioOnly = taskSnapshot.kind == DownloadKind.AUDIO,
                folderName = settings.outputFolder,
            )
            if (requestedStops.containsKey(taskId)) {
                contentResolver.delete(saved.uri, null, null)
                throw MediaProcessingStoppedException()
            }
            repository.updateTask(taskId) { task ->
                task.copy(
                    status = DownloadStatus.COMPLETED,
                    progress = 100,
                    downloadedBytes = task.totalBytes ?: task.downloadedBytes,
                    outputUri = saved.uri.toString(),
                    outputMimeType = saved.mimeType,
                    outputName = saved.displayName,
                    error = null,
                )
            }
            DownloadController.cleanTaskFiles(this, taskId)
            repository.task(taskId)?.let { completed ->
                notificationManager.notify(
                    DownloadNotifications.resultNotificationId(taskId),
                    DownloadNotifications.completed(this, completed),
                )
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            val requestedStatus = requestedStops.remove(taskId)
            when (requestedStatus) {
                DownloadStatus.PAUSED -> repository.updateTask(taskId) { it.copy(status = DownloadStatus.PAUSED) }
                DownloadStatus.CANCELLED -> {
                    repository.updateTask(taskId) { it.copy(status = DownloadStatus.CANCELLED) }
                    DownloadController.cleanTaskFiles(this, taskId)
                }
                else -> {
                    if (!hasSuitableNetwork(settings.wifiOnly)) {
                        repository.updateTask(taskId) { current ->
                            current.copy(status = DownloadStatus.WAITING_FOR_WIFI, error = null)
                        }
                    } else {
                        val message = readableError(error)
                        repository.updateTask(taskId) { current ->
                            current.copy(status = DownloadStatus.FAILED, error = message)
                        }
                        repository.task(taskId)?.let { failed ->
                            notificationManager.notify(
                                DownloadNotifications.resultNotificationId(taskId),
                                DownloadNotifications.failed(this, failed),
                            )
                        }
                    }
                }
            }
        } finally {
            currentConnection?.disconnect()
            currentConnection = null
            if (downloadWakeLock.isHeld) downloadWakeLock.release()
            currentTaskId = null
        }
    }

    private suspend fun createFinalMediaWithRecovery(task: DownloadTask, directory: File): File {
        var firstError: Throwable? = null
        repeat(2) { attempt ->
            try {
                val resolved = api.resolve(task.sourceUrl, task.formatId)
                return createFinalMedia(task, resolved, directory)
            } catch (error: Throwable) {
                if (error is CancellationException || requestedStops.containsKey(task.id)) throw error
                if (attempt == 0) {
                    firstError = error
                    delay(500)
                } else {
                    throw error
                }
            }
        }
        throw firstError ?: IllegalStateException("Unable to resolve download")
    }

    private suspend fun createFinalMedia(task: DownloadTask, resolved: ResolvedDownload, directory: File): File {
        return when (task.kind) {
            DownloadKind.AUDIO -> {
                val stream = resolved.stream ?: resolved.audio ?: error("API did not return an audio stream")
                val source = downloadStream(task, stream, directory, SOURCE_AUDIO_STEM, 0, 90)
                throwIfStopRequested(task.id)
                val extension = stream.extension.lowercase().ifBlank { "m4a" }
                if (extension == "m4a" || extension == "mp4" || extension == "aac") {
                    val output = File(directory, DownloadFormatTools.outputFileName(task, "m4a"))
                    moveFile(source, output)
                    updateProgress(task.id, 99)
                    output
                } else {
                    updateProgress(task.id, 92)
                    val output = File(directory, DownloadFormatTools.outputFileName(task, "m4a"))
                    mediaProcessor.convertToM4a(source, output)
                    updateProgress(task.id, 99)
                    output
                }
            }

            DownloadKind.VIDEO -> {
                if (resolved.requiresMerge) {
                    val videoStream = resolved.video ?: error("API did not return the video stream")
                    val audioStream = resolved.audio ?: error("API did not return the audio stream")
                    val video = downloadStream(task, videoStream, directory, SOURCE_VIDEO_STEM, 0, 72)
                    throwIfStopRequested(task.id)
                    val audio = downloadStream(task, audioStream, directory, COMPANION_AUDIO_STEM, 72, 88)
                    throwIfStopRequested(task.id)
                    val muxAudio = if (audioStream.extension.equals("m4a", true) || audioStream.extension.equals("mp4", true)) {
                        audio
                    } else {
                        updateProgress(task.id, 90)
                        File(directory, "$NORMALIZED_AUDIO_STEM.m4a").also { normalized ->
                            mediaProcessor.convertToM4a(audio, normalized)
                        }
                    }
                    throwIfStopRequested(task.id)
                    updateProgress(task.id, 95)
                    val output = File(directory, DownloadFormatTools.outputFileName(task, "mp4"))
                    mediaProcessor.muxMp4(video, muxAudio, output) { requestedStops.containsKey(task.id) }
                    updateProgress(task.id, 99)
                    output
                } else {
                    val stream = resolved.stream ?: error("API did not return a direct video stream")
                    val source = downloadStream(task, stream, directory, SOURCE_VIDEO_STEM, 0, 98)
                    val extension = stream.extension.lowercase().ifBlank { resolved.extension.lowercase().ifBlank { "mp4" } }
                    val output = File(directory, DownloadFormatTools.outputFileName(task, extension))
                    moveFile(source, output)
                    updateProgress(task.id, 99)
                    output
                }
            }
        }
    }

    private suspend fun downloadStream(
        task: DownloadTask,
        stream: MediaStream,
        directory: File,
        outputStem: String,
        progressStart: Int,
        progressEnd: Int,
    ): File {
        val extension = stream.extension.lowercase().filter(Char::isLetterOrDigit).ifBlank { "bin" }
        val finalFile = File(directory, "$outputStem.$extension")
        val partial = File(directory, "$outputStem.$extension.part")
        if (finalFile.isFile && stream.fileSize != null && finalFile.length() == stream.fileSize) return finalFile
        if (finalFile.exists()) finalFile.delete()

        var existing = partial.length().coerceAtLeast(0L)
        var connection = openStreamConnection(stream, existing)
        var code = connection.responseCode
        if (existing > 0L && code == HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            partial.delete()
            existing = 0L
            connection = openStreamConnection(stream, 0L)
            code = connection.responseCode
        }
        if (code !in listOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL)) {
            connection.disconnect()
            error("HTTP $code while downloading media")
        }
        currentConnection = connection

        val contentLength = connection.contentLengthLong.takeIf { it > 0L }
        val total = when {
            code == HttpURLConnection.HTTP_PARTIAL && contentLength != null -> existing + contentLength
            stream.fileSize != null -> stream.fileSize
            else -> contentLength
        }
        val append = code == HttpURLConnection.HTTP_PARTIAL && existing > 0L
        var downloaded = if (append) existing else 0L
        var lastBytes = downloaded
        var lastAt = System.currentTimeMillis()
        var lastUiAt = 0L

        try {
            connection.inputStream.buffered().use { input ->
                FileOutputStream(partial, append).buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        throwIfStopRequested(task.id)
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        val now = System.currentTimeMillis()
                        if (now - lastUiAt >= PROGRESS_UPDATE_INTERVAL_MS) {
                            val elapsed = (now - lastAt).coerceAtLeast(1L)
                            val speed = ((downloaded - lastBytes) * 1000L / elapsed).coerceAtLeast(0L)
                            lastAt = now
                            lastBytes = downloaded
                            lastUiAt = now
                            val localPercent = if (total != null && total > 0L) {
                                ((downloaded * 100.0) / total).roundToInt().coerceIn(0, 100)
                            } else 0
                            val percent = progressStart +
                                ((progressEnd - progressStart) * localPercent / 100f).roundToInt()
                            val eta = if (total != null && speed > 0L) ((total - downloaded).coerceAtLeast(0L) / speed) else null
                            updateProgress(task.id, percent, downloaded, total, speed, eta)
                        }
                    }
                    output.flush()
                }
            }
        } finally {
            connection.disconnect()
            if (currentConnection === connection) currentConnection = null
        }
        throwIfStopRequested(task.id)
        if (total != null && partial.length() < total) error("Media download ended before all bytes were received")
        if (!partial.renameTo(finalFile)) {
            partial.copyTo(finalFile, overwrite = true)
            partial.delete()
        }
        updateProgress(task.id, progressEnd, finalFile.length(), total ?: finalFile.length(), 0L, 0L)
        return finalFile
    }

    private fun openStreamConnection(stream: MediaStream, existingBytes: Long): HttpURLConnection {
        return (URL(stream.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 30_000
            useCaches = false
            setRequestProperty("Accept-Encoding", "identity")
            stream.headers.forEach { (name, value) ->
                if (name.isBlank() || value.isBlank()) return@forEach
                if (name.equals("Host", true) || name.equals("Content-Length", true) || name.equals("Range", true)) return@forEach
                setRequestProperty(name, value)
            }
            if (existingBytes > 0L) setRequestProperty("Range", "bytes=$existingBytes-")
        }
    }

    private suspend fun updateProgress(
        taskId: String,
        percent: Int,
        downloadedBytes: Long? = null,
        totalBytes: Long? = null,
        speedBytesPerSecond: Long? = null,
        etaSeconds: Long? = null,
    ) {
        repository.updateTask(taskId) { task ->
            if (task.status == DownloadStatus.DOWNLOADING) {
                task.copy(
                    progress = maxOf(task.progress, percent.coerceIn(0, 99)),
                    downloadedBytes = downloadedBytes ?: task.downloadedBytes,
                    totalBytes = totalBytes ?: task.totalBytes,
                    speedBytesPerSecond = speedBytesPerSecond ?: task.speedBytesPerSecond,
                    etaSeconds = etaSeconds ?: task.etaSeconds,
                )
            } else task
        }
        repository.task(taskId)?.takeIf { it.status == DownloadStatus.DOWNLOADING }?.let(::showActiveNotification)
    }

    private fun throwIfStopRequested(taskId: String) {
        if (requestedStops.containsKey(taskId)) throw MediaProcessingStoppedException()
    }

    private fun moveFile(source: File, destination: File) {
        if (source.absolutePath == destination.absolutePath) return
        if (destination.exists()) destination.delete()
        if (!source.renameTo(destination)) {
            source.copyTo(destination, overwrite = true)
            source.delete()
        }
    }

    private fun readableError(error: Throwable): String = error.message
        ?.lineSequence()
        ?.lastOrNull { it.isNotBlank() }
        ?.trim()
        ?.take(MAX_ERROR_LENGTH)
        ?: error::class.java.simpleName

    private fun promoteToForeground() {
        if (foregroundStarted) return
        startForeground(
            DownloadNotifications.FOREGROUND_NOTIFICATION_ID,
            DownloadNotifications.preparing(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        foregroundStarted = true
    }

    private fun showActiveNotification(task: DownloadTask) {
        startForeground(
            DownloadNotifications.FOREGROUND_NOTIFICATION_ID,
            DownloadNotifications.active(this, task),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        foregroundStarted = true
    }

    private suspend fun stopIfQueueIsIdle() {
        val hasRunnableTask = repository.tasks.first().any { task ->
            task.status == DownloadStatus.QUEUED ||
                task.status == DownloadStatus.WAITING_FOR_WIFI ||
                task.status == DownloadStatus.DOWNLOADING
        }
        if (!hasRunnableTask && currentTaskId == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
            stopSelf()
        }
    }

    companion object {
        const val ACTION_ENQUEUE = "com.anas_mugally.videodownloader.action.ENQUEUE"
        const val ACTION_PAUSE = "com.anas_mugally.videodownloader.action.PAUSE"
        const val ACTION_RESUME = "com.anas_mugally.videodownloader.action.RESUME"
        const val ACTION_RETRY = "com.anas_mugally.videodownloader.action.RETRY"
        const val ACTION_CANCEL = "com.anas_mugally.videodownloader.action.CANCEL"
        const val EXTRA_TASK_ID = "task_id"
        private const val SOURCE_AUDIO_STEM = "source-audio"
        private const val SOURCE_VIDEO_STEM = "source-video"
        private const val COMPANION_AUDIO_STEM = "companion-audio-source"
        private const val NORMALIZED_AUDIO_STEM = "companion-audio"
        private const val PROGRESS_UPDATE_INTERVAL_MS = 600L
        private const val NETWORK_POLL_INTERVAL_MS = 3_000L
        private const val DOWNLOAD_WAKE_LOCK_TIMEOUT_MS = 6L * 60L * 60L * 1_000L
        private const val MAX_ERROR_LENGTH = 500
    }
}
