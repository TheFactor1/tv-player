package com.thefactor1.player.meta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkipSegmentsTest {
    private val intro = SkipSegment(SkipSegment.Kind.INTRO, 60_000, 120_000)
    private val credits = SkipSegment(SkipSegment.Kind.CREDITS, 2_500_000, null)

    @Test
    fun findsTheSegmentPlaying() {
        val segments = listOf(intro, credits)
        assertEquals(intro, SkipSegments.at(segments, 61_000, 2_700_000))
        assertNull(SkipSegments.at(segments, 59_000, 2_700_000))
        assertEquals(credits, SkipSegments.at(segments, 2_600_000, 2_700_000))
    }

    @Test
    fun stopsOfferingRightBeforeTheEnd() {
        assertNull(SkipSegments.at(listOf(intro), 119_000, 2_700_000))
    }

    @Test
    fun creditsWithoutAnEndRunToTheEndOfTheVideo() {
        assertEquals(2_700_000L, SkipSegments.endOf(credits, 2_700_000))
    }

    @Test
    fun dropsTimingsThatDoNotFitTheVideo() {
        val backwards = SkipSegment(SkipSegment.Kind.INTRO, 50_000, 40_000)
        val tiny = SkipSegment(SkipSegment.Kind.RECAP, 0, 2_000)
        val pastTheEnd = SkipSegment(SkipSegment.Kind.CREDITS, 3_000_000, null)
        assertEquals(listOf(intro), SkipSegments.usable(listOf(backwards, tiny, pastTheEnd, intro), 2_700_000))
    }
}
