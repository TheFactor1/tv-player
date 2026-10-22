package com.thefactor1.player.meta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetaMatchTest {

    @Test
    fun queryFromReleaseNameParts() {
        assertEquals(MetaQuery("Dune Part Two", 2024, null, null), MetaQuery.from("Dune Part Two", "2024"))
        assertEquals(MetaQuery("Crystal Lake", null, 1, 1), MetaQuery.from("Crystal Lake", "S1 E1"))
    }

    @Test
    fun queryFromATitleThatStillCarriesYearOrEpisode() {
        assertEquals(MetaQuery("Dune: Part Two", 2024, null, null), MetaQuery.from("Dune: Part Two (2024)", null))
        assertEquals(MetaQuery("Crystal Lake", null, 1, 2), MetaQuery.from("Crystal Lake - S01E02", null))
    }

    @Test
    fun noQueryWithoutAName() {
        assertNull(MetaQuery.from(null, null))
        assertNull(MetaQuery.from("  ", "2024"))
    }

    @Test
    fun namesMatchAcrossPunctuationAndAccents() {
        assertEquals(MetaMatch.normalize("Dune: Part Two"), MetaMatch.normalize("Dune Part Two"))
        assertEquals(MetaMatch.normalize("Amélie"), MetaMatch.normalize("Amelie"))
        assertEquals(MetaMatch.normalize("Fast & Furious"), MetaMatch.normalize("Fast and Furious"))
    }

    @Test
    fun yearPicksTheRightRemake() {
        val candidates = listOf(
            MetaCandidate("tt0087182", "Dune", "1984"),
            MetaCandidate("tt1160419", "Dune", "2021"),
        )
        assertEquals("tt1160419", MetaMatch.pick(MetaQuery("Dune", 2021, null, null), candidates)?.id)
        // Without a year the catalogue's own order (most popular first) decides.
        assertEquals("tt0087182", MetaMatch.pick(MetaQuery("Dune", null, null, null), candidates)?.id)
    }

    @Test
    fun aDifferentFilmIsNoMatch() {
        val candidates = listOf(MetaCandidate("tt1", "Dune Drifter", "2020"), MetaCandidate("tt2", "Jodorowsky's Dune", "2013"))
        assertNull(MetaMatch.pick(MetaQuery("Dune", null, null, null), candidates))
    }

    @Test
    fun aLongerTitleNeedsTheYear() {
        val candidates = listOf(MetaCandidate("tt3", "Dune Part Two Extended", "2024"))
        assertEquals("tt3", MetaMatch.pick(MetaQuery("Dune Part Two", 2024, null, null), candidates)?.id)
        assertNull(MetaMatch.pick(MetaQuery("Dune Part Two", null, null, null), candidates))
    }

    @Test
    fun showYearsAreRanges() {
        assertEquals(2019, MetaMatch.firstYear("2019–2023"))
        assertEquals("tt4", MetaMatch.pick(MetaQuery("Crystal Lake", null, 1, 1), listOf(MetaCandidate("tt4", "Crystal Lake", "2025–")))?.id)
    }

    @Test
    fun runtimeReadsLikeATvApp() {
        assertEquals("2 h 46 m", MetaMatch.runtime("166 min"))
        assertEquals("45 m", MetaMatch.runtime("45 min"))
        assertEquals("2 h", MetaMatch.runtime("120 min"))
        assertNull(MetaMatch.runtime(""))
        assertNull(MetaMatch.runtime(null))
    }

    @Test
    fun cacheKeyIsSharedByEveryEpisodeAndSafeAsAFileName() {
        assertEquals(
            MetaMatch.cacheKey(MetaQuery("Crystal Lake", null, 1, 1)),
            MetaMatch.cacheKey(MetaQuery("Crystal Lake", null, 2, 5)),
        )
        assertEquals("film-dune-part-two-2024", MetaMatch.cacheKey(MetaQuery("Dune: Part Two", 2024, null, null)))
        assertNotEquals(
            MetaMatch.cacheKey(MetaQuery("Dune", 1984, null, null)),
            MetaMatch.cacheKey(MetaQuery("Dune", 2021, null, null)),
        )
        val long = MetaMatch.cacheKey(MetaQuery("a".repeat(300), null, null, null))
        assertTrue(long.length < 120)
        assertTrue(long.all { it.isLetterOrDigit() || it == '-' })
    }
}
