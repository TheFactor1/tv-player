package com.thefactor1.player.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpTest {

    @Test
    fun readsMethodPathHeadersAndBody() {
        val raw = "POST /api/pair HTTP/1.1\r\nHost: tv\r\nX-Token: abc\r\nContent-Length: 15\r\n\r\n{\"code\":\"4817\"}"
        val request = Http.read(raw.byteInputStream())!!
        assertEquals("POST", request.method)
        assertEquals("/api/pair", request.path)
        assertEquals("abc", request.headers["x-token"])
        assertEquals("{\"code\":\"4817\"}", request.body)
    }

    @Test
    fun aGetHasNoBody() {
        val request = Http.read("GET /api/state HTTP/1.1\nHost: tv\n\n".byteInputStream())!!
        assertEquals("", request.body)
    }

    @Test
    fun aConnectionThatClosesEarlyReadsAsNothing() {
        assertNull(Http.read("".byteInputStream()))
        assertNull(Http.read("GET / HTTP/1.1\r\nHost: tv\r\n".byteInputStream()))
    }

    @Test
    fun responseCarriesLengthAndStatus() {
        val text = String(Http.Response(404, "text/plain", "Not found".toByteArray()).bytes())
        assertTrue(text.startsWith("HTTP/1.1 404 Not Found\r\n"))
        assertTrue(text.contains("Content-Length: 9\r\n"))
        assertTrue(text.endsWith("\r\n\r\nNot found"))
    }
}
