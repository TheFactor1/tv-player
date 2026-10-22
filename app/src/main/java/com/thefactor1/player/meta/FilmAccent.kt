package com.thefactor1.player.meta

import kotlin.math.max
import kotlin.math.min

/**
 * The one colour a film lends the player: its backdrop's dominant hue, toned down so it reads on a
 * projector. A backdrop with almost no colour in it (a black-and-white film) lends none, and the
 * player stays white.
 */
object FilmAccent {

    /** Takes ARGB pixels from a small copy of the backdrop; returns an ARGB colour or null. */
    fun pick(pixels: IntArray): Int? {
        if (pixels.isEmpty()) return null
        val weights = DoubleArray(BINS)
        val sines = DoubleArray(BINS)
        val cosines = DoubleArray(BINS)
        val hsv = FloatArray(3)
        var colourful = 0
        for (pixel in pixels) {
            toHsv(pixel, hsv)
            val hue = hsv[0]
            val saturation = hsv[1]
            val value = hsv[2]
            if (saturation < MIN_SATURATION || value < MIN_VALUE) continue
            colourful++
            val bin = (hue / 360f * BINS).toInt().coerceIn(0, BINS - 1)
            val weight = (saturation * value).toDouble()
            weights[bin] += weight
            val radians = Math.toRadians(hue.toDouble())
            sines[bin] += kotlin.math.sin(radians) * weight
            cosines[bin] += kotlin.math.cos(radians) * weight
        }
        if (colourful < pixels.size * MIN_COLOURFUL_SHARE) return null
        // Neighbouring bins count together, so a hue that straddles a bin edge isn't split in two.
        val best = (0 until BINS).maxBy { weights[it] + 0.5 * (weights[(it + 1) % BINS] + weights[(it + BINS - 1) % BINS]) }
        val hue = (Math.toDegrees(kotlin.math.atan2(sines[best], cosines[best])) + 360) % 360
        return fromHsv(hue.toFloat(), SATURATION, VALUE)
    }

    private fun toHsv(argb: Int, out: FloatArray) {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        val delta = hi - lo
        out[0] = when {
            delta == 0f -> 0f
            hi == r -> 60f * (((g - b) / delta).mod(6f))
            hi == g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }
        out[1] = if (hi == 0f) 0f else delta / hi
        out[2] = hi
    }

    private fun fromHsv(hue: Float, saturation: Float, value: Float): Int {
        val c = value * saturation
        val x = c * (1 - kotlin.math.abs((hue / 60f).mod(2f) - 1))
        val m = value - c
        val (r, g, b) = when ((hue / 60f).toInt().coerceIn(0, 5)) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun channel(v: Float) = ((v + m) * 255 + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }

    private const val BINS = 24
    private const val MIN_SATURATION = 0.22f
    private const val MIN_VALUE = 0.18f
    /** Under this share of colourful pixels, the backdrop counts as black and white. */
    private const val MIN_COLOURFUL_SHARE = 0.06

    // Soft and light: the BenQ turns strong saturated colours (the old gold) muddy.
    private const val SATURATION = 0.42f
    private const val VALUE = 0.86f
}
