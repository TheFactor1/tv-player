package com.thefactor1.player.resume

import com.thefactor1.player.resume.ResumePolicy.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumePolicyTest {
    private val saved = ResumeStore.Entry(positionMs = 2_400_000, durationMs = 7_200_000, savedAt = 0)
    private val now = 1_000_000_000_000L
    private val recent = saved.copy(savedAt = now - 10 * 60_000)

    @Test
    fun appPositionWins() {
        assertEquals(ResumePolicy.Start(600_000, Source.APP), ResumePolicy.start(600_000, false, saved, now))
    }

    @Test
    fun localPositionFillsInWhenTheAppSendsNone() {
        assertEquals(ResumePolicy.Start(2_400_000, Source.LOCAL), ResumePolicy.start(0, false, saved, now))
    }

    @Test
    fun fromStartIgnoresTheAppAndOldMemories() {
        assertEquals(ResumePolicy.Start(0, Source.NONE), ResumePolicy.start(600_000, true, saved, now))
        assertEquals(ResumePolicy.Start(0, Source.NONE), ResumePolicy.start(0, true, null, now))
    }

    @Test
    fun fromStartAtZeroAfterADroppedStreamStillResumesARecentSpot() {
        // A source app re-searching after a dropped stream relaunches at 0 with "from start" set.
        assertEquals(ResumePolicy.Start(2_400_000, Source.LOCAL), ResumePolicy.start(0, true, recent, now))
    }

    @Test
    fun watchedOrFalseStartIsNotResumed() {
        val nearEnd = saved.copy(positionMs = 7_150_000)
        val falseStart = saved.copy(positionMs = 10_000)
        assertEquals(Source.NONE, ResumePolicy.start(0, false, nearEnd, now).source)
        assertEquals(Source.NONE, ResumePolicy.start(0, false, falseStart, now).source)
        assertEquals(Source.NONE, ResumePolicy.start(0, false, null, now).source)
    }

    @Test
    fun lengthsWithinAFewPercentMatch() {
        assertTrue(ResumePolicy.sameLength(7_200_000, 7_230_000))
        assertTrue(ResumePolicy.sameLength(1_320_000, 1_370_000))
        assertFalse(ResumePolicy.sameLength(1_320_000, 2_640_000))
        assertTrue(ResumePolicy.sameLength(0, 2_640_000))
    }

    @Test
    fun forgetOnlyWhenWatchedAndKeepWhenBarelyStarted() {
        assertEquals(ResumePolicy.Leave.FORGET, ResumePolicy.onLeave(100_000, 7_200_000, completed = true))
        assertEquals(ResumePolicy.Leave.FORGET, ResumePolicy.onLeave(6_700_000, 7_200_000, completed = false))
        assertEquals(ResumePolicy.Leave.SAVE, ResumePolicy.onLeave(2_400_000, 7_200_000, completed = false))
        // A stream that failed to load, or was backed out of, leaves another stream's position alone.
        assertEquals(ResumePolicy.Leave.KEEP, ResumePolicy.onLeave(20_000, 7_200_000, completed = false))
        assertEquals(ResumePolicy.Leave.KEEP, ResumePolicy.onLeave(0, 0, completed = false))
    }

    @Test
    fun aFewSecondsFromTheAppDoNotHideTheLocalPosition() {
        assertEquals(ResumePolicy.Start(2_400_000, Source.LOCAL), ResumePolicy.start(4_000, false, saved, now))
    }

    @Test
    fun lastWatchedFilmIsPickedUpByANearlyIdenticalLength() {
        val last = saved.copy(savedAt = now - 60_000)
        assertTrue(ResumePolicy.lastWatchedFits(last, false, false, 7_212_000, now))
        assertTrue("another release, a minute longer", ResumePolicy.lastWatchedFits(last, false, false, 7_260_000, now))
        assertFalse("different film", ResumePolicy.lastWatchedFits(last, false, false, 7_500_000, now))
        assertFalse("episodes", ResumePolicy.lastWatchedFits(last, true, false, 7_200_000, now))
        assertFalse("episodes", ResumePolicy.lastWatchedFits(last, false, true, 7_200_000, now))
        assertFalse("too long ago", ResumePolicy.lastWatchedFits(last.copy(savedAt = 0), false, false, 7_200_000, now))
        assertFalse("nothing", ResumePolicy.lastWatchedFits(null, false, false, 7_200_000, now))
    }

    @Test
    fun savedEntryRoundTrips() {
        assertEquals(saved, ResumeStore.Entry.decode(saved.encode()))
        assertEquals(null, ResumeStore.Entry.decode("garbage"))
    }
}
