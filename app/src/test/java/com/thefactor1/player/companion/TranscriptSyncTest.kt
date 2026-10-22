package com.thefactor1.player.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TranscriptSyncTest {

    // One distinct line every 20 s for two hours.
    private val file = (0 until 360).map { Cue(it * 20_000L, it * 20_000L + 2_000, "Line number $it is spoken here") }

    @Test
    fun learnsAnOffsetFromOneLine() {
        val sync = TranscriptSync()
        sync.observe(videoMs = 63_500, text = "Line number 3 is spoken here", cues = file)
        assertEquals(63_500L, sync.map(60_000))
        assertEquals(103_500L, sync.map(100_000))
    }

    @Test
    fun learnsDriftFromAFileMadeForAnotherFrameRate() {
        val sync = TranscriptSync()
        val ratio = 25.0 / 23.976
        listOf(10, 40, 90, 150, 200).forEach { i ->
            val fileMs = i * 20_000L
            sync.observe((fileMs * ratio + 2_000).toLong(), "Line number $i is spoken here", file)
        }
        assertEquals(ratio, sync.scale, 0.001)
        assertEquals((6_000_000 * ratio + 2_000).toLong().toDouble(), sync.map(6_000_000).toDouble(), 50.0)
    }

    @Test
    fun ignoresShortAndAmbiguousLines() {
        val sync = TranscriptSync()
        val cues = listOf(Cue(1_000, 2_000, "Yes."), Cue(5_000, 6_000, "I don't know, sir"), Cue(50_000, 51_000, "I don't know, sir"))
        assertFalse(sync.observe(9_000, "Yes.", cues))
        assertFalse(sync.observe(9_000, "I don't know, sir", cues))
        assertEquals(1_000L, sync.map(1_000))
    }

    @Test
    fun matchesPartOfALineSplitDifferently() {
        val sync = TranscriptSync()
        val cues = listOf(Cue(10_000, 14_000, "Never start with the head. The victim gets all fuzzy."))
        sync.observe(12_000, "The victim gets all fuzzy.", cues)
        assertEquals(12_000L, sync.map(10_000))
    }

    @Test
    fun rejectsAMatchFarFromTheMapping() {
        val sync = TranscriptSync()
        sync.observe(63_000, "Line number 3 is spoken here", file)
        assertFalse(sync.observe(1_000_000, "Line number 4 is spoken here", file))
        assertEquals(63_000L, sync.map(60_000))
    }
}
