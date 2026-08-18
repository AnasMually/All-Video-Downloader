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
import android.util.Log
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
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DownloadService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestedStops = ConcurrentHashMap<String, DownloadStatus>()
    private val activeConnections = ConcurrentHashMap.newKeySet<HttpURLConnection>()

    private lateinit var repository: AppRepository
    private lateinit var api: VideoFlowApi
    private lateinit var notificationManager: NotificationManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var downloadWakeLock: PowerManager.WakeLock
    private lateinit var mediaProcessor: OnDeviceMediaProcessor

    private var queueJob: Job? = null
    private var currentTaskId: String? = null
    private var foregroundStarted = false
    private var recoveredInterruptedTasks = false
    private var aggregateProgressTaskId: String? = null
    private var aggregateProgressOffsetBytes = 0L
    private var aggregateProgressTotalBytes: Long? = null

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
        disconnectActiveConnections()
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
            disconnectActiveConnections()
            mediaProcessor.cancelActive()
            serviceScope.launch {
                repository.updateTask(activeId) { task ->
                    task.copy(status = DownloadStatus.FAILED, error = getString(com.anas_mugally.videodownloader.R.string.download_failed_user))
                }
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private fun pauseTask(taskId: String) {
        requestedStops[taskId] = DownloadStatus.PAUSED
        if (currentTaskId == taskId) {
            disconnectActiveConnections()
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
            disconnectActiveConnections()
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
            disconnectActiveConnections()
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

    private suspend fun createFinalMedia(
        task: DownloadTask,
        resolved: ResolvedDownload,
        directory: File,
    ): File {
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
                    val videoStream = resolved.video ?: error("Video source is unavailable")
          val audioStream = resolved.audio ?: error("Audio source is unavailable")
          // Use a byte-accurate combined scale when both stream sizes are
          // known. If either size is unknown, reserve 0-75% for the video
          // and 75-90% for companion audio so progress never reaches 90%
          // after only the first stream and then appears to download again.
          val expectedTotal = if (videoStream.fileSize != null && audioStream.fileSize != null) {
              videoStream.fileSize + audioStream.fileSize
          } else {
              null
          }
          val useAggregateBytes = expectedTotal != null && expectedTotal > 0L
          if (useAggregateBytes) beginAggregateProgress(task.id, expectedTotal)

          val video: File
          val audio: File
          try {
              val videoEnd = if (useAggregateBytes) 90 else 75
              video = downloadStream(task, videoStream, directory, SOURCE_VIDEO_STEM, 0, videoEnd)
              throwIfStopRequested(task.id)
              if (useAggregateBytes) aggregateProgressOffsetBytes = video.length()
              val audioStart = if (useAggregateBytes) 0 else videoEnd
              audio = downloadStream(task, audioStream, directory, COMPANION_AUDIO_STEM, audioStart, 90)
              throwIfStopRequested(task.id)
          } finally {
              if (useAggregateBytes) endAggregateProgress(task.id)
          }

          val transferredBytes = video.length() + audio.length()
                    updateProgress(
                        task.id,
                        90,
                        transferredBytes,
                        transferredBytes,
                        0L,
                        0L,
                    )

                    val muxAudio = if (
                        audioStream.extension.equals("m4a", true) ||
                        audioStream.extension.equals("mp4", true)
                    ) {
                        audio
                    } else {
                        updateProgress(task.id, 92, speedBytesPerSecond = 0L, etaSeconds = 0L)
                        File(directory, "$NORMALIZED_AUDIO_STEM.m4a").also { normalized ->
                            mediaProcessor.convertToM4a(audio, normalized)
                        }
                    }
                    throwIfStopRequested(task.id)
                    updateProgress(task.id, 95, speedBytesPerSecond = 0L, etaSeconds = 0L)
                    val output = File(directory, DownloadFormatTools.outputFileName(task, "mp4"))
                    mediaProcessor.muxMp4(video, muxAudio, output) { requestedStops.containsKey(task.id) }
                    updateProgress(task.id, 99, speedBytesPerSecond = 0L, etaSeconds = 0L)
                    output
                } else {
                    val stream = resolved.stream ?: error("API did not return a direct video stream")
                    val source = downloadStream(task, stream, directory, SOURCE_VIDEO_STEM, 0, 98)
                    val extension = stream.extension.lowercase()
                        .ifBlank { resolved.extension.lowercase().ifBlank { "mp4" } }
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
        if (stream.isAdaptiveManifest) {
            val adaptiveFile = File(directory, "$outputStem.mp4")
            if (adaptiveFile.isFile && adaptiveFile.length() > 0L) return adaptiveFile
            if (adaptiveFile.exists()) adaptiveFile.delete()
            updateProgress(task.id, progressStart)
            mediaProcessor.materializeAdaptiveStream(stream, adaptiveFile)
            throwIfStopRequested(task.id)
            require(adaptiveFile.isFile && adaptiveFile.length() > 0L) {
                "Adaptive media export produced an empty file"
            }
            updateProgress(
                task.id,
                progressEnd,
                adaptiveFile.length(),
                adaptiveFile.length(),
                0L,
                0L,
            )
            return adaptiveFile
        }

        val extension = stream.extension.lowercase().filter(Char::isLetterOrDigit).ifBlank { "bin" }
        val finalFile = File(directory, "$outputStem.$extension")
        if (finalFile.isFile && stream.fileSize != null && finalFile.length() == stream.fileSize) {
            return finalFile
        }
        if (finalFile.exists()) finalFile.delete()

        val rangeSupport = probeRangeSupport(stream)
        if (rangeSupport != null && rangeSupport.totalBytes >= MIN_PARALLEL_DOWNLOAD_BYTES) {
            val segments = parallelSegmentCount(rangeSupport.totalBytes)
            if (segments > 1) {
                return try {
                    downloadStreamParallel(
                        task = task,
                        stream = stream,
                        directory = directory,
                        outputStem = outputStem,
                        extension = extension,
                        finalFile = finalFile,
                        total = rangeSupport.totalBytes,
                        segmentCount = segments,
                        progressStart = progressStart,
                        progressEnd = progressEnd,
                    )
                } catch (error: RangeDownloadUnsupportedException) {
                    deleteSegmentParts(directory, outputStem, extension)
                    downloadStreamSingle(
                        task = task,
                        stream = stream,
                        finalFile = finalFile,
                        partial = File(directory, "$outputStem.$extension.part"),
                        progressStart = progressStart,
                        progressEnd = progressEnd,
                    )
                }
            }
        }

        return downloadStreamSingle(
            task = task,
            stream = stream,
            finalFile = finalFile,
            partial = File(directory, "$outputStem.$extension.part"),
            progressStart = progressStart,
            progressEnd = progressEnd,
        )
    }

    private suspend fun downloadStreamSingle(
        task: DownloadTask,
        stream: MediaStream,
        finalFile: File,
        partial: File,
        progressStart: Int,
        progressEnd: Int,
    ): File {
        var existing = partial.length().coerceAtLeast(0L)
        var connection = openStreamConnection(
            stream = stream,
            rangeStart = existing.takeIf { it > 0L },
        )
        registerConnection(connection)
        var code = connection.responseCode

        if (existing > 0L && code == HttpURLConnection.HTTP_OK) {
            unregisterConnection(connection)
            connection.disconnect()
            partial.delete()
            existing = 0L
            connection = openStreamConnection(stream = stream)
            registerConnection(connection)
            code = connection.responseCode
        }

        if (code !in listOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL)) {
            unregisterConnection(connection)
            connection.disconnect()
            error("HTTP $code while downloading media")
        }

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
        var smoothedSpeed = 0L

        try {
            connection.inputStream.buffered(NETWORK_BUFFER_BYTES).use { input ->
                FileOutputStream(partial, append).buffered(NETWORK_BUFFER_BYTES).use { output ->
                    val buffer = ByteArray(NETWORK_BUFFER_BYTES)
                    while (true) {
                        throwIfStopRequested(task.id)
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        val now = System.currentTimeMillis()
                        if (now - lastUiAt >= PROGRESS_UPDATE_INTERVAL_MS) {
                            val elapsed = (now - lastAt).coerceAtLeast(1L)
                            val instantSpeed = ((downloaded - lastBytes) * 1000L / elapsed).coerceAtLeast(0L)
                            smoothedSpeed = smoothSpeed(smoothedSpeed, instantSpeed)
                            lastAt = now
                            lastBytes = downloaded
                            lastUiAt = now
                            publishStreamProgress(
                                taskId = task.id,
                                downloaded = downloaded,
                                total = total,
                                speed = smoothedSpeed,
                                progressStart = progressStart,
                                progressEnd = progressEnd,
                            )
                        }
                    }
                    output.flush()
                }
            }
        } finally {
            unregisterConnection(connection)
            connection.disconnect()
        }

        throwIfStopRequested(task.id)
        if (total != null && partial.length() < total) {
            error("Media download ended before all bytes were received")
        }
        if (!partial.renameTo(finalFile)) {
            partial.copyTo(finalFile, overwrite = true)
            partial.delete()
        }
        updateProgress(
            task.id,
            progressEnd,
            finalFile.length(),
            total ?: finalFile.length(),
            0L,
            0L,
        )
        return finalFile
    }

    private suspend fun downloadStreamParallel(
        task: DownloadTask,
        stream: MediaStream,
        directory: File,
        outputStem: String,
        extension: String,
        finalFile: File,
        total: Long,
        segmentCount: Int,
        progressStart: Int,
        progressEnd: Int,
    ): File = coroutineScope {
        val segmentSize = (total + segmentCount - 1L) / segmentCount
        val segments = (0 until segmentCount).mapNotNull { index ->
            val start = index * segmentSize
            if (start >= total) {
                null
            } else {
                val end = minOf(total - 1L, start + segmentSize - 1L)
                val file = File(directory, "$outputStem.$extension.segment-$index.part")
                Segment(index = index, start = start, end = end, file = file)
            }
        }

        val existingBytes = segments.sumOf { segment ->
            val expected = segment.length
            val currentLength = segment.file.length()
            when {
                currentLength == expected -> expected
                currentLength > 0L && currentLength < expected -> currentLength
                else -> {
                    if (segment.file.exists()) segment.file.delete()
                    0L
                }
            }
        }
        val downloadedBytes = AtomicLong(existingBytes)
        var monitorSpeed = 0L

        val downloads = segments.map { segment ->
            async(Dispatchers.IO) {
                downloadSegment(task, stream, segment, downloadedBytes)
            }
        }

        val monitor = launch(Dispatchers.IO) {
            var lastBytes = downloadedBytes.get()
            var lastAt = System.currentTimeMillis()
            while (isActive) {
                delay(PROGRESS_UPDATE_INTERVAL_MS)
                val now = System.currentTimeMillis()
                val current = downloadedBytes.get()
                val elapsed = (now - lastAt).coerceAtLeast(1L)
                val instantSpeed = ((current - lastBytes) * 1000L / elapsed).coerceAtLeast(0L)
                monitorSpeed = smoothSpeed(monitorSpeed, instantSpeed)
                lastBytes = current
                lastAt = now
                publishStreamProgress(
                    taskId = task.id,
                    downloaded = current,
                    total = total,
                    speed = monitorSpeed,
                    progressStart = progressStart,
                    progressEnd = progressEnd,
                )
            }
        }

        try {
            downloads.awaitAll()
        } finally {
            monitor.cancel()
        }

        throwIfStopRequested(task.id)
        segments.forEach { segment ->
            if (segment.file.length() != segment.length) {
                error("Parallel media segment ${segment.index} ended before all bytes were received")
            }
        }

        FileOutputStream(finalFile, false).buffered(NETWORK_BUFFER_BYTES).use { output ->
            segments.sortedBy(Segment::index).forEach { segment ->
                segment.file.inputStream().buffered(NETWORK_BUFFER_BYTES).use { input ->
                    input.copyTo(output, NETWORK_BUFFER_BYTES)
                }
            }
            output.flush()
        }
        if (finalFile.length() != total) {
            finalFile.delete()
            error("Parallel media download size mismatch")
        }

        segments.forEach { it.file.delete() }
        File(directory, "$outputStem.$extension.part").delete()
        updateProgress(task.id, progressEnd, total, total, 0L, 0L)
        finalFile
    }

    private fun downloadSegment(
        task: DownloadTask,
        stream: MediaStream,
        segment: Segment,
        downloadedBytes: AtomicLong,
    ) {
        val expectedLength = segment.length
        var existing = segment.file.length()
        if (existing == expectedLength) return
        if (existing < 0L || existing > expectedLength) {
            segment.file.delete()
            existing = 0L
        }

        val requestStart = segment.start + existing
        val connection = openStreamConnection(
            stream = stream,
            rangeStart = requestStart,
            rangeEnd = segment.end,
        )
        registerConnection(connection)

        try {
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                throw RangeDownloadUnsupportedException()
            }
            if (code != HttpURLConnection.HTTP_PARTIAL) {
                error("HTTP $code while downloading media segment")
            }

            var remaining = expectedLength - existing
            connection.inputStream.buffered(NETWORK_BUFFER_BYTES).use { input ->
                FileOutputStream(segment.file, true).buffered(NETWORK_BUFFER_BYTES).use { output ->
                    val buffer = ByteArray(NETWORK_BUFFER_BYTES)
                    while (remaining > 0L) {
                        throwIfStopRequested(task.id)
                        val maxRead = minOf(buffer.size.toLong(), remaining).toInt()
                        val count = input.read(buffer, 0, maxRead)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        remaining -= count
                        downloadedBytes.addAndGet(count.toLong())
                    }
                    output.flush()
                }
            }
            if (segment.file.length() != expectedLength) {
                error("Media segment ended before all bytes were received")
            }
        } finally {
            unregisterConnection(connection)
            connection.disconnect()
        }
    }

    private fun probeRangeSupport(stream: MediaStream): RangeSupport? {
        val connection = openStreamConnection(stream, rangeStart = 0L, rangeEnd = 0L)
        registerConnection(connection)
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_PARTIAL) return null
            val contentRange = connection.getHeaderField("Content-Range") ?: return null
            val total = contentRange.substringAfterLast('/').trim().toLongOrNull() ?: return null
            if (total <= 1L) return null

            runCatching {
                connection.inputStream.use { input ->
                    val probe = ByteArray(1)
                    input.read(probe)
                }
            }
            RangeSupport(total)
        } catch (_: Throwable) {
            null
        } finally {
            unregisterConnection(connection)
            connection.disconnect()
        }
    }

    private fun openStreamConnection(
        stream: MediaStream,
        rangeStart: Long? = null,
        rangeEnd: Long? = null,
    ): HttpURLConnection {
        return (URL(stream.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 45_000
            useCaches = false
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Connection", "Keep-Alive")
            stream.headers.forEach { (name, value) ->
                if (name.isBlank() || value.isBlank()) return@forEach
                if (
                    name.equals("Host", true) ||
                    name.equals("Content-Length", true) ||
                    name.equals("Range", true) ||
                    name.equals("Accept-Encoding", true)
                ) {
                    return@forEach
                }
                setRequestProperty(name, value)
            }
            if (rangeStart != null) {
                val suffix = rangeEnd?.let { "-$it" } ?: "-"
                setRequestProperty("Range", "bytes=$rangeStart$suffix")
            }
        }
    }

    private suspend fun publishStreamProgress(
        taskId: String,
        downloaded: Long,
        total: Long?,
        speed: Long,
        progressStart: Int,
        progressEnd: Int,
    ) {
        val localPercent = if (total != null && total > 0L) {
            ((downloaded * 100.0) / total).roundToInt().coerceIn(0, 100)
        } else {
            0
        }
        val percent = progressStart +
            ((progressEnd - progressStart) * localPercent / 100f).roundToInt()
        val eta = if (total != null && speed > 0L) {
            ((total - downloaded).coerceAtLeast(0L) / speed)
        } else {
            null
        }
        updateProgress(taskId, percent, downloaded, total, speed, eta)
    }

    private fun smoothSpeed(previous: Long, current: Long): Long {
        if (previous <= 0L) return current
        if (current <= 0L) return previous
        return ((previous * 7L) + (current * 3L)) / 10L
    }

    private fun parallelSegmentCount(totalBytes: Long): Int {
        val bySize = ((totalBytes + MIN_SEGMENT_BYTES - 1L) / MIN_SEGMENT_BYTES)
            .coerceAtLeast(1L)
            .coerceAtMost(MAX_PARALLEL_SEGMENTS.toLong())
            .toInt()
        return bySize.coerceAtLeast(1)
    }

    private fun deleteSegmentParts(directory: File, outputStem: String, extension: String) {
        directory.listFiles()
            ?.filter { file ->
                file.name.startsWith("$outputStem.$extension.segment-") &&
                    file.name.endsWith(".part")
            }
            ?.forEach(File::delete)
    }

    private fun registerConnection(connection: HttpURLConnection) {
        activeConnections.add(connection)
    }

    private fun unregisterConnection(connection: HttpURLConnection) {
        activeConnections.remove(connection)
    }

    private fun disconnectActiveConnections() {
        activeConnections.toList().forEach { connection ->
            runCatching { connection.disconnect() }
        }
        activeConnections.clear()
    }

    private fun beginAggregateProgress(taskId: String, totalBytes: Long?) {
        aggregateProgressTaskId = taskId
        aggregateProgressOffsetBytes = 0L
        aggregateProgressTotalBytes = totalBytes
    }

    private fun endAggregateProgress(taskId: String) {
        if (aggregateProgressTaskId == taskId) {
            aggregateProgressTaskId = null
            aggregateProgressOffsetBytes = 0L
            aggregateProgressTotalBytes = null
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
        val aggregating = aggregateProgressTaskId == taskId && downloadedBytes != null
        val effectiveDownloaded = if (aggregating) {
            aggregateProgressOffsetBytes + requireNotNull(downloadedBytes)
        } else {
            downloadedBytes
        }
        val effectiveTotal = if (aggregating) {
            aggregateProgressTotalBytes ?: totalBytes?.let { aggregateProgressOffsetBytes + it }
        } else {
            totalBytes
        }
        val effectivePercent = if (
            aggregating && effectiveDownloaded != null && effectiveTotal != null && effectiveTotal > 0L
        ) {
            ((effectiveDownloaded * 90.0) / effectiveTotal).roundToInt().coerceIn(0, 90)
        } else {
            percent
        }
        val effectiveEta = if (
            aggregating && effectiveDownloaded != null && effectiveTotal != null &&
            speedBytesPerSecond != null && speedBytesPerSecond > 0L
        ) {
            ((effectiveTotal - effectiveDownloaded).coerceAtLeast(0L) / speedBytesPerSecond)
        } else {
            etaSeconds
        }

        repository.updateTask(taskId) { task ->
            if (task.status == DownloadStatus.DOWNLOADING) {
                task.copy(
                    progress = maxOf(task.progress, effectivePercent.coerceIn(0, 99)),
                    downloadedBytes = effectiveDownloaded ?: task.downloadedBytes,
                    totalBytes = effectiveTotal ?: task.totalBytes,
                    speedBytesPerSecond = speedBytesPerSecond ?: task.speedBytesPerSecond,
                    etaSeconds = effectiveEta ?: task.etaSeconds,
                )
            } else {
                task
            }
        }
        repository.task(taskId)
            ?.takeIf { it.status == DownloadStatus.DOWNLOADING }
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

    private fun readableError(error: Throwable): String {
        Log.e("DownloadService", "Download failed", error)
        return getString(com.anas_mugally.videodownloader.R.string.download_failed_user)
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

    private data class RangeSupport(val totalBytes: Long)

    private data class Segment(
        val index: Int,
        val start: Long,
        val end: Long,
        val file: File,
    ) {
        val length: Long
            get() = end - start + 1L
    }

    private class RangeDownloadUnsupportedException : IllegalStateException()

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

        private const val MAX_PARALLEL_SEGMENTS = 4
        private const val MIN_SEGMENT_BYTES = 512L * 1024L
        private const val MIN_PARALLEL_DOWNLOAD_BYTES = 768L * 1024L
        private const val NETWORK_BUFFER_BYTES = 512 * 1024
    }
}
