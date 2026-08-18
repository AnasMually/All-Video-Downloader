from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"Expected text not found in {path}: {old[:140]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


service = "app/src/main/java/com/anas_mugally/videodownloader/download/DownloadService.kt"
replace_once(service, "import android.os.PowerManager\n", "import android.os.PowerManager\nimport android.util.Log\n")
replace_once(
    service,
    "    private var recoveredInterruptedTasks = false\n",
    "    private var recoveredInterruptedTasks = false\n    private var aggregateProgressTaskId: String? = null\n    private var aggregateProgressOffsetBytes = 0L\n    private var aggregateProgressTotalBytes: Long? = null\n",
)
replace_once(
    service,
    '                    task.copy(status = DownloadStatus.FAILED, error = "Android foreground-service timeout")',
    "                    task.copy(status = DownloadStatus.FAILED, error = getString(com.anas_mugally.videodownloader.R.string.download_failed_user))",
)

old_merge = '''                if (resolved.requiresMerge) {
                    val videoStream = resolved.video ?: error("API did not return the video stream")
                    val audioStream = resolved.audio ?: error("API did not return the audio stream")
                    val video = downloadStream(task, videoStream, directory, SOURCE_VIDEO_STEM, 0, 72)
                    throwIfStopRequested(task.id)
                    val audio = downloadStream(task, audioStream, directory, COMPANION_AUDIO_STEM, 72, 88)
                    throwIfStopRequested(task.id)
                    val muxAudio = if (
                        audioStream.extension.equals("m4a", true) ||
                        audioStream.extension.equals("mp4", true)
                    ) {
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
                } else {'''
new_merge = '''                if (resolved.requiresMerge) {
                    val videoStream = resolved.video ?: error("Video source is unavailable")
                    val audioStream = resolved.audio ?: error("Audio source is unavailable")
                    val expectedTotal = resolved.fileSize
                        ?: if (videoStream.fileSize != null && audioStream.fileSize != null) {
                            videoStream.fileSize + audioStream.fileSize
                        } else {
                            null
                        }

                    var video: File? = null
                    var audio: File? = null
                    beginAggregateProgress(task.id, expectedTotal)
                    try {
                        video = downloadStream(task, videoStream, directory, SOURCE_VIDEO_STEM, 0, 90)
                        throwIfStopRequested(task.id)
                        aggregateProgressOffsetBytes = video.length()
                        audio = downloadStream(task, audioStream, directory, COMPANION_AUDIO_STEM, 0, 90)
                        throwIfStopRequested(task.id)
                    } finally {
                        endAggregateProgress(task.id)
                    }
                    val downloadedVideo = requireNotNull(video)
                    val downloadedAudio = requireNotNull(audio)
                    val transferredBytes = downloadedVideo.length() + downloadedAudio.length()
                    updateProgress(
                        task.id,
                        90,
                        transferredBytes,
                        expectedTotal ?: transferredBytes,
                        0L,
                        0L,
                    )

                    val muxAudio = if (
                        audioStream.extension.equals("m4a", true) ||
                        audioStream.extension.equals("mp4", true)
                    ) {
                        downloadedAudio
                    } else {
                        updateProgress(task.id, 92, speedBytesPerSecond = 0L, etaSeconds = 0L)
                        File(directory, "$NORMALIZED_AUDIO_STEM.m4a").also { normalized ->
                            mediaProcessor.convertToM4a(downloadedAudio, normalized)
                        }
                    }
                    throwIfStopRequested(task.id)
                    updateProgress(task.id, 95, speedBytesPerSecond = 0L, etaSeconds = 0L)
                    val output = File(directory, DownloadFormatTools.outputFileName(task, "mp4"))
                    mediaProcessor.muxMp4(downloadedVideo, muxAudio, output) { requestedStops.containsKey(task.id) }
                    updateProgress(task.id, 99, speedBytesPerSecond = 0L, etaSeconds = 0L)
                    output
                } else {'''
replace_once(service, old_merge, new_merge)

old_update = '''    private suspend fun updateProgress(
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
            } else {
                task
            }
        }
        repository.task(taskId)
            ?.takeIf { it.status == DownloadStatus.DOWNLOADING }
            ?.let(::showActiveNotification)
    }
'''
new_update = '''    private fun beginAggregateProgress(taskId: String, totalBytes: Long?) {
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
        val effectiveDownloaded = if (aggregating) aggregateProgressOffsetBytes + downloadedBytes!! else downloadedBytes
        val effectiveTotal = if (aggregating) {
            aggregateProgressTotalBytes ?: totalBytes?.let { aggregateProgressOffsetBytes + it }
        } else {
            totalBytes
        }
        val effectivePercent = if (aggregating && effectiveDownloaded != null && effectiveTotal != null && effectiveTotal > 0L) {
            ((effectiveDownloaded * 90.0) / effectiveTotal).roundToInt().coerceIn(0, 90)
        } else {
            percent
        }
        val effectiveEta = if (
            aggregating && effectiveDownloaded != null && effectiveTotal != null && speedBytesPerSecond != null && speedBytesPerSecond > 0L
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
'''
replace_once(service, old_update, new_update)

replace_once(
    service,
    '''    private fun readableError(error: Throwable): String = error.message
        ?.lineSequence()
        ?.lastOrNull { it.isNotBlank() }
        ?.trim()
        ?.take(MAX_ERROR_LENGTH)
        ?: error::class.java.simpleName''',
    '''    private fun readableError(error: Throwable): String {
        Log.e("DownloadService", "Download failed", error)
        return getString(com.anas_mugally.videodownloader.R.string.download_failed_user)
    }''',
)

progress = "app/src/main/java/com/anas_mugally/videodownloader/download/DownloadProgressText.kt"
replace_once(
    progress,
    '''    fun primary(context: Context, task: DownloadTask): String {
        val downloaded = task.downloadedBytes''',
    '''    fun primary(context: Context, task: DownloadTask): String {
        if (task.progress >= 90 && (task.speedBytesPerSecond ?: 0L) <= 0L) {
            return context.getString(R.string.processing_media, task.progress)
        }
        val downloaded = task.downloadedBytes''',
)
replace_once(
    progress,
    '''    fun secondary(context: Context, task: DownloadTask): String? {
        val speed = task.speedBytesPerSecond''',
    '''    fun secondary(context: Context, task: DownloadTask): String? {
        if (task.progress >= 90 && (task.speedBytesPerSecond ?: 0L) <= 0L) return null
        val speed = task.speedBytesPerSecond''',
)

viewmodel = "app/src/main/java/com/anas_mugally/videodownloader/ui/MainViewModel.kt"
replace_once(
    viewmodel,
    '''                repository.updateTask(task.id) { saved ->
                    saved.copy(status = DownloadStatus.FAILED, error = error.message?.take(240))
                }''',
    '''                repository.updateTask(task.id) { saved ->
                    saved.copy(status = DownloadStatus.FAILED, error = app.getString(R.string.download_failed_user))
                }''',
)
replace_once(
    viewmodel,
    '''    private fun userFacingError(error: Throwable): String {
        if (error is IllegalArgumentException) return app.getString(R.string.invalid_url)
        val technical = when (error) {
            is ApiException -> error.code
            else -> error.message
        }?.lineSequence()?.lastOrNull { it.isNotBlank() }?.trim()?.take(240)
        return if (technical.isNullOrBlank()) app.getString(R.string.analysis_failed)
        else app.getString(R.string.analysis_failed_with_reason, technical)
    }''',
    '''    private fun userFacingError(error: Throwable): String {
        if (error is IllegalArgumentException) return app.getString(R.string.invalid_url)
        return app.getString(R.string.analysis_failed)
    }''',
)

for path, processing, failed, merge, m4a, preparing in [
    (
        "app/src/main/res/values/strings.xml",
        "Processing media… %1$d%%",
        "Could not finish this download. Please retry or choose another quality.",
        "Video and audio will be combined automatically.",
        "Audio will be saved as M4A when conversion is needed.",
        "Preparing download…",
    ),
    (
        "app/src/main/res/values-ar/strings.xml",
        "جارٍ تجهيز الملف… %1$d%%",
        "تعذر إكمال التنزيل. حاول مرة أخرى أو اختر جودة أخرى.",
        "سيتم دمج الفيديو والصوت تلقائيًا.",
        "سيُحفظ الصوت بصيغة M4A عند الحاجة إلى التحويل.",
        "جارٍ تجهيز التنزيل…",
    ),
]:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    anchor = '    <string name="waiting_for_wifi">'
    if 'name="processing_media"' not in text:
        text = text.replace(
            anchor,
            f'    <string name="processing_media">{processing}</string>\n'
            f'    <string name="download_failed_user">{failed}</string>\n' + anchor,
            1,
        )
    if path.endswith("/values/strings.xml"):
        text = text.replace("Compatible audio will be downloaded and merged on-device", merge)
        text = text.replace("Audio is converted on-device to M4A (AAC) with Google Media3. FFmpeg is not included.", m4a)
        text = text.replace("Preparing the on-device download engine…", preparing)
    else:
        text = text.replace("سيُنزل صوت متوافق ويُدمج داخل الهاتف", merge)
        text = text.replace("يُحوّل الصوت داخل الهاتف إلى M4A ‏(AAC) عبر Google Media3. لا يتضمن التطبيق FFmpeg.", m4a)
        text = text.replace("جارٍ تجهيز محرك التنزيل داخل الجهاز…", preparing)
    p.write_text(text, encoding="utf-8")

replace_once(
    "app/build.gradle.kts",
    'versionCode = 11\n        versionName = "1.6.2"',
    'versionCode = 12\n        versionName = "1.6.3"',
)
