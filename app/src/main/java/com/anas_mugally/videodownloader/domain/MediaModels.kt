package com.anas_mugally.videodownloader.domain

data class MediaInfo(
    val sourceUrl: String,
    val title: String,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val extractor: String,
    val formats: List<MediaFormat>
)

data class MediaFormat(
    val formatId: String,
    val label: String,
    val extension: String,
    val height: Int?,
    val fileSize: Long?,
    val hasVideo: Boolean,
    val hasAudio: Boolean
)

enum class DownloadKind { VIDEO, AUDIO }
