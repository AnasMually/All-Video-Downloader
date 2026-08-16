package com.anas_mugally.videodownloader.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadFormatToolsTest {
    @Test
    fun videoOnlyFormatAddsBestAudioFallbacks() {
        assertEquals(
            "137+bestaudio/137/best",
            DownloadFormatTools.selector(task(formatId = "137", hasAudio = false)),
        )
    }

    @Test
    fun combinedFormatIsNotMergedWithDuplicateAudio() {
        assertEquals(
            "22/best",
            DownloadFormatTools.selector(task(formatId = "22", hasAudio = true)),
        )
    }

    @Test
    fun audioSelectionHonorsChosenFormat() {
        assertEquals(
            "251/bestaudio/best",
            DownloadFormatTools.selector(
                task(
                    formatId = "251",
                    hasAudio = true,
                    kind = DownloadKind.AUDIO,
                ),
            ),
        )
    }

    @Test
    fun folderNameRemovesPathSeparatorsAndReservedCharacters() {
        assertEquals(
            "My unsafe folder",
            DownloadFormatTools.safeFolderName("My/unsafe:*? folder"),
        )
    }

    @Test
    fun mimeTypesUseKnownMediaMappings() {
        assertEquals("audio/mpeg", DownloadFormatTools.mimeType("mp3", true))
        assertEquals("video/x-matroska", DownloadFormatTools.mimeType("mkv", false))
        assertEquals("video/mp4", DownloadFormatTools.mimeType("unknown", false))
    }

    private fun task(
        formatId: String,
        hasAudio: Boolean,
        kind: DownloadKind = DownloadKind.VIDEO,
    ) = DownloadTask(
        id = "task",
        sourceUrl = "https://example.com/media",
        title = "Media",
        thumbnailUrl = null,
        formatId = formatId,
        formatLabel = formatId,
        formatHasAudio = hasAudio,
        kind = kind,
        requestedAudioFormat = AudioFormat.MP3,
        fileNameMode = FileNameMode.TITLE_AND_ID,
    )
}

