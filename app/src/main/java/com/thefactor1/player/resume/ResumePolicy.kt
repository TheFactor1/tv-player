package com.thefactor1.player.resume

import kotlin.math.abs

/** Where playback starts, and when a position is worth keeping. Pure, so it is unit tested. */
object ResumePolicy {
    /** Positions before this are a false start, not something to come back to. */
    const val MIN_RESUME_MS = 30_000L

    /** Credits: this close to the end counts as watched. */
    const val WATCHED_FRACTION = 0.92
    const val WATCHED_REMAINING_MS = 60_000L

    enum class Source { APP, LOCAL, NONE }

    data class Start(val positionMs: Long, val source: Source)

    /**
     * The source app's position wins, because its Continue Watching is what the viewer picked
     * from. Without one, this player's own memory of the title takes over.
     */
    fun start(appPositionMs: Long, fromStart: Boolean, saved: ResumeStore.Entry?): Start = when {
        fromStart -> Start(0L, Source.NONE)
        appPositionMs > 0 -> Start(appPositionMs, Source.APP)
        saved != null && saved.positionMs >= MIN_RESUME_MS && !isWatched(saved.positionMs, saved.durationMs) ->
            Start(saved.positionMs, Source.LOCAL)
        else -> Start(0L, Source.NONE)
    }

    fun isWatched(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && (positionMs >= durationMs * WATCHED_FRACTION || durationMs - positionMs <= WATCHED_REMAINING_MS)

    /**
     * Whether a position saved from a stream of [savedDurationMs] fits one of [actualDurationMs].
     * Releases of the same thing differ by seconds; a different episode or cut usually by more.
     * An unknown length on either side gives the benefit of the doubt.
     */
    fun sameLength(savedDurationMs: Long, actualDurationMs: Long): Boolean {
        if (savedDurationMs <= 0 || actualDurationMs <= 0) return true
        val tolerance = maxOf(actualDurationMs / 20, 60_000L)
        return abs(savedDurationMs - actualDurationMs) <= tolerance
    }

    /** What to do with the saved position when the viewer leaves at [positionMs]. */
    fun shouldForget(positionMs: Long, durationMs: Long, completed: Boolean): Boolean =
        completed || positionMs < MIN_RESUME_MS || isWatched(positionMs, durationMs)
}
