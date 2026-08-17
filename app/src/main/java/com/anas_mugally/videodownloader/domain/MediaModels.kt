package com.anas_mugally.videodownloader.domain

data class MediaInfo(
    val id: String?,
    val sourceUrl: String,
    val title: String,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val extractor: String,
    val formats: List<MediaFormat>,
    val audioTracks: List<AudioTrack> = emptyList(),
    val defaultAudioTrackId: String? = null,
    val subtitles: List<SubtitleTrack> = emptyList(),
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
    val requiresMerge: Boolean = false,
)

data class AudioTrack(
    val id: String,
    val label: String,
    val language: String?,
    val isOriginal: Boolean,
    val isDefault: Boolean,
    val extension: String,
    val bitrateKbps: Int?,
    val fileSize: Long?,
)

data class SubtitleTrack(
    val id: String,
    val label: String,
    val language: String?,
    val extension: String,
    val automatic: Boolean,
)

enum class DownloadKind {
    VIDEO,
    AUDIO,
}
