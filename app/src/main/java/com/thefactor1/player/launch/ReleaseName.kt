package com.thefactor1.player.launch

/**
 * Turns a release file name, which some apps send as the title, into something worth showing:
 * "Crystal.Lake.S01E01.2160p.WEB-DL.DV.mkv" becomes "Crystal Lake" with "S1 E1".
 * Titles that already read like titles are left alone.
 */
data class ReleaseName(val title: String, val detail: String?) {
    companion object {
        private val EXTENSION = Regex("""\.(mkv|mp4|m4v|avi|ts|m2ts|webm|mov)$""", RegexOption.IGNORE_CASE)
        private val EPISODE = Regex("""\bS(\d{1,2})[ .]?E(\d{1,3})\b""", RegexOption.IGNORE_CASE)
        private val YEAR = Regex("""\b(19\d{2}|20\d{2})\b""")
        private val QUALITY = Regex(
            """\b(2160p|1080p|720p|576p|480p|4k|uhd|web[- ]?dl|webrip|bluray|blu[- ]ray|remux|hdtv|dv|hdr10?\+?|x26[45]|h ?26[45]|hevc)\b""",
            RegexOption.IGNORE_CASE,
        )

        fun parse(raw: String?): ReleaseName? {
            val input = raw?.trim().orEmpty()
            if (input.isEmpty()) return null
            val looksLikeFile = EXTENSION.containsMatchIn(input) || (' ' !in input && input.count { it == '.' } >= 2)
            if (!looksLikeFile) return ReleaseName(input, null)

            val cleaned = EXTENSION.replace(input, "").replace('.', ' ').replace('_', ' ').replace(Regex("""\s+"""), " ").trim()

            EPISODE.find(cleaned)?.let { match ->
                val name = cleaned.substring(0, match.range.first).trim().trimEnd('-').trim()
                val season = match.groupValues[1].toInt()
                val episode = match.groupValues[2].toInt()
                return ReleaseName(name.ifEmpty { cleaned }, "S$season E$episode")
            }
            YEAR.findAll(cleaned).firstOrNull { it.range.first > 0 }?.let { match ->
                val name = cleaned.substring(0, match.range.first).trim().trimEnd('(', '[').trim()
                if (name.isNotEmpty()) return ReleaseName(name, match.value)
            }
            QUALITY.find(cleaned)?.let { match ->
                val name = cleaned.substring(0, match.range.first).trim()
                if (name.isNotEmpty()) return ReleaseName(name, null)
            }
            return ReleaseName(cleaned, null)
        }
    }
}
