package com.thefactor1.player.playback

import com.thefactor1.player.playback.SubtitlePick.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitlePickTest {

    @Test
    fun forcedEnglishGivesWayToFullEnglish() {
        val tracks = listOf(Track("en", forced = true), Track("en", forced = false), Track("es", forced = false))
        assertEquals(1, SubtitlePick.fullInsteadOfForced(tracks, selected = 0))
    }

    @Test
    fun plainBeatsSdhAndCommentaryIsSkipped() {
        val tracks = listOf(
            Track("en", forced = true),
            Track("en", forced = false, commentary = true),
            Track("en", forced = false, hearingImpaired = true),
            Track("en", forced = false),
        )
        assertEquals(3, SubtitlePick.fullInsteadOfForced(tracks, selected = 0))
    }

    @Test
    fun regionTagsStillMatch() {
        val tracks = listOf(Track("en-US", forced = true), Track("en", forced = false))
        assertEquals(1, SubtitlePick.fullInsteadOfForced(tracks, selected = 0))
    }

    @Test
    fun leavesFullAndLoneForcedTracksAlone() {
        val tracks = listOf(Track("en", forced = false), Track("en", forced = true), Track("fr", forced = false))
        assertNull(SubtitlePick.fullInsteadOfForced(tracks, selected = 0))
        assertNull(SubtitlePick.fullInsteadOfForced(tracks.drop(1), selected = 0))
        assertNull(SubtitlePick.fullInsteadOfForced(tracks, selected = -1))
    }

    @Test
    fun unsupportedTracksAreNotOffered() {
        val tracks = listOf(Track("en", forced = true), Track("en", forced = false, supported = false))
        assertNull(SubtitlePick.fullInsteadOfForced(tracks, selected = 0))
    }
}
