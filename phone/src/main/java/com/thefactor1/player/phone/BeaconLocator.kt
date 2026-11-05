package com.thefactor1.player.phone

import java.nio.ByteBuffer
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Where the TV picture sits in the camera's view: four corners (top left, top right, bottom left,
 * bottom right) as fractions of the frame. The picture can be anywhere in view, any size, and a
 * little skewed when the phone is off to one side.
 */
class Quad(val corners: FloatArray) {
    init {
        require(corners.size == 8)
    }

    fun x(corner: Int) = corners[corner * 2]
    fun y(corner: Int) = corners[corner * 2 + 1]

    /** The point [u] across and [v] down the picture, into [out] as x, y. */
    fun at(u: Float, v: Float, out: FloatArray) {
        val topX = x(0) + (x(1) - x(0)) * u
        val topY = y(0) + (y(1) - y(0)) * u
        val bottomX = x(2) + (x(3) - x(2)) * u
        val bottomY = y(2) + (y(3) - y(2)) * u
        out[0] = topX + (bottomX - topX) * v
        out[1] = topY + (bottomY - topY) * v
    }

    val width: Float get() = (hypot(x(1) - x(0), y(1) - y(0)) + hypot(x(3) - x(2), y(3) - y(2))) / 2
    val height: Float get() = (hypot(x(2) - x(0), y(2) - y(0)) + hypot(x(3) - x(1), y(3) - y(1))) / 2

    /** Mostly in view, the right way round, and not folded over itself. */
    val sensible: Boolean
        get() = corners.all { it in -OVERHANG..1f + OVERHANG } && width >= MIN_SIZE && height >= MIN_SIZE &&
            x(1) > x(0) && x(3) > x(2) && y(2) > y(0) && y(3) > y(1)

    fun nudged(index: Int, by: Float) = Quad(corners.copyOf().also { it[index] += by })

    fun shifted(dx: Float, dy: Float) = Quad(FloatArray(8) { i -> corners[i] + if (i % 2 == 0) dx else dy })

    fun scaled(factor: Float): Quad {
        val cx = (x(0) + x(1) + x(2) + x(3)) / 4
        val cy = (y(0) + y(1) + y(2) + y(3)) / 4
        return Quad(FloatArray(8) { i -> if (i % 2 == 0) cx + (corners[i] - cx) * factor else cy + (corners[i] - cy) * factor })
    }

    companion object {
        const val MIN_SIZE = 0.12f
        private const val OVERHANG = 0.03f

        fun rect(cx: Float, cy: Float, w: Float, h: Float) =
            Quad(floatArrayOf(cx - w / 2, cy - h / 2, cx + w / 2, cy - h / 2, cx - w / 2, cy + h / 2, cx + w / 2, cy + h / 2))
    }
}

/** The camera frame boiled down to a grid of average brightnesses, small enough to keep seconds of. */
object Blocks {
    const val W = 96
    const val H = 54

    /** Each block's mean luma, from every [step]th pixel of a camera frame's Y plane. */
    fun of(luma: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int, step: Int = 2): FloatArray {
        val sums = FloatArray(W * H)
        val counts = IntArray(W * H)
        val column = IntArray((width + step - 1) / step) { i -> i * step * W / width }
        var y = 0
        while (y < height) {
            val rowStart = (y * H / height) * W
            val offset = y * rowStride
            var i = 0
            var x = 0
            while (x < width) {
                val block = rowStart + column[i]
                sums[block] += (luma.get(offset + x * pixelStride).toInt() and 0xFF).toFloat()
                counts[block]++
                x += step
                i++
            }
            y += step
        }
        for (b in sums.indices) if (counts[b] > 0) sums[b] /= counts[b]
        return sums
    }

    /** The brightness [share] of the way up from the darkest block to the brightest, within [quad] if given. */
    fun level(blocks: FloatArray, quad: Quad?, share: Float): Float {
        val values = if (quad == null) blocks.copyOf() else {
            val inside = ArrayList<Float>()
            val point = FloatArray(2)
            for (j in 0 until 12) for (i in 0 until 12) {
                quad.at((i + 0.5f) / 12, (j + 0.5f) / 12, point)
                inside += sample(blocks, point[0], point[1])
            }
            inside.toFloatArray()
        }
        values.sort()
        return values[((values.size - 1) * share).toInt()]
    }

    fun sample(blocks: FloatArray, x: Float, y: Float): Float {
        val gx = (x * W - 0.5f).coerceIn(0f, W - 1f)
        val gy = (y * H - 0.5f).coerceIn(0f, H - 1f)
        val x0 = floor(gx).toInt().coerceAtMost(W - 2)
        val y0 = floor(gy).toInt().coerceAtMost(H - 2)
        val fx = gx - x0
        val fy = gy - y0
        val top = blocks[y0 * W + x0] * (1 - fx) + blocks[y0 * W + x0 + 1] * fx
        val bottom = blocks[(y0 + 1) * W + x0] * (1 - fx) + blocks[(y0 + 1) * W + x0 + 1] * fx
        return top * (1 - fy) + bottom * fy
    }
}

/**
 * Finds the TV's code in the camera's view and reads it. It keeps a few seconds of block frames and,
 * every few frames, tries where the picture might be: first a sweep of places and sizes, then small
 * moves of each corner, keeping whichever placement makes the code's cells flicker most clearly
 * against each other. Once found it follows the picture as the hand drifts.
 *
 * [flipped] is a camera mounted upside down: the TV's top left is then the frame's bottom right.
 */
class BeaconLocator(private val flipped: Boolean = false) {
    /**
     * [ready] is a code worth answering with: the picture found, every pair sure of itself, the check
     * agreeing, and the same code read twice running, a few frames apart.
     */
    class Result(val reading: BeaconDecoder.Reading, val quad: Quad, val found: Boolean, val fit: Float, val ready: Boolean)

    private val frames = ArrayDeque<FloatArray>()
    /** The best placement so far, kept through a [reset]. */
    var quad: Quad? = null
        private set
    private var score = 0f
    private var sinceUpdate = 0
    private var sinceSweep = SWEEP_EVERY
    private var lastCode: Int? = null

    val found: Boolean get() = quad != null && score >= FOUND

    /** Starts the reading over (after a misread, or new exposure), keeping where the picture was. */
    fun reset() {
        frames.clear()
        sinceUpdate = 0
        lastCode = null
    }

    fun add(blocks: FloatArray): Result? {
        require(blocks.size == Blocks.W * Blocks.H)
        frames.addLast(blocks)
        while (frames.size > BeaconDecoder.WINDOW + 1) frames.removeFirst()
        if (frames.size < BeaconDecoder.MIN_FRAMES || ++sinceUpdate < UPDATE_EVERY) return null
        sinceUpdate = 0
        val history = frames.toList()
        update(history)
        val best = quad ?: return null
        val reading = BeaconDecoder.decode(cellFrames(best, history, FULL_SAMPLES, history.size)) ?: return null
        val code = reading.code.takeIf { reading.accepted && found }
        val ready = code != null && code == lastCode
        lastCode = code
        return Result(reading, best, found, score, ready)
    }

    private fun update(history: List<FloatArray>) {
        val candidates = ArrayList<Pair<Quad, Float>>()
        quad?.let { candidates += it to strength(it, history, CLIMB_SAMPLES, CLIMB_FRAMES) }
        val holding = (candidates.firstOrNull()?.second ?: 0f) >= LOCKED
        // Sweeps now and then even while holding: a placement on part of the picture can look good
        // enough until the whole of it is tried.
        if (++sinceSweep >= if (holding) SWEEP_WHILE_HOLDING else SWEEP_EVERY) {
            sinceSweep = 0
            candidates += sweep(history)
        }
        var bestQuad: Quad? = null
        var bestScore = 0f
        for ((start, startScore) in candidates) {
            val (climbed, climbedScore) = climb(start, startScore, history, if (holding) HOLD_PASSES else CLIMB_PASSES)
            if (climbedScore > bestScore) {
                bestQuad = climbed
                bestScore = climbedScore
            }
        }
        quad = bestQuad
        score = bestScore
    }

    /** Every place and size the picture might be, on a cheap reading; the few best to work on. */
    private fun sweep(history: List<FloatArray>): List<Pair<Quad, Float>> =
        SWEEP.map { it to strength(it, history, 1, SWEEP_FRAMES) }
            .sortedByDescending { it.second }
            .take(SWEEP_KEEP)
            .map { (quad, _) -> quad to strength(quad, history, CLIMB_SAMPLES, CLIMB_FRAMES) }

    /** Small moves of the whole picture and of each corner, kept while they make the code clearer. */
    private fun climb(start: Quad, startScore: Float, history: List<FloatArray>, passes: Int): Pair<Quad, Float> {
        var best = start
        var bestScore = startScore
        for (pass in 0 until passes) {
            val d = best.width * STEPS[minOf(pass, STEPS.size - 1)]
            val moves = ArrayList<Quad>(22)
            moves += best.shifted(d, 0f)
            moves += best.shifted(-d, 0f)
            moves += best.shifted(0f, d)
            moves += best.shifted(0f, -d)
            moves += best.scaled(1 + d / best.width)
            moves += best.scaled(1 - d / best.width)
            for (i in 0 until 8) {
                moves += best.nudged(i, d)
                moves += best.nudged(i, -d)
            }
            for (move in moves) {
                if (!move.sensible) continue
                val moved = strength(move, history, CLIMB_SAMPLES, CLIMB_FRAMES)
                if (moved > bestScore) {
                    best = move
                    bestScore = moved
                }
            }
        }
        return best to bestScore
    }

    /** How clearly the code's pairs flicker against each other, read through [quad]; up to 1. */
    private fun strength(quad: Quad, history: List<FloatArray>, samples: Int, last: Int): Float {
        if (!quad.sensible) return 0f
        return BeaconDecoder.fit(cellFrames(quad, history, samples, last))
    }

    /**
     * The 36 cells' brightness in each of the last [last] frames, each cell from a [samples] by
     * [samples] spread over its middle half, so a placement a little off still reads the right cell.
     */
    private fun cellFrames(quad: Quad, history: List<FloatArray>, samples: Int, last: Int): List<FloatArray> {
        val cells = BeaconCode.COLS * BeaconCode.ROWS
        val taps = samples * samples * 4
        val index = IntArray(cells * taps)
        val weight = FloatArray(cells * taps)
        val point = FloatArray(2)
        for (cell in 0 until cells) {
            val col = cell % BeaconCode.COLS
            val row = cell / BeaconCode.COLS
            var tap = cell * taps
            for (j in 0 until samples) for (i in 0 until samples) {
                var u = (col + 0.25f + 0.5f * (i + 0.5f) / samples) / BeaconCode.COLS
                var v = (row + 0.25f + 0.5f * (j + 0.5f) / samples) / BeaconCode.ROWS
                if (flipped) {
                    u = 1 - u
                    v = 1 - v
                }
                quad.at(u, v, point)
                val gx = (point[0] * Blocks.W - 0.5f).coerceIn(0f, Blocks.W - 1.001f)
                val gy = (point[1] * Blocks.H - 0.5f).coerceIn(0f, Blocks.H - 1.001f)
                val x0 = gx.toInt()
                val y0 = gy.toInt()
                val fx = gx - x0
                val fy = gy - y0
                val share = 1f / (samples * samples)
                index[tap] = y0 * Blocks.W + x0; weight[tap++] = (1 - fx) * (1 - fy) * share
                index[tap] = y0 * Blocks.W + x0 + 1; weight[tap++] = fx * (1 - fy) * share
                index[tap] = (y0 + 1) * Blocks.W + x0; weight[tap++] = (1 - fx) * fy * share
                index[tap] = (y0 + 1) * Blocks.W + x0 + 1; weight[tap++] = fx * fy * share
            }
        }
        val from = maxOf(0, history.size - last)
        return (from until history.size).map { f ->
            val blocks = history[f]
            FloatArray(cells) { cell ->
                var sum = 0f
                for (tap in cell * taps until (cell + 1) * taps) sum += blocks[index[tap]] * weight[tap]
                sum
            }
        }
    }

    companion object {
        /** How well a placement fits at which the picture counts as found, and as held well enough to stop sweeping. */
        const val FOUND = 0.35f
        const val LOCKED = 0.45f
        private const val UPDATE_EVERY = 5
        private const val SWEEP_EVERY = 2
        private const val SWEEP_WHILE_HOLDING = 4
        private const val SWEEP_FRAMES = 40
        private const val SWEEP_KEEP = 4
        private const val CLIMB_FRAMES = 50
        private const val CLIMB_SAMPLES = 2
        private const val FULL_SAMPLES = 3
        private const val CLIMB_PASSES = 4
        private const val HOLD_PASSES = 1
        /** Each pass's move, as a share of the picture's width: coarse, then finer. */
        private val STEPS = floatArrayOf(0.03f, 0.015f, 0.008f, 0.004f)

        /**
         * Places and sizes to try, from the whole view down to a third of it. A 16:9 TV in the
         * camera's 16:9 frame takes the same share of its width and its height. Steps are small enough
         * that one of them lands within half a cell of the truth.
         */
        private val SWEEP: List<Quad> = buildList {
            var size = 0.98f
            while (size >= 0.3f) {
                val stepX = size / 10
                val stepY = size / 5
                var cy = size / 2
                while (cy <= 1 - size / 2 + 1e-4f) {
                    var cx = size / 2
                    while (cx <= 1 - size / 2 + 1e-4f) {
                        add(Quad.rect(cx, cy, size, size))
                        cx += stepX
                    }
                    cy += stepY
                }
                size *= 0.84f
            }
        }
    }
}

/** How often the camera takes a frame, picked so its frames land on both halves of the TV's flicker. */
object CameraTiming {
    /** The camera can't keep up much faster than this with a full frame to read. */
    private const val SHORTEST_NS = 33_000_000.0

    /**
     * The TV's code swaps every refresh, so it repeats every two. A frame every 1.2 of those cycles
     * (40 ms at 60 Hz, 48 ms at 50 Hz) moves a fifth of a cycle along each time, so a run of frames
     * sees every part of it. A frame every whole cycle would see the same half each time and nothing.
     */
    fun frameNs(refreshHz: Float): Long {
        val cycleNs = 2e9 / refreshHz.coerceAtLeast(1f)
        var cycles = 0
        while (cycleNs * (cycles + 0.2) < SHORTEST_NS) cycles++
        return (cycleNs * (cycles + 0.2)).toLong()
    }
}
