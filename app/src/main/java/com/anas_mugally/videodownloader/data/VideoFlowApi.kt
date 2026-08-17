package com.anas_mugally.videodownloader.data

import com.anas_mugally.videodownloader.BuildConfig
import com.anas_mugally.videodownloader.domain.MediaFormat
import com.anas_mugally.videodownloader.domain.MediaInfo
import com.anas_mugally.videodownloader.domain.UrlTools
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class EngineState(
    val initializing: Boolean = false,
    val ready: Boolean = false,
    val version: String? = null,
    val error: String? = null,
)

data class MediaStream(
    val url: String,
    val headers: Map<String, String>,
    val extension: String,
    val fileSize: Long?,
)

data class ResolvedDownload(
    val id: String,
    val label: String,
    val kind: String,
    val requiresMerge: Boolean,
    val extension: String,
    val fileSize: Long?,
    val stream: MediaStream?,
    val video: MediaStream?,
    val audio: MediaStream?,
)

class ApiException(val code: String) : IllegalStateException(code)

class VideoFlowApi {
    private val _state = MutableStateFlow(EngineState())
    val state = _state.asStateFlow()

    suspend fun refreshHealth() {
        _state.value = EngineState(initializing = true)
        runCatching {
            withContext(Dispatchers.IO) {
                val root = requestJson("health.php", method = "GET")
                val ytDlp = root.optJSONObject("tools")?.optJSONObject("yt_dlp")
                val ready = root.optBoolean("ok", false) && ytDlp?.optBoolean("ok", false) == true
                EngineState(
                    ready = ready,
                    version = ytDlp?.optString("version")?.takeIf(String::isNotBlank),
                    error = if (ready) null else "api_unavailable",
                )
            }
        }.onSuccess { _state.value = it }
            .onFailure { error ->
                _state.value = EngineState(error = error.message ?: "api_unavailable")
            }
    }

    suspend fun extract(rawUrl: String): MediaInfo = withContext(Dispatchers.IO) {
        val url = UrlTools.extractHttpUrl(rawUrl) ?: throw IllegalArgumentException("Invalid web link")
        val root = requestJson(
            "extract.php",
            body = JSONObject().put("url", url),
        )
        val video = root.getJSONObject("video")
        val downloads = video.optJSONArray("downloads")
        val formats = buildList {
            if (downloads != null) {
                for (index in 0 until downloads.length()) {
                    val item = downloads.optJSONObject(index) ?: continue
                    val id = item.optString("id").takeIf(String::isNotBlank) ?: continue
                    val kind = item.optString("kind")
                    val requiresMerge = item.optBoolean("requires_merge", false)
                    val height = item.optInt("height", 0).takeIf { it > 0 }
                    val fps = item.optDouble("fps", 0.0).takeIf { it > 0 }?.toInt()
                    val fileSize = item.optLong("filesize", -1L).takeIf { it > 0L }
                    add(
                        MediaFormat(
                            formatId = id,
                            label = item.optString("label", id),
                            extension = item.optString("ext", if (kind == "audio") "m4a" else "mp4"),
                            height = height,
                            width = null,
                            framesPerSecond = fps,
                            audioBitrateKbps = null,
                            fileSize = fileSize,
                            hasVideo = kind != "audio",
                            hasAudio = kind == "audio" || !requiresMerge,
                        ),
                    )
                }
            }
        }
        check(formats.isNotEmpty()) { "No downloadable formats were returned by the API" }
        MediaInfo(
            id = video.optString("id").takeIf(String::isNotBlank),
            sourceUrl = url,
            title = video.optString("title", "Media"),
            thumbnailUrl = video.optString("thumbnail").takeIf(String::isNotBlank),
            durationSeconds = video.optLong("duration", 0L).takeIf { it > 0L },
            extractor = root.optString("platform", video.optString("extractor")),
            formats = formats,
        )
    }

    suspend fun resolve(sourceUrl: String, downloadId: String): ResolvedDownload = withContext(Dispatchers.IO) {
        val root = requestJson(
            "resolve.php",
            body = JSONObject()
                .put("url", sourceUrl)
                .put("download_id", downloadId),
        )
        parseDownload(root.getJSONObject("download"))
    }

    private fun parseDownload(item: JSONObject): ResolvedDownload = ResolvedDownload(
        id = item.optString("id"),
        label = item.optString("label"),
        kind = item.optString("kind"),
        requiresMerge = item.optBoolean("requires_merge", false),
        extension = item.optString("ext", "mp4"),
        fileSize = item.optLong("filesize", -1L).takeIf { it > 0L },
        stream = item.optJSONObject("stream")?.let(::parseStream),
        video = item.optJSONObject("video")?.let(::parseStream),
        audio = item.optJSONObject("audio")?.let(::parseStream),
    )

    private fun parseStream(item: JSONObject): MediaStream {
        val headers = buildMap {
            val objectHeaders = item.optJSONObject("headers")
            if (objectHeaders != null) {
                objectHeaders.keys().forEach { name ->
                    val value = objectHeaders.optString(name)
                    if (name.isNotBlank() && value.isNotBlank()) put(name, value)
                }
            }
        }
        return MediaStream(
            url = item.getString("url"),
            headers = headers,
            extension = item.optString("ext", "mp4"),
            fileSize = item.optLong("filesize", -1L).takeIf { it > 0L },
        )
    }

    private fun requestJson(
        endpoint: String,
        method: String = "POST",
        body: JSONObject? = null,
    ): JSONObject {
        val base = BuildConfig.VIDEOFLOW_API_BASE_URL.trimEnd('/') + "/"
        val connection = (URL(base + endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 60_000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            if (BuildConfig.VIDEOFLOW_API_KEY.isNotBlank()) {
                setRequestProperty("X-API-Key", BuildConfig.VIDEOFLOW_API_KEY)
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            }
            val code = connection.responseCode
            val input = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = input?.use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader -> reader.readText() }
            }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse {
                throw ApiException("invalid_api_response")
            }
            if (code !in 200..299 || !json.optBoolean("ok", false)) {
                throw ApiException(json.optString("error", "api_request_failed"))
            }
            return json
        } finally {
            connection.disconnect()
        }
    }
}
