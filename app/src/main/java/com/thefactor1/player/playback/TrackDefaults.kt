package com.thefactor1.player.playback

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import com.thefactor1.player.playback.PlayerPrefs.SubtitleMode

/**
 * Turns the viewer's default audio and subtitle choices into track selection parameters. Any
 * manual pick from the panels is dropped, so a changed default takes effect straight away.
 */
object TrackDefaults {

    fun apply(
        params: TrackSelectionParameters,
        audioLanguage: String,
        subtitleMode: SubtitleMode,
        subtitleLanguage: String,
        deviceLanguage: String,
    ): TrackSelectionParameters {
        val builder = params.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setPreferredAudioLanguage(audioLanguage.ifEmpty { null })
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, subtitleMode == SubtitleMode.OFF)
        val textLanguage = subtitleLanguage.ifEmpty { deviceLanguage }
        when (subtitleMode) {
            SubtitleMode.AUTO, SubtitleMode.OFF -> builder
                .setPreferredTextLanguage(null)
                .setSelectUndeterminedTextLanguage(false)
                .setIgnoredTextSelectionFlags(0)
            // Only tracks flagged forced (signs, foreign dialogue), never the file's full default.
            SubtitleMode.FORCED_ONLY -> builder
                .setPreferredTextLanguage(null)
                .setSelectUndeterminedTextLanguage(false)
                .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            // Unlabelled tracks (common for subtitles a source app adds) still count as a match.
            SubtitleMode.ALWAYS -> builder
                .setPreferredTextLanguage(textLanguage)
                .setSelectUndeterminedTextLanguage(true)
                .setIgnoredTextSelectionFlags(0)
        }
        return builder.build()
    }
}
