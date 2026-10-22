package com.thefactor1.player.resume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleKeysTest {

    @Test
    fun episodeFromTwoSourcesSharesAKey() {
        val torrent = TitleKeys.from(
            title = "Crystal.Lake.S01E02.2160p.WEB-DL.DV.mkv",
            fileName = null,
            url = "https://debrid.example/dl/abc123/Crystal.Lake.S01E02.2160p.WEB-DL.DV.mkv?token=1",
        )
        val other = TitleKeys.from(
            title = "Crystal Lake - S1E2",
            fileName = null,
            url = "https://cdn.example/x/9f8e7d/crystal_lake_s01e02_1080p.mp4",
        )
        assertTrue("name:crystal lake|s1e2" in torrent)
        assertTrue("name:crystal lake|s1e2" in other)
    }

    @Test
    fun differentEpisodesNeverShareAKey() {
        val one = TitleKeys.from("Crystal Lake S01E01", null, "https://a.example/1.mkv")
        val two = TitleKeys.from("Crystal Lake S01E02", null, "https://a.example/2.mkv")
        assertTrue(one.intersect(two.toSet()).isEmpty())
    }

    @Test
    fun bareEpisodeTitleIsNotUsedWhenTheFileNamesTheEpisode() {
        val keys = TitleKeys.from("Pilot", null, "https://a.example/Some.Show.S01E01.1080p.mkv")
        assertFalse(keys.any { it.startsWith("name:pilot") })
        assertTrue("name:some show|s1e1" in keys)
    }

    @Test
    fun filmMatchesWithOrWithoutYear() {
        val fromApp = TitleKeys.from("Dune: Part Two", null, "https://a.example/stream/1")
        val fromFile = TitleKeys.from(null, null, "https://b.example/dl/Dune.Part.Two.2024.2160p.UHD.BluRay.REMUX.mkv")
        assertTrue("name:dune part two" in fromApp)
        assertTrue("name:dune part two|2024" in fromFile)
        assertTrue("name:dune part two" in fromFile)
    }

    @Test
    fun imdbIdComesFirstAfterTheStreamItself() {
        val keys = TitleKeys.from(
            title = "Crystal Lake",
            fileName = null,
            url = "https://addon.example/resolve/tt1234567:2:5/stream.mkv?x=1",
        )
        assertEquals("url:https://addon.example/resolve/tt1234567:2:5/stream.mkv", keys[0])
        assertEquals("imdb:tt1234567:s2e5", keys[1])
        assertEquals("tt1234567", TitleKeys.imdbId(keys))
    }

    @Test
    fun imdbIdInAnExtraIsUsed() {
        val keys = TitleKeys.from("Heat", null, "https://a.example/v", textExtras = listOf("tt0113277"))
        assertTrue("imdb:tt0113277" in keys)
        assertEquals("tt0113277", TitleKeys.imdbId(keys))
        assertEquals(null, TitleKeys.imdbId(TitleKeys.from("Heat", null, "https://a.example/v")))
    }

    @Test
    fun streamKeyDropsTheToken() {
        val a = TitleKeys.from(null, null, "https://a.example/f.mkv?token=1")
        val b = TitleKeys.from(null, null, "https://a.example/f.mkv?token=2")
        assertEquals(a[0], b[0])
    }

    @Test
    fun parseSplitsCommonForms() {
        assertEquals(TitleKeys.Parsed("Crystal Lake", 1, 1, null), TitleKeys.parse("Crystal.Lake.S01E01.2160p.mkv"))
        assertEquals(TitleKeys.Parsed("Crystal Lake", 3, 12, null), TitleKeys.parse("Crystal Lake 3x12"))
        assertEquals(TitleKeys.Parsed("Heat", null, null, 1995), TitleKeys.parse("Heat (1995)"))
        assertEquals(TitleKeys.Parsed("1917", null, null, 2019), TitleKeys.parse("1917.2019.1080p.BluRay.mkv"))
    }

    @Test
    fun accentsAndPunctuationAreIgnored() {
        assertEquals("amelie", TitleKeys.normalize("Amélie"))
        assertEquals("fast and furious", TitleKeys.normalize("Fast & Furious!"))
    }

    @Test
    fun torrentAndUsenetStreamsOfAFilmShareAKey() {
        val torrent = TitleKeys.from(
            title = "[RD+] Dune.Part.Two.2024.2160p.WEB-DL.DV.mkv\n👤 120 💾 22 GB ⚙️ Torrentio",
            fileName = null,
            url = "https://debrid.example/resolve/realdebrid/abc/Dune.Part.Two.2024.2160p.WEB-DL.DV.mkv",
        )
        val usenet = TitleKeys.from(
            title = "[TB] Dune Part Two (2024) 2160p",
            fileName = null,
            url = "https://api.example/v1/usenet/requestdl?token=x&usenet_id=9&file_id=0",
        )
        assertTrue("name:dune part two|2024" in torrent)
        assertTrue("name:dune part two|2024" in usenet)
    }

    @Test
    fun genericUrlSegmentsAndHashesAreNotNames() {
        val generic = TitleKeys.from(null, null, "https://api.example/v1/usenet/requestdl?usenet_id=9")
        assertEquals(listOf("url:https://api.example/v1/usenet/requestdl"), generic)
        val hashed = TitleKeys.from(null, null, "https://nzb.example/dl/a8f9c7e2b1d34e0f.mkv")
        assertFalse(hashed.any { it.startsWith("name:") })
    }

    @Test
    fun episodeKeysAreRecognised() {
        assertTrue(TitleKeys.isEpisode(TitleKeys.from("Crystal Lake S01E02", null, null)))
        assertFalse(TitleKeys.isEpisode(TitleKeys.from("Heat (1995)", null, null)))
    }
}
