package com.thefactor1.player.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HubJsonTest {

    private val film = """
        {"id":"tt0111161","type":"movie","title":"The Long Harbour","year":2023,"runtimeMin":115,
         "genres":["Drama","Mystery"],"poster":"p","backdrop":"b","logo":"https://x/logo.png","overview":"Fog.",
         "badges":{"res":"4K","hdr":"DV","audio":"Atmos"},"inLibrary":true,"keptAt":1761500000,
         "resume":{"positionMs":600000,"durationMs":6900000,"updatedAt":"2026-10-27T12:00:00Z"},"nextEpisode":null}
    """

    @Test
    fun readsTheContractItem() {
        val item = HubJson.items("[$film]").single()
        assertEquals("tt0111161", item.id)
        assertEquals("The Long Harbour", item.title)
        assertEquals(2023, item.year)
        assertEquals(115, item.runtimeMin)
        assertEquals(listOf("Drama", "Mystery"), item.genres)
        assertEquals(Badges("4K", "DV", "Atmos"), item.badges)
        assertTrue(item.inLibrary)
        assertFalse(item.isSeries)
        // Seconds are read as seconds, ISO as UTC.
        assertEquals(1_761_500_000_000L, item.keptAt)
        assertEquals(HubResume(600_000, 6_900_000, 1_793_102_400_000L), item.resume)
        assertNull(item.nextEpisode)
    }

    @Test
    fun acceptsWrappedListsAndStremioNames() {
        val wrapped = HubJson.items("""{"metas":[{"id":"tt1","name":"Kingfisher","type":"series","year":"2021–",
            "nextEpisode":{"s":2,"e":3,"title":"Low Tide"},"badges":null,"resume":null}]}""")
        val show = wrapped.single()
        assertEquals("Kingfisher", show.title)
        assertEquals(2021, show.year)
        assertTrue(show.isSeries)
        assertEquals(NextEpisode(2, 3, "Low Tide"), show.nextEpisode)
        assertEquals(Badges(null, null, null), show.badges)
        assertNull(show.resume)
    }

    @Test
    fun catalogMetasTakeTheCatalogsType() {
        assertTrue(HubJson.items("""{"metas":[{"id":"tt1","name":"Kingfisher"}]}""", defaultType = "series").single().isSeries)
        assertFalse(HubJson.items("""[{"id":"tt1","name":"Atlas","type":"movie"}]""", defaultType = "series").single().isSeries)
    }

    @Test
    fun dropsItemsWithoutIdOrTitle() {
        assertEquals(1, HubJson.items("""[{"id":"tt1"},{"title":"No id"},{"id":"tt2","title":"Ok"}]""").size)
        assertTrue(HubJson.items("""{"items":[]}""").isEmpty())
    }

    @Test
    fun readsCatalogs() {
        val catalogs = HubJson.catalogs("""{"catalogs":[{"type":"movie","id":"top","name":"Popular"},{"type":"series","id":"x"}]}""")
        assertEquals(listOf(Catalog("movie", "top", "Popular"), Catalog("series", "x", "x")), catalogs)
    }

    @Test
    fun readsStreamsAndPlayAnswers() {
        val streams = HubJson.streams("""{"streams":[
            {"url":"https://a/1.mkv","name":"Remux","badges":{"res":"4K"},"source":"TorBox","headers":{"Referer":"https://a"}},
            {"name":"no url"},
            {"url":"https://a/2.mkv","badges":{"res":"1080p","audio":"DD+"}}]}""")
        assertEquals(2, streams.size)
        assertEquals(mapOf("Referer" to "https://a"), streams[0].headers)
        assertEquals("TorBox", streams[0].source)
        assertEquals("1080p · DD+", streams[1].name)
    }

    @Test
    fun readsTheLiveHubsListBadgesAndSourceObject() {
        val stream = HubJson.stream(org.json.JSONObject("""{"url":"https://a/1.mkv","headers":{},
            "badges":{"res":"2160p","hdr":["DV"],"audio":["DTS-HD","DD+"],"source":"remux","sizeGb":60.0,"cached":true},
            "source":{"addon":"aiostreams.example","name":"4K"}}"""))!!
        assertEquals(Badges("2160p", "DV", "DTS-HD / DD+"), stream.badges)
        assertEquals("aiostreams.example", stream.source)
        assertNull(HubJson.stream(org.json.JSONObject("""{"url":"u","badges":{"hdr":[]}}"""))!!.badges.hdr)
    }

    @Test
    fun nameslessStreamsAreLabelledFromTheirBadges() {
        val stream = HubJson.stream(org.json.JSONObject("""{"url":"https://a/1.mkv","description":"Dune.Part.Two.2160p.REMUX",
            "badges":{"res":"2160p","hdr":["DV"],"audio":"Atmos","source":"remux","sizeGb":58.24,"mbps":61.6},
            "source":{"addon":"aiostreams.example"}}"""))!!
        assertEquals("2160p · DV · Atmos · remux · 58.2 GB · 62 Mbps", stream.label())
        assertEquals("Dune.Part.Two.2160p.REMUX", stream.description)
        val bytes = HubJson.stream(org.json.JSONObject("""{"url":"u","size":4500000000,"badges":{"res":"1080p"}}"""))!!
        assertEquals("1080p · 4.5 GB", bytes.label())
        // Nothing to go on: the name or the source stands in.
        assertEquals("Remux", HubJson.stream(org.json.JSONObject("""{"url":"u","name":"Remux"}"""))!!.label())
    }

    @Test
    fun aShowsFullRecordCarriesItsEpisodesInOrder() {
        val show = HubJson.items("""[{"id":"tt1","type":"series","name":"Kingfisher","poster":"https://p","background":"https://b",
            "videos":[{"season":1,"episode":2,"title":"Low Tide","released":"2021-03-08T00:00:00.000Z"},
                      {"season":1,"number":1,"name":"Pilot","overview":"Fog.","thumbnail":"https://t"},
                      {"s":2,"e":1,"title":"Seven Thirty-Seven"},
                      {"title":"no season"}]}]""").single()
        assertEquals("https://b", show.backdrop)
        assertEquals("https://p", show.poster)
        assertEquals(
            listOf(Video(1, 1, "Pilot", "Fog.", "https://t", null), Video(1, 2, "Low Tide", null, null, "2021-03-08"),
                Video(2, 1, "Seven Thirty-Seven", null, null, null)),
            show.videos,
        )
    }

    @Test
    fun versionsKnowTheirPickCacheAndMatch() {
        val streams = HubJson.streams("""[{"url":"a","badges":{"res":"2160p","hdr":"DV","audio":"DTS-HD","source":"remux","sizeGb":60.2,"mbps":52,"cached":true,"match":"ok"}},
            {"url":"b","badges":{"res":"1080p","match":"unverified","cached":null}}]""")
        // Ranked by the hub: the first is its pick.
        assertTrue(streams[0].best)
        assertFalse(streams[1].best)
        assertFalse(streams[1].cached)
        assertEquals("2160p · DV · DTS-HD", streams[0].quality())
        assertEquals("remux · 60.2 GB · 52 Mbps · ⚡ cached", streams[0].delivery())
        assertFalse(streams[1].verified)
        assertTrue(streams[0].verified)
    }

    @Test
    fun hubBeaconRepliesBecomeAddresses() {
        assertEquals(FoundHub("Living room", "http://192.168.50.10:8097"), HubDiscovery.parse("MEDIABOX 1 8097 Living room", "192.168.50.10"))
        assertEquals(FoundHub("10.0.0.2", "http://10.0.0.2:8097"), HubDiscovery.parse("MEDIABOX 1 8097\n", "10.0.0.2"))
        assertNull(HubDiscovery.parse("MEDIABOX 2 8097 Future", "10.0.0.2"))
        assertNull(HubDiscovery.parse("MEDIABOX?", "10.0.0.2"))
        val request = HubDiscovery.request()
        assertEquals(64, request.size)
        assertEquals("MEDIABOX?", String(request, Charsets.US_ASCII).trim())
        assertNull(HubDiscovery.parse("MEDIABOX 1 99999 Bad port", "10.0.0.2"))
        assertEquals("482 913", com.thefactor1.player.home.PairingSetup.spaced("482913"))
    }

    @Test
    fun readsPresetsEitherWayAndCutAndBitrate() {
        val keyed = HubJson.presets("""{"slow":{"max_mbps":8,"max_res":"1080p"},"max":{"max_mbps":null,"max_res":null}}""")
        assertEquals(8.0, keyed.getValue("slow").maxMbps!!, 0.0)
        assertNull(keyed.getValue("max").maxMbps)
        val listed = HubJson.presets("""[{"name":"standard","max_mbps":20}]""")
        assertEquals(20.0, listed.getValue("standard").maxMbps!!, 0.0)
        val stream = HubJson.stream(org.json.JSONObject("""{"url":"u","badges":{"cut":"Director's Cut","mbps":61.5}}"""))!!
        assertEquals("Director's Cut", stream.badges.cut)
        assertEquals(61.5, stream.badges.mbps!!, 0.0)
    }

    @Test
    fun autoAsksForSeventyPercentOfTheLinkOrFallsBack() {
        assertEquals("preset=fast", Quality.query("fast", 100.0))
        assertEquals("max_mbps=43.4", Quality.query(Quality.AUTO, 62.0))
        assertEquals("preset=standard", Quality.query(Quality.AUTO, null))
    }

    @Test
    fun resumeNeedsAPosition() {
        assertNull(HubJson.resume(org.json.JSONObject("""{"durationMs":5}""")))
        assertEquals(HubResume(5, 0, 1_700_000_000_000L), HubJson.resume(org.json.JSONObject("""{"positionMs":5,"updatedAt":1700000000000}""")))
    }

    @Test
    fun addressesGetASchemeAndLoseTheSlash() {
        assertEquals("http://192.168.50.10:8097", HubClient.normalize(" 192.168.50.10:8097/ "))
        assertEquals("https://hub.example.ts.net", HubClient.normalize("https://hub.example.ts.net"))
        assertNull(HubClient.normalize(""))
        assertNull(HubClient.normalize("ftp://box"))
        assertNull(HubClient.normalize("my hub"))
    }
}
