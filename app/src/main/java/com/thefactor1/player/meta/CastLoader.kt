package com.thefactor1.player.meta

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** One credited person: who they are, who they play, and a headshot path on TMDB's image server. */
data class CastMember(
    val name: String,
    val character: String?,
    val photoPath: String?,
    /** TMDB's billing order; guest stars are counted after the regulars. */
    val order: Int,
)

/**
 * The cast for X-Ray, from TMDB, keyed by the IMDb id Cinemeta matched. Without an API key (none
 * was built in) every lookup just returns nothing. Like the artwork, it runs on one low-priority
 * thread and answers on the main thread: the list first, then each headshot as it arrives.
 */
class CastLoader(context: Context, private val apiKey: String) {

    interface Listener {
        fun onCast(cast: List<CastMember>)
        fun onPhoto(member: CastMember, bitmap: Bitmap)
    }

    private val cacheDir = File(context.cacheDir, "cast")
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "cast").apply { isDaemon = true }
    }
    @Volatile private var generation = 0

    val enabled: Boolean get() = apiKey.isNotBlank()

    fun cancel() {
        generation++
    }

    /** [title] is the name Cinemeta gave; TMDB's entry for the id has to agree before its cast is shown. */
    fun load(imdbId: String, title: String?, season: Int?, episode: Int?, listener: Listener) {
        if (!enabled) return
        val ticket = ++generation
        executor.execute {
            try {
                val cast = lookUp(imdbId, title, season, episode) ?: return@execute
                if (cast.isEmpty() || ticket != generation) return@execute
                post(ticket) { listener.onCast(cast) }
                for (member in cast) {
                    if (ticket != generation) return@execute
                    val path = member.photoPath ?: continue
                    photo(path)?.let { bitmap -> post(ticket) { listener.onPhoto(member, bitmap) } }
                }
            } catch (e: Exception) {
                // Offline, no key, or an answer we can't read: X-Ray simply has no one to show.
            }
        }
    }

    private fun lookUp(imdbId: String, title: String?, season: Int?, episode: Int?): List<CastMember>? {
        val found = getJson("$API/find/$imdbId?external_source=imdb_id")
        val series = season != null && episode != null
        val entry = found.optJSONArray(if (series) "tv_results" else "movie_results")?.optJSONObject(0) ?: return null
        // A wrong cast is worse than none: the entry TMDB filed under this id must carry the same name.
        val names = listOf("name", "original_name", "title", "original_title").map { entry.optString(it) }
        if (!CastOrder.sameTitle(names, title)) return null
        val id = entry.optInt("id").takeIf { it > 0 } ?: return null
        var credits = if (series) getJson("$API/tv/$id/season/$season/episode/$episode/credits") else getJson("$API/movie/$id/credits")
        // Brand-new episodes often have no credits yet; the show's own cast stands in.
        if (series && credits.optJSONArray("cast").isNullOrEmpty() && credits.optJSONArray("guest_stars").isNullOrEmpty()) {
            credits = getJson("$API/tv/$id/credits")
        }
        val regulars = credits.optJSONArray("cast").members()
        val guests = credits.optJSONArray("guest_stars").members()
        return CastOrder.billing(regulars, guests, LIMIT)
    }

    private fun JSONArray?.isNullOrEmpty() = this == null || length() == 0

    private fun JSONArray?.members(): List<CastMember> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            val person = optJSONObject(i) ?: return@mapNotNull null
            val name = person.optString("name").ifEmpty { return@mapNotNull null }
            CastMember(
                name = name,
                character = person.optString("character").ifEmpty { null },
                photoPath = person.optString("profile_path").takeIf { it.startsWith("/") },
                order = person.optInt("order", i),
            )
        }
    }

    private fun photo(path: String): Bitmap? {
        val file = File(cacheDir, path.trimStart('/'))
        if (!file.exists()) {
            cacheDir.mkdirs()
            val partial = File(cacheDir, "${file.name}.part")
            val connection = open("$IMAGES$path")
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
                connection.inputStream.use { input -> partial.outputStream().use { input.copyTo(it) } }
            } finally {
                connection.disconnect()
            }
            if (!partial.renameTo(file)) return null
        }
        return BitmapFactory.decodeFile(file.path) ?: run { file.delete(); null }
    }

    private fun getJson(url: String): JSONObject {
        val separator = if ('?' in url) '&' else '?'
        val connection = open("$url${separator}api_key=$apiKey")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return JSONObject()
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }

    private fun post(ticket: Int, block: () -> Unit) {
        main.post { if (ticket == generation) block() }
    }

    private companion object {
        const val API = "https://api.themoviedb.org/3"
        // 185 px wide is plenty for a face drawn at 56 dp.
        const val IMAGES = "https://image.tmdb.org/t/p/w185"
        const val TIMEOUT_MS = 5_000
        const val LIMIT = 8
    }
}

/** Who to show first. Kept apart from the network code so it can be tested on its own. */
object CastOrder {
    /** Regulars by billing, then an episode's guest stars, without repeating anyone. */
    fun billing(regulars: List<CastMember>, guests: List<CastMember>, limit: Int): List<CastMember> {
        val seen = HashSet<String>()
        val ordered = regulars.sortedBy { it.order } + guests.sortedBy { it.order }
        return ordered.filter { seen.add(it.name) }.take(limit)
    }

    /**
     * Whether any of TMDB's names for the entry is the title we're playing, ignoring case and
     * punctuation; one may extend the other ("Lanterns" / "DC's Lanterns"). No title, no check.
     */
    fun sameTitle(names: List<String>, title: String?): Boolean {
        val want = normalise(title ?: return true)
        if (want.isEmpty()) return true
        return names.map(::normalise).filter { it.isNotEmpty() }.any { have ->
            have == want || (minOf(have.length, want.length) >= MIN_CONTAINED && (have.contains(want) || want.contains(have)))
        }
    }

    private fun normalise(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

    private const val MIN_CONTAINED = 4
}
