package com.thefactor1.player.meta

/** A stretch of an episode or film a viewer may want to skip, from the community skip-times database. */
data class SkipSegment(val kind: Kind, val startMs: Long, val endMs: Long?) {
    enum class Kind { RECAP, INTRO, CREDITS }
}

/** Which skippable segment is playing, and which timings are trustworthy enough to offer. */
object SkipSegments {

    /** The segment playing at [positionMs]; credits with no end run to [durationMs]. */
    fun at(segments: List<SkipSegment>, positionMs: Long, durationMs: Long): SkipSegment? =
        segments.firstOrNull { positionMs >= it.startMs && positionMs < endOf(it, durationMs) - END_MARGIN_MS }

    fun endOf(segment: SkipSegment, durationMs: Long): Long =
        segment.endMs ?: durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE

    /**
     * Drops timings that can't be right for this video: ends before starts, too short to be worth a
     * button, or starting after the video ends (a different cut of the episode).
     */
    fun usable(segments: List<SkipSegment>, durationMs: Long): List<SkipSegment> = segments.filter { segment ->
        val end = segment.endMs
        segment.startMs >= 0 &&
            (end == null || end - segment.startMs >= MIN_LENGTH_MS) &&
            (durationMs <= 0 || segment.startMs < durationMs)
    }.sortedBy { it.startMs }

    /** The button goes away this close to the segment's end; skipping a second would only stutter. */
    const val END_MARGIN_MS = 1_500L
    const val MIN_LENGTH_MS = 5_000L
}
