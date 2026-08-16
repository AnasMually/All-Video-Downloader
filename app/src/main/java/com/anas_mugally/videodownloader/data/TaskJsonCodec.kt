package com.anas_mugally.videodownloader.data

import com.anas_mugally.videodownloader.domain.DownloadKind
import com.anas_mugally.videodownloader.domain.DownloadStatus
import com.anas_mugally.videodownloader.domain.DownloadTask
import com.anas_mugally.videodownloader.domain.FileNameMode
import org.json.JSONArray
import org.json.JSONObject

object TaskJsonCodec {
    fun encode(tasks: List<DownloadTask>): String {
        val array = JSONArray()
        tasks.forEach { task ->
            array.put(
                JSONObject().apply {
                    put("id", task.id)
                    putNullable("mediaId", task.mediaId)
                    put("sourceUrl", task.sourceUrl)
                    put("title", task.title)
                    putNullable("thumbnailUrl", task.thumbnailUrl)
                    put("formatId", task.formatId)
                    put("formatLabel", task.formatLabel)
                    put("formatHasAudio", task.formatHasAudio)
                    put("kind", task.kind.name)
                    put("fileNameMode", task.fileNameMode.name)
                    put("status", task.status.name)
                    put("progress", task.progress)
                    put("createdAt", task.createdAt)
                    put("updatedAt", task.updatedAt)
                    putNullable("outputUri", task.outputUri)
                    putNullable("outputMimeType", task.outputMimeType)
                    putNullable("outputName", task.outputName)
                    putNullable("error", task.error)
                },
            )
        }
        return array.toString()
    }

    fun decode(value: String?): List<DownloadTask> {
        if (value.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(value)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").takeIf(String::isNotBlank) ?: continue
                    val sourceUrl = item.optString("sourceUrl").takeIf(String::isNotBlank) ?: continue
                    add(
                        DownloadTask(
                            id = id,
                            mediaId = item.nullableString("mediaId"),
                            sourceUrl = sourceUrl,
                            title = item.optString("title", "Media"),
                            thumbnailUrl = item.nullableString("thumbnailUrl"),
                            formatId = item.optString("formatId", "best"),
                            formatLabel = item.optString("formatLabel", "Best"),
                            formatHasAudio = item.optBoolean("formatHasAudio", false),
                            kind = item.enumValue("kind", DownloadKind.VIDEO),
                            fileNameMode = item.enumValue("fileNameMode", FileNameMode.TITLE_AND_ID),
                            status = item.enumValue("status", DownloadStatus.FAILED),
                            progress = item.optInt("progress", 0).coerceIn(0, 100),
                            createdAt = item.optLong("createdAt", System.currentTimeMillis()),
                            updatedAt = item.optLong("updatedAt", System.currentTimeMillis()),
                            outputUri = item.nullableString("outputUri"),
                            outputMimeType = item.nullableString("outputMimeType"),
                            outputName = item.nullableString("outputName"),
                            error = item.nullableString("error"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun JSONObject.putNullable(key: String, value: String?) {
        if (value == null) put(key, JSONObject.NULL) else put(key, value)
    }

    private fun JSONObject.nullableString(key: String): String? {
        if (isNull(key)) return null
        return optString(key).takeIf(String::isNotBlank)
    }

    private inline fun <reified T : Enum<T>> JSONObject.enumValue(key: String, fallback: T): T {
        return runCatching { enumValueOf<T>(optString(key)) }.getOrDefault(fallback)
    }
}
