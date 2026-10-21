package com.thefactor1.player.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseNameTest {

    @Test
    fun episodeFileName() {
        assertEquals(
            ReleaseName("Crystal Lake", "S1 E1"),
            ReleaseName.parse("Crystal.Lake.S01E01.2160p.PCOK.WEB-DL.DV.HDR.H.265.mkv"),
        )
    }

    @Test
    fun movieFileNameWithYear() {
        assertEquals(ReleaseName("Dune Part Two", "2024"), ReleaseName.parse("Dune.Part.Two.2024.2160p.UHD.BluRay.REMUX.mkv"))
    }

    @Test
    fun yearAtTheStartIsPartOfTheTitle() {
        assertEquals(ReleaseName("2001 A Space Odyssey", "1968"), ReleaseName.parse("2001.A.Space.Odyssey.1968.1080p.mkv"))
    }

    @Test
    fun noYearCutsAtQuality() {
        assertEquals(ReleaseName("Some Show Special", null), ReleaseName.parse("Some_Show_Special_1080p_WEBRip.mp4"))
    }

    @Test
    fun ordinaryTitlesAreLeftAlone() {
        assertEquals(ReleaseName("The Bear", null), ReleaseName.parse("The Bear"))
        assertEquals(ReleaseName("Mr. Robot", null), ReleaseName.parse("Mr. Robot"))
    }

    @Test
    fun blankIsNull() {
        assertNull(ReleaseName.parse("  "))
        assertNull(ReleaseName.parse(null))
    }
}
