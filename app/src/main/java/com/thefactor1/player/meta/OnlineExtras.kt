package com.thefactor1.player.meta

import android.os.Handler
import android.os.Looper
import android.os.Process
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Skip times and subtitles for a film or episode Cinemeta has matched, both keyed by its IMDb id.
 * Lookups run one at a time on a low-priority thread, and a lookup that fails just returns nothing:
 * the player works the same without them.
 *
 * Skip times come from IntroDB (introdb.app), which is community-timed and free to read. Subtitles
 * come from Stremio's OpenSubtitles addon, which needs no account and serves UTF-8 SRT.
 */
class OnlineExtras {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "online-extras").apply { isDaemon = true }
    }
    @Volatile private var generation = 0

    /** Forgets any lookup still running, e.g. when a different video starts. */
    fun cancel() {
        generation++
    }

    fun skipSegments(imdbId: String, season: Int?, episode: Int?, done: (List<SkipSegment>) -> Unit) {
        val query = if (season != null && episode != null) "season=$season&episode=$episode" else "is_movie=true"
        fetch("$INTRODB/segments?imdb_id=$imdbId&$query", ::parseSegments, done)
    }

    fun subtitles(imdbId: String, season: Int?, episode: Int?, done: (List<OnlineSubtitle>) -> Unit) {
        val path = if (season != null && episode != null) "series/$imdbId:$season:$episode" else "movie/$imdbId"
        fetch("$OPENSUBTITLES/subtitles/$path.json", ::parseSubtitles, done)
    }

    private fun <T> fetch(url: String, parse: (JSONObject) -> T, done: (T) -> Unit) {
        val ticket = generation
        executor.execute {
            if (ticket != generation) return@execute
            val result = try {
                getJson(url)?.let(parse)
            } catch (e: Exception) {
                null
            } ?: return@execute
            main.post { if (ticket == generation) done(result) }
        }
    }

    private fun parseSegments(json: JSONObject): List<SkipSegment> = listOfNotNull(
        segment(json.optJSONObject("recap"), SkipSegment.Kind.RECAP),
        segment(json.optJSONObject("intro"), SkipSegment.Kind.INTRO),
        segment(json.optJSONObject("outro"), SkipSegment.Kind.CREDITS),
    )

    private fun segment(json: JSONObject?, kind: SkipSegment.Kind): SkipSegment? {
        json ?: return null
        val start = millis(json, "start_ms", "start_sec") ?: return null
        return SkipSegment(kind, start, millis(json, "end_ms", "end_sec"))
    }

    private fun millis(json: JSONObject, msKey: String, secKey: String): Long? = when {
        json.has(msKey) && !json.isNull(msKey) -> json.optLong(msKey)
        json.has(secKey) && !json.isNull(secKey) -> (json.optDouble(secKey) * 1000).toLong()
        else -> null
    }

    private fun parseSubtitles(json: JSONObject): List<OnlineSubtitle> {
        val list = json.optJSONArray("subtitles") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val item = list.optJSONObject(i) ?: return@mapNotNull null
            val url = item.optString("url").takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            OnlineSubtitle(url, SubtitleLanguages.twoLetter(item.optString("lang")))
        }
    }

    private fun getJson(url: String): JSONObject? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val INTRODB = "https://api.introdb.app"
        const val OPENSUBTITLES = "https://opensubtitles-v3.strem.io"
        const val TIMEOUT_MS = 8_000
    }
}
