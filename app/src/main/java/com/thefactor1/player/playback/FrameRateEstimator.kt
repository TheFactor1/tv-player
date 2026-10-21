package com.thefactor1.player.playback

import kotlin.math.abs

/**
 * Measures a video's frame rate from the presentation times of rendered frames, for streams whose
 * container doesn't declare one. Containers often round timestamps to whole milliseconds (23.976 fps
 * becomes 41 and 42 ms steps), so it counts frames over a few seconds of video and snaps to the
 * nearest broadcast rate, which tells 23.976 from 24. Dropped frames show up as double-length steps
 * and are counted as the frames they stand for, so a struggling decoder still gets an answer.
 */
class FrameRateEstimator(private val spanUs: Long = 3_000_000) {

    private val deltas = ArrayList<Long>(256)
    private var lastUs = Long.MIN_VALUE

    /** Feeds one frame's presentation time; returns the frame rate once enough video is in, else null. */
    fun add(presentationTimeUs: Long): Float? {
        val previous = lastUs
        lastUs = presentationTimeUs
        if (previous == Long.MIN_VALUE || presentationTimeUs <= previous) return null
        deltas += presentationTimeUs - previous
        if (deltas.size < MIN_FRAMES) return null
        return estimate()
    }

    fun reset() {
        deltas.clear()
        lastUs = Long.MIN_VALUE
    }

    private fun estimate(): Float? {
        val frameUs = deltas.sorted()[deltas.size / 2]
        var spannedUs = 0L
        var frames = 0L
        for (delta in deltas) {
            val steps = Math.round(delta.toDouble() / frameUs)
            // Anything longer than a few frames is a seek or a stall, not a frame interval.
            if (steps < 1 || steps > MAX_DROPPED_RUN) continue
            spannedUs += delta
            frames += steps
        }
        if (spannedUs < spanUs) return null
        return snap(frames * 1_000_000.0 / spannedUs)
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
        const val MIN_FRAMES = 16
        const val MAX_DROPPED_RUN = 4
    }
}
