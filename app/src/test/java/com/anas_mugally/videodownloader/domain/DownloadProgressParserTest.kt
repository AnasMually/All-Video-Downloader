package com.anas_mugally.videodownloader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadProgressParserTest {
    @Test
    fun parsesMachineReadableProgressWithExactTotal() {
        val sample = DownloadProgressParser.parse(
            "__AVD_PROGRESS__5242880|10485760|NA|2097152|3| 50.0%",
        )!!

        assertEquals(50, sample.percent)
        assertEquals(5_242_880L, sample.downloadedBytes)
        assertEquals(10_485_760L, sample.totalBytes)
        assertEquals(2_097_152L, sample.speedBytesPerSecond)
        assertEquals(3L, sample.etaSeconds)
    }

    @Test
    fun usesEstimatedTotalWhenExactTotalIsUnavailable() {
        val sample = DownloadProgressParser.parse(
            "__AVD_PROGRESS__250|NA|1000|NA|NA|25.0%",
        )!!

        assertEquals(25, sample.percent)
        assertEquals(1_000L, sample.totalBytes)
        assertNull(sample.speedBytesPerSecond)
        assertNull(sample.etaSeconds)
    }

    @Test
    fun parsesModernStandardLineWithoutEta() {
        val sample = DownloadProgressParser.parse("[download]  37.4% of 10.00MiB")!!

        assertEquals(37, sample.percent)
    }
}
