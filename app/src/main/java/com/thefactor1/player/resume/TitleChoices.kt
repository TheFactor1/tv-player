package com.thefactor1.player.resume

/**
 * What the viewer picked for one film or show: its audio track, its subtitles (a track, or off),
 * and its lip-sync. A show's episodes share these, so picking Japanese audio with English
 * subtitles once holds for the next episode. Subtitle timing belongs to one file, so it is kept
 * per film or episode instead ([timingKeys]).
 *
 * Only what the viewer chose from the menus is kept; a field left null follows the defaults.
 */
data class TitleChoices(
    val audio: Track? = null,
    val subtitle: Track? = null,
    val subtitlesOff: Boolean = false,
    val audioDelayMs: Int? = null,
    val subtitleDelayMs: Int? = null,
    val savedAt: Long = 0L,
) {
    /** A track told apart the way a viewer sees it: language and name first, then format. */
    data class Track(
        val language: String?,
        val label: String?,
        val mimeType: String?,
        val channels: Int,
        val forced: Boolean,
    )

    fun encode(): String = listOf(
        VERSION,
        audio?.let(::encodeTrack).orEmpty(),
        subtitle?.let(::encodeTrack).orEmpty(),
        if (subtitlesOff) "1" else "",
        audioDelayMs?.toString().orEmpty(),
        subtitleDelayMs?.toString().orEmpty(),
        savedAt.toString(),
    ).joinToString(FIELD)

    companion object {
        private const val FIELD = "\u001F"
        private const val PART = "\u001E"
        private const val VERSION = "v1"

        private val EPISODE_SUFFIX = Regex("""[:|]s\d+e\d+$""")

        fun decode(value: String?): TitleChoices? {
            val fields = value?.split(FIELD) ?: return null
            if (fields.size != 7 || fields[0] != VERSION) return null
            return TitleChoices(
                audio = decodeTrack(fields[1]),
                subtitle = decodeTrack(fields[2]),
                subtitlesOff = fields[3] == "1",
                audioDelayMs = fields[4].toIntOrNull(),
                subtitleDelayMs = fields[5].toIntOrNull(),
                savedAt = fields[6].toLongOrNull() ?: 0L,
            )
        }

        /**
         * The keys a film's or show's track choices live under, from the title's [TitleKeys]. A film
         * keeps its own keys; an episode's become its show's, without the stream (which is one
         * episode's file) and without the episode number.
         */
        fun choiceKeys(titleKeys: List<String>): List<String> {
            if (!TitleKeys.isEpisode(titleKeys)) return titleKeys.map { "film:$it" }
            return titleKeys
                .filterNot { it.startsWith("url:") }
                .filter { EPISODE_SUFFIX.containsMatchIn(it) }
                .map { "show:" + EPISODE_SUFFIX.replace(it, "") }
                .distinct()
        }

        /** The keys this one file's subtitle timing lives under. */
        fun timingKeys(titleKeys: List<String>): List<String> = titleKeys.map { "timing:$it" }

        /**
         * The track in [candidates] that best matches [wanted], or null when none is the same
         * language. Name, then format and channels, decide between tracks of one language; a
         * forced track never stands in for a full one or the other way round.
         */
        fun bestMatch(candidates: List<Track>, wanted: Track): Int? {
            var best: Int? = null
            var bestScore = Int.MIN_VALUE
            for ((i, track) in candidates.withIndex()) {
                if (baseLanguage(track.language) != baseLanguage(wanted.language)) continue
                if (wanted.language == null && track.label != wanted.label) continue
                if (track.forced != wanted.forced) continue
                var score = 0
                if (track.language == wanted.language) score += 1
                if (!wanted.label.isNullOrEmpty() && track.label == wanted.label) score += 8
                if (track.mimeType == wanted.mimeType) score += 4
                if (track.channels == wanted.channels) score += 2
                if (score > bestScore) {
                    bestScore = score
                    best = i
                }
            }
            return best
        }

        /** "en-US" and "en" are one language here; "pt-BR" and "pt-PT" too, the name tells them apart. */
        private fun baseLanguage(code: String?): String? =
            code?.lowercase()?.substringBefore('-')?.substringBefore('_')?.ifEmpty { null }

        private fun encodeTrack(track: Track): String = listOf(
            track.language.orEmpty(),
            track.label.orEmpty(),
            track.mimeType.orEmpty(),
            track.channels.toString(),
            if (track.forced) "1" else "",
        ).joinToString(PART) { it.replace(FIELD, " ").replace(PART, " ") }

        private fun decodeTrack(value: String): Track? {
            if (value.isEmpty()) return null
            val parts = value.split(PART)
            if (parts.size != 5) return null
            return Track(
                language = parts[0].ifEmpty { null },
                label = parts[1].ifEmpty { null },
                mimeType = parts[2].ifEmpty { null },
                channels = parts[3].toIntOrNull() ?: -1,
                forced = parts[4] == "1",
            )
        }
    }
}
