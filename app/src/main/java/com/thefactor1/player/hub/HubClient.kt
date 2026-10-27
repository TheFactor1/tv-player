package com.thefactor1.player.hub

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/** A playable answer from `/play`: the stream right now, what to send with it, and what it is. */
data class PlayInfo(val url: String, val headers: Map<String, String>, val badges: Badges = Badges(null, null, null))

/** The hub refused or couldn't be reached; [message] is the hub's own reason when it gave one. */
open class HubException(message: String) : IOException(message)

/** The hub answered 401: this box's token is missing, wrong or rotated, and it has to pair again. */
class HubUnauthorized : HubException("Not paired")

/** A pairing the hub is holding for this TV: the code to show, and the phone's setup page if it has one. */
data class PairRequest(val id: String, val code: String, val setupUrl: String?)

sealed class PairStatus {
    object Waiting : PairStatus()
    object Expired : PairStatus()
    class Paired(val token: String) : PairStatus()
}

/**
 * Talks to the hub (DATAFLOW.md, "Hub API"). Every call but `/health` carries the pairing token.
 * Every call runs on a background thread and answers on the main thread; GETs are kept for a minute
 * so tab switches and Back don't refetch.
 */
class HubClient(baseUrl: String, private val token: String = "") {
    private val base = baseUrl.trimEnd('/')
    private val main = Handler(Looper.getMainLooper())

    /** Called on the main thread when the hub refuses the token, before the call's own answer. */
    var onUnauthorized: (() -> Unit)? = null

    /** Runs [call] off the main thread and hands its result, or the failure, back on it. */
    fun <T> run(call: HubClient.() -> T, done: (Result<T>) -> Unit) {
        executor.execute {
            val result = runCatching { call() }
            main.post {
                if (result.exceptionOrNull() is HubUnauthorized) onUnauthorized?.invoke()
                done(result)
            }
        }
    }

    /**
     * The token for [code]: six words from the hub's screen are exchanged at `/pair`; anything
     * else is taken to be the token itself. Either way it is checked before it is trusted.
     */
    fun pair(code: String): String {
        val words = code.trim().split(Regex("""\s+"""))
        val token = if (words.size == PAIR_WORDS) {
            JSONObject(send("POST", "/pair", JSONObject().put("words", words.joinToString(" ").lowercase()), auth = false)).optString("token")
        } else {
            code.trim()
        }
        if (token.isEmpty()) throw HubUnauthorized()
        HubClient(base, token).presets()
        return token
    }

    /** Asks the hub to pair this TV: it answers with a code to show and, often, a page for the phone. */
    fun requestPairing(name: String): PairRequest {
        val json = JSONObject(send("POST", "/pair/request", JSONObject().put("name", name), auth = false))
        val id = json.optString("requestId").ifEmpty { json.optString("id") }
        val code = json.optString("code")
        if (id.isEmpty() || code.isEmpty()) throw HubException("No pairing code")
        return PairRequest(id, code, json.optString("setupUrl").ifEmpty { null })
    }

    /** Where [request] stands: still waiting for approval, approved with a token, or gone. */
    fun pairingStatus(request: PairRequest): PairStatus {
        val connection = open("$base/pair/request/${encode(request.id)}", auth = false)
        try {
            return when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_ACCEPTED -> PairStatus.Waiting
                // A hub restarted since the code was shown has forgotten it: the same as expired.
                HttpURLConnection.HTTP_GONE, HttpURLConnection.HTTP_NOT_FOUND -> PairStatus.Expired
                HttpURLConnection.HTTP_OK -> connection.inputStream.bufferedReader().use { it.readText() }
                    .let { JSONObject(it).optString("token") }.ifEmpty { null }?.let { PairStatus.Paired(it) } ?: PairStatus.Waiting
                else -> throw HubException("HTTP $code")
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Throws unless a hub answers at this address; a hub whose addons are down still counts. */
    fun health() {
        get("/health", cached = false, auth = false)
    }
    fun library(collection: String) = HubJson.items(get("/library?collection=$collection"))
    fun newReleases(skip: Int) = HubJson.items(get("/new?skip=$skip"))
    fun catalogs() = HubJson.catalogs(get("/browse/catalogs"))
    fun catalog(catalog: Catalog, skip: Int) =
        HubJson.items(get("/browse/catalog/${catalog.type}/${encode(catalog.id)}?skip=$skip"), defaultType = catalog.type)
    fun meta(item: HubItem): HubItem? = JSONObject(get("/browse/meta/${item.type}/${item.id}")).let { HubJson.item(it.optJSONObject("meta") ?: it) }
    fun streams(item: HubItem, episode: NextEpisode?) = HubJson.streams(get("/browse/streams/${item.type}/${item.id}${path(episode)}", cached = false))
    fun search(query: String) = HubJson.items(get("/search?q=${encode(query)}"))

    fun presets() = HubJson.presets(get("/presets"))

    /** The best stream right now under [query] ("preset=fast", "max_mbps=43.4"; see [Quality.query]). */
    fun play(item: HubItem, episode: NextEpisode?, query: String): PlayInfo {
        val stream = HubJson.stream(JSONObject(get("/play/${item.id}${path(episode)}?json=1&$query", cached = false)))
            ?: throw HubException("No stream")
        return PlayInfo(stream.url, stream.headers, stream.badges)
    }

    /**
     * Auto's link test: the first [PROBE_BYTES] of the best uncapped stream for [item], timed, in
     * Mbps. Stops early after [PROBE_MS] on a slow link and works from what arrived.
     */
    fun probe(item: HubItem, episode: NextEpisode?): Double {
        val info = play(item, episode, "preset=max")
        // Cleartext only to the paired hub's own host; any other stream must be HTTPS to be probed.
        if (!info.url.startsWith("https://") && URL(info.url).host != URL(base).host) throw HubException("Probe needs HTTPS")
        val connection = open(info.url, auth = false)
        connection.setRequestProperty("Accept", "*/*")
        info.headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
        connection.setRequestProperty("Range", "bytes=0-${PROBE_BYTES - 1}")
        try {
            if (connection.responseCode !in 200..299) throw HubException("Probe HTTP ${connection.responseCode}")
            // Timed from the first byte: connect, TLS and the debrid's redirects aren't the link's speed.
            val started = System.nanoTime()
            var total = 0L
            val buffer = ByteArray(64 * 1024)
            connection.inputStream.use { input ->
                while (total < PROBE_BYTES && System.nanoTime() - started < PROBE_MS * 1_000_000) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                }
            }
            val seconds = (System.nanoTime() - started) / 1e9
            if (total < MIN_PROBE_BYTES || seconds <= 0) throw HubException("Probe too short")
            return total * 8 / seconds / 1e6
        } finally {
            connection.disconnect()
        }
    }

    /** The shared position, or null when no front end has saved one. */
    fun resume(item: HubItem, episode: NextEpisode?): HubResume? = try {
        // Nothing stored comes back as {} (or null from older hubs).
        val text = get("/resume/${item.id}${path(episode)}", cached = false).trim()
        if (text.isEmpty() || text == "null") null else HubJson.resume(JSONObject(text))
    } catch (e: HubException) {
        if (e is HubUnauthorized) throw e
        null
    }

    fun saveResume(id: String, episode: NextEpisode?, positionMs: Long, durationMs: Long) {
        send("PUT", "/resume/$id${path(episode)}", JSONObject().put("positionMs", positionMs).put("durationMs", durationMs))
        clearCache()
    }

    fun keep(item: HubItem): HubItem {
        val text = send("POST", "/keep", JSONObject().put("id", item.id).put("type", item.type))
        clearCache()
        val kept = runCatching { JSONObject(text).let { HubJson.item(it.optJSONObject("item") ?: it) } }.getOrNull()
        return (kept ?: item).copy(inLibrary = true)
    }

    private fun path(episode: NextEpisode?) = episode?.let { "/${it.season}/${it.episode}" }.orEmpty()

    private fun get(path: String, cached: Boolean = true, auth: Boolean = true): String {
        val url = base + path
        if (cached) {
            synchronized(cache) { cache[url] }?.takeIf { System.currentTimeMillis() - it.first < CACHE_MS }?.let { return it.second }
        }
        val text = read(open(url, auth))
        if (cached) synchronized(cache) { cache[url] = System.currentTimeMillis() to text }
        return text
    }

    private fun send(method: String, path: String, body: JSONObject, auth: Boolean = true): String {
        val connection = open(base + path, auth)
        connection.requestMethod = method
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toString().toByteArray()) }
        return read(connection)
    }

    /** [auth] sends the token; it never goes anywhere but the hub. */
    private fun open(url: String, auth: Boolean) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
        // A redirect would carry the token along to wherever it points; streams (no token) may follow theirs.
        instanceFollowRedirects = !auth
        setRequestProperty("Accept", "application/json")
        if (auth && token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
    }

    private fun read(connection: HttpURLConnection): String {
        try {
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) throw HubUnauthorized()
            if (code !in 200..299) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val reason = runCatching { JSONObject(error).optString("reason") }.getOrNull()?.ifEmpty { null }
                throw HubException(reason ?: "HTTP $code")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    companion object {
        private const val TIMEOUT_MS = 8_000
        private const val CACHE_MS = 60_000L
        private const val PROBE_BYTES = 10L * 1024 * 1024
        private const val MIN_PROBE_BYTES = 512L * 1024
        private const val PROBE_MS = 8_000L
        private const val PAIR_WORDS = 6
        // shortcut: one process-wide cache keyed by URL, cleared whole on any write; fine for one hub and a few hundred titles.
        private val cache = HashMap<String, Pair<Long, String>>()
        private val executor = Executors.newFixedThreadPool(3) { Thread(it, "hub").apply { isDaemon = true } }

        fun clearCache() = synchronized(cache) { cache.clear() }

        /** "192.168.50.10:8097" becomes "http://192.168.50.10:8097"; null when it isn't an address at all. */
        fun normalize(input: String): String? {
            val text = input.trim().trimEnd('/')
            if (text.isEmpty() || text.any { it.isWhitespace() }) return null
            val withScheme = if ("://" in text) text else "http://$text"
            val scheme = withScheme.substringBefore("://").lowercase()
            if (scheme != "http" && scheme != "https") return null
            val host = withScheme.substringAfter("://").substringBefore('/').substringBefore(':')
            return if (host.isEmpty()) null else withScheme
        }
    }
}
