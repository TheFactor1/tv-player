package com.thefactor1.player.resume

import java.net.URLDecoder
import java.text.Normalizer

/**
 * The names a saved position is filed under, so that a different stream of the same film or
 * episode finds it: another addon, another debrid link, another release of the same thing.
 *
 * Keys come strongest first. The exact stream, then an IMDb id (with season and episode for a
 * show), then the title the source app sent, then the release name in the file. A position is saved
 * under all of them and looked up by the first that has one.
 */
object TitleKeys {
    private val IMDB = Regex("""\btt\d{7,10}\b""")
    // Stremio's video id form, "tt0944947:1:2", which some addons carry into their stream URLs.
    private val IMDB_EPISODE = Regex("""\b(tt\d{7,10})[:/](\d{1,2})[:/](\d{1,3})\b""")
    private val EPISODE = Regex("""\bS(\d{1,2})\s?[ .\-_]?\s?E(\d{1,3})\b""", RegexOption.IGNORE_CASE)
    private val EPISODE_X = Regex("""\b(\d{1,2})x(\d{2,3})\b""")
    private val YEAR = Regex("""\b(19\d{2}|20\d{2})\b""")
    private val EXTENSION = Regex("""\.(mkv|mp4|m4v|avi|ts|m2ts|webm|mov)$""", RegexOption.IGNORE_CASE)
    private val QUALITY = Regex(
        """\b(2160p|1080p|720p|576p|480p|4k|uhd|web[- ]?dl|webrip|bluray|blu[- ]ray|remux|hdtv|dv|hdr10?\+?|x26[45]|h ?26[45]|hevc|repack|proper)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val NOT_ALNUM = Regex("""[^a-z0-9]+""")
    private val MARKS = Regex("""\p{Mn}+""")

    /** A name with what was found next to it. */
    data class Parsed(val name: String, val season: Int?, val episode: Int?, val year: Int?)

    fun from(title: String?, fileName: String?, url: String?, textExtras: Collection<String> = emptyList()): List<String> {
        val keys = LinkedHashSet<String>()
        streamKey(url)?.let { keys += it }

        val haystack = textExtras + listOfNotNull(title, fileName, url)
        val imdbEpisode = haystack.firstNotNullOfOrNull { IMDB_EPISODE.find(it) }
        val imdb = imdbEpisode?.groupValues?.get(1) ?: haystack.firstNotNullOfOrNull { IMDB.find(it)?.value }

        val fromTitle = parse(title)
        val fromFile = parse(fileName ?: url?.let(::lastPathSegment))
        val season = imdbEpisode?.groupValues?.get(2)?.toInt() ?: fromTitle?.season ?: fromFile?.season
        val episode = imdbEpisode?.groupValues?.get(3)?.toInt() ?: fromTitle?.episode ?: fromFile?.episode
        val isEpisode = season != null && episode != null

        if (imdb != null) keys += if (isEpisode) "imdb:$imdb:s${season}e$episode" else "imdb:$imdb"

        for (parsed in listOfNotNull(fromTitle, fromFile)) {
            val name = normalize(parsed.name)
            if (name.isEmpty() || name.all { it.isDigit() || it == ' ' }) continue
            when {
                // An episode is only named by a source that says which episode it is; a bare
                // episode title like "Pilot" would match every show's first episode.
                parsed.season != null && parsed.episode != null -> keys += "name:$name|s${parsed.season}e${parsed.episode}"
                isEpisode -> Unit
                else -> {
                    parsed.year?.let { keys += "name:$name|$it" }
                    keys += "name:$name"
                }
            }
        }
        return keys.toList()
    }

    /** Splits "Crystal.Lake.S01E01.2160p.mkv", "Crystal Lake - S1E1" or "Dune Part Two (2024)". */
    fun parse(raw: String?): Parsed? {
        var text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        text = EXTENSION.replace(text, "")
        if (' ' !in text) text = text.replace('.', ' ').replace('_', ' ')

        val episodeMatch = EPISODE.find(text) ?: EPISODE_X.find(text)
        if (episodeMatch != null) {
            val name = text.substring(0, episodeMatch.range.first).trim().trimEnd('-', ':', '·', '(', '[').trim()
            return Parsed(name.ifEmpty { return null }, episodeMatch.groupValues[1].toInt(), episodeMatch.groupValues[2].toInt(), null)
        }
        // A year after the name, not a film called "1917".
        val year = YEAR.findAll(text).firstOrNull { it.range.first > 0 }
        val cut = listOfNotNull(year?.range?.first, QUALITY.find(text)?.range?.first).minOrNull()
        val name = (if (cut != null) text.substring(0, cut) else text).trim().trimEnd('-', '(', '[').trim()
        return Parsed(name.ifEmpty { return null }, null, null, year?.value?.toInt())
    }

    fun normalize(text: String): String =
        MARKS.replace(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD), "")
            .replace("&", " and ")
            .replace(NOT_ALNUM, " ")
            .trim()

    /** The stream without its query string, which often carries a token that changes each time. */
    private fun streamKey(url: String?): String? =
        url?.substringBefore('?')?.substringBefore('#')?.takeIf { it.isNotBlank() }?.let { "url:$it" }

    private fun lastPathSegment(url: String): String? {
        val segment = url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        if (segment.isEmpty()) return null
        return try {
            URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8")
        } catch (e: IllegalArgumentException) {
            segment
        }
    }
}
