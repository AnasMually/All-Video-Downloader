package com.anas_mugally.videodownloader.data

import com.anas_mugally.videodownloader.domain.DownloadKind
import com.anas_mugally.videodownloader.domain.DownloadStatus
import com.anas_mugally.videodownloader.domain.DownloadTask
import com.anas_mugally.videodownloader.domain.FileNameMode
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskJsonCodecTest {
    @Test
    fun roundTripPreservesDownloadTask() {
        val original = DownloadTask(
            id = "download-1",
            mediaId = "media-1",
            sourceUrl = "https://example.com/video",
            title = "A title with العربية",
            thumbnailUrl = "https://example.com/thumb.jpg",
            formatId = "137",
            formatLabel = "1080p",
            formatHasAudio = false,
            kind = DownloadKind.VIDEO,
            fileNameMode = FileNameMode.TITLE_AND_ID,
            status = DownloadStatus.COMPLETED,
            progress = 100,
            createdAt = 10L,
            updatedAt = 20L,
            outputUri = "content://media/video/1",
            outputMimeType = "video/mp4",
            outputName = "video.mp4",
            error = null,
        )

        assertEquals(listOf(original), TaskJsonCodec.decode(TaskJsonCodec.encode(listOf(original))))
    }

    @Test
    fun malformedStorageDoesNotCrashStartup() {
        assertEquals(emptyList<DownloadTask>(), TaskJsonCodec.decode("{not-json"))
    }
}
