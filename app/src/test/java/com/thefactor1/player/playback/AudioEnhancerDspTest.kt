package com.thefactor1.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class AudioEnhancerDspTest {

    private val rate = 48_000

    /** A sine of [rmsDb] dBFS RMS at [hz] on the channels in [on], silence on the rest. */
    private fun tone(channels: Int, rmsDb: Float, hz: Float = 1_000f, on: Set<Int> = (0 until channels).toSet()): (Int) -> FloatArray {
        val amplitude = 10f.pow(rmsDb / 20f) * sqrt(2f)
        return { n ->
            val s = amplitude * sin(2.0 * PI * hz * n / rate).toFloat()
            FloatArray(channels) { if (it in on) s else 0f }
        }
    }

    /** Runs two seconds through the DSP; returns each channel's RMS (dBFS) and peak over the last half second. */
    private fun run(dsp: AudioEnhancerDsp, source: (Int) -> FloatArray, night: Boolean, dialogue: Boolean): Pair<FloatArray, Float> {
        val total = rate * 2
        val measureFrom = total - rate / 2
        var sums = FloatArray(0)
        var peak = 0f
        for (n in 0 until total) {
            val frame = source(n)
            if (sums.isEmpty()) sums = FloatArray(frame.size)
            dsp.process(frame, night, dialogue)
            if (n >= measureFrom) for (c in frame.indices) {
                sums[c] += frame[c] * frame[c]
                peak = max(peak, abs(frame[c]))
            }
        }
        val count = (total - measureFrom).toFloat()
        return FloatArray(sums.size) { 10f * log10(max(sums[it] / count, 1e-20f)) } to peak
    }

    private fun levelDb(channels: Int, rmsDb: Float, night: Boolean = true, dialogue: Boolean = false) =
        run(AudioEnhancerDsp(rate, channels), tone(channels, rmsDb), night, dialogue).first[0]

    @Test
    fun idleWhenNothingIsOn() {
        val dsp = AudioEnhancerDsp(rate, 2)
        assertTrue(dsp.isIdle(nightMode = false, dialogueBoost = false))
        val frame = floatArrayOf(0.3f, -0.2f)
        dsp.process(frame, nightMode = false, dialogueBoost = false)
        assertEquals(0.3f, frame[0], 1e-6f)
        assertEquals(-0.2f, frame[1], 1e-6f)
    }

    @Test
    fun nightModeLiftsDialogueAndWhispers() {
        // Film dialogue sits around -31 dBFS once decoded; a whisper 15 dB below that.
        assertTrue("dialogue comes up", levelDb(1, -31f) - -31f > 4f)
        assertTrue("whispers come up a lot", levelDb(1, -46f) - -46f > 12f)
    }

    @Test
    fun nightModeBringsExplosionsWellDown() {
        assertTrue(levelDb(1, -8f) - -8f < -10f)
        assertTrue(levelDb(1, -3f) - -3f < -13f)
    }

    @Test
    fun nightModeSquashesTheRangeOfTheFilm() {
        val quiet = levelDb(1, -46f)
        val loud = levelDb(1, -6f)
        assertTrue("40 dB of range becomes under 15", loud - quiet < 15f)
    }

    @Test
    fun nightModeLeavesHissAlone() {
        assertTrue(levelDb(1, -85f) - -85f < 1f)
    }

    @Test
    fun nightModeTurnsTheLfeDown() {
        val (levels, _) = run(AudioEnhancerDsp(rate, 6), tone(6, -20f, hz = 60f), night = true, dialogue = false)
        assertTrue(levels[3] < levels[0] - 5f)
    }

    @Test
    fun switchingOnFadesInInsteadOfJumping() {
        val dsp = AudioEnhancerDsp(rate, 2)
        val first = floatArrayOf(0.01f, 0.01f)
        dsp.process(first, nightMode = true, dialogueBoost = false)
        assertTrue(abs(first[0] - 0.01f) < 0.001f)
        assertTrue(!dsp.isIdle(nightMode = false, dialogueBoost = false))
    }

    @Test
    fun dialogueBoostPutsTheCentreWellAboveTheRest() {
        val (levels, _) = run(AudioEnhancerDsp(rate, 6), tone(6, -30f, hz = 2_000f), night = false, dialogue = true)
        assertTrue("voices come up", levels[2] > -30f + 8f)
        assertTrue("the rest steps back", levels[0] < -30f - 4f)
        assertTrue("centre leads the fronts by a lot", levels[2] - levels[0] > 12f)
    }

    @Test
    fun dialogueBoostLiftsTheSpeechBandMoreThanBass() {
        val speech = run(AudioEnhancerDsp(rate, 6), tone(6, -30f, hz = 2_000f, on = setOf(2)), false, true).first[2]
        val bass = run(AudioEnhancerDsp(rate, 6), tone(6, -30f, hz = 80f, on = setOf(2)), false, true).first[2]
        assertTrue(speech - bass > 4f)
    }

    @Test
    fun dialogueBoostWorksOn7Point1() {
        val (levels, _) = run(AudioEnhancerDsp(rate, 8), tone(8, -30f, hz = 2_000f), night = false, dialogue = true)
        assertTrue(levels[2] - levels[6] > 12f)
    }

    @Test
    fun dialogueBoostOnStereoLiftsTheMiddleAndNarrowsTheSides() {
        val centred = run(AudioEnhancerDsp(rate, 2), tone(2, -30f, hz = 2_000f), false, true).first[0]
        assertTrue(centred > -30f + 6f)
        val wideSource = tone(2, -30f, hz = 2_000f, on = setOf(0))
        val wide = run(AudioEnhancerDsp(rate, 2), { n -> wideSource(n).also { it[1] = -it[0] } }, false, true).first[0]
        assertTrue(wide < -30f - 4f)
    }

    @Test
    fun boostedLoudAudioNeverClips() {
        val (_, peak) = run(AudioEnhancerDsp(rate, 6), tone(6, -3f, hz = 2_000f), night = false, dialogue = true)
        assertTrue(peak <= AudioEnhancerDsp.LIMIT_CEILING)
        val (_, nightPeak) = run(AudioEnhancerDsp(rate, 6), tone(6, -3f, hz = 2_000f), night = true, dialogue = true)
        assertTrue(nightPeak <= AudioEnhancerDsp.LIMIT_CEILING)
    }

    @Test
    fun softLimitNeverPassesFullScale() {
        assertTrue(AudioEnhancerDsp.softLimit(3f) < 1f)
        assertTrue(AudioEnhancerDsp.softLimit(-3f) > -1f)
        assertEquals(0.5f, AudioEnhancerDsp.softLimit(0.5f), 0f)
    }

    @Test
    fun gainCurvePullsTowardsTheTarget() {
        fun power(db: Float) = 10f.pow(db / 10f)
        assertEquals(0f, AudioEnhancerDsp.targetGainDb(power(AudioEnhancerDsp.TARGET_DB)), 1e-3f)
        assertEquals(AudioEnhancerDsp.MAX_BOOST_DB, AudioEnhancerDsp.targetGainDb(power(-55f)), 1e-3f)
        assertTrue(AudioEnhancerDsp.targetGainDb(power(0f)) < -15f)
        assertEquals(0f, AudioEnhancerDsp.targetGainDb(power(-90f)), 1e-3f)
    }
}
