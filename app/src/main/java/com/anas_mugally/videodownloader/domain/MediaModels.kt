package com.anas_mugally.videodownloader.domain

data class MediaInfo(
    val id: String?,
    val sourceUrl: String,
    val title: String,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val extractor: String,
    val formats: List<MediaFormat>,
)

data class MediaFormat(
    val formatId: String,
    val label: String,
    val extension: String,
    val height: Int?,
    val width: Int?,
    val framesPerSecond: Int?,
    val audioBitrateKbps: Int?,
    val fileSize: Long?,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
)

enum class DownloadKind {
    VIDEO,
    AUDIO,
}
