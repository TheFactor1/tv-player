package com.thefactor1.player.meta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastLoaderTest {

    @Test
    fun readsTheHubsCastAndKeepsOnlyTmdbPhotos() {
        val cast = CastLoader.members("""{"cast":[
            {"name":"Rebecca Ferguson","character":"Jessica","photo":"https://image.tmdb.org/t/p/w185/abc.jpg"},
            {"name":"Javier Bardem","character":null,"photo":null},
            {"name":"Somebody","character":"X","photo":"https://evil.example/x.jpg"},
            {"name":"","character":"No one"}]}""")
        assertEquals(listOf("Rebecca Ferguson", "Javier Bardem", "Somebody"), cast.map { it.name })
        assertEquals("https://image.tmdb.org/t/p/w185/abc.jpg", cast[0].photoUrl)
        assertNull(cast[1].character)
        assertNull(cast[1].photoUrl)
        assertNull(cast[2].photoUrl)
    }

    @Test
    fun aHubWithoutAKeyHasNoOne() {
        assertTrue(CastLoader.members("""{"cast":[]}""").isEmpty())
        assertTrue(CastLoader.members("""{}""").isEmpty())
    }
}
