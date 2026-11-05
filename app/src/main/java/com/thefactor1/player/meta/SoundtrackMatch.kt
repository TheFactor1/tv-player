package com.thefactor1.player.meta

import java.text.Normalizer
import java.util.Locale

/** A film's soundtrack album and its tracks, as the phone lists them. */
data class Soundtrack(
    val id: String,
    val album: String,
    val artist: String?,
    val year: Int?,
    val tracks: List<Track>,
) {
    data class Track(val title: String, val lengthMs: Long?)

    /** "Dune" for "Dune (Original Motion Picture Soundtrack)": the album's own name, for a one-line note. */
    val shortName: String get() = album.replace(SOUNDTRACK_TAIL, "").trim().ifEmpty { album }

    private companion object {
        val SOUNDTRACK_TAIL = Regex("""\s*[(\[][^)\]]*(soundtrack|score|music from)[^)\]]*[)\]]\s*$""", RegexOption.IGNORE_CASE)
    }
}

/**
 * Picks a film's soundtrack from a MusicBrainz search. The album has to be named for the film and
 * nothing else ("Dune (Original Motion Picture Soundtrack)" for Dune, never "Dune: Part Two …"),
 * and come out within a year of it. Of the albums left, the best-scored and earliest wins.
 */
object SoundtrackMatch {
    data class Candidate(val id: String, val title: String, val year: Int?, val score: Int)

    fun pick(candidates: List<Candidate>, film: String, filmYear: Int?): Candidate? {
        val name = words(film)
        if (name.isEmpty()) return null
        return candidates
            .filter { it.score >= MIN_SCORE && namedFor(words(it.title), name) }
            .filter { filmYear == null || it.year == null || kotlin.math.abs(it.year - filmYear) <= 1 }
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.year ?: Int.MAX_VALUE })
            .firstOrNull()
    }

    /** The album's words start with the film's, and whatever follows only says it's a soundtrack. */
    private fun namedFor(album: List<String>, film: List<String>): Boolean =
        album.size >= film.size && album.subList(0, film.size) == film && album.drop(film.size).all { it in SOUNDTRACK_WORDS }

    private fun words(text: String): List<String> =
        Normalizer.normalize(text.lowercase(Locale.ROOT).replace("&", " and "), Normalizer.Form.NFD)
            .replace(Regex("""\p{M}"""), "")
            .split(Regex("""[^a-z0-9]+"""))
            .filter { it.isNotEmpty() }

    private const val MIN_SCORE = 80
    private val SOUNDTRACK_WORDS = setOf(
        "original", "motion", "picture", "soundtrack", "score", "music", "from", "the", "film", "movie", "ost",
        "inspired", "by", "songs", "and", "deluxe", "edition", "expanded", "complete", "official", "netflix", "series",
    )
}
