package com.thefactor1.player.companion

import kotlin.math.abs

/**
 * Lines up a downloaded subtitle file with the video. The file is often from another release, so it
 * can start early or late, or drift (a 25 fps file against a 23.976 fps film). Each line the TV shows
 * is matched to the same words in the file, and the matches give a mapping from file time to video
 * time: an offset at first, and a stretch once matches span long enough to see drift.
 */
class TranscriptSync {
    private class Match(val fileMs: Long, val videoMs: Long)

    private val matches = ArrayList<Match>()

    /** videoMs = fileMs * scale + offsetMs. */
    var scale = 1.0
        private set
    var offsetMs = 0.0
        private set

    fun reset() {
        matches.clear()
        scale = 1.0
        offsetMs = 0.0
    }

    fun map(fileMs: Long): Long = (fileMs * scale + offsetMs).toLong().coerceAtLeast(0)

    /**
     * A line the TV showed at [videoMs]. Returns true when the mapping moved enough that the phone
     * should be sent the transcript again.
     */
    fun observe(videoMs: Long, text: String, cues: List<Cue>): Boolean {
        val key = normalise(text)
        if (key.length < MIN_MATCH_CHARS) return false
        // Of the lines with the same words, the one the current mapping puts nearest is the one on screen.
        // The TV's subtitles may split or join lines differently, so a long enough part of a line also counts.
        val same = cues.filter {
            val line = normalise(it.text)
            line == key || (minOf(line.length, key.length) >= MIN_PART_CHARS && (line.contains(key) || key.contains(line)))
        }
        // The first match sets the offset alone, so it has to be a line that appears only once.
        if (matches.isEmpty() && same.size != 1) return false
        val cue = same.minByOrNull { abs(map(it.startMs) - videoMs) } ?: return false
        // A real match agrees with the nearest earlier one, give or take what drift could add in between.
        matches.minByOrNull { abs(it.fileMs - cue.startMs) }?.let { near ->
            val allowed = MAX_JUMP_MS + (MAX_SCALE - 1) * abs(near.fileMs - cue.startMs)
            if (abs((videoMs - cue.startMs) - (near.videoMs - near.fileMs)) > allowed) return false
        }
        matches.removeAll { abs(it.fileMs - cue.startMs) < SAME_LINE_MS }
        matches += Match(cue.startMs, videoMs)
        if (matches.size > MAX_MATCHES) matches.removeAt(0)
        return refit()
    }

    private fun refit(): Boolean {
        val before = Pair(map(0), map(LONG_FILM_MS))
        val span = matches.maxOf { it.fileMs } - matches.minOf { it.fileMs }
        if (matches.size >= MIN_FIT_MATCHES && span >= MIN_FIT_SPAN_MS) {
            val meanFile = matches.map { it.fileMs.toDouble() }.average()
            val meanVideo = matches.map { it.videoMs.toDouble() }.average()
            var top = 0.0
            var bottom = 0.0
            matches.forEach {
                top += (it.fileMs - meanFile) * (it.videoMs - meanVideo)
                bottom += (it.fileMs - meanFile) * (it.fileMs - meanFile)
            }
            val fitted = top / bottom
            scale = if (fitted in MIN_SCALE..MAX_SCALE) fitted else 1.0
            offsetMs = meanVideo - scale * meanFile
        } else {
            scale = 1.0
            offsetMs = matches.map { (it.videoMs - it.fileMs).toDouble() }.sorted().let { it[it.size / 2] }
        }
        val after = Pair(map(0), map(LONG_FILM_MS))
        return abs(after.first - before.first) > RESEND_MS || abs(after.second - before.second) > RESEND_MS
    }

    private companion object {
        const val MIN_MATCH_CHARS = 8
        const val MIN_PART_CHARS = 16
        const val MAX_JUMP_MS = 60_000L
        const val SAME_LINE_MS = 500L
        const val MAX_MATCHES = 40
        const val MIN_FIT_MATCHES = 4
        const val MIN_FIT_SPAN_MS = 10 * 60_000L
        // 25 fps against 23.976 is 4.3%; nothing real is further out than that.
        const val MIN_SCALE = 0.94
        const val MAX_SCALE = 1.06
        const val LONG_FILM_MS = 3 * 60 * 60_000L
        const val RESEND_MS = 250L

        fun normalise(text: String) = text.lowercase().filter { it.isLetterOrDigit() }
    }
}
