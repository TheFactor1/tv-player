package com.thefactor1.player.playback

import kotlin.math.abs
import kotlin.math.roundToInt

/** A display mode reduced to what matching needs, so the choice can be unit tested. */
data class ModeInfo(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

/**
 * Picks the display mode whose refresh rate is an exact multiple of the video's frame rate,
 * so 23.976 fps plays at 23.976 Hz (or 47.952) instead of judder-prone 60 Hz.
 */
object DisplayModeMatcher {

    /** How far a mode's rate, divided by the multiple, may sit from the frame rate. Tells 23.976 from 24. */
    private const val TOLERANCE_HZ = 0.01f

    /**
     * Returns the mode to switch to, or null to stay put: when the frame rate is unknown,
     * no mode is an exact multiple, or the current mode is already the best one.
     * Resolution is kept as it is; only the refresh rate changes.
     */
    fun choose(modes: List<ModeInfo>, current: ModeInfo, frameRate: Float): ModeInfo? {
        if (frameRate <= 0f || frameRate.isNaN()) return null
        val best = modes
            .filter { it.width == current.width && it.height == current.height }
            .mapNotNull { mode -> multipleOf(mode.refreshRate, frameRate)?.let { mode to it } }
            .minByOrNull { (_, multiple) -> multiple }
            ?.first
            ?: return null
        val currentMultiple = multipleOf(current.refreshRate, frameRate)
        val bestMultiple = multipleOf(best.refreshRate, frameRate)
        return if (currentMultiple != null && currentMultiple <= bestMultiple!!) null else best
    }

    private fun multipleOf(refreshRate: Float, frameRate: Float): Int? {
        val multiple = (refreshRate / frameRate).roundToInt()
        if (multiple < 1) return null
        return if (abs(refreshRate / multiple - frameRate) <= TOLERANCE_HZ) multiple else null
    }
}
