package com.thefactor1.player.meta

import java.text.Normalizer

/** What to look a film or show up by, from the title the source app sent. */
data class MetaQuery(val name: String, val year: Int?, val season: Int?, val episode: Int?) {
    val isSeries: Boolean get() = season != null

    companion object {
        private val EPISODE = Regex("""\bS(\d{1,2})\s?E(\d{1,3})\b""", RegexOption.IGNORE_CASE)
        private val YEAR_IN_BRACKETS = Regex("""[(\[]\s*((?:19|20)\d{2})\s*[)\]]""")
        private val YEAR = Regex("""^(19|20)\d{2}$""")

        /**
         * [title] and [detail] as the release-name parser left them: "Crystal Lake" with "S1 E1",
         * or "Dune: Part Two" with "2024". A title that still carries "(2024)" or "S01E02" is split too.
         */
        fun from(title: String?, detail: String?): MetaQuery? {
            var name = title?.trim().orEmpty()
            var year: Int? = null
            var season: Int? = null
            var episode: Int? = null

            val episodeSource = EPISODE.find(detail.orEmpty()) ?: EPISODE.find(name)?.also { match ->
                name = name.substring(0, match.range.first)
            }
            episodeSource?.let {
                season = it.groupValues[1].toInt()
                episode = it.groupValues[2].toInt()
            }
            detail?.trim()?.takeIf { YEAR.matches(it) }?.let { year = it.toInt() }
            YEAR_IN_BRACKETS.find(name)?.let { match ->
                if (year == null) year = match.groupValues[1].toInt()
                name = name.substring(0, match.range.first)
            }
            name = name.trim().trimEnd('-', '·', ':').trim()
            if (MetaMatch.normalize(name).isEmpty()) return null
            return MetaQuery(name, year, season, episode)
        }
    }
}

/** One search result: an id, its name, and its release info ("2024", or "2019–2023" for a show). */
data class MetaCandidate(val id: String, val name: String, val releaseInfo: String?)

/**
 * Picks the search result that is really the film being played. Showing nothing beats showing
 * the wrong film's art, so the name has to match, and the year breaks ties when there is one.
 */
object MetaMatch {
    private val ANY_YEAR = Regex("""(19|20)\d{2}""")
    private val NOT_ALNUM = Regex("""[^a-z0-9]+""")
    private val MARKS = Regex("""\p{Mn}+""")
    private val DIGITS = Regex("""\d+""")

    fun normalize(text: String): String =
        MARKS.replace(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD), "")
            .replace("&", " and ")
            .replace(NOT_ALNUM, " ")
            .trim()

    fun firstYear(releaseInfo: String?): Int? = ANY_YEAR.find(releaseInfo.orEmpty())?.value?.toInt()

    fun pick(query: MetaQuery, candidates: List<MetaCandidate>): MetaCandidate? {
        val wanted = normalize(query.name)
        if (wanted.isEmpty()) return null
        var best: MetaCandidate? = null
        var bestScore = 0
        for (candidate in candidates.take(MAX_CANDIDATES)) {
            val score = score(wanted, query.year, candidate)
            if (score > bestScore) {
                best = candidate
                bestScore = score
            }
        }
        return best
    }

    private fun score(wanted: String, year: Int?, candidate: MetaCandidate): Int {
        val name = normalize(candidate.name)
        val candidateYear = firstYear(candidate.releaseInfo)
        val yearScore = when {
            year == null || candidateYear == null -> 0
            year == candidateYear -> 2
            kotlin.math.abs(year - candidateYear) == 1 -> 1
            else -> -2
        }
        return when {
            name == wanted -> 3 + yearScore
            // "Dune Part Two" against "Dune: Part Two - Extended": only trusted with the year to back it.
            yearScore == 2 && (name.startsWith("$wanted ") || wanted.startsWith("$name ")) -> 2
            else -> 0
        }
    }

    /** "166 min" → "2 h 46 m"; anything unreadable is left out. */
    fun runtime(text: String?): String? {
        val minutes = DIGITS.find(text.orEmpty())?.value?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0 -> "$rest m"
            rest == 0 -> "$hours h"
            else -> "$hours h $rest m"
        }
    }

    private const val MAX_CANDIDATES = 10
}
