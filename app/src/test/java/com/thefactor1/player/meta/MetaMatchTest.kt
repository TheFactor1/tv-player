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
    fun theFilmPartOfAFranchiseNameMatches() {
        val candidates = listOf(MetaCandidate("tt0080684", "Star Wars: Episode V - The Empire Strikes Back", "1980"))
        assertEquals("tt0080684", MetaMatch.pick(MetaQuery("The Empire Strikes Back", null, null, null), candidates)?.id)
        assertEquals("tt0080684", MetaMatch.pick(MetaQuery("Empire Strikes Back", 1980, null, null), candidates)?.id)
        // Never against the year.
        assertNull(MetaMatch.pick(MetaQuery("The Empire Strikes Back", 2004, null, null), candidates))
    }

    @Test
    fun theFranchiseInFrontIsNoMatch() {
        val candidates = listOf(MetaCandidate("tt15239678", "Dune: Part Two", "2024"))
        assertNull(MetaMatch.pick(MetaQuery("Dune", null, null, null), candidates))
        // A one-word film part needs the year.
        val fallout = listOf(MetaCandidate("tt4912910", "Mission: Impossible - Fallout", "2018"))
        assertNull(MetaMatch.pick(MetaQuery("Fallout", null, null, null), fallout))
        assertEquals("tt4912910", MetaMatch.pick(MetaQuery("Fallout", 2018, null, null), fallout)?.id)
    }

    @Test
    fun articlesAndRomanNumeralsAreForgiven() {
        assertEquals("tt1", MetaMatch.pick(MetaQuery("Rocky 2", 1979, null, null), listOf(MetaCandidate("tt1", "Rocky II", "1979")))?.id)
        assertEquals("tt2", MetaMatch.pick(MetaQuery("Batman", null, null, null), listOf(MetaCandidate("tt2", "The Batman", "2022")))?.id)
        assertEquals("tt9", MetaMatch.pick(MetaQuery("Spiderman Homecoming", 2017, null, null), listOf(MetaCandidate("tt9", "Spider-Man: Homecoming", "2017")))?.id)
        // An exact name still wins over a forgiven one.
        val both = listOf(MetaCandidate("tt2", "The Batman", "2022"), MetaCandidate("tt3", "Batman", "1989"))
        assertEquals("tt3", MetaMatch.pick(MetaQuery("Batman", null, null, null), both)?.id)
    }

    @Test
    fun aSequelNumberLeftOutStillMatches() {
        val sequel = listOf(MetaCandidate("tt5", "Ready or Not 2: Here I Come", "2026"))
        assertEquals("tt5", MetaMatch.pick(MetaQuery("Ready or Not, Here I Come", 2026, null, null), sequel)?.id)
        assertEquals("tt5", MetaMatch.pick(MetaQuery("Ready or Not, Here I Come", null, null, null), sequel)?.id)
        assertNull(MetaMatch.pick(MetaQuery("Ready or Not, Here I Come", 2019, null, null), sequel))
        // The other way round, a number the source sent is never dropped: "Rocky 2" isn't "Rocky".
        assertNull(MetaMatch.pick(MetaQuery("Rocky 2", null, null, null), listOf(MetaCandidate("tt6", "Rocky", "1976"))))
        // And the film without a number wins over its sequel.
        val both = listOf(MetaCandidate("tt7", "Toy Story 2", "1999"), MetaCandidate("tt8", "Toy Story", "1995"))
        assertEquals("tt8", MetaMatch.pick(MetaQuery("Toy Story", null, null, null), both)?.id)
    }

    @Test
    fun otherNamesToSearchBy() {
        assertEquals(listOf("Alien Romulus", "Romulus"), MetaMatch.searchVariants("Alien: Romulus"))
        assertEquals(listOf("Empire Strikes Back"), MetaMatch.searchVariants("The Empire Strikes Back"))
        assertEquals(emptyList<String>(), MetaMatch.searchVariants("Carrie"))
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
