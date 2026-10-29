package com.thefactor1.player.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CspTest {

    @Test
    fun theScriptIsHashedAsTheBrowserSeesIt() {
        val page = "<html>\r\n<script>\r\nconst a = 1;\r\n</script>\r\n</html>"
        assertEquals(listOf("\nconst a = 1;\n"), Csp.scripts(page))
        // sha256 of "\nconst a = 1;\n", base64.
        val policy = Csp.forPage(page)
        assertTrue(policy, policy.contains("script-src 'sha256-${Csp.hash("\nconst a = 1;\n")}';"))
        assertTrue(policy.startsWith("default-src 'none';"))
        assertTrue(policy.contains("img-src https://image.tmdb.org;"))
        assertTrue(policy.endsWith("frame-ancestors 'none'"))
    }

    @Test
    fun knownHash() {
        // printf 'alert(1)' | openssl dgst -sha256 -binary | base64
        assertEquals("bhHHL3z2vDgxUt0W3dWQOrprscmda2Y5pLsLg4GF+pI=", Csp.hash("alert(1)"))
    }
}
