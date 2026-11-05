package com.thefactor1.player.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * A simulated room: a dark wall with the TV picture somewhere on it, seen from a little off to one
 * side, a film playing (with a cut), the code flickering on every 60 Hz refresh, and a phone camera
 * with a rolling shutter, sensor noise, whole-number brightness and a hand that drifts.
 */
class BeaconLocatorTest {
    private class Room(
        val code: Int?,
        val left: Float, val right: Float,
        /** Top and bottom of the picture's left and right edges: unequal when seen from the side. */
        val topLeft: Float, val bottomLeft: Float, val topRight: Float, val bottomRight: Float,
        val strength: Float = 2f,
        val noise: Float = 3f,
        val refresh: Double = 59.94,
        val frameMs: Double = 40.0,
        val drift: Float = 0.004f,
        val seed: Long = 1,
    )

    private val width = 384
    private val height = 216

    /** Feeds the locator until it reads a code it would answer with; that code and how many frames it took. */
    private fun scan(room: Room, maxFrames: Int, flipped: Boolean = false): Pair<Int?, Int> {
        val random = Random(room.seed)
        val cells = room.code?.let(BeaconCode::cells)
        val locator = BeaconLocator(flipped)
        val vsync = 1000.0 / room.refresh
        val luma = ByteBuffer.allocate(width * height)
        val clock = random.nextDouble() * 100
        for (frame in 0 until maxFrames) {
            val start = clock + frame * room.frameMs
            val dx = room.drift * sin(frame / 23.0).toFloat() + room.drift * 0.3f * random.nextGaussian().toFloat()
            val dy = room.drift * cos(frame / 31.0).toFloat()
            val scene = if (frame < 45) 0.0 else 1.7
            for (y in 0 until height) {
                // The rolling shutter: each row is exposed 8 ms, starting a little later down the frame.
                val rowStart = start + 20.0 * y / height
                val flip = phase(rowStart, rowStart + 8.0, vsync)
                val py = y.toFloat() / height - dy
                for (x in 0 until width) {
                    val px = x.toFloat() / width - dx
                    var value = 14f
                    val u = (px - room.left) / (room.right - room.left)
                    if (u in 0f..1f) {
                        val top = room.topLeft + (room.topRight - room.topLeft) * u
                        val bottom = room.bottomLeft + (room.bottomRight - room.bottomLeft) * u
                        val v = (py - top) / (bottom - top)
                        if (v in 0f..1f) {
                            value = film(u, v, frame, scene)
                            if (cells != null) {
                                var col = floor(u * BeaconCode.COLS).toInt().coerceAtMost(BeaconCode.COLS - 1)
                                var row = floor(v * BeaconCode.ROWS).toInt().coerceAtMost(BeaconCode.ROWS - 1)
                                if (flipped) {
                                    col = BeaconCode.COLS - 1 - col
                                    row = BeaconCode.ROWS - 1 - row
                                }
                                value += room.strength * flip * if (cells[row * BeaconCode.COLS + col]) 1 else -1
                            }
                        }
                    }
                    value += room.noise * random.nextGaussian().toFloat()
                    luma.put(y * width + x, Math.round(value).coerceIn(0, 255).toByte())
                }
            }
            val result = locator.add(Blocks.of(luma, width, height, width, 1)) ?: continue
            if (result.ready) return result.reading.code to frame + 1
        }
        return null to maxFrames
    }

    /** A film: soft shapes that move, and a cut to a different shot partway through. */
    private fun film(u: Float, v: Float, frame: Int, scene: Double): Float {
        val t = frame / 25.0
        val wave = sin(2 * PI * (u * 1.3 + t * 0.15 + scene)) * cos(2 * PI * (v * 0.8 - scene))
        val blobX = 0.5 + 0.3 * sin(t * 0.9 + scene)
        val blobY = 0.5 + 0.2 * cos(t * 0.7)
        val blob = 80 * kotlin.math.exp(-((u - blobX) * (u - blobX) + (v - blobY) * (v - blobY)) / 0.02)
        return (90 + 50 * wave + blob).toFloat()
    }

    private fun phase(from: Double, to: Double, vsync: Double): Float {
        var t = from
        var index = floor(from / vsync).toLong()
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
    fun findsTheTvAnywhereInView() {
        val rooms = listOf(
            // Filling most of the view.
            Room(1234, 0.06f, 0.94f, 0.07f, 0.93f, 0.07f, 0.93f, seed = 1),
            // Small, up and to the left.
            Room(77, 0.08f, 0.48f, 0.1f, 0.5f, 0.1f, 0.5f, seed = 2),
            // From the right-hand side: the far edge looks shorter.
            Room(4000, 0.2f, 0.85f, 0.12f, 0.88f, 0.2f, 0.78f, seed = 3),
            // Low and to the right, from the left.
            Room(2048, 0.4f, 0.95f, 0.42f, 0.95f, 0.36f, 0.99f, seed = 4),
        )
        for (room in rooms) {
            val (code, frames) = scan(room, maxFrames = 300)
            assertEquals("room ${room.seed} after $frames frames", room.code, code)
            assertTrue("room ${room.seed} took $frames frames", frames <= 200)
        }
    }

    /** Rooms at random: any size from a third of the view up, anywhere, a little skewed. */
    @Test
    fun readsNearlyEveryRandomRoomAndNeverTheWrongCode() {
        val random = Random(99)
        var read = 0
        var wrong = 0
        val rooms = 10
        for (i in 0 until rooms) {
            val size = 0.35f + random.nextFloat() * 0.55f
            val left = random.nextFloat() * (0.97f - size) + 0.015f
            val top = random.nextFloat() * (0.97f - size) + 0.015f
            val skew = (random.nextFloat() - 0.5f) * 0.12f * size
            val code = random.nextInt(4096)
            val room = Room(code, left, left + size, top, top + size, top + skew, top + size - skew, seed = 1000L + i)
            when (scan(room, maxFrames = 375).first) {
                code -> read++
                null -> Unit
                else -> wrong++
            }
        }
        assertEquals(0, wrong)
        assertTrue("read $read of $rooms", read >= rooms - 1)
    }

    @Test
    fun readsAFiftyHertzTvWithTheMatchingFrameRate() {
        val room = Room(555, 0.1f, 0.9f, 0.1f, 0.9f, 0.1f, 0.9f, refresh = 50.0, frameMs = CameraTiming.frameNs(50f) / 1e6, seed = 5)
        assertEquals(555, scan(room, maxFrames = 300).first)
    }

    @Test
    fun aCameraMountedUpsideDown() {
        val room = Room(3210, 0.15f, 0.8f, 0.15f, 0.82f, 0.15f, 0.82f, seed = 6)
        assertEquals(3210, scan(room, maxFrames = 300, flipped = true).first)
    }

    @Test
    fun aPictureWithoutTheCodeIsNeverRead() {
        for (seed in 1L..6L) {
            val room = Room(null, 0.1f, 0.9f, 0.1f, 0.9f, 0.1f, 0.9f, seed = seed)
            assertFalse("seed $seed", scan(room, maxFrames = 250).first != null)
        }
    }

    @Test
    fun frameRatesLandOnBothHalvesOfTheFlicker() {
        assertEquals(40_000_000.0, CameraTiming.frameNs(60f).toDouble(), 1e5)
        assertEquals(48_000_000.0, CameraTiming.frameNs(50f).toDouble(), 1e5)
        // 120 Hz: two cycles and a fifth, since one and a fifth would be faster than the camera goes.
        assertEquals(36_666_666.0, CameraTiming.frameNs(120f).toDouble(), 1e5)
    }
}
