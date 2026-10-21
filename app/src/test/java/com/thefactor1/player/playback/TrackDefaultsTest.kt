package com.thefactor1.player.playback

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import com.thefactor1.player.playback.PlayerPrefs.SubtitleMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Language preferences go through Android's Locale normalisation, which plain JVM tests can't run,
// so these cover the mode switches only.
class TrackDefaultsTest {

    private fun apply(audio: String = "", mode: SubtitleMode = SubtitleMode.AUTO, subs: String = "") =
        TrackDefaults.apply(TrackSelectionParameters.DEFAULT, audio, mode, subs, deviceLanguage = "en")

    @Test
    fun autoFollowsTheFile() {
        val params = apply()
        assertTrue(params.preferredAudioLanguages.isEmpty())
        assertTrue(params.preferredTextLanguages.isEmpty())
        assertFalse(params.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
    }

    @Test
    fun offDisablesTextAndAutoTurnsItBackOn() {
        val off = apply(mode = SubtitleMode.OFF)
        assertTrue(off.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
        val auto = TrackDefaults.apply(off, "", SubtitleMode.AUTO, "", "en")
        assertFalse(auto.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
    }

    @Test
    fun forcedOnlyIgnoresTheDefaultFlag() {
        assertEquals(C.SELECTION_FLAG_DEFAULT, apply(mode = SubtitleMode.FORCED_ONLY).ignoredTextSelectionFlags)
    }
}
