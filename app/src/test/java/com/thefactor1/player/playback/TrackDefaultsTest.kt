package com.thefactor1.player.playback

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import com.thefactor1.player.playback.PlayerPrefs.SubtitleMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun audioLanguageIsPreferred() {
        assertEquals(listOf("ja"), apply(audio = "ja").preferredAudioLanguages)
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

    @Test
    fun alwaysUsesTheChosenOrDeviceLanguage() {
        val device = apply(mode = SubtitleMode.ALWAYS)
        assertEquals(listOf("en"), device.preferredTextLanguages)
        assertTrue(device.selectUndeterminedTextLanguage)
        assertEquals(listOf("fr"), apply(mode = SubtitleMode.ALWAYS, subs = "fr").preferredTextLanguages)
    }
}
