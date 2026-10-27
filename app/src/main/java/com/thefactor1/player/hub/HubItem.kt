package com.thefactor1.player.hub

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * Quality of the best stream the hub found: "4K", "DV", "Atmos", which cut it is ("Director's Cut")
 * and its bitrate. Any of them may be missing.
 */
data class Badges(val res: String?, val hdr: String?, val audio: String?, val cut: String? = null, val mbps: Double? = null) {
    fun all(): List<String> = listOfNotNull(res, hdr, audio)
}

data class HubResume(val positionMs: Long, val durationMs: Long, val updatedAt: Long)

data class NextEpisode(val season: Int, val episode: Int, val title: String?)

/** A film or show as the hub describes it, the same shape from every call. */
data class HubItem(
    /** The IMDb id, "tt1234567". */
    val id: String,
    /** "movie" or "series". */
    val type: String,
    val title: String,
    val year: Int?,
    val runtimeMin: Int?,
    val genres: List<String>,
    val logo: String?,
    val overview: String?,
    val badges: Badges,
    val inLibrary: Boolean,
    val keptAt: Long?,
    val resume: HubResume?,
    val nextEpisode: NextEpisode?,
    /** Art as the hub serves it; null when it has none, and then the Cinemeta lookup stands in. */
    val poster: String? = null,
    val backdrop: String? = null,
) {
    val isSeries: Boolean get() = type == "series"
}

data class Catalog(val type: String, val id: String, val name: String)

/** One copy of a title: where to fetch it and what it is. */
data class HubStream(
    val url: String,
    val name: String,
    val badges: Badges,
    val source: String?,
    val headers: Map<String, String>,
    val description: String? = null,
    val sizeGb: Double? = null,
    /** What the copy was made from: "remux", "web", "bluray". */
    val origin: String? = null,
) {
    /**
     * "2160p · DV · Atmos · remux · 58.2 GB · 62 Mbps": the hub often sends no name, and copies told
     * apart only by a name would all look the same.
     */
    fun label(): String {
        val size = sizeGb?.let { String.format(Locale.US, "%.1f GB", it) }
        val rate = badges.mbps?.let { "${it.roundToInt()} Mbps" }
        return listOfNotNull(badges.res, badges.hdr, badges.audio, origin, size, rate).joinToString(" · ").ifEmpty { name }
    }
}

/**
 * Reads the hub's JSON. Lenient on the envelope (a bare array or one wrapped in an object), strict
 * on what an item can't do without: an id and a title. Anything else missing is just left out.
 */
object HubJson {
    /** Above this a size is bytes; below, it's already GB. */
    private const val BYTES_NOT_GB = 10_000.0
    fun items(text: String): List<HubItem> = list(text, "items", "metas", "results").mapNotNull(::item)

    fun item(json: JSONObject): HubItem? {
        val id = json.text("id") ?: return null
        val title = json.text("title") ?: json.text("name") ?: return null
        return HubItem(
            id = id,
            type = json.text("type") ?: "movie",
            title = title,
            year = json.int("year") ?: json.text("year")?.take(4)?.toIntOrNull(),
            runtimeMin = json.int("runtimeMin"),
            genres = json.optJSONArray("genres")?.let { array -> (0 until array.length()).mapNotNull { array.optString(it).ifEmpty { null } } }.orEmpty(),
            logo = json.text("logo"),
            overview = json.text("overview") ?: json.text("description"),
            badges = badges(json.optJSONObject("badges")),
            inLibrary = json.optBoolean("inLibrary"),
            keptAt = time(json.opt("keptAt")),
            resume = json.optJSONObject("resume")?.let(::resume),
            nextEpisode = json.optJSONObject("nextEpisode")?.let { next ->
                val season = next.int("s") ?: return@let null
                val episode = next.int("e") ?: return@let null
                NextEpisode(season, episode, next.text("title"))
            },
            poster = json.text("poster"),
            backdrop = json.text("backdrop") ?: json.text("background"),
        )
    }

    fun resume(json: JSONObject): HubResume? {
        val position = json.optLong("positionMs", -1)
        if (position < 0) return null
        return HubResume(position, json.optLong("durationMs"), time(json.opt("updatedAt")) ?: 0L)
    }

    fun catalogs(text: String): List<Catalog> = list(text, "catalogs").mapNotNull { json ->
        val type = json.text("type") ?: return@mapNotNull null
        val id = json.text("id") ?: return@mapNotNull null
        Catalog(type, id, json.text("name") ?: id)
    }

    /** Streams, best first, as the hub ranked them; ones without a URL can't be played and are dropped. */
    fun streams(text: String): List<HubStream> = list(text, "streams").mapNotNull(::stream)

    fun stream(json: JSONObject): HubStream? {
        val url = json.text("url") ?: return null
        val badges = badges(json.optJSONObject("badges"))
        // The live hub sends source as {addon, name}; the addon's host is the readable part.
        val source = json.optJSONObject("source")?.text("addon") ?: json.text("source")
        val name = json.text("name") ?: json.text("title") ?: badges.all().joinToString(" · ").ifEmpty { source ?: url }
        // Size arrives as bytes or as GB, at the top level or among the badges.
        val badgeJson = json.optJSONObject("badges")
        val size = listOf(badgeJson?.opt("sizeGb"), badgeJson?.opt("size"), json.opt("sizeGb"), json.opt("size"))
            .firstNotNullOfOrNull { (it as? Number)?.toDouble() }
            ?.let { if (it > BYTES_NOT_GB) it / 1e9 else it }
        return HubStream(url, name, badges, source, headers(json.optJSONObject("headers")), json.text("description"), size, badgeJson?.label("source"))
    }

    private fun badges(json: JSONObject?) = Badges(
        json?.label("res"), json?.label("hdr"), json?.label("audio"), json?.label("cut"),
        json?.opt("mbps").let { (it as? Number)?.toDouble() ?: (it as? String)?.toDoubleOrNull() },
    )

    /** GET /presets: an object keyed by preset name, or a list of presets with a "name". */
    fun presets(text: String): Map<String, Quality.Cap> {
        val trimmed = text.trim()
        fun cap(json: JSONObject) = Quality.Cap(
            json.opt("max_mbps").let { (it as? Number)?.toDouble() },
            json.text("max_res"),
        )
        if (trimmed.startsWith("[")) return list(trimmed).mapNotNull { json -> json.text("name")?.let { it to cap(json) } }.toMap()
        val json = JSONObject(trimmed).let { it.optJSONObject("presets") ?: it }
        return json.keys().asSequence().mapNotNull { key -> json.optJSONObject(key)?.let { key to cap(it) } }.toMap()
    }

    private fun headers(json: JSONObject?): Map<String, String> {
        json ?: return emptyMap()
        val headers = LinkedHashMap<String, String>()
        json.keys().forEach { key -> json.text(key)?.let { headers[key] = it } }
        return headers
    }

    /** The objects of a top-level array, or of the first of [keys] that holds one. */
    private fun list(text: String, vararg keys: String): List<JSONObject> {
        val trimmed = text.trim()
        val array = if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            val json = JSONObject(trimmed)
            keys.firstNotNullOfOrNull { json.optJSONArray(it) } ?: JSONArray()
        }
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    /**
     * Epoch milliseconds from a number (seconds when it is too small to be milliseconds) or an
     * ISO-8601 UTC string. shortcut: ISO offsets other than Z are read as UTC; fine while the hub writes UTC.
     */
    fun time(value: Any?): Long? = when (value) {
        is Number -> value.toLong().let { if (it in 1 until 100_000_000_000L) it * 1000 else it }
        is String -> value.toLongOrNull()?.let { time(it) } ?: try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(value.take(19))?.time
        } catch (e: Exception) {
            null
        }
        else -> null
    }

    private fun JSONObject.text(name: String): String? = if (isNull(name)) null else optString(name).trim().ifEmpty { null }
    /** A badge the hub sends as a string or as a list ("hdr":["DV","HDR10"]). */
    private fun JSONObject.label(name: String): String? {
        val array = optJSONArray(name) ?: return text(name)
        return (0 until array.length()).mapNotNull { array.optString(it).trim().ifEmpty { null } }.joinToString(" / ").ifEmpty { null }
    }
    private fun JSONObject.int(name: String): Int? = if (has(name) && !isNull(name) && opt(name) is Number) optInt(name) else null
}
