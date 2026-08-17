package com.anas_mugally.videodownloader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFormatToolsTest {
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
        assertEquals("video/webm", DownloadFormatTools.mimeType("webm", false))
    }

    @Test
    fun longUnicodeTitlesStayWithinFilesystemByteLimit() {
        val name = DownloadFormatTools.outputFileName(
            task(title = "عنوان طويل جدًا ".repeat(30)),
            "mp4",
        )

        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 184)
    }

    private fun task(
        kind: DownloadKind = DownloadKind.VIDEO,
        title: String = "A title",
    ) = DownloadTask(
        id = "task",
        mediaId = "media-id",
        sourceUrl = "https://example.com/media",
        title = title,
        thumbnailUrl = null,
        formatId = "single:18",
        formatLabel = "360p",
        formatHasAudio = true,
        kind = kind,
        fileNameMode = FileNameMode.TITLE_AND_ID,
    )
}
