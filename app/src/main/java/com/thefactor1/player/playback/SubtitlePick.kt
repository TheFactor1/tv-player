package com.thefactor1.player.playback

import java.util.Locale

/**
 * Media3 ranks a track the file flags as default above a full one in the same language, and it
 * can't tell a forced track that is only named "Forced". Either way "Always on" can land on a
 * track that shows a handful of lines, so this finds the full track to use instead. Kept free of
 * Android types so it can be unit tested.
 */
object SubtitlePick {

    class Track(
        val language: String?,
        val forced: Boolean,
        val hearingImpaired: Boolean = false,
        val commentary: Boolean = false,
        val supported: Boolean = true,
    )

    /**
     * @return the index of a full track in the same language as the selected forced one, plain
     * before SDH, or null when the selection is already full or there is nothing better.
     */
    fun fullInsteadOfForced(tracks: List<Track>, selected: Int): Int? {
        val current = tracks.getOrNull(selected) ?: return null
        if (!current.forced) return null
        val language = baseLanguage(current.language)
        return tracks.indices
            .filter { i ->
                val t = tracks[i]
                t.supported && !t.forced && !t.commentary && baseLanguage(t.language) == language
            }
            .minByOrNull { if (tracks[it].hearingImpaired) 1 else 0 }
    }

    private fun baseLanguage(tag: String?): String? = tag
        ?.substringBefore('-')?.substringBefore('_')
        ?.lowercase(Locale.ROOT)
        ?.takeIf { it.isNotBlank() && it != "und" }
}
