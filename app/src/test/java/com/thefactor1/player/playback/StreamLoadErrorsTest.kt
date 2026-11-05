package com.thefactor1.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamLoadErrorsTest {

    @Test
    fun waitsGrowThenLevelOff() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 10_000L, 10_000L), (1..6).map { StreamLoadErrors.delayMs(it) })
        assertEquals(10_000L, StreamLoadErrors.delayMs(40))
    }

    @Test
    fun busyServersAreRetriedMissingLinksAreNot() {
        listOf(408, 429, 500, 502, 503, 504).forEach { assertTrue("$it", StreamLoadErrors.isTransient(it)) }
        listOf(0, 400, 401, 403, 404, 410, 416).forEach { assertFalse("$it", StreamLoadErrors.isTransient(it)) }
    }
}
