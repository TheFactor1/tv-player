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
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** The film or episode's details from Cinemeta, Stremio's own catalogue, so the art matches what Stremio shows. */
data class MetaDetails(
    val name: String,
    /** "2024 · 2 h 46 m · Science Fiction, Adventure" */
    val kicker: String?,
    /** "S1 E1 · Pilot" for an episode. */
    val episodeLine: String?,
    val description: String?,
    /** The IMDb id ("tt0903747"), which skip times and subtitle search are keyed by. */
    val imdbId: String? = null,
)

/**
 * Looks up the film being played and fetches its backdrop and title logo, on low-priority threads
 * so it never competes with playback. Answers arrive on the main thread as each one lands, and a
 * lookup that is cancelled or fails just stops: without art the screens fall back to text on black.
 *
 * It is built to put a picture up as soon as it can. The match and the film's details are kept on
 * disk, so a rewatch or the next episode needs no network at all. Once the IMDb id is known the
 * backdrop, logo and details are fetched side by side, and a small backdrop goes up first while
 * the full one is still on its way.
 */
class ArtworkLoader(context: Context) {

    interface Listener {
        fun onDetails(details: MetaDetails)
        /** Called up to twice: a small backdrop first, then the full one, never the other way round. */
        fun onBackdrop(bitmap: Bitmap)
        fun onLogo(bitmap: Bitmap)
    }

    private val cacheDir = File(context.cacheDir, "artwork")
    private val screenWidth = context.resources.displayMetrics.widthPixels
    private val main = Handler(Looper.getMainLooper())
    // A handful of short requests at once; a cached pool, since a lookup waits on its own searches.
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "artwork").apply { isDaemon = true }
    }
    @Volatile private var generation = 0
    /** How sharp the backdrop on screen is (0 none, 1 small, 2 full). Main thread only. */
    private var backdropShown = 0

    /** [imdbHint] is an id the source app sent with the stream, which skips the name search. */
    fun load(query: MetaQuery, listener: Listener, imdbHint: String? = null) {
        val ticket = ++generation
        backdropShown = 0
        background {
            lookUp(query, imdbHint?.takeIf { IMDB_ID.matches(it) }, ticket, listener)
        }
    }

    fun cancel() {
        generation++
    }

    private fun lookUp(query: MetaQuery, imdbHint: String?, ticket: Int, listener: Listener) {
        val cached = cachedMatch(query)
        if (cached == null) warmUp()
        val (type, id) = cached
            ?: imdbHint?.let { (if (query.isSeries) "series" else "movie") to it }
            ?: search(query, ticket)
            ?: return
        if (!IMDB_ID.matches(id) || ticket != generation) return

        // The art only needs the id, so it doesn't wait for the details.
        val meta = executor.submit(Callable {
            meta(type, id, query, ticket)
                ?: if (imdbHint != null && type == "movie") meta("series", id, query, ticket) else null
        })
        background { backdrop(id, meta, ticket, listener) }
        background {
            val logo = image("$METAHUB/logo/medium/$id/img", "$id-logo", screenWidth / 2, ticket)
                ?: meta.get()?.optString("logo")?.takeIf { it != "$METAHUB/logo/medium/$id/img" }
                    ?.let { image(it, "$id-logo", screenWidth / 2, ticket) }
            if (logo != null) post(ticket) { listener.onLogo(logo) }
        }

        val json = meta.get() ?: return
        val details = details(json, query).copy(imdbId = id)
        post(ticket) { listener.onDetails(details) }
    }

    private fun backdrop(id: String, meta: Future<JSONObject?>, ticket: Int, listener: Listener) {
        val full = "$id-bg"
        if (!File(cacheDir, full).exists()) {
            // The small one is a few tens of KB; it holds the screen while the full one downloads.
            background {
                image("$METAHUB/background/small/$id/img", "$id-bg-small", screenWidth / 2, ticket)
                    ?.let { showBackdrop(1, it, ticket, listener) }
            }
        }
        val bitmap = image("$METAHUB/background/medium/$id/img", full, screenWidth, ticket)
            ?: meta.get()?.optString("background")?.takeIf { it != "$METAHUB/background/medium/$id/img" }
                ?.let { image(it, full, screenWidth, ticket) }
            ?: return
        showBackdrop(2, bitmap, ticket, listener)
    }

    private fun showBackdrop(sharpness: Int, bitmap: Bitmap, ticket: Int, listener: Listener) {
        post(ticket) {
            if (sharpness > backdropShown) {
                backdropShown = sharpness
                listener.onBackdrop(bitmap)
            }
        }
    }

    /** Runs [block] on the pool, where a failure (offline, blocked, unreadable) just means no answer. */
    private fun background(block: () -> Unit) {
        executor.execute {
            try {
                block()
            } catch (e: Exception) {
                // The screens keep their text-only look.
            }
        }
    }

    // ---- Finding the film --------------------------------------------------------------------

    /** For a film, the movie and series catalogues are searched side by side and a movie wins. */
    private fun search(query: MetaQuery, ticket: Int): Pair<String, String>? {
        val types = if (query.isSeries) listOf("series") else listOf("movie", "series")
        val searches = types.map { type -> type to executor.submit(Callable { searchCatalog(type, query.name) }) }
        for ((type, results) in searches) {
            if (ticket != generation) return null
            val match = MetaMatch.pick(query, safely { results.get() }.orEmpty()) ?: continue
            if (!IMDB_ID.matches(match.id)) continue
            saveMatch(query, type, match.id)
            return type to match.id
        }
        return null
    }

    private fun searchCatalog(type: String, name: String): List<MetaCandidate> {
        val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val metas = getJson("$CINEMETA/catalog/$type/top/search=$encoded.json").optJSONArray("metas") ?: return emptyList()
        return (0 until metas.length()).mapNotNull { i ->
            val meta = metas.optJSONObject(i) ?: return@mapNotNull null
            val id = meta.optString("imdb_id").ifEmpty { meta.optString("id") }
            val title = meta.optString("name")
            if (id.isEmpty() || title.isEmpty()) null else MetaCandidate(id, title, meta.optString("releaseInfo").ifEmpty { null })
        }
    }

    private fun cachedMatch(query: MetaQuery): Pair<String, String>? {
        val line = readCache("match-${MetaMatch.cacheKey(query)}", MATCH_TTL_MS) ?: return null
        val type = line.substringBefore(' ')
        val id = line.substringAfter(' ', "")
        return if (type.isNotEmpty() && IMDB_ID.matches(id)) type to id else null
    }

    private fun saveMatch(query: MetaQuery, type: String, id: String) {
        writeCache("match-${MetaMatch.cacheKey(query)}", "$type $id")
    }

    /**
     * The film's Cinemeta entry, from disk when it's recent and (for a show) already lists the
     * episode being played; otherwise from the network, falling back to an older copy.
     */
    private fun meta(type: String, id: String, query: MetaQuery, ticket: Int): JSONObject? {
        val key = "meta-$type-$id"
        val fresh = readCache(key, META_TTL_MS)?.let { metaOf(it) }
        if (fresh != null && (query.episode == null || hasEpisode(fresh, query))) return fresh
        if (ticket != generation) return null
        val fetched = safely { getJson("$CINEMETA/meta/$type/$id.json").optJSONObject("meta") }
        if (fetched != null) {
            writeCache(key, fetched.toString())
            return fetched
        }
        return fresh ?: readCache(key, Long.MAX_VALUE)?.let { metaOf(it) }
    }

    private fun metaOf(text: String): JSONObject? = safely { JSONObject(text) }

    private fun hasEpisode(meta: JSONObject, query: MetaQuery): Boolean = episode(meta, query) != null

    private fun episode(meta: JSONObject, query: MetaQuery): JSONObject? {
        val videos = meta.optJSONArray("videos") ?: return null
        return (0 until videos.length()).asSequence().mapNotNull { videos.optJSONObject(it) }.firstOrNull {
            it.optInt("season", -1) == query.season && it.optInt("episode", it.optInt("number", -1)) == query.episode
        }
    }

    private fun details(meta: JSONObject, query: MetaQuery): MetaDetails {
        val year = meta.optString("releaseInfo").ifEmpty { meta.optString("year") }.ifEmpty { null }
        val genres = meta.optJSONArray("genres")?.strings()?.take(2)?.joinToString(", ")?.ifEmpty { null }
        val kicker = listOfNotNull(year, MetaMatch.runtime(meta.optString("runtime")), genres)
            .joinToString("  ·  ").ifEmpty { null }

        var episodeLine: String? = null
        var description = meta.optString("description").ifEmpty { null }
        if (query.season != null && query.episode != null) {
            val label = "S${query.season} E${query.episode}"
            val video = episode(meta, query)
            val episodeName = video?.optString("name")?.ifEmpty { null } ?: video?.optString("title")?.ifEmpty { null }
            episodeLine = listOfNotNull(label, episodeName).joinToString("  ·  ")
            video?.optString("overview")?.ifEmpty { null }?.let { description = it }
        }
        return MetaDetails(meta.optString("name").ifEmpty { query.name }, kicker, episodeLine, description)
    }

    /** A cached or freshly downloaded image, decoded no larger than it needs to be for [targetWidth]. */
    private fun image(url: String, key: String, targetWidth: Int, ticket: Int): Bitmap? {
        if (!url.startsWith("https://")) return null
        val file = File(cacheDir, key)
        if (!file.exists()) {
            if (ticket != generation) return null
            cacheDir.mkdirs()
            // Each download has its own partial file, since the small and full backdrops can race.
            val partial = File.createTempFile(key, ".part", cacheDir)
            try {
                val connection = open(url)
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    connection.disconnect()
                    return null
                }
                // Read to the end and closed, the connection goes back to the pool for the next request.
                connection.inputStream.use { input -> partial.outputStream().use { input.copyTo(it) } }
                if (!partial.renameTo(file) && !file.exists()) return null
            } finally {
                partial.delete()
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) {
            file.delete()
            return null
        }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun getJson(url: String): JSONObject {
        val connection = open(url)
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            return JSONObject()
        }
        return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
    }

    /**
     * Opens a connection to the image server while the name search is still out, so the first
     * image request skips the DNS lookup and TLS handshake. A HEAD, with nothing to read.
     */
    private fun warmUp() {
        background {
            val connection = open("$METAHUB/")
            connection.requestMethod = "HEAD"
            val stream = if (connection.responseCode >= 400) connection.errorStream else connection.inputStream
            stream?.close()
        }
    }

    private fun readCache(key: String, maxAgeMs: Long): String? {
        val file = File(cacheDir, key)
        if (!file.exists() || System.currentTimeMillis() - file.lastModified() > maxAgeMs) return null
        return safely { file.readText() }
    }

    private fun writeCache(key: String, text: String) {
        safely {
            cacheDir.mkdirs()
            val partial = File.createTempFile(key, ".part", cacheDir)
            partial.writeText(text)
            if (!partial.renameTo(File(cacheDir, key))) partial.delete()
        }
    }

    private inline fun <T> safely(block: () -> T?): T? = try {
        block()
    } catch (e: Exception) {
        null
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/json, image/*")
        }

    private fun post(ticket: Int, block: () -> Unit) {
        main.post { if (ticket == generation) block() }
    }

    private fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { optString(it).ifEmpty { null } }

    private companion object {
        const val CINEMETA = "https://v3-cinemeta.strem.io"
        const val METAHUB = "https://images.metahub.space"
        const val TIMEOUT_MS = 5_000
        const val MATCH_TTL_MS = 30L * 24 * 60 * 60 * 1000
        const val META_TTL_MS = 3L * 24 * 60 * 60 * 1000
        val IMDB_ID = Regex("""tt\d+""")
    }
}
