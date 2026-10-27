package com.thefactor1.player.phone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReachTest {
    @Test
    fun sameHomeNetwork() {
        assertTrue(Reach.sameNetwork("192.168.50.14", "192.168.50.2", 24))
        assertFalse(Reach.sameNetwork("192.168.1.14", "192.168.50.2", 24))
        // A wider home network, as mesh systems often hand out.
        assertTrue(Reach.sameNetwork("192.168.51.9", "192.168.50.2", 23))
    }

    @Test
    fun unreadableAddressesAreGivenTheBenefitOfTheDoubt() {
        assertTrue(Reach.sameNetwork("tv.local", "192.168.50.2", 24))
        assertTrue(Reach.sameNetwork("192.168.50.2", "300.1.1.1", 24))
    }
}
