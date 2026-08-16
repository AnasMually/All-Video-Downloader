package com.anas_mugally.videodownloader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatCatalogTest {
    @Test
    fun videoChoicesAreMp4OnlyAndUniquePerResolution() {
        val choices = FormatCatalog.videoChoices(
            listOf(
                format("mp4-1080-a", "mp4", 1080, fps = 30),
                format("mp4-1080-b", "mp4", 1080, fps = 60),
                format("webm-1080", "webm", 1080, fps = 60),
                format("mp4-720", "mp4", 720, fps = 30),
            ),
        )

        assertEquals(listOf(1080, 720), choices.map(MediaFormat::height))
        assertEquals("mp4-1080-b", choices.first().formatId)
        assertTrue(choices.all { it.extension == "mp4" })
    }

    @Test
    fun audioChoicePrefersM4aAndReturnsOnlyOneSource() {
        val curated = FormatCatalog.curate(
            listOf(
                format("video", "mp4", 720),
                format("opus", "webm", null, video = false, audio = true, abr = 160),
                format("aac", "m4a", null, video = false, audio = true, abr = 128),
            ),
        )

        assertEquals(2, curated.size)
        assertEquals("aac", curated.last().formatId)
    }

    @Test
    fun combinedSourceBecomesOneAudioOnlyPresentationChoice() {
        val curated = FormatCatalog.curate(
            listOf(format("combined", "mp4", 720, video = true, audio = true)),
        )

        assertEquals(2, curated.size)
        assertTrue(curated.first().hasVideo)
        assertTrue(!curated.last().hasVideo && curated.last().hasAudio)
    }

    private fun format(
        id: String,
        extension: String,
        height: Int?,
        fps: Int = 30,
        video: Boolean = true,
        audio: Boolean = false,
        abr: Int? = null,
    ) = MediaFormat(
        formatId = id,
        label = height?.let { "${it}p" } ?: "audio",
        extension = extension,
        height = height,
        width = height?.let { it * 16 / 9 },
        framesPerSecond = fps,
        audioBitrateKbps = abr,
        fileSize = null,
        hasVideo = video,
        hasAudio = audio,
    )
}
