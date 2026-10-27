package com.thefactor1.player.meta

import com.thefactor1.player.meta.SoundtrackMatch.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SoundtrackMatchTest {
    @Test
    fun picksTheFilmsOwnSoundtrack() {
        val picked = SoundtrackMatch.pick(
            listOf(
                Candidate("two", "Dune: Part Two (Original Motion Picture Soundtrack)", 2024, 100),
                Candidate("one", "Dune (Original Motion Picture Soundtrack)", 2021, 100),
            ),
            "Dune", 2021,
        )
        assertEquals("one", picked?.id)
    }

    @Test
    fun skipsAnAlbumFromAnotherYear() {
        val picked = SoundtrackMatch.pick(listOf(Candidate("old", "Dune (Original Motion Picture Soundtrack)", 1984, 100)), "Dune", 2021)
        assertNull(picked)
    }

    @Test
    fun acceptsMusicFromTheFilmAndAccents() {
        val picked = SoundtrackMatch.pick(listOf(Candidate("a", "Music From the Motion Picture Amélie & Friends", 2001, 95)), "Amelie and Friends", 2001)
        // "Music from…" comes first, so the album doesn't start with the film's name.
        assertNull(picked)
        val named = SoundtrackMatch.pick(listOf(Candidate("b", "Amélie & Friends: Music From the Motion Picture", 2001, 95)), "Amelie and Friends", 2001)
        assertEquals("b", named?.id)
    }

    @Test
    fun prefersTheBestScoreThenTheEarliest() {
        val picked = SoundtrackMatch.pick(
            listOf(
                Candidate("deluxe", "Heat (Deluxe Edition)", 1996, 90),
                Candidate("score", "Heat (Original Motion Picture Score)", 1995, 100),
                Candidate("low", "Heat", 1995, 60),
            ),
            "Heat", 1995,
        )
        assertEquals("score", picked?.id)
    }

    @Test
    fun shortNameDropsTheSoundtrackTail() {
        assertEquals("Dune", Soundtrack("x", "Dune (Original Motion Picture Soundtrack)", null, null, emptyList()).shortName)
        assertEquals("Drive", Soundtrack("x", "Drive", null, null, emptyList()).shortName)
    }
}
