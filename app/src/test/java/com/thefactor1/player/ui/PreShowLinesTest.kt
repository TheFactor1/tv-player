package com.thefactor1.player.ui

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class PreShowLinesTest {

    private val projector = DisplayInfo(1920, 1080, 23.976f, emptySet())
    private val hdrTv = DisplayInfo(3840, 2160, 23.976f, setOf(HdrType.HDR10, HdrType.HLG))

    private fun video(mime: String, codecs: String? = null, transfer: Int = C.COLOR_TRANSFER_SDR, bits: Int = 8) =
        Format.Builder()
            .setSampleMimeType(mime)
            .setCodecs(codecs)
            .setWidth(3840)
            .setHeight(2160)
            .setColorInfo(ColorInfo.Builder().setColorTransfer(transfer).setLumaBitdepth(bits).build())
            .build()

    @Test
    fun dolbyVisionPictureNamesBaseCodecDepthAndProfile() {
        val dv = video(MimeTypes.VIDEO_DOLBY_VISION, codecs = "dvhe.08.06", transfer = C.COLOR_TRANSFER_ST2084, bits = 10)
        assertEquals("3840×2160 · HEVC 10-bit · Dolby Vision P8", PreShowLines.picture(dv))
    }

    @Test
    fun sdrPictureSaysSo() {
        assertEquals("3840×2160 · H.264 · SDR", PreShowLines.picture(video(MimeTypes.VIDEO_H264)))
    }

    @Test
    fun hdrToAnSdrDisplayIsShownAsSdr() {
        val hdr10 = video(MimeTypes.VIDEO_H265, transfer = C.COLOR_TRANSFER_ST2084, bits = 10)
        assertEquals("1920×1080 · shown as SDR", PreShowLines.output(hdr10, projector))
        assertEquals("3840×2160 · HDR10", PreShowLines.output(hdr10, hdrTv))
    }

    @Test
    fun dolbyVisionFallsBackToHdr10OnAnHdr10Display() {
        val dv = video(MimeTypes.VIDEO_DOLBY_VISION, codecs = "dvhe.08.06", transfer = C.COLOR_TRANSFER_ST2084)
        assertEquals("3840×2160 · HDR10", PreShowLines.output(dv, hdrTv))
    }

    @Test
    fun sdrOutputForSdrPicture() {
        assertEquals("1920×1080 · SDR", PreShowLines.output(video(MimeTypes.VIDEO_H264), projector))
    }

    @Test
    fun soundAddsTheOutputOnceKnown() {
        val truehd = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_TRUEHD).setChannelCount(8).build()
        assertEquals("TrueHD 7.1", PreShowLines.sound(truehd, passthrough = null))
        assertEquals("TrueHD 7.1 · passthrough", PreShowLines.sound(truehd, passthrough = true))
        assertEquals("TrueHD 7.1 · decoded", PreShowLines.sound(truehd, passthrough = false))
    }

    @Test
    fun rateSaysWhetherTheDisplayMatches() {
        assertEquals("23.976 fps · display matched", PreShowLines.rate(23.976f, 23.976f))
        assertEquals("24 fps · display matched", PreShowLines.rate(24f, 48f))
        assertEquals("23.976 fps · display at 60 Hz", PreShowLines.rate(23.976f, 60f))
        assertNull(PreShowLines.rate(-1f, 60f))
    }

    @Test
    fun nearMissesAreNotMatches() {
        assertFalse(PreShowLines.matches(24f, 23.976f))
        assertFalse(PreShowLines.matches(24f, 23.976f * 2.5f))
        assertFalse(PreShowLines.matches(23.976f, 24f * 2.5f))
        assertTrue(PreShowLines.matches(25f, 50f))
        assertTrue(PreShowLines.matches(29.97f, 59.94f))
    }
}
