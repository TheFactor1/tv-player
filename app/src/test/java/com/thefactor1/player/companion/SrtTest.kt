package com.thefactor1.player.companion

import org.junit.Assert.assertEquals
import org.junit.Test

class SrtTest {

    @Test
    fun readsCuesWithTimesAndText() {
        val cues = Srt.parse(
            """
            1
            00:00:01,500 --> 00:00:03,000
            Why so serious?

            2
            01:02:03,040 --> 01:02:05,900
            You complete me.
            """.trimIndent(),
        )
        assertEquals(listOf(Cue(1_500, 3_000, "Why so serious?"), Cue(3_723_040, 3_725_900, "You complete me.")), cues)
    }

    @Test
    fun joinsLinesAndStripsTags() {
        val cues = Srt.parse("﻿1\r\n00:00:01,000 --> 00:00:02,000\r\n{\\an8}<i>Two</i>\r\nlines\r\n")
        assertEquals("Two lines", cues.single().text)
    }

    @Test
    fun acceptsDotsAndShortFractions() {
        val cues = Srt.parse("00:00:01.5 --> 00:00:02.25\nHi\n")
        assertEquals(Cue(1_500, 2_250, "Hi"), cues.single())
    }

    @Test
    fun skipsEmptyCuesAndSortsByStart() {
        val cues = Srt.parse(
            """
            00:00:05,000 --> 00:00:06,000
            Later

            00:00:03,000 --> 00:00:04,000
            <i></i>

            00:00:01,000 --> 00:00:02,000
            Earlier
            """.trimIndent(),
        )
        assertEquals(listOf("Earlier", "Later"), cues.map { it.text })
    }

    @Test
    fun readsWebVttWithoutHours() {
        val cues = Srt.parse("WEBVTT\n\n00:01.500 --> 00:03.000 line:90%\nHello there\n\n01:02:03.000 --> 01:02:04.000\nLater\n")
        assertEquals(listOf(Cue(1_500, 3_000, "Hello there"), Cue(3_723_000, 3_724_000, "Later")), cues)
    }
}
