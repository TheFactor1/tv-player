package com.thefactor1.player.phone

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Reads the TV's invisible code from the camera. Each camera frame gives the average brightness of
 * the 36 grid cells. The TV flips every cell between a little brighter and a little darker on each
 * screen refresh, and the camera's short shutter catches one or the other, so from frame to frame
 * "on" cells move opposite to "off" ones. A pair's bit is whichever way it moves against its row's
 * reference cell. Rows are compared only within themselves, since the camera reads its rows at
 * slightly different moments.
 */
class BeaconDecoder(private val window: Int = WINDOW) {
    /** [bits] as read; [code] when the check agrees; [confidence] is the weakest pair's agreement, 0 to 1. */
    class Reading(val bits: Int, val code: Int?, val confidence: Float, val frames: Int) {
        val accepted: Boolean get() = code != null && confidence >= ACCEPT
    }

    private val frames = ArrayDeque<FloatArray>()

    fun add(cellMeans: FloatArray) {
        require(cellMeans.size == BeaconCode.COLS * BeaconCode.ROWS)
        frames.addLast(cellMeans.copyOf())
        while (frames.size > window + 1) frames.removeFirst()
    }

    fun reset() = frames.clear()

    fun read(): Reading? {
        if (frames.size < MIN_FRAMES) return null
        val cells = BeaconCode.COLS * BeaconCode.ROWS
        val steps = frames.size - 1
        // Frame-to-frame changes, less what the whole picture did (a scene change, the projector's lamp).
        val delta = Array(steps) { t ->
            val step = FloatArray(cells) { c -> frames[t + 1][c] - frames[t][c] }
            val common = step.average().toFloat()
            for (c in 0 until cells) step[c] -= common
            step
        }
        val pairs = Array(BeaconCode.PAIRS) { k ->
            FloatArray(steps) { t -> delta[t][BeaconCode.pairCell(k, 0)] - delta[t][BeaconCode.pairCell(k, 1)] }
        }
        val references = Array(BeaconCode.ROWS) { row ->
            val sign = if (row % 2 == 0) 1f else -1f
            FloatArray(steps) { t -> sign * delta[t][row * BeaconCode.COLS] }
        }
        // First guess against the reference cell alone; then again against the reference plus the
        // row's other pairs as first read, which is a much steadier yardstick than one cell.
        val first = FloatArray(BeaconCode.PAIRS) { k -> agreement(pairs[k], references[k / 4]) }
        var bits = 0
        var weakest = 1f
        for (k in 0 until BeaconCode.PAIRS) {
            val row = k / 4
            val yardstick = references[row].copyOf()
            for (other in row * 4 until row * 4 + 4) {
                if (other == k) continue
                val sign = if (first[other] >= 0) 1f else -1f
                for (t in 0 until steps) yardstick[t] += sign * pairs[other][t]
            }
            val agreement = agreement(pairs[k], yardstick)
            if (agreement > 0) bits = bits or (1 shl (15 - k))
            weakest = minOf(weakest, abs(agreement))
        }
        return Reading(bits, BeaconCode.code(bits), weakest, frames.size)
    }

    /** Normalised correlation, -1 to 1. */
    private fun agreement(a: FloatArray, b: FloatArray): Float {
        var ab = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in a.indices) {
            ab += a[i] * b[i]
            aa += a[i] * a[i]
            bb += b[i] * b[i]
        }
        if (aa == 0.0 || bb == 0.0) return 0f
        return (ab / sqrt(aa * bb)).toFloat()
    }

    companion object {
        /** About three seconds at 25 frames a second. */
        const val WINDOW = 75
        const val MIN_FRAMES = 20
        /** By chance alone every one of 16 pairs agrees this well far less than once in a million. */
        const val ACCEPT = 0.25f
    }
}
