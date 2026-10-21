package com.thefactor1.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameRateEstimatorTest {

    /** Frame times for [fps], optionally rounded to whole milliseconds like many MKVs. */
    private fun times(fps: Double, count: Int, roundToMs: Boolean, startUs: Long = 0): List<Long> =
        (0 until count).map { i ->
            val us = startUs + i * 1_000_000.0 / fps
            if (roundToMs) Math.round(us / 1000.0) * 1000 else Math.round(us)
        }

    private fun estimate(points: List<Long>): Float? {
        val estimator = FrameRateEstimator()
        var result: Float? = null
        for (t in points) result = estimator.add(t) ?: result
        return result
    }

    @Test
    fun film23976WithMillisecondRoundingIsNotMistakenFor24() {
        assertEquals(23.976f, estimate(times(24_000 / 1_001.0, 97, roundToMs = true))!!, 0.001f)
    }

    @Test
    fun true24StaysAt24() {
        assertEquals(24f, estimate(times(24.0, 97, roundToMs = true))!!, 0.001f)
    }

    @Test
    fun pal25AndNtsc5994AreRecognised() {
        assertEquals(25f, estimate(times(25.0, 97, roundToMs = true))!!, 0.001f)
        assertEquals(59.94f, estimate(times(60_000 / 1_001.0, 97, roundToMs = true))!!, 0.001f)
    }

    @Test
    fun aSeekInTheMiddleDoesNotSkewTheResult() {
        val before = times(24_000 / 1_001.0, 50, roundToMs = true)
        val after = times(24_000 / 1_001.0, 50, roundToMs = true, startUs = 600_000_000)
        assertEquals(23.976f, estimate(before + after)!!, 0.001f)
    }

    @Test
    fun noAnswerUntilEnoughFrames() {
        assertNull(estimate(times(24.0, 40, roundToMs = false)))
    }
}
