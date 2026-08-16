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
import com.anas_mugally.videodownloader.data.YtDlpRuntime
import com.anas_mugally.videodownloader.domain.AppSettings
import com.anas_mugally.videodownloader.domain.DownloadFormatTools
import com.anas_mugally.videodownloader.domain.DownloadKind
import com.anas_mugally.videodownloader.domain.DownloadStatus
import com.anas_mugally.videodownloader.domain.DownloadTask
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
    private lateinit var runtime: YtDlpRuntime
    private lateinit var notificationManager: NotificationManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var downloadWakeLock: PowerManager.WakeLock
    private lateinit var mediaProcessor: OnDeviceMediaProcessor
    private var queueJob: Job? = null
    private var currentTaskId: String? = null
    private var foregroundStarted = false
    private var recoveredInterruptedTasks = false

    override fun onCreate() {
        super.onCreate()
        val app = application as VideoDownloaderApp
        repository = app.repository
        runtime = app.ytDlpRuntime
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
        currentTaskId?.let { YoutubeDL.getInstance().destroyProcessById(it) }
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
            YoutubeDL.getInstance().destroyProcessById(activeId)
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
            YoutubeDL.getInstance().destroyProcessById(taskId)
            mediaProcessor.cancelActive()
        }
        serviceScope.launch {
            repository.updateTask(taskId) { task ->
                task.copy(status = DownloadStatus.PAUSED, error = null)
            }
            stopIfQueueIsIdle()
        }
    }

    private fun cancelTask(taskId: String) {
        requestedStops[taskId] = DownloadStatus.CANCELLED
        val wasCurrent = currentTaskId == taskId
        if (wasCurrent) {
            YoutubeDL.getInstance().destroyProcessById(taskId)
            mediaProcessor.cancelActive()
        }
        serviceScope.launch {
            repository.updateTask(taskId) { task ->
                task.copy(status = DownloadStatus.CANCELLED, error = null)
            }
            if (!wasCurrent) DownloadController.cleanTaskFiles(this@DownloadService, taskId)
            stopIfQueueIsIdle()
        }
    }

    private fun resumeTask(taskId: String) {
        requestedStops.remove(taskId)
        serviceScope.launch {
            repository.updateTask(taskId) { task ->
                task.copy(status = DownloadStatus.QUEUED, error = null)
            }
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
        if (latest.status != DownloadStatus.QUEUED && latest.status != DownloadStatus.WAITING_FOR_WIFI) {
            return false
        }
        while (!hasSuitableNetwork(currentSettings.wifiOnly)) {
            latest = repository.task(task.id) ?: return false
            if (latest.status != DownloadStatus.QUEUED && latest.status != DownloadStatus.WAITING_FOR_WIFI) {
                return false
            }
            repository.updateTask(task.id) { current ->
                current.copy(status = DownloadStatus.WAITING_FOR_WIFI)
            }
            repository.task(task.id)?.let(::showActiveNotification)
            delay(NETWORK_POLL_INTERVAL_MS)
            currentSettings = repository.settings.first()
        }
        latest = repository.task(task.id) ?: return false
        if (latest.status != DownloadStatus.QUEUED && latest.status != DownloadStatus.WAITING_FOR_WIFI) {
            return false
        }
        repository.updateTask(task.id) { current ->
            if (current.status == DownloadStatus.QUEUED || current.status == DownloadStatus.WAITING_FOR_WIFI) {
                current.copy(status = DownloadStatus.QUEUED)
            } else {
                current
            }
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
            task.copy(status = DownloadStatus.DOWNLOADING, error = null)
        }
        repository.task(taskId)?.let(::showActiveNotification)

        val taskDirectory = DownloadController.taskDirectory(this, taskId).apply { mkdirs() }
        if (!downloadWakeLock.isHeld) downloadWakeLock.acquire(DOWNLOAD_WAKE_LOCK_TIMEOUT_MS)
        try {
            runtime.ensureReady()
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
            if (downloadWakeLock.isHeld) downloadWakeLock.release()
            currentTaskId = null
        }
    }

    private suspend fun createFinalMediaWithRecovery(task: DownloadTask, directory: File): File {
        return try {
            createFinalMedia(task, directory)
        } catch (error: Throwable) {
            if (error is CancellationException || !runtime.recoverFromDownloadError(error)) throw error
            throwIfStopRequested(task.id)
            directory.listFiles().orEmpty().forEach { file ->
                check(file.deleteRecursively()) { "Unable to reset partial download files" }
            }
            updateProgress(task.id, 0)
            createFinalMedia(task, directory)
        }
    }

    private suspend fun createFinalMedia(task: DownloadTask, directory: File): File {
        return when (task.kind) {
            DownloadKind.AUDIO -> {
                val source = executeDownload(
                    task = task,
                    selector = DownloadFormatTools.primarySelector(task),
                    directory = directory,
                    outputStem = SOURCE_AUDIO_STEM,
                    progressStart = 0,
                    progressEnd = 88,
                )
                throwIfStopRequested(task.id)
                updateProgress(task.id, 90)
                val output = File(directory, DownloadFormatTools.outputFileName(task, "m4a"))
                mediaProcessor.convertToM4a(source, output)
                updateProgress(task.id, 98)
                output
            }

            DownloadKind.VIDEO -> {
                val downloadEnd = if (task.formatHasAudio) 96 else 72
                val video = executeDownload(
                    task = task,
                    selector = DownloadFormatTools.primarySelector(task),
                    directory = directory,
                    outputStem = SOURCE_VIDEO_STEM,
                    progressStart = 0,
                    progressEnd = downloadEnd,
                )
                throwIfStopRequested(task.id)
                val output = File(directory, DownloadFormatTools.outputFileName(task, "mp4"))
                if (task.formatHasAudio) {
                    require(video.extension.equals("mp4", ignoreCase = true)) {
                        "Selected video is not an MP4 file"
                    }
                    moveFile(video, output)
                    updateProgress(task.id, 99)
                    output
                } else {
                    val audio = executeDownload(
                        task = task,
                        selector = DownloadFormatTools.companionAudioSelector(),
                        directory = directory,
                        outputStem = COMPANION_AUDIO_STEM,
                        progressStart = 72,
                        progressEnd = 86,
                    )
                    throwIfStopRequested(task.id)
                    val normalizedAudio = File(directory, "$NORMALIZED_AUDIO_STEM.m4a")
                    updateProgress(task.id, 88)
                    mediaProcessor.convertToM4a(audio, normalizedAudio)
                    throwIfStopRequested(task.id)
                    updateProgress(task.id, 94)
                    mediaProcessor.muxMp4(video, normalizedAudio, output) {
                        requestedStops.containsKey(task.id)
                    }
                    updateProgress(task.id, 99)
                    output
                }
            }
        }
    }

    private suspend fun executeDownload(
        task: DownloadTask,
        selector: String,
        directory: File,
        outputStem: String,
        progressStart: Int,
        progressEnd: Int,
    ): File {
        val request = YoutubeDLRequest(task.sourceUrl).apply {
            addOption("-f", selector)
            addOption("-o", File(directory, "$outputStem.%(ext)s").absolutePath)
            addOption("--no-playlist")
            addOption("--newline")
            addOption("--continue")
            addOption("--fixup", "never")
            addOption("--retries", 10)
            addOption("--fragment-retries", 10)
            addOption("--retry-sleep", "exp=1:20")
            addOption("--print", "after_move:$OUTPUT_MARKER%(filepath)s")
            if (runtime.hasCookies()) addOption("--cookies", runtime.cookiesFile().absolutePath)
        }
        val lastUpdateAt = AtomicLong(0L)
        val lastProgress = AtomicInteger(-1)
        val response = YoutubeDL.getInstance().execute(request, task.id) { progress, _, _ ->
            val scaled = progressStart +
                ((progressEnd - progressStart) * progress.coerceIn(0f, 100f) / 100f).roundToInt()
            val percent = scaled.coerceIn(progressStart, progressEnd)
            val now = System.currentTimeMillis()
            val shouldUpdate = percent == progressEnd ||
                (percent != lastProgress.get() && now - lastUpdateAt.get() >= PROGRESS_UPDATE_INTERVAL_MS)
            if (shouldUpdate) {
                lastProgress.set(percent)
                lastUpdateAt.set(now)
                serviceScope.launch { updateProgress(task.id, percent) }
            }
        }
        throwIfStopRequested(task.id)
        return findOutputFile(response.out, directory, outputStem)
    }

    private suspend fun updateProgress(taskId: String, percent: Int) {
        repository.updateTask(taskId) { task ->
            if (task.status == DownloadStatus.DOWNLOADING) {
                task.copy(progress = maxOf(task.progress, percent.coerceIn(0, 99)))
            } else {
                task
            }
        }
        repository.task(taskId)?.takeIf { it.status == DownloadStatus.DOWNLOADING }
            ?.let(::showActiveNotification)
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

    private fun findOutputFile(stdout: String, directory: File, outputStem: String): File {
        val markedPath = stdout.lineSequence()
            .map(String::trim)
            .lastOrNull { it.startsWith(OUTPUT_MARKER) }
            ?.removePrefix(OUTPUT_MARKER)
        val markedFile = markedPath?.let(::File)?.takeIf(File::isFile)
        return markedFile ?: directory.walkTopDown()
            .filter(File::isFile)
            .filter { it.nameWithoutExtension == outputStem }
            .filterNot { file ->
                file.name.endsWith(".part") ||
                    file.name.endsWith(".ytdl") ||
                    file.name.endsWith(".json")
            }
            .maxByOrNull(File::lastModified)
            ?: error("Downloaded output file was not found")
    }

    private fun readableError(error: Throwable): String {
        return error.message
            ?.lineSequence()
            ?.lastOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(MAX_ERROR_LENGTH)
            ?: error::class.java.simpleName
    }

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
        private const val OUTPUT_MARKER = "__AVD_FILE__"
        private const val SOURCE_AUDIO_STEM = "source-audio"
        private const val SOURCE_VIDEO_STEM = "source-video"
        private const val COMPANION_AUDIO_STEM = "companion-audio-source"
        private const val NORMALIZED_AUDIO_STEM = "companion-audio"
        private const val PROGRESS_UPDATE_INTERVAL_MS = 750L
        private const val NETWORK_POLL_INTERVAL_MS = 3_000L
        private const val DOWNLOAD_WAKE_LOCK_TIMEOUT_MS = 6L * 60L * 60L * 1_000L
        private const val MAX_ERROR_LENGTH = 500
    }
}
