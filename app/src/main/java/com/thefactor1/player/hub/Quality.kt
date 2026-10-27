package com.thefactor1.player.hub

import android.os.SystemClock
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The Quality setting: one of the hub's presets, or Auto, which measures this box's link and asks
 * the hub for streams under 70 % of it. The measurement lasts half an hour, or until a playback
 * stall says the link has changed.
 */
object Quality {
    const val AUTO = "auto"
    /** The setting's choices, in menu order; all but Auto are the hub's preset names. */
    val CHOICES = listOf("slow", "standard", "fast", "max", AUTO)
    /** What Auto falls back to when it can't measure. */
    const val FALLBACK = "standard"
    /** Streams are picked under this share of the measured speed, leaving room for the link to dip. */
    const val HEADROOM = 0.7

    private val NAMES = mapOf("slow" to "Slow internet", "standard" to "Standard", "fast" to "Fast", "max" to "Max", AUTO to "Auto")
    private const val MEASURE_TTL_MS = 30L * 60 * 1000

    class Cap(val maxMbps: Double?, val maxRes: String?)

    /** The hub's effective presets (GET /presets), once Home has fetched them. */
    @Volatile var caps: Map<String, Cap> = emptyMap()

    @Volatile private var measured: Pair<Double, Long>? = null

    fun measuredMbps(): Double? = measured?.takeIf { SystemClock.elapsedRealtime() - it.second < MEASURE_TTL_MS }?.first

    fun remember(mbps: Double) {
        measured = mbps to SystemClock.elapsedRealtime()
    }

    /** A stall in playback: measure again before the next film. */
    fun forget() {
        measured = null
    }

    /** "Standard · up to 20 Mbps", "Max · no cap", "Auto · measured 62 Mbps". */
    fun label(choice: String): String {
        val name = NAMES[choice] ?: choice
        if (choice == AUTO) return measuredMbps()?.let { "$name · measured ${it.roundToInt()} Mbps" } ?: name
        val cap = caps[choice] ?: return name
        val mbps = cap.maxMbps?.let { "up to ${it.roundToInt()} Mbps" }
        return listOfNotNull(name, mbps ?: if (cap.maxRes == null) "no cap" else null, cap.maxRes).joinToString(" · ")
    }

    /** The `/play` query for [choice], given Auto's measurement (null when it failed). */
    fun query(choice: String, measuredMbps: Double?): String = when {
        choice != AUTO -> "preset=$choice"
        measuredMbps == null -> "preset=$FALLBACK"
        else -> "max_mbps=" + String.format(Locale.US, "%.1f", measuredMbps * HEADROOM)
    }
}
