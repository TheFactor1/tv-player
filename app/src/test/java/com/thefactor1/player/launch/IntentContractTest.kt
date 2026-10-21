package com.thefactor1.player.launch

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IntentContractTest {

    @Test
    fun headersArePairedInOrder() {
        val headers = IntentContract.headersFromArray(arrayOf("User-Agent", "Stremio", "Referer", "https://example.com"))
        assertEquals(mapOf("User-Agent" to "Stremio", "Referer" to "https://example.com"), headers)
    }

    @Test
    fun trailingHeaderKeyAndBlankKeysAreDropped() {
        val headers = IntentContract.headersFromArray(arrayOf(" ", "ignored", "Cookie", "a=1", "Dangling"))
        assertEquals(mapOf("Cookie" to "a=1"), headers)
    }

    @Test
    fun missingHeadersGiveEmptyMap() {
        assertEquals(emptyMap<String, String>(), IntentContract.headersFromArray(null))
    }

    @Test
    fun subtitleTypeComesFromExtensionIgnoringQuery() {
        assertEquals(MimeTypes.TEXT_SSA, IntentContract.subtitleMimeType("https://x/subs/ep1.ASS?token=abc"))
        assertEquals(MimeTypes.TEXT_VTT, IntentContract.subtitleMimeType("track.vtt"))
        assertEquals(MimeTypes.APPLICATION_SUBRIP, IntentContract.subtitleMimeType("English.srt"))
    }

    @Test
    fun subtitleWithoutExtensionDefaultsToSrt() {
        assertEquals(MimeTypes.APPLICATION_SUBRIP, IntentContract.subtitleMimeType("https://opensubtitles.example/download/123"))
        assertEquals(MimeTypes.APPLICATION_SUBRIP, IntentContract.subtitleMimeType(null))
    }

    @Test
    fun onlyAdaptiveTypesBecomeMimeHints() {
        assertEquals(MimeTypes.APPLICATION_M3U8, IntentContract.mediaMimeHint("application/x-mpegURL"))
        assertEquals(MimeTypes.APPLICATION_MPD, IntentContract.mediaMimeHint("application/dash+xml"))
        assertNull(IntentContract.mediaMimeHint("video/*"))
        assertNull(IntentContract.mediaMimeHint(null))
    }

    @Test
    fun positionAcceptsIntOrLongAndClampsNegative() {
        assertEquals(42_000L, IntentContract.positionFrom(42_000))
        assertEquals(5_000_000_000L, IntentContract.positionFrom(5_000_000_000L))
        assertEquals(0L, IntentContract.positionFrom(-5))
        assertEquals(12L, IntentContract.positionFrom("12"))
        assertEquals(1_500L, IntentContract.positionFrom(1_500.7))
        assertEquals(0L, IntentContract.positionFrom("soon"))
        assertEquals(0L, IntentContract.positionFrom(null))
    }
}
