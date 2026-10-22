package com.thefactor1.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class TrackNamesTest {

    private fun name(tag: String?, label: String?, forced: Boolean = false) =
        TrackNames.name(tag, label, forced = forced, displayLocale = Locale.ENGLISH)

    @Test
    fun releaseGroupLabelsBecomeLanguageNames() {
        assertEquals("English", name("eng", "BTM"))
        assertEquals("English (SDH)", name("eng", "BTM SDH"))
        assertEquals("Spanish (Latin America)", name("spa", "BTM LA"))
        assertEquals("Spanish (Europe)", name("spa", "BTM EU"))
    }

    @Test
    fun regionFromTheTagItself() {
        assertEquals("Spanish (Latin America)", name("es-419", null))
        assertEquals("Portuguese (Brazil)", name("pt-BR", null))
        assertEquals("English", name("en-US", null))
    }

    @Test
    fun languageFromTheLabelWhenTheTagIsMissing() {
        assertEquals("French (Forced)", name("und", "French Forced"))
        assertEquals("English (SDH)", name(null, "English.SDH"))
    }

    @Test
    fun forcedFlagIsShown() {
        assertEquals("English (Forced)", name("en", null, forced = true))
    }

    @Test
    fun nothingKnownGivesNull() {
        assertNull(name(null, "BTM"))
        assertNull(name("und", null))
    }

    @Test
    fun forcedFromTheTrackName() {
        assertEquals(true, TrackNames.saysForced("English [Forced]"))
        assertEquals(true, TrackNames.saysForced("Foreign parts only"))
        assertEquals(false, TrackNames.saysForced("English SDH"))
        assertEquals(false, TrackNames.saysForced(null))
    }
}
