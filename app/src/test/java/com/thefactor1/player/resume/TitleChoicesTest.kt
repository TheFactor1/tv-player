package com.thefactor1.player.resume

import com.thefactor1.player.resume.TitleChoices.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleChoicesTest {
    private val japanese = Track("ja", null, "audio/eac3", 6, forced = false)
    private val english = Track("en", "English", "audio/true-hd", 8, forced = false)
    private val commentary = Track("en", "Director's commentary", "audio/ac3", 2, forced = false)
    private val englishSubs = Track("en", "English", "application/x-subrip", -1, forced = false)
    private val englishForced = Track("en", "English (Forced)", "application/x-subrip", -1, forced = true)

    @Test
    fun roundTrips() {
        val choices = TitleChoices(
            audio = japanese,
            subtitle = englishSubs,
            subtitlesOff = false,
            audioDelayMs = -50,
            subtitleDelayMs = 750,
            savedAt = 123,
        )
        assertEquals(choices, TitleChoices.decode(choices.encode()))
        assertEquals(TitleChoices(subtitlesOff = true), TitleChoices.decode(TitleChoices(subtitlesOff = true).encode()))
    }

    @Test
    fun oddCharactersInANameDoNotBreakTheRecord() {
        val choices = TitleChoices(audio = Track("en", "Commentary \u001F with \u001E marks", null, 2, false))
        val back = TitleChoices.decode(choices.encode())
        assertEquals("Commentary   with   marks", back?.audio?.label)
    }

    @Test
    fun junkIsNotARecord() {
        assertNull(TitleChoices.decode(null))
        assertNull(TitleChoices.decode(""))
        assertNull(TitleChoices.decode("1200|7200|5"))
    }

    @Test
    fun episodesShareTheirShowsKeys() {
        val one = TitleChoices.choiceKeys(TitleKeys.from("Crystal Lake S01E01", null, "https://a.example/1.mkv"))
        val two = TitleChoices.choiceKeys(TitleKeys.from("Crystal Lake - S2E5", null, "https://b.example/x.mp4"))
        assertTrue("show:name:crystal lake" in one)
        assertTrue(one.intersect(two.toSet()).isNotEmpty())
        assertTrue(one.none { it.contains("url:") })
    }

    @Test
    fun episodesByImdbIdShareTheShow() {
        val keys = TitleKeys.from("Episode 3", null, "https://addon.example/stream/tt0944947:1:3/file.mkv")
        assertTrue("show:imdb:tt0944947" in TitleChoices.choiceKeys(keys))
    }

    @Test
    fun aFilmKeepsItsOwnKeysAndNeverMeetsAShowOfTheSameName() {
        val film = TitleChoices.choiceKeys(TitleKeys.from("Dune (2021)", null, "https://a.example/dune.mkv"))
        val show = TitleChoices.choiceKeys(TitleKeys.from("Dune S01E01", null, "https://a.example/d.mkv"))
        assertTrue("film:name:dune|2021" in film)
        assertTrue(film.intersect(show.toSet()).isEmpty())
    }

    @Test
    fun timingStaysWithOneFile() {
        val one = TitleChoices.timingKeys(TitleKeys.from("Crystal Lake S01E01", null, "https://a.example/1.mkv"))
        val two = TitleChoices.timingKeys(TitleKeys.from("Crystal Lake S01E02", null, "https://a.example/2.mkv"))
        assertTrue(one.intersect(two.toSet()).isEmpty())
    }

    @Test
    fun findsTheSameLanguageInAnotherRelease() {
        val otherRelease = listOf(
            Track("en", null, "audio/eac3", 6, false),
            Track("ja", null, "audio/ac3", 6, false),
        )
        assertEquals(1, TitleChoices.bestMatch(otherRelease, japanese))
    }

    @Test
    fun nameDecidesBetweenTracksOfOneLanguage() {
        val wanted = commentary
        assertEquals(1, TitleChoices.bestMatch(listOf(english, commentary), wanted))
        assertEquals(0, TitleChoices.bestMatch(listOf(english, commentary), english))
    }

    @Test
    fun formatAndChannelsDecideWhenNamesAreMissing() {
        val stereo = Track("en", null, "audio/aac", 2, false)
        val surround = Track("en", null, "audio/eac3", 6, false)
        assertEquals(1, TitleChoices.bestMatch(listOf(stereo, surround), surround.copy()))
    }

    @Test
    fun regionsOfOneLanguageStillMatch() {
        val tracks = listOf(Track("en-US", "English", "application/x-subrip", -1, false))
        assertEquals(0, TitleChoices.bestMatch(tracks, englishSubs))
    }

    @Test
    fun forcedNeverStandsInForFull() {
        assertNull(TitleChoices.bestMatch(listOf(englishForced), englishSubs))
        assertEquals(1, TitleChoices.bestMatch(listOf(englishForced, englishSubs), englishSubs))
    }

    @Test
    fun noSameLanguageMeansNoMatch() {
        assertNull(TitleChoices.bestMatch(listOf(english), japanese))
    }
}
