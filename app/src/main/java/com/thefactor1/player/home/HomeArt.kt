package com.thefactor1.player.home

import android.graphics.Bitmap
import android.widget.ImageView
import com.thefactor1.player.hub.HubClient
import com.thefactor1.player.hub.HubImages
import com.thefactor1.player.hub.HubItem

/**
 * Where a title's art comes from: the hub's own URLs first; if the hub gave none, its full
 * `/browse/meta` record (fetched once per title and kept); and last, Cinemeta's image server by
 * IMDb id, the same source the player's own art loader uses.
 */
object HomeArt {
    private const val METAHUB = "https://images.metahub.space"
    private val IMDB_ID = Regex("""tt\d+""")

    /** Fuller records fetched for titles the hub listed without art or a year, by id. */
    private val fuller = HashMap<String, HubItem>()
    private val asked = HashSet<String>()

    fun poster(item: HubItem): String? = item.poster ?: fuller[item.id]?.poster ?: metahub("poster", item.id)
    fun backdrop(item: HubItem): String? = item.backdrop ?: fuller[item.id]?.backdrop ?: metahub("background", item.id)
    /** None under Broadsheet: logos are drawn light for dark art, and the paper sets every title as a headline. */
    fun logo(item: HubItem): String? =
        if (Mercury.paper) null else item.logo ?: fuller[item.id]?.logo ?: metahub("logo", item.id)

    /** An episode's still: the hub's if it sent one, else Cinemeta's episode image by IMDb id, else the show's backdrop. */
    fun episode(item: HubItem, season: Int, episode: Int, thumbnail: String?): String? =
        thumbnail ?: if (IMDB_ID.matches(item.id)) "https://episodes.metahub.space/${item.id}/$season/$episode/w780.jpg" else backdrop(item)

    /** A show's episodes, once its full record has been fetched (see [fill]). */
    fun videos(item: HubItem) = fuller[item.id]?.videos.orEmpty()

    /** Fetches a show's full record once, for its seasons; [done] gets the merged item. */
    fun fill(hub: HubClient, item: HubItem, done: (HubItem) -> Unit) {
        if (!item.isSeries || item.videos.isNotEmpty() || fuller[item.id]?.videos?.isNotEmpty() == true) return
        if (!asked.add(item.id + ":videos")) return
        hub.run({ meta(item) }) { result ->
            result.getOrNull()?.let {
                fuller[item.id] = it
                done(merged(item))
            }
        }
    }

    /** [item] with whatever the fuller record adds (art, year, runtime, genres, overview). */
    fun merged(item: HubItem): HubItem {
        val more = fuller[item.id] ?: return item
        return item.copy(
            year = item.year ?: more.year,
            runtimeMin = item.runtimeMin ?: more.runtimeMin,
            genres = item.genres.ifEmpty { more.genres },
            overview = item.overview ?: more.overview,
            logo = item.logo ?: more.logo,
            poster = item.poster ?: more.poster,
            backdrop = item.backdrop ?: more.backdrop,
            videos = item.videos.ifEmpty { more.videos },
        )
    }

    /**
     * A title must never show without a poster and a year: when the hub's item lacks either, its
     * `/browse/meta` record is fetched once and [done] gets the merged item. Nothing happens when
     * the item is already complete or was asked for before.
     */
    fun complete(hub: HubClient, item: HubItem, done: (HubItem) -> Unit) {
        if (item.poster != null && item.year != null) return
        fuller[item.id]?.let { return done(merged(item)) }
        if (!asked.add(item.id)) return
        hub.run({ meta(item) }) { result ->
            result.getOrNull()?.let {
                fuller[item.id] = it
                done(merged(item))
            }
        }
    }

    /** Loads [url] into [image] at [width] px, unless the view has been given another picture meanwhile. */
    fun into(image: ImageView, url: String?, width: Int, ready: (Bitmap) -> Unit = {}) {
        image.tag = url
        if (url == null) {
            image.setImageDrawable(null)
            return
        }
        HubImages.cached(url, width)?.let {
            image.setImageBitmap(it)
            ready(it)
            return
        }
        image.setImageDrawable(null)
        HubImages.load(url, width) { bitmap ->
            if (bitmap != null && image.tag == url) {
                image.setImageBitmap(bitmap)
                ready(bitmap)
            }
        }
    }

    private fun metahub(kind: String, id: String) = if (IMDB_ID.matches(id)) "$METAHUB/$kind/medium/$id/img" else null
}
