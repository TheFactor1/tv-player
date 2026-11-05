package com.thefactor1.player.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * A simulated TV and camera: the screen flips the pattern every refresh (59.94 Hz), the camera takes
 * a frame every 40 ms with an 8 ms shutter that sweeps down the picture over 20 ms, and the picture
 * underneath drifts and is noisy.
 */
class BeaconDecoderTest {
    private fun film(code: Int?, strength: Float, noise: Float, frames: Int, seed: Long): BeaconDecoder {
        val random = Random(seed)
        val cells = code?.let(BeaconCode::cells)
        val vsync = 1000.0 / 59.94
        val base = FloatArray(BeaconCode.COLS * BeaconCode.ROWS) { 40f + random.nextFloat() * 150f }
        val offset = random.nextDouble() * 40
        val decoder = BeaconDecoder()
        repeat(frames) { frame ->
            val means = FloatArray(base.size) { c ->
                val row = c / BeaconCode.COLS
                val start = offset + frame * 40.0 + 20.0 * (0.15 + 0.7 * (row + 0.5) / BeaconCode.ROWS) + random.nextGaussian() * 0.3
                val flip = phase(start, start + 8.0, vsync)
                val sign = when {
                    cells == null -> 0f
                    cells[c] -> 1f
                    else -> -1f
                }
                base[c] + 6f * kotlin.math.sin(frame / 9.0).toFloat() + strength * flip * sign + noise * random.nextGaussian().toFloat()
            }
            decoder.add(means)
        }
        return decoder
    }

    /** -1 to 1: how much of the exposure fell on "brighter" refreshes rather than "darker" ones. */
    private fun phase(from: Double, to: Double, vsync: Double): Float {
        var t = from
        var index = Math.floor(from / vsync).toLong()
        var sum = 0.0
        while (t < to) {
            val end = minOf(to, (index + 1) * vsync)
            sum += (end - t) * (if (index % 2 == 0L) 1 else -1)
            t = end
            index++
        }
        return (sum / (to - from)).toFloat()
    }

    @Test
    fun readsTheCodeThroughNoise() {
        for (seed in 1L..20L) {
            val code = (seed * 197 % 4096).toInt()
            val reading = film(code, strength = 1.5f, noise = 1f, frames = 76, seed = seed).read()!!
            assertTrue("seed $seed confidence ${reading.confidence}", reading.accepted)
            assertEquals(code, reading.code)
        }
    }

    @Test
    fun nothingOnScreenIsNeverAccepted() {
        for (seed in 1L..200L) {
            val reading = film(null, strength = 0f, noise = 1f, frames = 76, seed = seed).read()!!
            assertFalse("seed $seed", reading.accepted)
        }
    }

    @Test
    fun checkCatchesAFlippedBit() {
        val bits = (1234 shl 4) or BeaconCode.crc4(1234)
        assertEquals(1234, BeaconCode.code(bits))
        for (bit in 0 until 16) assertEquals(null, BeaconCode.code(bits xor (1 shl bit)))
    }

    /** The TV's BeaconPatternTest pins the same layout; the two must agree. */
    @Test
    fun layoutMatchesTheTv() {
        assertEquals("101100101010100110101011001001011010", BeaconCode.cells(1234).joinToString("") { if (it) "1" else "0" })
    }

    @Test
    fun everyFrameIsBalanced() {
        for (code in listOf(0, 1, 2048, 4095, 1234)) {
            assertEquals(BeaconCode.cells(code).size / 2, BeaconCode.cells(code).count { it })
        }
    }
}
