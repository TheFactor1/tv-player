package com.thefactor1.player.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamBufferTest {
    private val mb = 1024L * 1024

    @Test
    fun halfOfTheHeapBetweenFloorAndCeiling() {
        assertEquals((256 * mb).toInt(), StreamBuffer.targetBytes(512 * mb))
        assertEquals((192 * mb).toInt(), StreamBuffer.targetBytes(384 * mb))
    }

    @Test
    fun smallHeapKeepsMedia3sBudget() {
        assertEquals(StreamBuffer.FLOOR_BYTES, StreamBuffer.targetBytes(192 * mb))
    }

    @Test
    fun hugeHeapIsCapped() {
        assertEquals(StreamBuffer.CEILING_BYTES, StreamBuffer.targetBytes(4096 * mb))
    }
}
