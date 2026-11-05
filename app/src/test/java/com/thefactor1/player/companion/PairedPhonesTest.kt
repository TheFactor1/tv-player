package com.thefactor1.player.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairedPhonesTest {
    @Test
    fun roundTripsNamesAndTimes() {
        val phones = listOf(PairedPhone("a1", "Matt's Pixel", 10L), PairedPhone("b2", "Tab\tbed", 20L))
        val back = PairedPhones.parse(PairedPhones.format(phones))
        assertEquals(listOf("a1", "b2"), back.map { it.token })
        assertEquals("Matt's Pixel", back[0].name)
        assertEquals("Tab bed", back[1].name)
        assertEquals(20L, back[1].since)
    }

    @Test
    fun keepsTheNewestEight() {
        var phones = emptyList<PairedPhone>()
        for (i in 1..10) phones = PairedPhones.add(phones, PairedPhone("t$i", "P$i", i.toLong()))
        assertEquals(8, phones.size)
        assertEquals("t3", phones.first().token)
        assertEquals("t10", phones.last().token)
    }

    @Test
    fun oldTokensBecomeUnnamedPhones() {
        val phones = PairedPhones.fromTokens(setOf("x", ""))
        assertEquals(listOf(PairedPhone("x", PairedPhones.UNNAMED, 0L)), phones)
        assertEquals(emptyList<PairedPhone>(), PairedPhones.parse(""))
    }

    @Test
    fun threeWrongCodesLockForHalfAMinute() {
        var now = 0L
        val guard = BeaconGuard { now }
        assertTrue(guard.canStart())
        assertFalse(guard.miss())
        assertFalse(guard.miss())
        assertTrue(guard.miss())
        assertFalse(guard.canStart())
        now += BeaconGuard.LOCK_MS
        assertTrue(guard.canStart())
    }
}
