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
        // A few seconds from the app is a stream it barely touched, not a place to come back to;
        // letting it win would start from the top while this player remembers more.
        appPositionMs >= MIN_RESUME_MS -> Start(appPositionMs, Source.APP)
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

    /**
     * Whether a position saved from a stream of [savedDurationMs] is close enough to one of
     * [actualDurationMs] to be the same film when nothing else ties them together. Tighter than
     * [sameLength], because the length is all there is to go on.
     */
    fun nearlySameLength(savedDurationMs: Long, actualDurationMs: Long): Boolean {
        if (savedDurationMs <= 0 || actualDurationMs <= 0) return false
        val tolerance = maxOf(actualDurationMs / 200, 10_000L)
        return abs(savedDurationMs - actualDurationMs) <= tolerance
    }

    /**
     * The last thing watched, picked up by a stream that shares none of its keys: a Usenet release
     * with an obfuscated file name, an app that sends no title. Only for a film left within
     * [LAST_WATCHED_WINDOW_MS] with a near-identical length; episodes of a show are too alike in
     * length to tell apart this way, so neither side may be one.
     */
    fun lastWatchedFits(
        last: ResumeStore.Entry?,
        lastWasEpisode: Boolean,
        nowIsEpisode: Boolean,
        actualDurationMs: Long,
        nowMs: Long,
    ): Boolean = last != null && !lastWasEpisode && !nowIsEpisode &&
        nowMs - last.savedAt in 0..LAST_WATCHED_WINDOW_MS &&
        last.positionMs >= MIN_RESUME_MS &&
        !isWatched(last.positionMs, last.durationMs) &&
        nearlySameLength(last.durationMs, actualDurationMs)

    const val LAST_WATCHED_WINDOW_MS = 48L * 60 * 60 * 1000

    enum class Leave { SAVE, FORGET, KEEP }

    /**
     * What to do with the saved position when the viewer leaves at [positionMs]. A stream that
     * failed to load or was left within seconds says nothing about the title, so whatever another
     * stream of it saved stays put.
     */
    fun onLeave(positionMs: Long, durationMs: Long, completed: Boolean): Leave = when {
        completed || isWatched(positionMs, durationMs) -> Leave.FORGET
        positionMs < MIN_RESUME_MS -> Leave.KEEP
        else -> Leave.SAVE
    }
}
