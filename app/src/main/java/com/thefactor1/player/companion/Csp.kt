package com.thefactor1.player.companion

import java.security.MessageDigest
import kotlin.io.encoding.Base64

/**
 * The companion page's Content-Security-Policy: nothing loads or runs but the page's own inline
 * script (pinned by its hash, so an injected one won't run), its own styles, TMDB's photos, and
 * calls back to the TV.
 */
object Csp {
    fun forPage(html: String): String {
        val hashes = scripts(html).joinToString(" ") { "'sha256-${hash(it)}'" }
        return "default-src 'none'; script-src $hashes; style-src 'unsafe-inline'; img-src https://image.tmdb.org; " +
            "connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
    }

    /** The body of each inline `<script>`, as the browser hashes it: line ends already folded to LF. */
    fun scripts(html: String): List<String> {
        val text = html.replace("\r\n", "\n")
        return Regex("""<script>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).findAll(text).map { it.groupValues[1] }.toList()
    }

    fun hash(script: String): String =
        // Kotlin's own Base64: java.util.Base64 needs Android 8, and this app runs from Android 7.
        Base64.Default.encode(MessageDigest.getInstance("SHA-256").digest(script.toByteArray(Charsets.UTF_8)))
}
