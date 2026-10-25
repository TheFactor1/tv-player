package com.thefactor1.player.companion

import org.junit.Assert.assertEquals
import org.junit.Test

class BeaconPatternTest {
    /** The phone's BeaconDecoderTest pins the same layout; the two must agree. */
    @Test
    fun layoutMatchesThePhone() {
        val cells = BeaconPattern.cells(1234).joinToString("") { if (it) "1" else "0" }
        assertEquals("101100101010100110101011001001011010", cells)
    }

    @Test
    fun everyFrameIsBalanced() {
        for (code in listOf(0, 1, 2048, 4095, 1234)) {
            assertEquals(18, BeaconPattern.cells(code).count { it })
        }
    }
}
