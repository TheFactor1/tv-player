package com.thefactor1.player.companion

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Just enough HTTP/1.1 for one page and a JSON API: one request per connection, small bodies. */
object Http {
    /** [remote] is the phone's address, for per-phone limits. */
    class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String, val remote: String = "")

    class Response(private val status: Int, private val type: String, private val body: ByteArray, private val extra: Map<String, String> = emptyMap()) {
        fun bytes(): ByteArray {
            val reason = when (status) {
                200 -> "OK"
                400 -> "Bad Request"
                401 -> "Unauthorized"
                403 -> "Forbidden"
                404 -> "Not Found"
                405 -> "Method Not Allowed"
                429 -> "Too Many Requests"
                else -> "Service Unavailable"
            }
            val head = "HTTP/1.1 $status $reason\r\n" +
                "Content-Type: $type\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                // On every answer: no type sniffing, no framing, no referrer leaking the TV's address.
                "X-Content-Type-Options: nosniff\r\n" +
                "X-Frame-Options: DENY\r\n" +
                "Referrer-Policy: no-referrer\r\n" +
                extra.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } +
                "Connection: close\r\n\r\n"
            return head.toByteArray() + body
        }
    }

    fun read(input: InputStream, remote: String = ""): Request? {
        val requestLine = line(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) {
            val header = line(input) ?: return null
            if (header.isEmpty()) break
            val colon = header.indexOf(':')
            if (colon > 0) headers[header.substring(0, colon).trim().lowercase()] = header.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull()?.coerceIn(0, MAX_BODY) ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        return Request(parts[0], parts[1], headers, String(body, 0, read), remote)
    }

    private fun line(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("UTF-8")
            if (b == '\n'.code) return out.toString("UTF-8").trimEnd('\r')
            if (out.size() > MAX_LINE) return null
            out.write(b)
        }
    }

    private const val MAX_BODY = 4_096
    private const val MAX_LINE = 8_192
}
