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
    // Where a catalogue name splits into a franchise and the film: "Star Wars: Episode V - The Empire Strikes Back".
    private val SEGMENT_BREAK = Regex("""\s*(?:[:|–—]|\s-\s)\s*""")
    private val LEADING_ARTICLE = Regex("""^(the|a|an) """)
    private val ROMAN = mapOf("ii" to "2", "iii" to "3", "iv" to "4", "v" to "5", "vi" to "6", "vii" to "7", "viii" to "8", "ix" to "9", "x" to "10")

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
        val loose = loosen(wanted)
        return when {
            name == wanted -> 3 + yearScore
            // "Empire Strikes Back" or "Rocky II" against "The Empire Strikes Back" or "Rocky 2".
            loosen(name) == loose -> 3 + yearScore - 1
            // "The Empire Strikes Back" is the last part of "Star Wars: Episode V - The Empire Strikes Back".
            // The whole film part, never the franchise in front ("Dune" isn't "Dune: Part Two"), never
            // against the year, and a one-word name needs the year to back it.
            yearScore >= 0 && (yearScore == 2 || ' ' in loose) && lastSegment(candidate.name)?.let(::loosen) == loose -> 2 + yearScore
            // "Ready or Not, Here I Come" against "Ready or Not 2: Here I Come": a sequel's number left
            // out. Weakest of all, so the film without the number wins when it's there too.
            yearScore >= 0 && ' ' in loose && withoutNumbers(loose) == loose && loose == withoutNumbers(loosen(name)) ->
                if (yearScore == 2) 2 else 1
            // "Dune Part Two" against "Dune: Part Two - Extended": only trusted with the year to back it.
            yearScore == 2 && (name.startsWith("$wanted ") || wanted.startsWith("$name ")) -> 2
            else -> 0
        }
    }

    private fun withoutNumbers(loose: String): String = loose.split(' ').filterNot { it.all(Char::isDigit) }.joinToString(" ")

    /** What follows the last colon or spaced dash of a catalogue name, when it has one. */
    private fun lastSegment(name: String): String? {
        val parts = name.split(SEGMENT_BREAK).map(::normalize).filter { it.isNotEmpty() }
        return if (parts.size > 1) parts.last() else null
    }

    /** [normalize]d, without a leading "the", and with Roman numerals as digits, for a forgiving comparison. */
    fun loosen(normalized: String): String =
        LEADING_ARTICLE.replace(normalized, "").split(' ').joinToString(" ") { ROMAN[it] ?: it }

    /**
     * Other names worth searching the catalogue by when [name] finds nothing: each side of a colon,
     * and the name without a leading article. "Alien: Romulus" tries "Alien Romulus" and "Romulus".
     */
    fun searchVariants(name: String): List<String> {
        val variants = LinkedHashSet<String>()
        val parts = name.split(SEGMENT_BREAK).map { it.trim() }.filter { normalize(it).isNotEmpty() }
        if (parts.size > 1) {
            variants += parts.joinToString(" ")
            variants += parts.last()
        }
        val withoutArticle = name.trim().replaceFirst(Regex("""^(?i)(the|a|an)\s+"""), "")
        if (withoutArticle != name.trim() && withoutArticle.isNotEmpty()) variants += withoutArticle
        variants.remove(name.trim())
        return variants.filter { normalize(it).length >= MIN_VARIANT }
    }

    /** A file-safe name for what [query] was matched to, the same for every episode of a show. */
    fun cacheKey(query: MetaQuery): String {
        val name = normalize(query.name).replace(' ', '-')
        val short = if (name.length > MAX_KEY_NAME) name.take(MAX_KEY_NAME) + "-" + Integer.toHexString(name.hashCode()) else name
        return listOfNotNull(if (query.isSeries) "series" else "film", short, query.year?.toString()).joinToString("-")
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
    private const val MAX_KEY_NAME = 80
    private const val MIN_VARIANT = 3
}
