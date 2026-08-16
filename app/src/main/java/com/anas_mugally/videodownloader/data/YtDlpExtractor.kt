package com.anas_mugally.videodownloader.data

import com.anas_mugally.videodownloader.domain.FormatCatalog
import com.anas_mugally.videodownloader.domain.MediaFormat
import com.anas_mugally.videodownloader.domain.MediaInfo
import com.anas_mugally.videodownloader.domain.UrlTools
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class YtDlpExtractor(private val runtime: YtDlpRuntime) {
    suspend fun extract(rawUrl: String): MediaInfo {
        val url = UrlTools.extractHttpUrl(rawUrl) ?: throw IllegalArgumentException("Invalid web link")
        runtime.ensureReady()
        return withContext(Dispatchers.IO) {
            val request = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("--no-warnings")
                addOption("--socket-timeout", 20)
                if (runtime.hasCookies()) addOption("--cookies", runtime.cookiesFile().absolutePath)
            }
            val info = YoutubeDL.getInstance().getInfo(request)
            val extractedFormats = info.formats.orEmpty()
                .mapNotNull { format ->
                    val id = format.formatId ?: return@mapNotNull null
                    val video = !format.vcodec.isNullOrBlank() && format.vcodec != "none"
                    val audio = !format.acodec.isNullOrBlank() && format.acodec != "none"
                    if ((!video && !audio) || format.ext == "mhtml") return@mapNotNull null
                    val height = format.height.takeIf { it > 0 }
                    val width = format.width.takeIf { it > 0 }
                    val fps = format.fps.takeIf { it > 0 }
                    val audioBitrate = format.abr.takeIf { it > 0 }
                    val size = format.fileSize.takeIf { it > 0 }
                        ?: format.fileSizeApproximate.takeIf { it > 0 }
                    MediaFormat(
                        formatId = id,
                        label = formatLabel(
                            id = id,
                            video = video,
                            height = height,
                            fps = fps,
                            audioBitrate = audioBitrate,
                            note = format.formatNote,
                        ),
                        extension = format.ext ?: if (video) "mp4" else "m4a",
                        height = height,
                        width = width,
                        framesPerSecond = fps,
                        audioBitrateKbps = audioBitrate,
                        fileSize = size,
                        hasVideo = video,
                        hasAudio = audio,
                    )
                }
                .distinctBy(MediaFormat::formatId)
                .sortedWith(
                    compareByDescending<MediaFormat>(MediaFormat::hasVideo)
                        .thenByDescending { it.height ?: 0 }
                        .thenByDescending { it.framesPerSecond ?: 0 }
                        .thenByDescending { it.audioBitrateKbps ?: 0 },
                )
            val formats = FormatCatalog.curate(extractedFormats)
            check(formats.isNotEmpty()) { "No compatible MP4 or M4A formats were found" }
            MediaInfo(
                id = info.id,
                sourceUrl = url,
                title = info.title ?: info.fulltitle ?: "Media",
                thumbnailUrl = info.thumbnail,
                durationSeconds = info.duration.takeIf { it > 0 }?.toLong(),
                extractor = info.extractor.orEmpty(),
                formats = formats,
            )
        }
    }

    private fun formatLabel(
        id: String,
        video: Boolean,
        height: Int?,
        fps: Int?,
        audioBitrate: Int?,
        note: String?,
    ): String = when {
        video && height != null -> buildString {
            append(height).append('p')
            if (fps != null && fps >= 50) append(" · ").append(fps).append("fps")
        }
        !video && audioBitrate != null -> "$audioBitrate kbps"
        !note.isNullOrBlank() -> note
        else -> id
    }
}
