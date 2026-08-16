package com.anas_mugally.videodownloader.domain

import java.util.Locale

/** Produces the small, output-oriented format list shown to the user. */
object FormatCatalog {
    fun curate(formats: List<MediaFormat>): List<MediaFormat> {
        val videos = videoChoices(formats)
        val audio = bestAudioSource(formats)
        return videos + listOfNotNull(audio)
    }

    fun videoChoices(formats: List<MediaFormat>): List<MediaFormat> = formats
        .asSequence()
        .filter(MediaFormat::hasVideo)
        .filter { it.extension.lowercase(Locale.ROOT) == "mp4" }
        .groupBy(::resolutionKey)
        .values
        .mapNotNull { candidates -> candidates.maxWithOrNull(videoPreference) }
        .sortedWith(
            compareByDescending<MediaFormat> { it.height ?: 0 }
                .thenByDescending { it.width ?: 0 }
                .thenByDescending { it.framesPerSecond ?: 0 },
        )
        .take(MAX_VIDEO_CHOICES)

    fun bestAudioSource(formats: List<MediaFormat>): MediaFormat? {
        val audioOnly = formats.filter { it.hasAudio && !it.hasVideo }
        val candidates = audioOnly.ifEmpty { formats.filter(MediaFormat::hasAudio) }
        val selected = candidates.maxWithOrNull(audioPreference) ?: return null
        return if (selected.hasVideo) {
            selected.copy(
                label = selected.audioBitrateKbps?.let { "$it kbps" } ?: "M4A",
                height = null,
                width = null,
                framesPerSecond = null,
                hasVideo = false,
            )
        } else {
            selected
        }
    }

    private fun resolutionKey(format: MediaFormat): String = when {
        format.height != null -> "h:${format.height}"
        format.width != null -> "w:${format.width}"
        else -> "label:${format.label.lowercase(Locale.ROOT)}"
    }

    private val videoPreference = compareBy<MediaFormat> { it.framesPerSecond ?: 0 }
        .thenBy { it.fileSize ?: 0L }
        .thenBy { if (it.hasAudio) 1 else 0 }

    private val audioPreference = compareBy<MediaFormat> { audioContainerScore(it.extension) }
        .thenBy { it.audioBitrateKbps ?: 0 }
        .thenBy { it.fileSize ?: 0L }

    private fun audioContainerScore(extension: String): Int = when (extension.lowercase(Locale.ROOT)) {
        "m4a" -> 3
        "mp4" -> 2
        else -> 1
    }

    private const val MAX_VIDEO_CHOICES = 10
}
