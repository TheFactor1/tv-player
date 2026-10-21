package com.thefactor1.player.resume

import com.thefactor1.player.resume.ResumePolicy.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumePolicyTest {
    private val saved = ResumeStore.Entry(positionMs = 2_400_000, durationMs = 7_200_000, savedAt = 0)

    @Test
    fun appPositionWins() {
        assertEquals(ResumePolicy.Start(600_000, Source.APP), ResumePolicy.start(600_000, false, saved))
    }

    @Test
    fun localPositionFillsInWhenTheAppSendsNone() {
        assertEquals(ResumePolicy.Start(2_400_000, Source.LOCAL), ResumePolicy.start(0, false, saved))
    }

    @Test
    fun fromStartIgnoresEverything() {
        assertEquals(ResumePolicy.Start(0, Source.NONE), ResumePolicy.start(600_000, true, saved))
    }

    @Test
    fun watchedOrFalseStartIsNotResumed() {
        val nearEnd = saved.copy(positionMs = 7_150_000)
        val falseStart = saved.copy(positionMs = 10_000)
        assertEquals(Source.NONE, ResumePolicy.start(0, false, nearEnd).source)
        assertEquals(Source.NONE, ResumePolicy.start(0, false, falseStart).source)
        assertEquals(Source.NONE, ResumePolicy.start(0, false, null).source)
    }

    @Test
    fun lengthsWithinAFewPercentMatch() {
        assertTrue(ResumePolicy.sameLength(7_200_000, 7_230_000))
        assertTrue(ResumePolicy.sameLength(1_320_000, 1_370_000))
        assertFalse(ResumePolicy.sameLength(1_320_000, 2_640_000))
        assertTrue(ResumePolicy.sameLength(0, 2_640_000))
    }

    @Test
    fun forgetWhenFinishedOrBarelyStarted() {
        assertTrue(ResumePolicy.shouldForget(100_000, 7_200_000, completed = true))
        assertTrue(ResumePolicy.shouldForget(20_000, 7_200_000, completed = false))
        assertTrue(ResumePolicy.shouldForget(6_700_000, 7_200_000, completed = false))
        assertFalse(ResumePolicy.shouldForget(2_400_000, 7_200_000, completed = false))
    }

    @Test
    fun savedEntryRoundTrips() {
        assertEquals(saved, ResumeStore.Entry.decode(saved.encode()))
        assertEquals(null, ResumeStore.Entry.decode("garbage"))
    }
}
