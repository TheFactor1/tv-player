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

    fun load(imdbId: String, season: Int?, episode: Int?, listener: Listener) {
        if (!enabled) return
        val ticket = ++generation
        executor.execute {
            try {
                val cast = lookUp(imdbId, season, episode) ?: return@execute
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

    private fun lookUp(imdbId: String, season: Int?, episode: Int?): List<CastMember>? {
        val found = getJson("$API/find/$imdbId?external_source=imdb_id")
        val credits = if (season != null && episode != null) {
            val show = found.optJSONArray("tv_results")?.optJSONObject(0)?.optInt("id") ?: return null
            getJson("$API/tv/$show/season/$season/episode/$episode/credits")
        } else {
            val movie = found.optJSONArray("movie_results")?.optJSONObject(0)?.optInt("id") ?: return null
            getJson("$API/movie/$movie/credits")
        }
        val regulars = credits.optJSONArray("cast").members()
        val guests = credits.optJSONArray("guest_stars").members()
        return CastOrder.billing(regulars, guests, LIMIT)
    }

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
}
