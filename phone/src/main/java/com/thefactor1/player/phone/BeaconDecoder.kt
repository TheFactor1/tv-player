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
    /**
     * [bits] as read; [code] when the check agrees; [confidence] is the weakest pair's agreement and
     * [strength] the pairs' average, both 0 to 1.
     */
    class Reading(val bits: Int, val code: Int?, val confidence: Float, val frames: Int, val strength: Float = confidence) {
        val accepted: Boolean get() = code != null && confidence >= ACCEPT
    }

    private val frames = ArrayDeque<FloatArray>()

    fun add(cellMeans: FloatArray) {
        require(cellMeans.size == BeaconCode.COLS * BeaconCode.ROWS)
        frames.addLast(cellMeans.copyOf())
        while (frames.size > window + 1) frames.removeFirst()
    }

    fun reset() = frames.clear()

    fun read(): Reading? = decode(frames)

    companion object {
        /** About three seconds at 25 frames a second. */
        const val WINDOW = 75
        const val MIN_FRAMES = 20
        /** By chance alone every one of 16 pairs agrees this well far less than once in a million. */
        const val ACCEPT = 0.25f
        /** A step that changes this many times more than a typical one is a cut, a jolt or a refocus. */
        private const val JOLT = 3.5f
        private const val REFERENCE_WEIGHT = 2f

        /** Reads the code from a run of frames, each the 36 cells' average brightness, oldest first. */
        fun decode(frames: List<FloatArray>): Reading? {
            if (frames.size < MIN_FRAMES) return null
            val delta = changes(frames)
            val steps = delta.size
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
            var total = 0f
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
                total += abs(agreement)
            }
            return Reading(bits, BeaconCode.code(bits), weakest, frames.size, total / BeaconCode.PAIRS)
        }

        /**
         * How much the frames look like the code seen through the right cells, -1 to 1: the two cells
         * of every pair flicker in opposite directions, and so do neighbouring rows' reference cells. Cells that miss the grid, or share one of its
         * cells, or see only the film, move together or not at all. Used to find where the picture is.
         */
        fun fit(frames: List<FloatArray>): Float {
            if (frames.size < MIN_FRAMES) return 0f
            val delta = changes(frames)
            val steps = delta.size
            fun opposed(a: Int, b: Int) = -agreement(FloatArray(steps) { delta[it][a] }, FloatArray(steps) { delta[it][b] })
            var total = 0f
            for (k in 0 until BeaconCode.PAIRS) total += opposed(BeaconCode.pairCell(k, 0), BeaconCode.pairCell(k, 1))
            // The reference cells alternate down the rows, which catches a placement that reads one row twice.
            for (row in 0 until BeaconCode.ROWS - 1) total += REFERENCE_WEIGHT * opposed(row * BeaconCode.COLS, (row + 1) * BeaconCode.COLS)
            return total / (BeaconCode.PAIRS + REFERENCE_WEIGHT * (BeaconCode.ROWS - 1))
        }

        /**
         * How each cell's brightness changed, frame to frame, less what the whole picture did (a scene
         * change, the projector's lamp). Taken twice over: the film moves smoothly from frame to frame
         * and mostly cancels out, while the code's flicker, a fifth of a cycle along each frame, does not.
         */
        private fun changes(frames: List<FloatArray>): Array<FloatArray> {
            val cells = BeaconCode.COLS * BeaconCode.ROWS
            val delta = Array(frames.size - 2) { t ->
                val step = FloatArray(cells) { c -> frames[t + 2][c] - 2 * frames[t + 1][c] + frames[t][c] }
                val common = step.average().toFloat()
                for (c in 0 until cells) step[c] -= common
                step
            }
            dropJolts(delta)
            return delta
        }

        /**
         * Leaves out the few steps where everything changed far more than usual: a cut in the film,
         * the phone jolted, the lens refocusing. One of those outweighs seconds of the faint flicker.
         */
        private fun dropJolts(delta: Array<FloatArray>) {
            val size = FloatArray(delta.size) { t -> delta[t].fold(0f) { sum, d -> sum + abs(d) } / delta[t].size }
            val typical = size.sortedArray()[size.size / 2]
            if (typical <= 0f) return
            for (t in delta.indices) if (size[t] > JOLT * typical) delta[t].fill(0f)
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
    }
}
