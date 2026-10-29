package com.thefactor1.player.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingGateTest {

    private var clock = 1_000_000L
    private fun gate(code: String = "482913") = PairingGate({ clock }) { code }

    @Test
    fun noCodeUntilTheCardIsUp() {
        val gate = gate()
        assertEquals(PairingGate.Verdict.CLOSED, gate.attempt("10.0.0.2", "482913"))
        gate.open()
        assertEquals(PairingGate.Verdict.OK, gate.attempt("10.0.0.2", "482913"))
        // Used once, then gone until the card is shown again.
        assertNull(gate.code)
        assertEquals(PairingGate.Verdict.CLOSED, gate.attempt("10.0.0.3", "482913"))
    }

    @Test
    fun eachWrongGuessDoublesTheWaitForThatAddress() {
        assertEquals(1_000L, PairingGate.delayAfter(1))
        assertEquals(2_000L, PairingGate.delayAfter(2))
        assertEquals(8_000L, PairingGate.delayAfter(4))
        assertEquals(300_000L, PairingGate.delayAfter(30))
        val gate = gate()
        gate.open()
        assertEquals(PairingGate.Verdict.WRONG, gate.attempt("10.0.0.2", "000000"))
        assertEquals(PairingGate.Verdict.WAIT, gate.attempt("10.0.0.2", "482913"))
        // Another address isn't held up.
        assertEquals(PairingGate.Verdict.WRONG, gate.attempt("10.0.0.9", "111111"))
        clock += 1_000
        assertEquals(PairingGate.Verdict.WRONG, gate.attempt("10.0.0.2", "000001"))
        clock += 1_999
        assertEquals(PairingGate.Verdict.WAIT, gate.attempt("10.0.0.2", "482913"))
        clock += 1
        assertEquals(PairingGate.Verdict.OK, gate.attempt("10.0.0.2", "482913"))
    }

    @Test
    fun tenWrongGuessesInAllClosePairing() {
        val gate = gate()
        gate.open()
        repeat(PairingGate.MAX_WRONG) { assertEquals(PairingGate.Verdict.WRONG, gate.attempt("10.0.1.$it", "000000")) }
        assertNull(gate.code)
        assertEquals(PairingGate.Verdict.CLOSED, gate.attempt("10.0.0.2", "482913"))
        // Opening the card again starts over.
        gate.open()
        assertEquals(PairingGate.Verdict.OK, gate.attempt("10.0.0.2", "482913"))
    }

    @Test
    fun aTurnedDownPhoneWaitsAMinute() {
        val gate = gate()
        assertTrue(gate.mayAsk("10.0.0.2"))
        gate.refused("10.0.0.2")
        assertFalse(gate.mayAsk("10.0.0.2"))
        assertTrue(gate.mayAsk("10.0.0.3"))
        clock += PairingGate.HELLO_COOLDOWN_MS
        assertTrue(gate.mayAsk("10.0.0.2"))
    }

    @Test
    fun tokensKeepTheirIssueTimeAndTheOldestGoFirst() {
        var stored = setOf("legacy")
        stored = PhoneTokens.add(stored, "aa", 300, 2)
        stored = PhoneTokens.add(stored, "bb", 100, 2)
        // The token from before issue times were kept counts as oldest and goes first.
        assertEquals(setOf("bb|100", "aa|300"), stored)
        stored = PhoneTokens.add(stored, "cc", 400, 2)
        assertEquals(setOf("aa|300", "cc|400"), stored)
        assertTrue(PhoneTokens.contains(stored, "cc"))
        assertFalse(PhoneTokens.contains(stored, "bb"))
        assertFalse(PhoneTokens.contains(stored, ""))
        assertTrue(PhoneTokens.contains(setOf("legacy"), "legacy"))
    }
}
