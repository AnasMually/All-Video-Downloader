package com.anas_mugally.videodownloader.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadFormatToolsTest {
    @Test
    fun selectedVideoIsDownloadedDirectlyWithoutFfmpegMergeSyntax() {
        assertEquals(
            "137/best[ext=mp4]",
            DownloadFormatTools.primarySelector(task(formatId = "137")),
        )
    }

    @Test
    fun companionAudioPrefersM4a() {
        assertEquals(
            "bestaudio[ext=m4a]/bestaudio[ext=mp4]/bestaudio[acodec^=mp4a]/bestaudio",
            DownloadFormatTools.companionAudioSelector(),
        )
    }

    @Test
    fun outputFileNameHonorsNamingModeAndM4a() {
        assertEquals(
            "A title-media-id.m4a",
            DownloadFormatTools.outputFileName(
                task(kind = DownloadKind.AUDIO, title = "A/title"),
                "m4a",
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
    fun mimeTypesMatchFinalOutputContainers() {
        assertEquals("audio/mp4", DownloadFormatTools.mimeType("m4a", true))
        assertEquals("video/mp4", DownloadFormatTools.mimeType("mp4", false))
    }

    private fun task(
        formatId: String = "140",
        kind: DownloadKind = DownloadKind.VIDEO,
        title: String = "A title",
    ) = DownloadTask(
        id = "task",
        mediaId = "media-id",
        sourceUrl = "https://example.com/media",
        title = title,
        thumbnailUrl = null,
        formatId = formatId,
        formatLabel = formatId,
        formatHasAudio = false,
        kind = kind,
        fileNameMode = FileNameMode.TITLE_AND_ID,
    )
}
