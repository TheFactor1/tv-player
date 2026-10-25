package com.thefactor1.player.ui

import android.view.animation.PathInterpolator

/**
 * One set of curves and timings for every moving thing in the player. Things arrive on a long
 * settling curve and leave about a third quicker on an easy-start one; nothing travels more than
 * 10 dp, grows or shrinks more than 3%, or bounces.
 */
object Motion {
    /** Arrives fast and settles long. */
    val enter = PathInterpolator(0.16f, 1f, 0.3f, 1f)
    /** Leaves with an easy start and no drift at the end. */
    val exit = PathInterpolator(0.4f, 0f, 1f, 1f)

    const val ENTER_MS = 320L
    const val EXIT_MS = 180L
    /** Focus moving between buttons or rows. */
    const val FOCUS_MS = 160L
    /** Between parts of one thing arriving together (the bar, then the buttons, then the title). */
    const val STAGGER_MS = 40L
    /** How far things rise as they arrive, in dp. */
    const val RISE_DP = 10f
}
