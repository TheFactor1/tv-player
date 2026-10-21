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
import java.util.concurrent.Executors

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
 * Looks up the film being played and fetches its backdrop and title logo, on one low-priority
 * thread so it never competes with playback. Answers arrive on the main thread in stages
 * (details, then backdrop, then logo), and a lookup that is cancelled or fails just stops:
 * without art the screens fall back to text on black.
 */
class ArtworkLoader(context: Context) {

    interface Listener {
        fun onDetails(details: MetaDetails)
        fun onBackdrop(bitmap: Bitmap)
        fun onLogo(bitmap: Bitmap)
    }

    private val cacheDir = File(context.cacheDir, "artwork")
    private val screenWidth = context.resources.displayMetrics.widthPixels
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "artwork").apply { isDaemon = true }
    }
    @Volatile private var generation = 0

    fun load(query: MetaQuery, listener: Listener) {
        val ticket = ++generation
        executor.execute {
            try {
                lookUp(query, ticket, listener)
            } catch (e: Exception) {
                // Offline, blocked, or an answer we can't read: the screens keep their text-only look.
            }
        }
    }

    fun cancel() {
        generation++
    }

    private fun lookUp(query: MetaQuery, ticket: Int, listener: Listener) {
        val types = if (query.isSeries) listOf("series") else listOf("movie", "series")
        var found: Pair<String, MetaCandidate>? = null
        for (type in types) {
            if (ticket != generation) return
            val candidates = search(type, query.name)
            MetaMatch.pick(query, candidates)?.let { found = type to it }
            if (found != null) break
        }
        val (type, match) = found ?: return
        if (!IMDB_ID.matches(match.id) || ticket != generation) return

        val meta = getJson("$CINEMETA/meta/$type/${match.id}.json").optJSONObject("meta") ?: return
        val details = details(meta, query).copy(imdbId = match.id)
        post(ticket) { listener.onDetails(details) }

        image(meta.optString("background").ifEmpty { "$METAHUB/background/medium/${match.id}/img" }, "${match.id}-bg", screenWidth)
            ?.let { bitmap -> post(ticket) { listener.onBackdrop(bitmap) } }
        image(meta.optString("logo").ifEmpty { "$METAHUB/logo/medium/${match.id}/img" }, "${match.id}-logo", screenWidth / 2)
            ?.let { bitmap -> post(ticket) { listener.onLogo(bitmap) } }
    }

    private fun search(type: String, name: String): List<MetaCandidate> {
        val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val metas = getJson("$CINEMETA/catalog/$type/top/search=$encoded.json").optJSONArray("metas") ?: return emptyList()
        return (0 until metas.length()).mapNotNull { i ->
            val meta = metas.optJSONObject(i) ?: return@mapNotNull null
            val id = meta.optString("imdb_id").ifEmpty { meta.optString("id") }
            val title = meta.optString("name")
            if (id.isEmpty() || title.isEmpty()) null else MetaCandidate(id, title, meta.optString("releaseInfo").ifEmpty { null })
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
            val video = meta.optJSONArray("videos")?.let { videos ->
                (0 until videos.length()).asSequence().mapNotNull { videos.optJSONObject(it) }.firstOrNull {
                    it.optInt("season", -1) == query.season && it.optInt("episode", it.optInt("number", -1)) == query.episode
                }
            }
            val episodeName = video?.optString("name")?.ifEmpty { null } ?: video?.optString("title")?.ifEmpty { null }
            episodeLine = listOfNotNull(label, episodeName).joinToString("  ·  ")
            video?.optString("overview")?.ifEmpty { null }?.let { description = it }
        }
        return MetaDetails(meta.optString("name").ifEmpty { query.name }, kicker, episodeLine, description)
    }

    /** A cached or freshly downloaded image, decoded no larger than it needs to be for [targetWidth]. */
    private fun image(url: String, key: String, targetWidth: Int): Bitmap? {
        if (!url.startsWith("https://")) return null
        val file = File(cacheDir, key)
        if (!file.exists()) {
            cacheDir.mkdirs()
            val partial = File(cacheDir, "$key.part")
            open(url).let { connection ->
                try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
                    connection.inputStream.use { input -> partial.outputStream().use { input.copyTo(it) } }
                } finally {
                    connection.disconnect()
                }
            }
            if (!partial.renameTo(file)) return null
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
        val IMDB_ID = Regex("""tt\d+""")
    }
}
