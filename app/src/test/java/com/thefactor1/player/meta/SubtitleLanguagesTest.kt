package com.thefactor1.player.meta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleLanguagesTest {

    @Test
    fun readsTheCodesDatabasesUse() {
        assertEquals("en", SubtitleLanguages.twoLetter("eng"))
        assertEquals("fr", SubtitleLanguages.twoLetter("fre"))
        assertEquals("de", SubtitleLanguages.twoLetter("deu"))
        assertEquals("pt", SubtitleLanguages.twoLetter("pob"))
        assertEquals("pt", SubtitleLanguages.twoLetter("pt-BR"))
        assertNull(SubtitleLanguages.twoLetter("xyz"))
        assertNull(SubtitleLanguages.twoLetter(null))
    }

    @Test
    fun ranksPreferredLanguagesFirstAndKeepsTheDatabaseOrder() {
        val subs = listOf(
            OnlineSubtitle("https://a", "fr"),
            OnlineSubtitle("https://b", "en"),
            OnlineSubtitle("https://c", "es"),
            OnlineSubtitle("https://d", "en"),
        )
        val ranked = SubtitleLanguages.rank(subs, listOf("es", "en"), limit = 10).map { it.url }
        assertEquals(listOf("https://c", "https://b", "https://d", "https://a"), ranked)
        assertEquals(2, SubtitleLanguages.rank(subs, listOf("en", "en"), limit = 2).size)
    }
}
