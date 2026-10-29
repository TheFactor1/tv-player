package com.thefactor1.player.meta

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.thefactor1.player.hub.HubClient
import com.thefactor1.player.hub.HubVault
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** One credited person: who they are, who they play, and a headshot on TMDB's image server. */
data class CastMember(
    val name: String,
    val character: String?,
    /** An https://image.tmdb.org/ address, or null. */
    val photoUrl: String?,
    /** TMDB's billing order; guest stars are counted after the regulars. */
    val order: Int,
)

/**
 * The cast for X-Ray, from the paired hub (`GET /browse/cast/{type}/{id}`), keyed by the IMDb id
 * Cinemeta matched. The TMDB key lives on the hub, never in the app: without a hub, or with a hub
 * that has no key, every lookup just returns nothing. Like the artwork, it runs on one low-priority
 * thread and answers on the main thread: the list first, then each headshot as it arrives.
 */
class CastLoader(context: Context) {

    interface Listener {
        fun onCast(cast: List<CastMember>)
        fun onPhoto(member: CastMember, bitmap: Bitmap)
    }

    private val cacheDir = File(context.cacheDir, "cast")
    private val vault = HubVault(context)
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "cast").apply { isDaemon = true }
    }
    @Volatile private var generation = 0

    fun cancel() {
        generation++
    }

    fun load(imdbId: String, season: Int?, episode: Int?, listener: Listener) {
        val ticket = ++generation
        executor.execute {
            try {
                val cast = lookUp(imdbId, season, episode) ?: return@execute
                if (cast.isEmpty() || ticket != generation) return@execute
                post(ticket) { listener.onCast(cast) }
                for (member in cast) {
                    if (ticket != generation) return@execute
                    val url = member.photoUrl ?: continue
                    photo(url)?.let { bitmap -> post(ticket) { listener.onPhoto(member, bitmap) } }
                }
            } catch (e: Exception) {
                // No hub, offline, or an answer we can't read: X-Ray simply has no one to show.
            }
        }
    }

    private fun lookUp(imdbId: String, season: Int?, episode: Int?): List<CastMember>? {
        val address = vault.address.ifEmpty { return null }
        val token = vault.token.ifEmpty { return null }
        val type = if (season != null && episode != null) "series" else "movie"
        return members(HubClient(address, token).cast(type, imdbId))
    }

    private fun photo(url: String): Bitmap? {
        // Named after the image's own path on TMDB's server, so each face is fetched once.
        val file = File(cacheDir, url.substringAfterLast('/').ifEmpty { return null })
        if (!file.exists()) {
            cacheDir.mkdirs()
            val partial = File(cacheDir, "${file.name}.part")
            val connection = open(url)
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

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }

    private fun post(ticket: Int, block: () -> Unit) {
        main.post { if (ticket == generation) block() }
    }

    companion object {
        private const val TIMEOUT_MS = 5_000
        private const val LIMIT = 8
        private const val PHOTOS = "https://image.tmdb.org/"

        /** The hub's `{"cast": [{name, character, photo}]}`, in its order; photos from anywhere but TMDB are dropped. */
        fun members(json: String): List<CastMember> {
            val array = JSONObject(json).optJSONArray("cast") ?: return emptyList()
            val people = (0 until array.length()).mapNotNull { i ->
                val person = array.optJSONObject(i) ?: return@mapNotNull null
                val name = person.optString("name").trim().ifEmpty { return@mapNotNull null }
                CastMember(
                    name = name,
                    character = person.optString("character").trim().takeIf { it.isNotEmpty() && !person.isNull("character") },
                    photoUrl = person.optString("photo").takeIf { !person.isNull("photo") && it.startsWith(PHOTOS) },
                    order = i,
                )
            }
            return CastOrder.billing(people, emptyList(), LIMIT)
        }
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
