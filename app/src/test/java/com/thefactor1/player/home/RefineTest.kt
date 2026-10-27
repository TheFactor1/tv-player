package com.thefactor1.player.home

import com.thefactor1.player.hub.Badges
import com.thefactor1.player.hub.HubItem
import org.junit.Assert.assertEquals
import org.junit.Test

class RefineTest {

    private fun item(id: String, year: Int, minutes: Int?, genres: List<String>, badges: Badges = Badges(null, null, null)) =
        HubItem(id, "movie", id, year, minutes, genres, null, null, badges, false, null, null, null)

    private val items = listOf(
        item("Atlas", 2023, 115, listOf("Drama"), Badges("4K", "HDR10", "Atmos")),
        item("Dust", 2019, 45, listOf("Comedy")),
        item("Harbour", 2021, 150, listOf("Drama", "Thriller"), Badges("4K", "SDR", null)),
        item("Brine", 2024, null, listOf("Thriller"), Badges("1080p", "DV", "DD+")),
    )

    private fun ids(refine: Refine) = refine.apply(items).map { it.id }

    @Test
    fun nothingOnKeepsTheHubOrder() {
        assertEquals(listOf("Atlas", "Dust", "Harbour", "Brine"), ids(Refine()))
    }

    @Test
    fun genresAndLengthsWidenFormatsNarrow() {
        assertEquals(listOf("Atlas", "Dust", "Harbour"), ids(Refine(genres = setOf("Drama", "Comedy"))))
        assertEquals(listOf("Dust", "Harbour"), ids(Refine(lengths = setOf(0, 2))))
        assertEquals(listOf("Atlas", "Harbour"), ids(Refine(formats = setOf(0))))
        // SDR is not HDR; both 4K and HDR leaves one.
        assertEquals(listOf("Atlas"), ids(Refine(formats = setOf(0, 1))))
        assertEquals(listOf("Harbour"), ids(Refine(genres = setOf("Thriller"), formats = setOf(0))))
    }

    @Test
    fun sortsReplaceTheOrder() {
        assertEquals(listOf("Brine", "Atlas", "Harbour", "Dust"), ids(Refine(sort = 0)))
        assertEquals(listOf("Atlas", "Brine", "Dust", "Harbour"), ids(Refine(sort = 1)))
        // An unknown length goes last.
        assertEquals(listOf("Dust", "Atlas", "Harbour", "Brine"), ids(Refine(sort = 2)))
    }

    @Test
    fun genreCountsFollowTheOtherChoices() {
        val fourK = Refine(formats = setOf(0), genres = setOf("Comedy"))
        assertEquals(2, fourK.countFor("Drama", items))
        assertEquals(1, fourK.countFor("Thriller", items))
        assertEquals(0, fourK.countFor("Comedy", items))
        assertEquals(2, fourK.count)
    }

    @Test
    fun togglesAndCount() {
        val refine = Refine().toggleGenre("Drama").toggleLength(1).toggleFormat(2).pickSort(1)
        assertEquals(4, refine.count)
        assertEquals(Refine(), refine.toggleGenre("Drama").toggleLength(1).toggleFormat(2).pickSort(1))
    }

    @Test
    fun survivesSharedPreferences() {
        val refine = Refine(setOf("Drama", "Science Fiction"), setOf(0, 2), setOf(1), 2)
        assertEquals(refine, Refine.decode(refine.encode()))
        assertEquals(Refine(), Refine.decode(Refine().encode()))
        assertEquals(Refine(), Refine.decode("garbage"))
        assertEquals(Refine(lengths = setOf(1)), Refine.decode("|1,9||7"))
    }
}
