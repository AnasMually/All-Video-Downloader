package com.anas_mugally.videodownloader.data

import com.anas_mugally.videodownloader.domain.MediaFormat
import com.anas_mugally.videodownloader.domain.MediaInfo
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class YtDlpExtractor {
    suspend fun extract(url: String): MediaInfo = withContext(Dispatchers.IO) {
        require(url.startsWith("https://") || url.startsWith("http://")) { "أدخل رابطًا صحيحًا" }
        val request = YoutubeDLRequest(url).apply {
            addOption("--dump-single-json")
            addOption("--no-playlist")
            addOption("--no-warnings")
        }
        val info = YoutubeDL.getInstance().getInfo(request)
        val formats = info.formats.orEmpty().mapNotNull { format ->
            val id = format.formatId ?: return@mapNotNull null
            val height = format.height
            val video = format.vcodec != null && format.vcodec != "none"
            val audio = format.acodec != null && format.acodec != "none"
            if (!video && !audio) return@mapNotNull null
            MediaFormat(
                formatId = id,
                label = when { video && height != null -> "${height}p"; audio -> "صوت"; else -> format.format ?: id },
                extension = format.ext ?: "mp4",
                height = height,
                fileSize = format.fileSize,
                hasVideo = video,
                hasAudio = audio
            )
        }.distinctBy { "${it.label}-${it.extension}-${it.hasVideo}-${it.hasAudio}" }
            .sortedWith(compareByDescending<MediaFormat> { it.hasVideo }.thenByDescending { it.height ?: 0 })
        MediaInfo(url, info.title ?: "فيديو", info.thumbnail, info.duration?.toLong(), info.extractor ?: "", formats)
    }
}
