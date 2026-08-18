package com.anas_mugally.videodownloader.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStreamTest {
    @Test
    fun directHeAacAudioUsesMedia3NormalizationPath() {
        val stream = MediaStream(
            url = "https://video.xx.fbcdn.net/audio.m4a",
            headers = emptyMap(),
            extension = "m4a",
            fileSize = 752_208L,
            protocol = "https",
            videoCodec = null,
            audioCodec = "mp4a.40.5",
        )

        assertTrue(stream.isAudioOnly)
        assertTrue(stream.isAdaptiveManifest)
    }

    @Test
    fun directHeAacV2AudioUsesMedia3NormalizationPath() {
        val stream = MediaStream(
            url = "https://video.xx.fbcdn.net/audio-v2.mp4",
            headers = emptyMap(),
            extension = "mp4",
            fileSize = 752_208L,
            protocol = "https",
            videoCodec = null,
            audioCodec = "mp4a.40.29",
        )

        assertTrue(stream.isAudioOnly)
        assertTrue(stream.isAdaptiveManifest)
    }

    @Test
    fun ordinaryDirectAacLcAudioKeepsNormalDownloadPath() {
        val stream = MediaStream(
            url = "https://cdn.example/audio.m4a",
            headers = emptyMap(),
            extension = "m4a",
            fileSize = 752_208L,
            protocol = "https",
            videoCodec = null,
            audioCodec = "mp4a.40.2",
        )

        assertTrue(stream.isAudioOnly)
        assertFalse(stream.isAdaptiveManifest)
    }

    @Test
    fun heAacVideoStreamIsNotMisclassifiedAsAdaptiveAudio() {
        val stream = MediaStream(
            url = "https://cdn.example/video.mp4",
            headers = emptyMap(),
            extension = "mp4",
            fileSize = 13_000_000L,
            protocol = "https",
            videoCodec = "avc1.640028",
            audioCodec = "mp4a.40.5",
        )

        assertFalse(stream.isAudioOnly)
        assertFalse(stream.isAdaptiveManifest)
    }
}
