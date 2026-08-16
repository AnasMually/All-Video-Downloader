package com.anas_mugally.videodownloader.domain

import java.util.Locale

object DownloadFormatTools {
    fun primarySelector(task: DownloadTask): String = when (task.kind) {
        DownloadKind.AUDIO -> "${task.formatId}/bestaudio[ext=m4a]/bestaudio"
        DownloadKind.VIDEO -> "${task.formatId}/best[ext=mp4]"
    }

    fun companionAudioSelector(): String =
        "bestaudio[ext=m4a]/bestaudio[ext=mp4]/bestaudio[acodec^=mp4a]/bestaudio"

    fun outputFileName(task: DownloadTask, extension: String): String {
        val safeTitle = safeFileStem(task.title).ifBlank { "Media" }
        val safeMediaId = safeFileStem(task.mediaId.orEmpty()).ifBlank { task.id.take(8) }
        val stem = when (task.fileNameMode) {
            FileNameMode.TITLE -> safeTitle
            FileNameMode.TITLE_AND_ID -> "$safeTitle-$safeMediaId"
            FileNameMode.MEDIA_ID -> safeMediaId
        }
        val safeExtension = extension
            .lowercase(Locale.ROOT)
            .filter(Char::isLetterOrDigit)
            .ifBlank { if (task.kind == DownloadKind.AUDIO) "m4a" else "mp4" }
        return "${stem.takeUtf8Bytes(MAX_FILE_STEM_BYTES)}.$safeExtension"
    }

    fun safeFolderName(value: String): String {
        val sanitized = safeFileStem(value).take(MAX_FOLDER_LENGTH)
        return sanitized.ifBlank { AppSettings.DEFAULT_OUTPUT_FOLDER }
    }

    fun mimeType(extension: String, audioOnly: Boolean): String {
        val normalizedExtension = extension.lowercase(Locale.ROOT)
        return when {
            audioOnly && normalizedExtension == "mp3" -> "audio/mpeg"
            audioOnly -> "audio/mp4"
            else -> "video/mp4"
        }
    }

    private fun safeFileStem(value: String): String = value
        .replace(Regex("""[\\/:*?\"<>|\p{Cc}]"""), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trimEnd('.')

    private fun String.takeUtf8Bytes(maxBytes: Int): String {
        var index = 0
        var usedBytes = 0
        while (index < length) {
            val codePoint = Character.codePointAt(this, index)
            val characterCount = Character.charCount(codePoint)
            val encodedBytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
            if (usedBytes + encodedBytes > maxBytes) break
            usedBytes += encodedBytes
            index += characterCount
        }
        return substring(0, index).trimEnd()
    }

    private const val MAX_FOLDER_LENGTH = 40
    private const val MAX_FILE_STEM_BYTES = 180
}
