package com.thefactor1.player.companion

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** Downloads a subtitle file as text: follows redirects (even http↔https), unpacks gzip, guesses the charset. */
object SubtitleFetch {
    private const val TIMEOUT_MS = 8_000
    private const val MAX_REDIRECTS = 4
    private const val MAX_BYTES = 4 * 1024 * 1024

    fun text(url: String): String? = try {
        bytes(url)?.let(::decode)
    } catch (e: Exception) {
        null
    }

    private fun bytes(start: String): ByteArray? {
        var url = start
        repeat(MAX_REDIRECTS + 1) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) TVPlayer")
                setRequestProperty("Accept", "*/*")
            }
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    url = URL(URL(url), connection.getHeaderField("Location") ?: return null).toString()
                    return@repeat
                }
                if (status != HttpURLConnection.HTTP_OK) return null
                return connection.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        if (out.size() > MAX_BYTES) return null
                    }
                    out.toByteArray()
                }
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    /** UTF-8 when it reads cleanly, else the Windows Latin set most older subtitle files use. */
    fun decode(raw: ByteArray): String {
        val data = if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
            GZIPInputStream(ByteArrayInputStream(raw)).use { it.readBytes() }
        } else {
            raw
        }
        val utf8 = String(data, Charsets.UTF_8)
        return if ('�' in utf8) String(data, charset("windows-1252")) else utf8
    }
}
