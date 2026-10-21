package com.thefactor1.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayModeMatcherTest {

    private val uhd60 = ModeInfo(1, 3840, 2160, 60f)
    private val uhd5994 = ModeInfo(2, 3840, 2160, 59.94f)
    private val uhd50 = ModeInfo(3, 3840, 2160, 50f)
    private val uhd24 = ModeInfo(4, 3840, 2160, 24f)
    private val uhd23976 = ModeInfo(5, 3840, 2160, 23.976f)
    private val fhd23976 = ModeInfo(6, 1920, 1080, 23.976f)
    private val all = listOf(uhd60, uhd5994, uhd50, uhd24, uhd23976, fhd23976)

    @Test
    fun filmGoesToExact23976NotTo24() {
        assertEquals(uhd23976, DisplayModeMatcher.choose(all, uhd60, 23.976f))
    }

    @Test
    fun true24GoesTo24() {
        assertEquals(uhd24, DisplayModeMatcher.choose(all, uhd60, 24f))
    }

    @Test
    fun pal25UsesDoubleRateWhenNo25HzMode() {
        assertEquals(uhd50, DisplayModeMatcher.choose(all, uhd60, 25f))
    }

    @Test
    fun ntsc2997UsesDoubleRate() {
        assertEquals(uhd5994, DisplayModeMatcher.choose(all, uhd60, 29.97f))
    }

    @Test
    fun resolutionIsKept() {
        assertEquals(fhd23976, DisplayModeMatcher.choose(all, ModeInfo(7, 1920, 1080, 60f), 23.976f))
    }

    @Test
    fun alreadyMatchedStaysPut() {
        assertNull(DisplayModeMatcher.choose(all, uhd23976, 23.976f))
        assertNull(DisplayModeMatcher.choose(all, uhd60, 60f))
        assertNull(DisplayModeMatcher.choose(all, uhd60, 30f))
    }

    @Test
    fun unknownOrUnmatchableRateStaysPut() {
        assertNull(DisplayModeMatcher.choose(all, uhd60, -1f))
        assertNull(DisplayModeMatcher.choose(all, uhd60, Float.NaN))
        assertNull(DisplayModeMatcher.choose(listOf(uhd60), uhd60, 23.976f))
    }
}
