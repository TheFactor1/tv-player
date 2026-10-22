package com.thefactor1.player.companion

import com.thefactor1.player.companion.PairRequests.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairRequestsTest {
    private var clock = 0L
    private val requests = PairRequests { clock }

    @Test
    fun approvedPhoneGetsItsTokenOnce() {
        assertTrue(requests.open("a"))
        assertEquals(Status.PENDING, requests.check("a").status)
        requests.answer("a", approved = true, token = "t1")
        val answer = requests.check("a")
        assertEquals(Status.APPROVED, answer.status)
        assertEquals("t1", answer.token)
        assertEquals(Status.UNKNOWN, requests.check("a").status)
    }

    @Test
    fun deniedPhoneGetsNoToken() {
        requests.open("a")
        requests.answer("a", approved = false, token = "t1")
        val answer = requests.check("a")
        assertEquals(Status.DENIED, answer.status)
        assertNull(answer.token)
    }

    @Test
    fun oneRequestAtATime() {
        assertTrue(requests.open("a"))
        assertFalse(requests.open("b"))
        requests.answer("a", approved = false, token = null)
        assertTrue(requests.open("b"))
    }

    @Test
    fun unansweredRequestLapses() {
        requests.open("a")
        clock += PairRequests.TIMEOUT_MS + 1
        assertEquals(Status.EXPIRED, requests.check("a").status)
        requests.answer("a", approved = true, token = "late")
        assertEquals(Status.EXPIRED, requests.check("a").status)
        assertTrue(requests.open("b"))
    }
}
