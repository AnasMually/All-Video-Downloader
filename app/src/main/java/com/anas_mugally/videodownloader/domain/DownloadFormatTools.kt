package com.anas_mugally.videodownloader.domain

object DownloadFormatTools {
    fun selector(task: DownloadTask): String = when (task.kind) {
        DownloadKind.AUDIO -> "${task.formatId}/bestaudio/best"
        DownloadKind.VIDEO -> when {
            task.formatId == "best" -> "bestvideo+bestaudio/best"
            task.formatHasAudio -> "${task.formatId}/best"
            else -> "${task.formatId}+bestaudio/${task.formatId}/best"
        }
    }

    fun outputTemplate(mode: FileNameMode): String = when (mode) {
        FileNameMode.TITLE -> "%(title).180B.%(ext)s"
        FileNameMode.TITLE_AND_ID -> "%(title).150B-%(id)s.%(ext)s"
        FileNameMode.MEDIA_ID -> "%(id)s.%(ext)s"
    }

    fun safeFolderName(value: String): String {
        val sanitized = value
            .replace(Regex("""[\\/:*?\"<>|]"""), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(40)
        return sanitized.ifBlank { AppSettings.DEFAULT_OUTPUT_FOLDER }
    }

    fun mimeType(extension: String, audioOnly: Boolean): String {
        val normalizedExtension = extension.lowercase().ifBlank { if (audioOnly) "mp3" else "mp4" }
        return when (normalizedExtension) {
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            "opus" -> "audio/opus"
            "ogg", "oga" -> "audio/ogg"
            "wav" -> "audio/wav"
            "webm" -> if (audioOnly) "audio/webm" else "video/webm"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            else -> if (audioOnly) "audio/$normalizedExtension" else "video/mp4"
        }
    }
}
