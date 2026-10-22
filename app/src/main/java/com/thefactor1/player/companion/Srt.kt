package com.thefactor1.player.companion

/** One subtitle line for the phone's transcript, in ms from the start of the video. */
data class Cue(val startMs: Long, val endMs: Long, val text: String)

/** Reads an SRT (or WebVTT) file into plain-text cues: tags and positioning codes stripped, lines joined. */
object Srt {
    private val TIMING = Regex("""(?:(\d{1,2}):)?(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(?:(\d{1,2}):)?(\d{2}):(\d{2})[,.](\d{1,3})""")
    private val TAGS = Regex("""<[^>]*>|\{\\[^}]*\}""")
    private val SPACES = Regex("""\s+""")

    fun parse(text: String): List<Cue> {
        val cues = ArrayList<Cue>()
        val lines = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var i = 0
        while (i < lines.size) {
            val timing = TIMING.find(lines[i])
            if (timing == null) {
                i++
                continue
            }
            val g = timing.groupValues
            val start = millis(g[1], g[2], g[3], g[4])
            val end = millis(g[5], g[6], g[7], g[8])
            i++
            val body = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank() && TIMING.find(lines[i]) == null) {
                if (body.isNotEmpty()) body.append(' ')
                body.append(lines[i])
                i++
            }
            val clean = body.toString().replace(TAGS, "").replace(SPACES, " ").trim()
            if (clean.isNotEmpty() && end >= start) cues += Cue(start, end, clean)
        }
        return cues.sortedBy { it.startMs }
    }

    private fun millis(h: String, m: String, s: String, frac: String): Long {
        val ms = frac.padEnd(3, '0').toLong()
        return (((h.toLongOrNull() ?: 0) * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + ms
    }
}
