package com.thefactor1.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AudioEnhancerDspTest {

    private fun run(dsp: AudioEnhancerDsp, frame: () -> FloatArray, frames: Int, night: Boolean, dialogue: Boolean): FloatArray {
        var last = frame()
        repeat(frames) {
            last = frame()
            dsp.process(last, night, dialogue)
        }
        return last
    }

    @Test
    fun idleWhenNothingIsOn() {
        val dsp = AudioEnhancerDsp(48_000, 2)
        assertTrue(dsp.isIdle(nightMode = false, dialogueBoost = false))
        val out = run(dsp, { floatArrayOf(0.3f, -0.2f) }, 10, night = false, dialogue = false)
        assertEquals(0.3f, out[0], 1e-6f)
        assertEquals(-0.2f, out[1], 1e-6f)
    }

    @Test
    fun nightModeLiftsQuietAndLowersLoud() {
        val quiet = run(AudioEnhancerDsp(48_000, 2), { floatArrayOf(0.01f, 0.01f) }, 48_000, night = true, dialogue = false)
        assertTrue("quiet passages get louder", quiet[0] > 0.015f)
        val loud = run(AudioEnhancerDsp(48_000, 2), { floatArrayOf(0.8f, 0.8f) }, 48_000, night = true, dialogue = false)
        assertTrue("loud passages get quieter", loud[0] < 0.5f)
    }

    @Test
    fun switchingOnFadesInInsteadOfJumping() {
        val dsp = AudioEnhancerDsp(48_000, 2)
        val first = floatArrayOf(0.01f, 0.01f)
        dsp.process(first, nightMode = true, dialogueBoost = false)
        assertTrue(abs(first[0] - 0.01f) < 0.001f)
        assertTrue(!dsp.isIdle(nightMode = false, dialogueBoost = false))
    }

    @Test
    fun dialogueBoostLiftsTheCentreChannel() {
        val out = run(AudioEnhancerDsp(48_000, 6), { floatArrayOf(0.1f, 0.1f, 0.1f, 0.1f, 0.1f, 0.1f) }, 48_000, night = false, dialogue = true)
        assertTrue(out[2] > 0.15f)
        assertTrue(out[0] < 0.1f)
        assertEquals("LFE is left alone", 0.1f, out[3], 1e-6f)
    }

    @Test
    fun dialogueBoostOnStereoKeepsTheMiddleAndNarrowsTheSides() {
        val centred = run(AudioEnhancerDsp(48_000, 2), { floatArrayOf(0.1f, 0.1f) }, 48_000, night = false, dialogue = true)
        assertTrue(centred[0] > 0.1f)
        val wide = run(AudioEnhancerDsp(48_000, 2), { floatArrayOf(0.1f, -0.1f) }, 48_000, night = false, dialogue = true)
        assertTrue(abs(wide[0]) < 0.1f)
    }

    @Test
    fun limiterNeverPassesFullScale() {
        assertTrue(AudioEnhancerDsp.softLimit(3f) < 1f)
        assertTrue(AudioEnhancerDsp.softLimit(-3f) > -1f)
        assertEquals(0.5f, AudioEnhancerDsp.softLimit(0.5f), 0f)
    }

    @Test
    fun gainIsMakeupBelowThresholdAndLessAbove() {
        assertEquals(AudioEnhancerDsp.MAKEUP_DB, AudioEnhancerDsp.gainDb(0.001f), 1e-4f)
        assertTrue(AudioEnhancerDsp.gainDb(1f) < 0f)
    }
}
