package com.anas_mugally.videodownloader.domain

data class DownloadTask(
    val id: String,
    val mediaId: String?,
    val sourceUrl: String,
    val title: String,
    val thumbnailUrl: String?,
    val formatId: String,
    val formatLabel: String,
    val formatHasAudio: Boolean,
    val kind: DownloadKind,
    val fileNameMode: FileNameMode,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progress: Int = 0,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val speedBytesPerSecond: Long? = null,
    val etaSeconds: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val outputUri: String? = null,
    val outputMimeType: String? = null,
    val outputName: String? = null,
    val error: String? = null,
) {
    val isActive: Boolean
        get() = status == DownloadStatus.QUEUED ||
            status == DownloadStatus.WAITING_FOR_WIFI ||
            status == DownloadStatus.DOWNLOADING

    val canRetry: Boolean
        get() = status == DownloadStatus.FAILED || status == DownloadStatus.CANCELLED
}

enum class DownloadStatus {
    QUEUED,
    WAITING_FOR_WIFI,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
}

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

enum class FileNameMode {
    TITLE,
    TITLE_AND_ID,
    MEDIA_ID,
}

data class AppSettings(
    val wifiOnly: Boolean = false,
    val dynamicColor: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val outputFolder: String = DEFAULT_OUTPUT_FOLDER,
    val fileNameMode: FileNameMode = FileNameMode.TITLE_AND_ID,
) {
    companion object {
        const val DEFAULT_OUTPUT_FOLDER = "All Video Downloader"
    }
}
