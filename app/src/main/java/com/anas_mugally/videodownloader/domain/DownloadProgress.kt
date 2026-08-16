package com.anas_mugally.videodownloader.domain

import kotlin.math.roundToInt

data class DownloadProgressSample(
    val percent: Int,
    val downloadedBytes: Long?,
    val totalBytes: Long?,
    val speedBytesPerSecond: Long?,
    val etaSeconds: Long?,
)

/** Parses a stable machine-readable progress line emitted by yt-dlp. */
object DownloadProgressParser {
    private const val PREFIX = "__AVD_PROGRESS__"

    const val YT_DLP_TEMPLATE =
        "${PREFIX}%(progress.downloaded_bytes)s|%(progress.total_bytes)s|" +
            "%(progress.total_bytes_estimate)s|%(progress.speed)s|%(progress.eta)s|" +
            "%(progress._percent_str)s"

    fun parse(line: String, libraryPercent: Float? = null): DownloadProgressSample? {
        val markerIndex = line.indexOf(PREFIX)
        if (markerIndex >= 0) {
            val values = line.substring(markerIndex + PREFIX.length).trim().split('|')
            if (values.size >= FIELD_COUNT) {
                val downloaded = values[0].numberOrNull()?.toLong()
                val exactTotal = values[1].numberOrNull()?.toLong()
                val estimatedTotal = values[2].numberOrNull()?.toLong()
                val total = exactTotal?.takeIf { it > 0L } ?: estimatedTotal?.takeIf { it > 0L }
                val speed = values[3].numberOrNull()?.toLong()?.takeIf { it > 0L }
                val eta = values[4].numberOrNull()?.toLong()?.takeIf { it >= 0L }
                val printedPercent = PERCENT_PATTERN.find(values[5])
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toDoubleOrNull()
                val calculatedPercent = if (downloaded != null && total != null) {
                    downloaded.toDouble() * 100.0 / total.toDouble()
                } else {
                    null
                }
                val percent = (calculatedPercent ?: printedPercent ?: libraryPercent?.toDouble())
                    ?.takeIf { it >= 0.0 }
                    ?.roundToInt()
                    ?.coerceIn(0, 100)
                    ?: return null
                return DownloadProgressSample(percent, downloaded, total, speed, eta)
            }
        }

        val percent = STANDARD_PERCENT_PATTERN.find(line)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
            ?: libraryPercent?.takeIf { it >= 0f }?.toDouble()
            ?: return null
        return DownloadProgressSample(
            percent = percent.roundToInt().coerceIn(0, 100),
            downloadedBytes = null,
            totalBytes = null,
            speedBytesPerSecond = null,
            etaSeconds = null,
        )
    }

    private fun String.numberOrNull(): Double? = trim()
        .takeUnless { it.isEmpty() || it.equals("NA", ignoreCase = true) || it == "None" }
        ?.toDoubleOrNull()

    private const val FIELD_COUNT = 6
    private val PERCENT_PATTERN = Regex("([0-9]+(?:\\.[0-9]+)?)")
    private val STANDARD_PERCENT_PATTERN = Regex("\\[download]\\s+([0-9]+(?:\\.[0-9]+)?)%")
}
