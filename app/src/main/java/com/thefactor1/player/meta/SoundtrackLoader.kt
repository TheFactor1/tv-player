package com.thefactor1.player.meta

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Finds a film's soundtrack album and its track list on MusicBrainz, which is free and needs no
 * key. Two requests at most, a second apart as MusicBrainz asks, on a low-priority thread; a film
 * with no clear match simply has no soundtrack.
 */
class SoundtrackLoader(private val userAgent: String) {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "soundtrack").apply { isDaemon = true }
    }
    @Volatile private var generation = 0

    fun cancel() {
        generation++
    }

    fun load(film: String, year: Int?, done: (Soundtrack) -> Unit) {
        val ticket = ++generation
        executor.execute {
            val found = try {
                find(film, year) { ticket == generation }
            } catch (e: Exception) {
                Log.i(TAG, "no soundtrack for $film: $e")
                null
            }
            Log.i(TAG, "$film ($year) -> ${found?.album ?: "none"}")
            if (found != null) main.post { if (ticket == generation) done(found) }
        }
    }

    private fun find(film: String, year: Int?, wanted: () -> Boolean): Soundtrack? {
        val query = URLEncoder.encode("releasegroup:\"${film.replace("\"", "")}\" AND secondarytype:soundtrack", "UTF-8")
        val search = getJson("$API/release-group/?query=$query&fmt=json&limit=$CANDIDATES") ?: return null
        val groups = search.optJSONArray("release-groups") ?: return null
        val byId = (0 until groups.length()).mapNotNull { groups.optJSONObject(it) }.associateBy { it.optString("id") }
        val candidates = byId.values.map {
            SoundtrackMatch.Candidate(it.optString("id"), it.optString("title"), it.optString("first-release-date").take(4).toIntOrNull(), it.optInt("score"))
        }
        val group = SoundtrackMatch.pick(candidates, film, year)?.let { byId[it.id] } ?: return null

        // The search lists the group's releases; the first official one gives the track list.
        val releases = group.optJSONArray("releases")
        val release = (0 until (releases?.length() ?: 0)).mapNotNull { releases?.optJSONObject(it) }
            .sortedByDescending { it.optString("status") == "Official" }
            .firstOrNull()?.optString("id")
        var tracks = emptyList<Soundtrack.Track>()
        if (release != null && wanted()) {
            Thread.sleep(POLITE_GAP_MS)
            val media = getJson("$API/release/$release?inc=recordings&fmt=json")?.optJSONArray("media")
            tracks = (0 until (media?.length() ?: 0)).flatMap { m ->
                val list = media?.optJSONObject(m)?.optJSONArray("tracks")
                (0 until (list?.length() ?: 0)).mapNotNull { t ->
                    val track = list?.optJSONObject(t) ?: return@mapNotNull null
                    val title = track.optString("title").ifEmpty { return@mapNotNull null }
                    Soundtrack.Track(title, track.optLong("length").takeIf { it > 0 })
                }
            }
        }
        val credit = group.optJSONArray("artist-credit")
        val artist = (0 until (credit?.length() ?: 0)).joinToString("") { i ->
            val part = credit?.optJSONObject(i)
            part?.optString("name").orEmpty() + part?.optString("joinphrase").orEmpty()
        }.trim().ifEmpty { null }
        return Soundtrack(
            group.optString("id"),
            group.optString("title"),
            artist,
            group.optString("first-release-date").take(4).toIntOrNull(),
            tracks,
        )
    }

    private fun getJson(url: String): JSONObject? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            // MusicBrainz turns away requests that don't say who they are.
            setRequestProperty("User-Agent", "$userAgent ( $CONTACT )")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /** The album's front cover, small, for the phone; Cover Art Archive redirects to the image. */
        fun coverUrl(id: String) = "https://coverartarchive.org/release-group/$id/front-250"

        private const val TAG = "Soundtrack"
        private const val API = "https://musicbrainz.org/ws/2"
        private const val CONTACT = "https://github.com/TheFactor1/tv-player"
        private const val CANDIDATES = 10
        private const val POLITE_GAP_MS = 1_100L
        private const val TIMEOUT_MS = 8_000
    }
}
