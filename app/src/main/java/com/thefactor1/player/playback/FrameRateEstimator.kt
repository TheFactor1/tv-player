package com.thefactor1.player.playback

import kotlin.math.abs

/**
 * Measures a video's frame rate from the presentation times of rendered frames, for streams whose
 * container doesn't declare one. Containers often round timestamps to whole milliseconds (23.976 fps
 * becomes 41 and 42 ms steps), so it averages over a few seconds and snaps to the nearest broadcast
 * rate, which tells 23.976 from 24.
 */
class FrameRateEstimator(private val samplesNeeded: Int = 96) {

    private val deltas = ArrayList<Long>(samplesNeeded)
    private var lastUs = Long.MIN_VALUE

    /** Feeds one frame's presentation time; returns the frame rate once enough frames are in, else null. */
    fun add(presentationTimeUs: Long): Float? {
        val previous = lastUs
        lastUs = presentationTimeUs
        if (previous == Long.MIN_VALUE || presentationTimeUs <= previous) return null
        deltas += presentationTimeUs - previous
        if (deltas.size < samplesNeeded) return null
        return estimate()
    }

    fun reset() {
        deltas.clear()
        lastUs = Long.MIN_VALUE
    }

    private fun estimate(): Float? {
        val median = deltas.sorted()[deltas.size / 2]
        // Seeks and dropped frames show up as outliers; leave them out of the average.
        val steady = deltas.filter { it in (median / 2)..(median * 3 / 2) }
        if (steady.size < samplesNeeded / 2) {
            reset()
            return null
        }
        val measured = steady.size * 1_000_000.0 / steady.sum()
        return snap(measured)
    }

    private fun snap(measured: Double): Float {
        val nearest = STANDARD_RATES.minBy { abs(it - measured) }
        return if (abs(nearest - measured) / nearest <= SNAP_TOLERANCE) nearest.toFloat() else measured.toFloat()
    }

    private companion object {
        val STANDARD_RATES = doubleArrayOf(
            24_000 / 1_001.0, 24.0, 25.0, 30_000 / 1_001.0, 30.0,
            48_000 / 1_001.0, 48.0, 50.0, 60_000 / 1_001.0, 60.0,
        )
        const val SNAP_TOLERANCE = 0.005
    }
}
