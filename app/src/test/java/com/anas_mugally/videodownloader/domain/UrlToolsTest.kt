package com.anas_mugally.videodownloader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlToolsTest {
    @Test
    fun extractsUrlFromSharedSentence() {
        assertEquals(
            "https://www.youtube.com/watch?v=abc123",
            UrlTools.extractHttpUrl("Watch this: https://www.youtube.com/watch?v=abc123"),
        )
    }

    @Test
    fun removesTrailingSentencePunctuation() {
        assertEquals(
            "https://example.com/video",
            UrlTools.extractHttpUrl("https://example.com/video,"),
        )
    }

    @Test
    fun rejectsNonWebSchemesAndMissingHosts() {
        assertNull(UrlTools.extractHttpUrl("file:///sdcard/video.mp4"))
        assertNull(UrlTools.extractHttpUrl("https:///missing-host"))
        assertNull(UrlTools.extractHttpUrl("not a link"))
    }
}

